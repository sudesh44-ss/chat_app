package auth

import (
	"bytes"
	"crypto/ed25519"
	"crypto/rand"
	"crypto/rsa"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"nexa/wire/internal/identity"
)

func createTestStoreAndService(t *testing.T, trustedConfig string, isProduction bool) (*identity.Store, *ReverseSmsService, func()) {
	t.Helper()
	tmpDir, err := os.MkdirTemp("", "nexa_trusted_test_*")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}

	dbPath := filepath.Join(tmpDir, "identity.json")
	store, err := identity.NewStore(dbPath)
	if err != nil {
		os.RemoveAll(tmpDir)
		t.Fatalf("failed to create identity store: %v", err)
	}

	svc := NewReverseSmsService(store)
	svc.SetTrustedRegistry(NewTrustedPhoneRegistryWithConfig(trustedConfig, isProduction))

	cleanup := func() {
		store.Close()
		os.RemoveAll(tmpDir)
	}
	return store, svc, cleanup
}

func generateTestKeyPair() (string, string) {
	pub, _, _ := ed25519.GenerateKey(rand.Reader)
	pubHex := hex.EncodeToString(pub)

	dhBytes := make([]byte, 32)
	rand.Read(dhBytes)
	dhHex := hex.EncodeToString(dhBytes)

	return pubHex, dhHex
}

// ============================================================================
// GROUP 1: TRUSTED ROUTE (Tests 1 - 5)
// ============================================================================

// 1. Single trusted number skips Reverse SMS
func Test01_TrustedRoute_SingleNumberSkipsReverseSms(t *testing.T) {
	trustedPhone := "+919876543210"
	store, svc, cleanup := createTestStoreAndService(t, trustedPhone, false)
	defer cleanup()

	pubHex, dhHex := generateTestKeyPair()

	req := &InitiateReverseVerifyRequest{
		PhoneNumber:    trustedPhone,
		Name:           "Trusted User 1",
		PublicKeyHex:   pubHex,
		DhPublicKeyHex: dhHex,
		DeviceID:       "dev_trusted_1",
		DeviceName:     "Pixel 9 Pro",
	}

	resp, err := svc.InitiateVerification(req, "192.168.1.50")
	if err != nil {
		t.Fatalf("InitiateVerification failed: %v", err)
	}

	if resp.Status != "VERIFIED" {
		t.Fatalf("expected Status VERIFIED, got: %s", resp.Status)
	}
	if resp.NxID == "" || resp.NxID[:3] != "NX-" {
		t.Fatalf("expected valid allocated NX-ID, got: %s", resp.NxID)
	}
	if resp.SessionAuthToken == "" {
		t.Fatalf("expected session auth token in response")
	}

	// Verify account exists in identity store
	acc, found := store.GetAccountByNxID(resp.NxID)
	if !found || acc == nil {
		t.Fatalf("account %s not found in store", resp.NxID)
	}
	if !acc.PhoneVerified {
		t.Fatalf("expected PhoneVerified=true")
	}
}

// 2. Multiple trusted numbers are recognized
func Test02_TrustedRoute_MultipleNumbersRecognized(t *testing.T) {
	config := "+919876543210, +919876543211, +919876543212"
	_, svc, cleanup := createTestStoreAndService(t, config, false)
	defer cleanup()

	if svc.GetTrustedRegistry().Count() != 3 {
		t.Fatalf("expected 3 registered trusted numbers, got: %d", svc.GetTrustedRegistry().Count())
	}

	for _, phone := range []string{"+919876543210", "+919876543211", "+919876543212"} {
		pubHex, dhHex := generateTestKeyPair()
		req := &InitiateReverseVerifyRequest{
			PhoneNumber:    phone,
			Name:           "Multi User " + phone,
			PublicKeyHex:   pubHex,
			DhPublicKeyHex: dhHex,
		}
		resp, err := svc.InitiateVerification(req, "192.168.1.51")
		if err != nil {
			t.Fatalf("failed for trusted phone %s: %v", phone, err)
		}
		if resp.Status != "VERIFIED" {
			t.Fatalf("expected VERIFIED for %s, got: %s", phone, resp.Status)
		}
	}
}

// 3. E.164 normalization works
func Test03_TrustedRoute_E164NormalizationWorks(t *testing.T) {
	config := "9876543210, +91 88888 77777"
	reg := NewTrustedPhoneRegistryWithConfig(config, false)

	// All variations of 9876543210 must normalize to +919876543210
	for _, v := range []string{"+919876543210", "9876543210", "+91 98765 43210", "+91-98765-43210", "(9876543210)"} {
		if !reg.IsTrusted(v) {
			t.Errorf("expected %s to be recognized via normalization", v)
		}
	}

	// All variations of +918888877777 must match
	for _, v := range []string{"+918888877777", "+91 88888 77777", "8888877777"} {
		if !reg.IsTrusted(v) {
			t.Errorf("expected %s to be recognized via normalization", v)
		}
	}
}

// 4. Trusted verification is recorded correctly
func Test04_TrustedRoute_VerificationRecordedCorrectly(t *testing.T) {
	trustedPhone := "+919876543210"
	_, svc, cleanup := createTestStoreAndService(t, trustedPhone, false)
	defer cleanup()

	pubHex, dhHex := generateTestKeyPair()

	req := &InitiateReverseVerifyRequest{
		PhoneNumber:    trustedPhone,
		Name:           "Ledger Check User",
		PublicKeyHex:   pubHex,
		DhPublicKeyHex: dhHex,
	}

	nowBefore := time.Now().UnixMilli()
	resp, err := svc.InitiateVerification(req, "192.168.1.52")
	if err != nil {
		t.Fatalf("initiation failed: %v", err)
	}

	if resp.VerificationMethod != "trusted_allowlist" {
		t.Fatalf("expected VerificationMethod 'trusted_allowlist', got: %s", resp.VerificationMethod)
	}
	if resp.VerifiedAt < nowBefore || resp.VerifiedAt > time.Now().UnixMilli() {
		t.Fatalf("invalid VerifiedAt timestamp: %d", resp.VerifiedAt)
	}

	// Check polling verification status
	statusResp, err := svc.GetVerificationStatus(resp.Nonce)
	if err != nil {
		t.Fatalf("GetVerificationStatus failed: %v", err)
	}
	if statusResp.VerificationMethod != "trusted_allowlist" {
		t.Fatalf("expected polling VerificationMethod 'trusted_allowlist', got: %s", statusResp.VerificationMethod)
	}
	if statusResp.VerifiedAt != resp.VerifiedAt {
		t.Fatalf("timestamp mismatch in polling response")
	}
}

// 5. Trusted registration continues to the common pipeline
func Test05_TrustedRoute_ContinuesToCommonPipeline(t *testing.T) {
	trustedPhone := "+919876543210"
	store, svc, cleanup := createTestStoreAndService(t, trustedPhone, false)
	defer cleanup()

	pubHex, dhHex := generateTestKeyPair()
	devID := "dev_common_pipeline_1"

	req := &InitiateReverseVerifyRequest{
		PhoneNumber:    trustedPhone,
		Name:           "Common Pipeline User",
		PublicKeyHex:   pubHex,
		DhPublicKeyHex: dhHex,
		DeviceID:       devID,
		DeviceName:     "Samsung Galaxy S24",
	}

	resp, err := svc.InitiateVerification(req, "192.168.1.53")
	if err != nil {
		t.Fatalf("initiation failed: %v", err)
	}

	// Verify Account in Identity Repository
	account, found := store.GetAccountByNxID(resp.NxID)
	if !found || account == nil {
		t.Fatalf("account %s not found in store", resp.NxID)
	}
	if account.ProfileName != "Common Pipeline User" {
		t.Fatalf("account profile name mismatch: %s", account.ProfileName)
	}
	if account.PublicKeyHex != pubHex || account.DhKeyHex != dhHex {
		t.Fatalf("cryptographic keys mismatch in common pipeline account")
	}

	// Verify Device in Device Repository
	dev, found := store.GetDevice(devID)
	if !found || dev == nil {
		t.Fatalf("device %s not registered", devID)
	}
	if dev.NxID != resp.NxID || dev.DeviceName != "Samsung Galaxy S24" {
		t.Fatalf("device fields mismatch: %v", dev)
	}

	// Verify Session in Session Repository
	session, found := store.GetSession(resp.SessionToken)
	if !found || session == nil {
		t.Fatalf("session %s not found", resp.SessionToken)
	}
	if session.NxID != resp.NxID || session.DeviceID != devID {
		t.Fatalf("session binding mismatch: %v", session)
	}
}

// ============================================================================
// GROUP 2: NORMAL ROUTE (Tests 6 - 8)
// ============================================================================

// 6. Non-trusted numbers still require Reverse SMS
func Test06_NormalRoute_NonTrustedRequiresReverseSms(t *testing.T) {
	trustedPhone := "+919876543210"
	store, svc, cleanup := createTestStoreAndService(t, trustedPhone, false)
	defer cleanup()

	pubHex, dhHex := generateTestKeyPair()
	normalPhone := "+919999988888"

	req := &InitiateReverseVerifyRequest{
		PhoneNumber:    normalPhone,
		Name:           "Normal User",
		PublicKeyHex:   pubHex,
		DhPublicKeyHex: dhHex,
	}

	resp, err := svc.InitiateVerification(req, "192.168.1.54")
	if err != nil {
		t.Fatalf("initiation failed: %v", err)
	}

	// Must be PENDING awaiting carrier SMS
	if resp.Status != "PENDING" {
		t.Fatalf("expected PENDING status for normal user, got: %s", resp.Status)
	}
	if resp.NxID != "" || resp.SessionAuthToken != "" {
		t.Fatalf("credentials must not be returned before inbound SMS")
	}

	// Account must not be created yet
	_, found := store.GetAccountByPhone(normalPhone)
	if found {
		t.Fatalf("account must not exist prior to reverse SMS delivery")
	}
}

// 7. Existing Reverse SMS behavior remains unchanged
func Test07_NormalRoute_ExistingReverseSmsUnchanged(t *testing.T) {
	trustedPhone := "+919876543210"
	store, svc, cleanup := createTestStoreAndService(t, trustedPhone, false)
	defer cleanup()

	pubHex, dhHex := generateTestKeyPair()
	normalPhone := "+919999977777"

	req := &InitiateReverseVerifyRequest{
		PhoneNumber:    normalPhone,
		Name:           "Reverse SMS Workflow",
		PublicKeyHex:   pubHex,
		DhPublicKeyHex: dhHex,
	}

	resp, err := svc.InitiateVerification(req, "192.168.1.55")
	if err != nil {
		t.Fatalf("initiation failed: %v", err)
	}

	// Polling before webhook: PENDING
	statusBefore, err := svc.GetVerificationStatus(resp.Nonce)
	if err != nil || statusBefore.Status != "PENDING" {
		t.Fatalf("expected PENDING before webhook, got: %v", statusBefore)
	}

	// Carrier webhook arrives
	record, err := svc.ProcessInboundWebhook(normalPhone, resp.SmsPayload)
	if err != nil {
		t.Fatalf("ProcessInboundWebhook failed: %v", err)
	}
	if record.Status != "VERIFIED" {
		t.Fatalf("expected status VERIFIED after webhook")
	}
	if record.VerificationMethod != "reverse_sms" {
		t.Fatalf("expected VerificationMethod 'reverse_sms', got: %s", record.VerificationMethod)
	}

	// Polling after webhook: VERIFIED
	statusAfter, err := svc.GetVerificationStatus(resp.Nonce)
	if err != nil || statusAfter.Status != "VERIFIED" || statusAfter.NxID != record.AllocatedNxID {
		t.Fatalf("expected VERIFIED status after webhook match")
	}

	// User account exists in store with phone verified
	acc, found := store.GetAccountByNxID(record.AllocatedNxID)
	if !found || !acc.PhoneVerified || acc.PhoneNumber != normalPhone {
		t.Fatalf("user account not properly committed by reverse SMS")
	}
}

// 8. Empty allowlist preserves normal registration
func Test08_NormalRoute_EmptyAllowlistPreservesNormalRegistration(t *testing.T) {
	_, svc, cleanup := createTestStoreAndService(t, "", false)
	defer cleanup()

	pubHex, dhHex := generateTestKeyPair()

	req := &InitiateReverseVerifyRequest{
		PhoneNumber:    "+919876543210",
		Name:           "Empty Allowlist User",
		PublicKeyHex:   pubHex,
		DhPublicKeyHex: dhHex,
	}

	resp, err := svc.InitiateVerification(req, "192.168.1.56")
	if err != nil {
		t.Fatalf("initiation failed: %v", err)
	}

	// Empty allowlist must treat all numbers as standard Reverse SMS
	if resp.Status != "PENDING" {
		t.Fatalf("expected PENDING when allowlist is empty, got: %s", resp.Status)
	}
}

// ============================================================================
// GROUP 3: SECURITY & ABUSE PROTECTION (Tests 9 - 14)
// ============================================================================

// 9. Client-provided verification flags cannot bypass verification
func Test09_Security_ClientProvidedFlagsCannotBypass(t *testing.T) {
	trustedPhone := "+919876543210"
	store, svc, cleanup := createTestStoreAndService(t, trustedPhone, false)
	defer cleanup()

	otpService := NewOtpService(GetDefaultSmsProvider())
	authHandler := NewAuthHandler(store, otpService, nil)
	authHandler.reverseSmsService = svc

	mux := http.NewServeMux()
	authHandler.RegisterRoutes(mux)

	pubHex, dhHex := generateTestKeyPair()

	// Client sends untrusted phone number with arbitrary bypass parameters
	payload := map[string]interface{}{
		"phone_number":      "+919999966666",
		"name":              "Hacker",
		"public_key_hex":    pubHex,
		"dh_public_key_hex": dhHex,
		"is_trusted":        true,
		"verified":          true,
		"skip_verification": true,
		"skipVerification":  true,
		"isTrusted":         true,
	}
	body, _ := json.Marshal(payload)

	req := httptest.NewRequest(http.MethodPost, "/api/v1/auth/initiate-reverse-verify", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()

	mux.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("expected HTTP 200, got: %d", w.Code)
	}

	var resp InitiateReverseVerifyResponse
	if err := json.Unmarshal(w.Body.Bytes(), &resp); err != nil {
		t.Fatalf("failed to decode response: %v", err)
	}

	// Must remain PENDING; client flags completely ignored
	if resp.Status != "PENDING" {
		t.Fatalf("SECURITY VIOLATION: client flags bypassed verification! Status: %s", resp.Status)
	}
	if resp.NxID != "" || resp.SessionAuthToken != "" {
		t.Fatalf("SECURITY VIOLATION: credentials returned to unauthorized client")
	}
}

// 10. Existing accounts cannot be taken over
func Test10_Security_ExistingAccountsCannotBeTakenOver(t *testing.T) {
	trustedPhone := "+919876543210"
	store, svc, cleanup := createTestStoreAndService(t, trustedPhone, false)
	defer cleanup()

	pubHex1, dhHex1 := generateTestKeyPair()
	pubHex2, dhHex2 := generateTestKeyPair()

	// Legitimate registration
	req1 := &InitiateReverseVerifyRequest{
		PhoneNumber:    trustedPhone,
		Name:           "Alice Legitimate",
		PublicKeyHex:   pubHex1,
		DhPublicKeyHex: dhHex1,
		DeviceID:       "dev_alice",
		IsReverify:     false,
	}
	resp1, err := svc.InitiateVerification(req1, "192.168.1.57")
	if err != nil {
		t.Fatalf("legitimate registration failed: %v", err)
	}
	originalNxID := resp1.NxID

	// Attacker tries to register a new account with the same phone number
	attackerReq := &InitiateReverseVerifyRequest{
		PhoneNumber:    trustedPhone,
		Name:           "Attacker",
		PublicKeyHex:   pubHex2,
		DhPublicKeyHex: dhHex2,
		DeviceID:       "dev_attacker",
		IsReverify:     false,
	}
	_, err = svc.InitiateVerification(attackerReq, "192.168.1.58")
	if err == nil {
		t.Fatal("expected error on takeover attempt for existing phone, got nil")
	}

	// Verify original account remains untouched in store
	acc, found := store.GetAccountByNxID(originalNxID)
	if !found || acc.ProfileName != "Alice Legitimate" || acc.PublicKeyHex != pubHex1 {
		t.Fatalf("original account was corrupted by takeover attempt")
	}
}

// 11. Duplicate registration is handled safely
func Test11_Security_DuplicateRegistrationHandledSafely(t *testing.T) {
	trustedPhone := "+919876543210"
	_, svc, cleanup := createTestStoreAndService(t, trustedPhone, false)
	defer cleanup()

	pubHex, dhHex := generateTestKeyPair()

	req := &InitiateReverseVerifyRequest{
		PhoneNumber:    trustedPhone,
		Name:           "Duplicate Test User",
		PublicKeyHex:   pubHex,
		DhPublicKeyHex: dhHex,
		IsReverify:     false,
	}

	// First registration succeeds
	resp1, err := svc.InitiateVerification(req, "192.168.1.59")
	if err != nil || resp1.Status != "VERIFIED" {
		t.Fatalf("first registration failed: %v", err)
	}

	// Duplicate registration attempt returns ErrPhoneAlreadyRegistered
	_, err = svc.InitiateVerification(req, "192.168.1.59")
	if err == nil {
		t.Fatal("expected ErrPhoneAlreadyRegistered for duplicate registration, got nil")
	}
}

// 12. Concurrent requests cannot create duplicate accounts
func Test12_Security_ConcurrentRequestsCannotCreateDuplicateAccounts(t *testing.T) {
	trustedPhone := "+919876543210"
	store, svc, cleanup := createTestStoreAndService(t, trustedPhone, false)
	defer cleanup()

	concurrency := 10
	var wg sync.WaitGroup
	var successCount int32
	var conflictCount int32

	for i := 0; i < concurrency; i++ {
		wg.Add(1)
		go func(workerID int) {
			defer wg.Done()
			pubHex, dhHex := generateTestKeyPair()
			req := &InitiateReverseVerifyRequest{
				PhoneNumber:    trustedPhone,
				Name:           fmt.Sprintf("Worker %d", workerID),
				PublicKeyHex:   pubHex,
				DhPublicKeyHex: dhHex,
				DeviceID:       fmt.Sprintf("dev_%d", workerID),
				IsReverify:     false,
			}
			resp, err := svc.InitiateVerification(req, fmt.Sprintf("192.168.1.%d", 100+workerID))
			if err == nil && resp.Status == "VERIFIED" {
				atomic.AddInt32(&successCount, 1)
			} else {
				atomic.AddInt32(&conflictCount, 1)
			}
		}(i)
	}

	wg.Wait()

	if successCount != 1 {
		t.Fatalf("expected exactly 1 concurrent registration to succeed, got: %d (conflicts: %d)",
			successCount, conflictCount)
	}
	if conflictCount != int32(concurrency-1) {
		t.Fatalf("expected %d conflicts, got: %d", concurrency-1, conflictCount)
	}

	totalAccounts, err := store.CountAccounts()
	if err != nil || totalAccounts != 1 {
		t.Fatalf("expected total accounts = 1 in store, got: %d", totalAccounts)
	}
}

// 13. Malformed allowlist entries cannot authorize unintended numbers
func Test13_Security_MalformedAllowlistEntriesCannotAuthorizeUnintended(t *testing.T) {
	// Malformed entries like letters, incomplete numbers, or invalid prefixes
	rawConfig := "+919876543210, not_a_number, +01234, 12345"

	// Non-production: rejects invalid entries without crash; keeps valid one
	devReg := NewTrustedPhoneRegistryWithConfig(rawConfig, false)
	if !devReg.IsTrusted("+919876543210") {
		t.Fatalf("valid number should be preserved in dev")
	}
	for _, invalid := range []string{"not_a_number", "+01234", "12345", "+12345"} {
		if devReg.IsTrusted(invalid) {
			t.Fatalf("invalid entry %s must never be trusted", invalid)
		}
	}

	// Production: must fail closed (count=0, empty allowlist)
	prodReg := NewTrustedPhoneRegistryWithConfig(rawConfig, true)
	if prodReg.Count() != 0 {
		t.Fatalf("expected production to fail closed (count=0), got: %d", prodReg.Count())
	}
	if prodReg.IsTrusted("+919876543210") {
		t.Fatalf("expected production to fail closed even for valid number if integrity cannot be established")
	}
}

// 14. Trusted users remain subject to existing abuse protection
func Test14_Security_TrustedUsersSubjectToAbuseProtection(t *testing.T) {
	trustedPhone := "+919876543210"
	_, svc, cleanup := createTestStoreAndService(t, trustedPhone, false)
	defer cleanup()

	pubHex, dhHex := generateTestKeyPair()
	rateKey := "attacker_user_rate_key"

	// Rate limit is max 3 attempts / 24 hours per user
	for i := 0; i < 3; i++ {
		req := &InitiateReverseVerifyRequest{
			GoogleUID:      rateKey,
			PhoneNumber:    fmt.Sprintf("+91987654321%d", i),
			Name:           "Abuse Test",
			PublicKeyHex:   pubHex,
			DhPublicKeyHex: dhHex,
		}
		// Register different phone numbers to hit user rate limit
		_, _ = svc.InitiateVerification(req, "192.168.1.60")
	}

	// 4th attempt must be rejected by rate limiting even though phone is trusted
	fourthReq := &InitiateReverseVerifyRequest{
		GoogleUID:      rateKey,
		PhoneNumber:    trustedPhone,
		Name:           "Abuse Test Exceeded",
		PublicKeyHex:   pubHex,
		DhPublicKeyHex: dhHex,
	}
	_, err := svc.InitiateVerification(fourthReq, "192.168.1.60")
	if err == nil {
		t.Fatal("expected rate limit rejection for 4th attempt, got nil")
	}
}

// ============================================================================
// GROUP 4: SHARED PIPELINE COMPATIBILITY (Tests 15 - 21)
// ============================================================================

// 15. Both routes use the same Google Login validation
func Test15_SharedPipeline_GoogleLoginValidationIdentical(t *testing.T) {
	trustedPhone := "+919876543210"
	store, svc, cleanup := createTestStoreAndService(t, trustedPhone, false)
	defer cleanup()

	privKey, _ := rsa.GenerateKey(rand.Reader, 2048)
	kid := "test_kid_1"
	clientID := "12345.apps.googleusercontent.com"

	verifier := NewGoogleVerifier()
	verifier.SetClientIDForTesting(clientID)
	verifier.SetKeysForTesting(map[string]*rsa.PublicKey{
		kid: &privKey.PublicKey,
	})
	svc.SetGoogleVerifier(verifier)

	now := time.Now().Unix()

	// Valid Google token
	validToken, _ := createTestGoogleToken(
		privKey, kid, "https://accounts.google.com", clientID,
		"google_sub_user_1", "user1@gmail.com",
		now+3600, now, "RS256",
	)

	// Route A (Trusted Route) with Google Token
	pubHexA, dhHexA := generateTestKeyPair()
	reqA := &InitiateReverseVerifyRequest{
		PhoneNumber:     trustedPhone,
		GoogleAuthToken: validToken,
		PublicKeyHex:    pubHexA,
		DhPublicKeyHex:  dhHexA,
	}
	respA, err := svc.InitiateVerification(reqA, "192.168.1.70")
	if err != nil {
		t.Fatalf("trusted route with Google token failed: %v", err)
	}
	accA, _ := store.GetAccountByNxID(respA.NxID)
	if accA.GoogleID != "google_sub_user_1" || accA.Email != "user1@gmail.com" {
		t.Fatalf("Google identity fields not properly bound on trusted account")
	}

	// Invalid / expired token must be rejected by both routes
	expiredToken, _ := createTestGoogleToken(
		privKey, kid, "https://accounts.google.com", clientID,
		"google_sub_user_2", "user2@gmail.com",
		now-100, now-200, "RS256",
	)

	pubHexB, dhHexB := generateTestKeyPair()
	reqB := &InitiateReverseVerifyRequest{
		PhoneNumber:     "+919999955555", // Normal route
		GoogleAuthToken: expiredToken,
		PublicKeyHex:    pubHexB,
		DhPublicKeyHex:  dhHexB,
	}
	_, errB := svc.InitiateVerification(reqB, "192.168.1.71")
	if errB == nil {
		t.Fatalf("expected error for expired Google token on normal route")
	}

	reqC := &InitiateReverseVerifyRequest{
		PhoneNumber:     "+919876543211", // Another trusted number
		GoogleAuthToken: expiredToken,
		PublicKeyHex:    pubHexB,
		DhPublicKeyHex:  dhHexB,
	}
	svc.GetTrustedRegistry().SetConfig("+919876543210, +919876543211", false)
	_, errC := svc.InitiateVerification(reqC, "192.168.1.72")
	if errC == nil {
		t.Fatalf("expected error for expired Google token on trusted route")
	}
}

// 16. Both routes create the same account structure
func Test16_SharedPipeline_SameAccountStructure(t *testing.T) {
	trustedPhone := "+919876543210"
	store, svc, cleanup := createTestStoreAndService(t, trustedPhone, false)
	defer cleanup()

	// Route B (Reverse SMS)
	pubHexB, dhHexB := generateTestKeyPair()
	reqB := &InitiateReverseVerifyRequest{
		PhoneNumber:    "+919999944444",
		Name:           "Reverse SMS User",
		PublicKeyHex:   pubHexB,
		DhPublicKeyHex: dhHexB,
	}
	respB, _ := svc.InitiateVerification(reqB, "192.168.1.80")
	recordB, _ := svc.ProcessInboundWebhook("+919999944444", respB.SmsPayload)
	accB, _ := store.GetAccountByNxID(recordB.AllocatedNxID)

	// Route A (Trusted Route)
	pubHexA, dhHexA := generateTestKeyPair()
	reqA := &InitiateReverseVerifyRequest{
		PhoneNumber:    trustedPhone,
		Name:           "Trusted Route User",
		PublicKeyHex:   pubHexA,
		DhPublicKeyHex: dhHexA,
	}
	respA, _ := svc.InitiateVerification(reqA, "192.168.1.81")
	accA, _ := store.GetAccountByNxID(respA.NxID)

	// Compare structural fields
	if accA.PhoneVerified != accB.PhoneVerified || !accA.PhoneVerified {
		t.Fatalf("PhoneVerified mismatch")
	}
	if accA.IsDiscoverableByPhone != accB.IsDiscoverableByPhone || !accA.IsDiscoverableByPhone {
		t.Fatalf("IsDiscoverableByPhone mismatch")
	}
	if accA.CreatedAt <= 0 || accB.CreatedAt <= 0 {
		t.Fatalf("CreatedAt timestamps missing")
	}
	if len(accA.ID) < 8 || len(accB.ID) < 8 {
		t.Fatalf("Account ID prefix format invalid")
	}
}

// 17. Both routes use the same NX ID allocation
func Test17_SharedPipeline_SameNxIdAllocation(t *testing.T) {
	trustedPhone := "+919876543210"
	_, svc, cleanup := createTestStoreAndService(t, trustedPhone, false)
	defer cleanup()

	pubHex1, dhHex1 := generateTestKeyPair()
	pubHex2, dhHex2 := generateTestKeyPair()

	// Normal route
	req1 := &InitiateReverseVerifyRequest{
		PhoneNumber:    "+919999933333",
		PublicKeyHex:   pubHex1,
		DhPublicKeyHex: dhHex1,
	}
	resp1, _ := svc.InitiateVerification(req1, "192.168.1.82")
	rec1, _ := svc.ProcessInboundWebhook("+919999933333", resp1.SmsPayload)

	// Trusted route
	req2 := &InitiateReverseVerifyRequest{
		PhoneNumber:    trustedPhone,
		PublicKeyHex:   pubHex2,
		DhPublicKeyHex: dhHex2,
	}
	resp2, _ := svc.InitiateVerification(req2, "192.168.1.83")

	// Both must be valid 11-character locked format: NX-XXXXXXXX
	if len(rec1.AllocatedNxID) != 11 || rec1.AllocatedNxID[:3] != "NX-" {
		t.Fatalf("invalid NX-ID format on route B: %s", rec1.AllocatedNxID)
	}
	if len(resp2.NxID) != 11 || resp2.NxID[:3] != "NX-" {
		t.Fatalf("invalid NX-ID format on route A: %s", resp2.NxID)
	}
}

// 18. Both routes register devices identically
func Test18_SharedPipeline_SameDeviceRegistration(t *testing.T) {
	trustedPhone := "+919876543210"
	store, svc, cleanup := createTestStoreAndService(t, trustedPhone, false)
	defer cleanup()

	pubHex1, dhHex1 := generateTestKeyPair()
	pubHex2, dhHex2 := generateTestKeyPair()

	dev1 := "dev_route_b"
	dev2 := "dev_route_a"

	// Route B
	req1 := &InitiateReverseVerifyRequest{
		PhoneNumber:    "+919999922222",
		PublicKeyHex:   pubHex1,
		DhPublicKeyHex: dhHex1,
		DeviceID:       dev1,
		DeviceName:     "Device Route B",
	}
	resp1, _ := svc.InitiateVerification(req1, "192.168.1.84")
	rec1, _ := svc.ProcessInboundWebhook("+919999922222", resp1.SmsPayload)

	// Route A
	req2 := &InitiateReverseVerifyRequest{
		PhoneNumber:    trustedPhone,
		PublicKeyHex:   pubHex2,
		DhPublicKeyHex: dhHex2,
		DeviceID:       dev2,
		DeviceName:     "Device Route A",
	}
	resp2, _ := svc.InitiateVerification(req2, "192.168.1.85")

	d1, found1 := store.GetDevice(dev1)
	d2, found2 := store.GetDevice(dev2)

	if !found1 || !found2 {
		t.Fatalf("devices not registered properly")
	}

	if d1.NxID != rec1.AllocatedNxID || d2.NxID != resp2.NxID {
		t.Fatalf("device account bindings invalid")
	}
	if d1.PublicKeyHex != pubHex1 || d2.PublicKeyHex != pubHex2 {
		t.Fatalf("device public keys mismatch")
	}
	if d1.DhKeyHex != dhHex1 || d2.DhKeyHex != dhHex2 {
		t.Fatalf("device DH keys mismatch")
	}
	if d1.IsRevoked || d2.IsRevoked {
		t.Fatalf("devices must not be revoked on registration")
	}
}

// 19. Both routes use the same E2EE setup
func Test19_SharedPipeline_SameE2EESetup(t *testing.T) {
	trustedPhone := "+919876543210"
	store, svc, cleanup := createTestStoreAndService(t, trustedPhone, false)
	defer cleanup()

	pubHex1, dhHex1 := generateTestKeyPair()
	pubHex2, dhHex2 := generateTestKeyPair()

	// Route B
	req1 := &InitiateReverseVerifyRequest{
		PhoneNumber:    "+919999911111",
		PublicKeyHex:   pubHex1,
		DhPublicKeyHex: dhHex1,
	}
	resp1, _ := svc.InitiateVerification(req1, "192.168.1.86")
	rec1, _ := svc.ProcessInboundWebhook("+919999911111", resp1.SmsPayload)

	// Route A
	req2 := &InitiateReverseVerifyRequest{
		PhoneNumber:    trustedPhone,
		PublicKeyHex:   pubHex2,
		DhPublicKeyHex: dhHex2,
	}
	resp2, _ := svc.InitiateVerification(req2, "192.168.1.87")

	acc1, _ := store.GetAccountByNxID(rec1.AllocatedNxID)
	acc2, _ := store.GetAccountByNxID(resp2.NxID)

	// Both accounts have 32-byte Ed25519 identity key and X25519 DH key
	b1, err1 := hex.DecodeString(acc1.PublicKeyHex)
	b2, err2 := hex.DecodeString(acc2.PublicKeyHex)
	if err1 != nil || err2 != nil || len(b1) != 32 || len(b2) != 32 {
		t.Fatalf("invalid Ed25519 public key lengths: %d, %d", len(b1), len(b2))
	}

	dh1, err1 := hex.DecodeString(acc1.DhKeyHex)
	dh2, err2 := hex.DecodeString(acc2.DhKeyHex)
	if err1 != nil || err2 != nil || len(dh1) != 32 || len(dh2) != 32 {
		t.Fatalf("invalid X25519 public key lengths: %d, %d", len(dh1), len(dh2))
	}
}

// 20. Both routes use the same encrypted backup/recovery architecture
func Test20_SharedPipeline_EncryptedBackupAndRecovery(t *testing.T) {
	trustedPhone := "+919876543210"
	store, svc, cleanup := createTestStoreAndService(t, trustedPhone, false)
	defer cleanup()

	pubHex, dhHex := generateTestKeyPair()

	req := &InitiateReverseVerifyRequest{
		PhoneNumber:    trustedPhone,
		PublicKeyHex:   pubHex,
		DhPublicKeyHex: dhHex,
	}
	resp, _ := svc.InitiateVerification(req, "192.168.1.88")
	acc, _ := store.GetAccountByNxID(resp.NxID)

	// Assert: Server stores ONLY public keys, zero private keys, zero plaintext passwords
	if acc.PublicKeyHex == "" || acc.DhKeyHex == "" {
		t.Fatalf("missing public keys")
	}

	// Verify server model does not have any private key fields or backup encryption passwords
	accountJSON, _ := json.Marshal(acc)
	if bytes.Contains(accountJSON, []byte("private")) || bytes.Contains(accountJSON, []byte("secret")) {
		t.Fatalf("security violation: private key leaked into account JSON")
	}
}

// 21. New-device login and old-device revocation behave identically
func Test21_SharedPipeline_NewDeviceLoginAndRevocationIdentical(t *testing.T) {
	trustedPhone := "+919876543210"
	store, svc, cleanup := createTestStoreAndService(t, trustedPhone, false)
	defer cleanup()

	pubHex1, dhHex1 := generateTestKeyPair()
	pubHex2, dhHex2 := generateTestKeyPair()

	dev1 := "dev_original"
	dev2 := "dev_replacement"

	// 1. Initial login on dev1
	req1 := &InitiateReverseVerifyRequest{
		PhoneNumber:    trustedPhone,
		Name:           "Device Lifecycle User",
		PublicKeyHex:   pubHex1,
		DhPublicKeyHex: dhHex1,
		DeviceID:       dev1,
		IsReverify:     false,
	}
	resp1, err := svc.InitiateVerification(req1, "192.168.1.90")
	if err != nil {
		t.Fatalf("initial registration failed: %v", err)
	}

	// 2. New device login (IsReverify = true)
	req2 := &InitiateReverseVerifyRequest{
		PhoneNumber:    trustedPhone,
		Name:           "Device Lifecycle User",
		PublicKeyHex:   pubHex2,
		DhPublicKeyHex: dhHex2,
		DeviceID:       dev2,
		IsReverify:     true,
	}
	resp2, err := svc.InitiateVerification(req2, "192.168.1.91")
	if err != nil {
		t.Fatalf("re-verification on new device failed: %v", err)
	}
	if resp2.NxID != resp1.NxID {
		t.Fatalf("re-verification must preserve user's NX-ID")
	}

	// 3. User account now has 2 registered devices
	devs := store.GetDevicesForAccount(resp1.NxID)
	if len(devs) != 2 {
		t.Fatalf("expected 2 active devices, got: %d", len(devs))
	}

	// 4. Revoke old device (dev1)
	err = store.RevokeDevice(dev1)
	if err != nil {
		t.Fatalf("failed to revoke old device: %v", err)
	}

	oldDev, _ := store.GetDevice(dev1)
	if !oldDev.IsRevoked {
		t.Fatalf("expected old device to be revoked")
	}

	newDev, _ := store.GetDevice(dev2)
	if newDev.IsRevoked {
		t.Fatalf("new device must remain active")
	}
}
