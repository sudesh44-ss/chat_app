package auth

import (
	"crypto/ed25519"
	"crypto/rand"
	"encoding/hex"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"nexa/wire/internal/identity"
)

func TestDomesticPhoneValidation(t *testing.T) {
	validNumbers := []string{
		"+919876543210",
		"+91 98765 43210",
		"9876543210",
		"+91-88888-99999",
		"7012345678",
		"6123456789",
	}

	for _, num := range validNumbers {
		clean, err := CanonicalizeDomesticPhone(num)
		if err != nil {
			t.Fatalf("expected valid domestic phone for %s, got error: %v", num, err)
		}
		if len(clean) != 13 || clean[:3] != "+91" {
			t.Fatalf("unexpected canonical format: %s", clean)
		}
	}

	invalidNumbers := []string{
		"+14155552671", // US number (rejected)
		"+447911123456", // UK number (rejected)
		"1234567890",   // Starts with 1 (not Indian mobile series 6-9)
		"5555555555",   // Starts with 5
		"abcdefghij",   // Not numeric
		"+91123456789", // 9 digits
	}

	for _, num := range invalidNumbers {
		_, err := CanonicalizeDomesticPhone(num)
		if err == nil {
			t.Fatalf("expected error for invalid number %s, got nil", num)
		}
	}
}

func TestReverseSmsEndToEndWorkflow(t *testing.T) {
	tmpDir, _ := os.MkdirTemp("", "nexa_test_reverse_sms_*")
	defer os.RemoveAll(tmpDir)

	dbPath := filepath.Join(tmpDir, "identity.json")
	store, _ := identity.NewStore(dbPath)
	svc := NewReverseSmsService(store)

	pub, _, _ := ed25519.GenerateKey(rand.Reader)
	pubHex := hex.EncodeToString(pub)

	// 1. Pre-flight & Nonce Generation (Initiation)
	initReq := &InitiateReverseVerifyRequest{
		GoogleUID:    "google_sub_alice_123",
		Email:        "alice@example.com",
		PhoneNumber:  "+919876543210",
		Name:         "Alice Vance",
		AvatarURL:    "https://example.com/avatar.jpg",
		Gender:       "Female",
		Age:          24,
		Bio:          "Decentralized secure messenger user",
		PublicKeyHex: pubHex,
		DeviceID:     "dev_pixel_9",
		DeviceName:   "Google Pixel 9",
	}

	resp, err := svc.InitiateVerification(initReq, "192.168.1.50")
	if err != nil {
		t.Fatalf("InitiateVerification failed: %v", err)
	}

	if resp.Status != "PENDING" || resp.Nonce == "" {
		t.Fatalf("expected PENDING status with non-empty nonce")
	}

	// 2. Poll Status before SMS arrives: must be PENDING
	status1, err := svc.GetVerificationStatus(resp.Nonce)
	if err != nil || status1.Status != "PENDING" {
		t.Fatalf("expected PENDING status before inbound SMS, got: %v", status1)
	}

	// 3. Inbound SMS arrives from matching phone number with payload
	smsBody := resp.SmsPayload // "NX-VERIFY NX-XXXXX"
	record, err := svc.ProcessInboundWebhook("+919876543210", smsBody)
	if err != nil {
		t.Fatalf("ProcessInboundWebhook failed: %v", err)
	}

	if record.Status != "VERIFIED" {
		t.Fatalf("expected status VERIFIED, got: %s", record.Status)
	}

	if len(record.AllocatedNxID) != 11 || record.AllocatedNxID[:3] != "NX-" {
		t.Fatalf("expected valid NX-XXXXXXXX allocated ID, got: %s", record.AllocatedNxID)
	}

	// 4. Poll Status after SMS arrives: must be VERIFIED with allocated NX-ID & Session Token
	status2, err := svc.GetVerificationStatus(resp.Nonce)
	if err != nil || status2.Status != "VERIFIED" {
		t.Fatalf("expected VERIFIED status after inbound SMS, got: %v", status2)
	}

	if status2.NxID != record.AllocatedNxID || status2.SessionAuthToken == "" {
		t.Fatalf("status response missing NX-ID or session auth token")
	}

	if status2.User == nil || status2.User.ProfileName != "Alice Vance" {
		t.Fatalf("status response missing committed user profile")
	}

	// 5. Test User Resolution / QR Scan lookup
	resolved, err := svc.ResolveUser(status2.NxID, "")
	if err != nil {
		t.Fatalf("ResolveUser failed for NX-ID: %v", err)
	}
	if resolved.Name != "Alice Vance" || resolved.NxID != status2.NxID {
		t.Fatalf("resolved user mismatch")
	}

	// Also resolvable by phone number
	resolvedByPhone, err := svc.ResolveUser("+919876543210", "")
	if err != nil || resolvedByPhone.NxID != status2.NxID {
		t.Fatalf("ResolveUser failed for phone number: %v", err)
	}
}

func TestReverseSmsSecurityAttacks(t *testing.T) {
	tmpDir, _ := os.MkdirTemp("", "nexa_test_reverse_sec_*")
	defer os.RemoveAll(tmpDir)

	dbPath := filepath.Join(tmpDir, "identity.json")
	store, _ := identity.NewStore(dbPath)
	svc := NewReverseSmsService(store)

	pub, _, _ := ed25519.GenerateKey(rand.Reader)
	pubHex := hex.EncodeToString(pub)

	initReq := &InitiateReverseVerifyRequest{
		GoogleUID:    "google_sub_bob_456",
		PhoneNumber:  "+919999988888",
		Name:         "Bob",
		PublicKeyHex: pubHex,
	}

	resp, err := svc.InitiateVerification(initReq, "10.0.0.1")
	if err != nil {
		t.Fatalf("initiation failed: %v", err)
	}

	// Attack 1: Inbound SMS from wrong phone number (SIM spoofing / attacker attempt)
	_, err = svc.ProcessInboundWebhook("+917777766666", resp.SmsPayload)
	if err == nil {
		t.Fatal("expected error when sender number does not match initiation number")
	}

	// Attack 2: Inbound SMS with non-existent nonce
	_, err = svc.ProcessInboundWebhook("+919999988888", "NX-VERIFY NX-ZZZZZ")
	if err == nil {
		t.Fatal("expected error for non-existent nonce")
	}

	// Attack 3: Nonce expiration check
	svc.mu.Lock()
	if rec, ok := svc.pending[resp.Nonce]; ok {
		rec.ExpiresAt = time.Now().UnixMilli() - 1000 // Force expiration
	}
	svc.mu.Unlock()

	_, err = svc.ProcessInboundWebhook("+919999988888", resp.SmsPayload)
	if err == nil {
		t.Fatal("expected error for expired nonce")
	}
}

func TestReverseSmsZeroPermissionPayloadsAndSessionLookup(t *testing.T) {
	tmpDir, _ := os.MkdirTemp("", "nexa_test_zeroperm_*")
	defer os.RemoveAll(tmpDir)

	dbPath := filepath.Join(tmpDir, "identity.json")
	store, _ := identity.NewStore(dbPath)
	svc := NewReverseSmsService(store)

	pub, _, _ := ed25519.GenerateKey(rand.Reader)
	pubHex := hex.EncodeToString(pub)

	initReq := &InitiateReverseVerifyRequest{
		GoogleUID:    "google_sub_carol_789",
		PhoneNumber:  "+919811122233",
		Name:         "Carol Danvers",
		PublicKeyHex: pubHex,
		DeviceID:     "dev_pixel_zero_perm",
	}

	resp, err := svc.InitiateVerification(initReq, "10.0.0.2")
	if err != nil {
		t.Fatalf("InitiateVerification failed: %v", err)
	}

	if resp.SessionID == "" {
		t.Fatalf("expected non-empty SessionID")
	}
	if resp.InboundNumber == "" {
		t.Fatalf("expected non-empty InboundNumber")
	}

	// Verify status by SessionID before inbound SMS
	statusBySession, err := svc.GetVerificationStatus(resp.SessionID)
	if err != nil || statusBySession.Status != "PENDING" || statusBySession.IsVerified {
		t.Fatalf("expected PENDING and IsVerified=false by SessionID lookup, got %v", statusBySession)
	}

	// Test compound payload format: NEXA-VERIFY:<nonce>:<sessionId>
	compoundBody := "NEXA-VERIFY:" + resp.Nonce + ":" + resp.SessionID
	record, err := svc.ProcessInboundWebhook("+919811122233", compoundBody)
	if err != nil {
		t.Fatalf("ProcessInboundWebhook with compound payload failed: %v", err)
	}

	if record.Status != "VERIFIED" {
		t.Fatalf("expected status VERIFIED, got: %s", record.Status)
	}

	// Verify status by SessionID after inbound SMS
	statusVerifiedBySession, err := svc.GetVerificationStatus(resp.SessionID)
	if err != nil {
		t.Fatalf("GetVerificationStatus by SessionID failed: %v", err)
	}
	if !statusVerifiedBySession.IsVerified || statusVerifiedBySession.Status != "VERIFIED" {
		t.Fatalf("expected IsVerified=true and status=VERIFIED, got %+v", statusVerifiedBySession)
	}
	if statusVerifiedBySession.NxID != record.AllocatedNxID {
		t.Fatalf("NX-ID mismatch: expected %s, got %s", record.AllocatedNxID, statusVerifiedBySession.NxID)
	}
}

func TestReverseSmsProductionDummyRejection(t *testing.T) {
	tmpDir, _ := os.MkdirTemp("", "nexa_test_prod_dummy_*")
	defer os.RemoveAll(tmpDir)

	dbPath := filepath.Join(tmpDir, "identity.json")
	store, _ := identity.NewStore(dbPath)
	svc := NewReverseSmsService(store)

	pub, _, _ := ed25519.GenerateKey(rand.Reader)
	pubHex := hex.EncodeToString(pub)

	initReq := &InitiateReverseVerifyRequest{
		GoogleUID:    "google_sub_dan_101",
		PhoneNumber:  "+919822233344",
		Name:         "Dan Smith",
		PublicKeyHex: pubHex,
		DeviceID:     "dev_pixel_dummy_test",
	}

	// 1. In production, unconfigured or dummy receiver number MUST be rejected
	os.Setenv("APP_ENV", "production")
	os.Unsetenv("INBOUND_SMS_RECEIVER_NUMBER")
	os.Unsetenv("SMS_RECEIVER_NUMBER")
	defer func() {
		os.Unsetenv("APP_ENV")
		os.Unsetenv("INBOUND_SMS_RECEIVER_NUMBER")
		os.Unsetenv("SMS_RECEIVER_NUMBER")
	}()

	_, err := svc.InitiateVerification(initReq, "10.0.0.3")
	if err == nil {
		t.Fatalf("expected InitiateVerification to fail in production when receiver number is unset")
	}

	// 2. In production, explicit dummy number MUST also be rejected
	os.Setenv("INBOUND_SMS_RECEIVER_NUMBER", "+919800000000")
	_, err = svc.InitiateVerification(initReq, "10.0.0.3")
	if err == nil {
		t.Fatalf("expected InitiateVerification to fail in production when receiver number is dummy +919800000000")
	}

	// 3. In non-production (dev/test), dummy number is allowed as fallback
	os.Setenv("APP_ENV", "development")
	resp, err := svc.InitiateVerification(initReq, "10.0.0.3")
	if err != nil {
		t.Fatalf("expected InitiateVerification to succeed in dev mode with fallback, got error: %v", err)
	}
	if resp.InboundNumber != "+919800000000" {
		t.Errorf("expected dev fallback inbound number +919800000000, got: %s", resp.InboundNumber)
	}
}

func TestReverseSmsAntiReplayProtection(t *testing.T) {
	tmpDir, _ := os.MkdirTemp("", "nexa_test_antireplay_*")
	defer os.RemoveAll(tmpDir)

	dbPath := filepath.Join(tmpDir, "identity.json")
	store, _ := identity.NewStore(dbPath)
	svc := NewReverseSmsService(store)

	pub, _, _ := ed25519.GenerateKey(rand.Reader)
	pubHex := hex.EncodeToString(pub)

	initReq := &InitiateReverseVerifyRequest{
		GoogleUID:    "google_sub_replay_102",
		PhoneNumber:  "+919833344455",
		Name:         "Eve Replay",
		PublicKeyHex: pubHex,
		DeviceID:     "dev_pixel_replay_test",
	}

	resp, err := svc.InitiateVerification(initReq, "10.0.0.4")
	if err != nil {
		t.Fatalf("InitiateVerification failed: %v", err)
	}

	// First verification should succeed
	record, err := svc.ProcessInboundWebhook("+919833344455", resp.SmsPayload)
	if err != nil {
		t.Fatalf("first ProcessInboundWebhook failed: %v", err)
	}
	if record.Status != "VERIFIED" {
		t.Fatalf("expected VERIFIED, got: %s", record.Status)
	}

	// Replay attempt with same payload MUST be rejected
	_, err = svc.ProcessInboundWebhook("+919833344455", resp.SmsPayload)
	if err == nil {
		t.Fatalf("expected error on replay of already VERIFIED challenge, got nil")
	}
	if !strings.Contains(err.Error(), "replay attack rejected") {
		t.Errorf("expected 'replay attack rejected' error message, got: %v", err)
	}
}

func TestReverseSmsSingleUseTokenConsumption(t *testing.T) {
	tmpDir, _ := os.MkdirTemp("", "nexa_test_singleuse_*")
	defer os.RemoveAll(tmpDir)

	dbPath := filepath.Join(tmpDir, "identity.json")
	store, _ := identity.NewStore(dbPath)
	svc := NewReverseSmsService(store)

	pub, _, _ := ed25519.GenerateKey(rand.Reader)
	pubHex := hex.EncodeToString(pub)

	initReq := &InitiateReverseVerifyRequest{
		GoogleUID:    "google_sub_single_use_1",
		PhoneNumber:  "+919844455566",
		Name:         "Single Use User",
		PublicKeyHex: pubHex,
		DeviceID:     "dev_pixel_singleuse",
	}

	resp, err := svc.InitiateVerification(initReq, "10.0.0.5")
	if err != nil {
		t.Fatalf("InitiateVerification failed: %v", err)
	}

	_, err = svc.ProcessInboundWebhook("+919844455566", resp.SmsPayload)
	if err != nil {
		t.Fatalf("ProcessInboundWebhook failed: %v", err)
	}

	// First fetch: should return VERIFIED with SessionAuthToken
	firstFetch, err := svc.GetVerificationStatus(resp.Nonce)
	if err != nil || firstFetch.Status != "VERIFIED" || firstFetch.SessionAuthToken == "" {
		t.Fatalf("expected first fetch to return VERIFIED with token, got %+v (err: %v)", firstFetch, err)
	}

	// Second fetch: MUST return CONSUMED without token (single-use protection)
	secondFetch, err := svc.GetVerificationStatus(resp.Nonce)
	if err != nil {
		t.Fatalf("second fetch failed: %v", err)
	}
	if secondFetch.Status != "CONSUMED" || secondFetch.IsVerified {
		t.Fatalf("expected CONSUMED status on second fetch, got %+v", secondFetch)
	}
	if secondFetch.SessionAuthToken != "" {
		t.Fatalf("expected empty SessionAuthToken on second fetch, got %s", secondFetch.SessionAuthToken)
	}
}


