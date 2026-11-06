package auth

import (
	"crypto/ed25519"
	"crypto/rand"
	"encoding/hex"
	"os"
	"path/filepath"
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

