package auth

import (
	"crypto/ed25519"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"testing"
	"time"

	"nexa/wire/internal/identity"
)

// TestOtpBruteForceLockout verifies that exceeding failed verification attempts
// permanently invalidates the OTP and prevents subsequent successful verification.
func TestOtpBruteForceLockout(t *testing.T) {
	mockSms := NewMockSmsProvider()
	otpService := NewOtpService(mockSms)

	phone := "+919988776655"
	ip := "10.0.0.50"

	// 1. Request OTP
	_, err := otpService.RequestOtp(phone, ip)
	if err != nil {
		t.Fatalf("RequestOtp failed: %v", err)
	}

	validCode := mockSms.GetLastOtp(phone)
	if len(validCode) != 6 {
		t.Fatalf("expected 6-digit OTP code, got: %s", validCode)
	}

	// 2. Submit wrong OTP until lockout (>3 attempts)
	for i := 1; i <= 4; i++ {
		_, _ = otpService.VerifyOtp(phone, "000000")
	}

	// 3. Now submit the CORRECT OTP code; it MUST fail because the OTP record was invalidated
	_, err = otpService.VerifyOtp(phone, validCode)
	if err == nil {
		t.Fatalf("CRITICAL SECURITY FLAW: OTP verification succeeded after exceeding failed attempts")
	}
}

// TestOtpExpiration verifies that an OTP cannot be verified after its TTL expires.
func TestOtpExpiration(t *testing.T) {
	mockSms := NewMockSmsProvider()
	otpService := NewOtpService(mockSms)

	phone := "+919988776656"
	ip := "10.0.0.51"

	_, err := otpService.RequestOtp(phone, ip)
	if err != nil {
		t.Fatalf("RequestOtp failed: %v", err)
	}

	validCode := mockSms.GetLastOtp(phone)

	// Simulate expiration by backdating the OTP record
	canonicalPhone, _ := CanonicalizePhone(phone)
	otpService.mu.Lock()
	if entry, exists := otpService.records[canonicalPhone]; exists {
		entry.ExpiresAt = time.Now().UnixMilli() - 60000
	}
	otpService.mu.Unlock()

	// Verify expired OTP fails
	_, err = otpService.VerifyOtp(phone, validCode)
	if err == nil {
		t.Fatalf("CRITICAL SECURITY FLAW: expired OTP was accepted")
	}
}

// TestAuthTicketOneTimeConsumption verifies that an issued AuthProofTicket is strictly single-use.
func TestAuthTicketOneTimeConsumption(t *testing.T) {
	mockSms := NewMockSmsProvider()
	otpService := NewOtpService(mockSms)

	phone := "+919988776657"
	ip := "10.0.0.52"

	_, _ = otpService.RequestOtp(phone, ip)
	validCode := mockSms.GetLastOtp(phone)

	ticketToken, err := otpService.VerifyOtp(phone, validCode)
	if err != nil {
		t.Fatalf("VerifyOtp failed: %v", err)
	}
	if ticketToken == "" {
		t.Fatalf("expected non-empty auth proof ticket")
	}

	// Consume ticket first time: succeeds
	consumedTicket, err := otpService.ConsumeTicket(ticketToken)
	if err != nil || consumedTicket == nil {
		t.Fatalf("first ConsumeTicket failed: %v", err)
	}
	canonicalPhone, _ := CanonicalizePhone(phone)
	if consumedTicket.PhoneNumber != canonicalPhone {
		t.Fatalf("phone mismatch: got %s want %s", consumedTicket.PhoneNumber, canonicalPhone)
	}

	// Consume ticket second time (replay): MUST fail
	_, err2 := otpService.ConsumeTicket(ticketToken)
	if err2 == nil {
		t.Fatalf("CRITICAL SECURITY FLAW: AuthTicket was accepted a second time (replay vulnerability)")
	}
}

// TestUserResolve_Security verifies authentication, device revocation, and authorized access on /api/v1/users/resolve.
func TestUserResolve_Security(t *testing.T) {
	tmpDir, _ := os.MkdirTemp("", "nexa_test_resolve_*")
	defer os.RemoveAll(tmpDir)

	dbPath := filepath.Join(tmpDir, "identity.json")
	store, _ := identity.NewStore(dbPath)
	mockSms := NewMockSmsProvider()
	otpService := NewOtpService(mockSms)
	googleVerifier := NewGoogleVerifier()

	handler := NewAuthHandler(store, otpService, googleVerifier)

	// Create test target user
	targetPub, _, _ := ed25519.GenerateKey(rand.Reader)
	targetPubHex := hex.EncodeToString(targetPub)
	targetNxID, _ := store.AllocateNxID(targetPubHex)
	targetPhone := "+919876543210"

	targetAccount := &identity.UserAccount{
		ID:                    "usr_target",
		NxID:                  targetNxID,
		PublicKeyHex:          targetPubHex,
		PhoneNumber:           targetPhone,
		PhoneVerified:         true,
		IsDiscoverableByPhone: true,
		ProfileName:           "Target User",
		CreatedAt:             time.Now().UnixMilli(),
	}
	if err := store.CreateAccount(targetAccount); err != nil {
		t.Fatalf("failed to create target account: %v", err)
	}

	// Create test caller user and session
	callerPub, _, _ := ed25519.GenerateKey(rand.Reader)
	callerPubHex := hex.EncodeToString(callerPub)
	callerNxID, _ := store.AllocateNxID(callerPubHex)

	callerAccount := &identity.UserAccount{
		ID:                    "usr_caller",
		NxID:                  callerNxID,
		PublicKeyHex:          callerPubHex,
		PhoneNumber:           "+919123456789",
		PhoneVerified:         true,
		IsDiscoverableByPhone: true,
		ProfileName:           "Caller User",
		CreatedAt:             time.Now().UnixMilli(),
	}
	if err := store.CreateAccount(callerAccount); err != nil {
		t.Fatalf("failed to create caller account: %v", err)
	}

	callerDeviceID := "dev_caller_1"
	callerDevice := &identity.Device{
		DeviceID:     callerDeviceID,
		AccountID:    callerAccount.ID,
		NxID:         callerNxID,
		DeviceName:   "Caller Phone",
		PublicKeyHex: callerPubHex,
		IsRevoked:    false,
		CreatedAt:    time.Now().UnixMilli(),
	}
	if err := store.RegisterDevice(callerDevice); err != nil {
		t.Fatalf("failed to register caller device: %v", err)
	}

	validSessionToken := "sess_valid_caller_token"
	validSession := &identity.Session{
		Token:     validSessionToken,
		AccountID: callerAccount.ID,
		NxID:      callerNxID,
		DeviceID:  callerDeviceID,
		ExpiresAt: time.Now().Add(24 * time.Hour).UnixMilli(),
		IsRevoked: false,
		CreatedAt: time.Now().UnixMilli(),
	}
	if err := store.CreateSession(validSession); err != nil {
		t.Fatalf("failed to create session: %v", err)
	}

	// 1. Unauthenticated request -> MUST return 401 Unauthorized (stops scraping/enumeration)
	unauthReq := httptest.NewRequest(http.MethodGet, "/api/v1/users/resolve?target="+url.QueryEscape(targetPhone), nil)
	unauthRec := httptest.NewRecorder()
	handler.handleUserResolve(unauthRec, unauthReq)

	if unauthRec.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401 Unauthorized for unauthenticated request, got %d", unauthRec.Code)
	}

	// 2. Authenticated request with valid session -> MUST succeed (HTTP 200)
	authReq := httptest.NewRequest(http.MethodGet, "/api/v1/users/resolve?target="+url.QueryEscape(targetPhone), nil)
	authReq.Header.Set("Authorization", "Bearer "+validSessionToken)
	authRec := httptest.NewRecorder()
	handler.handleUserResolve(authRec, authReq)

	if authRec.Code != http.StatusOK {
		t.Fatalf("expected 200 OK for valid session, got %d: %s", authRec.Code, authRec.Body.String())
	}

	var res UserResolveResponse
	if err := json.Unmarshal(authRec.Body.Bytes(), &res); err != nil {
		t.Fatalf("failed to unmarshal resolve response: %v", err)
	}
	if res.NxID != targetNxID || res.Name != "Target User" {
		t.Fatalf("resolved user mismatch: %+v", res)
	}

	// 3. Revoke caller's device -> MUST return 401 Unauthorized
	if err := store.RevokeDevice(callerDeviceID); err != nil {
		t.Fatalf("failed to revoke caller device: %v", err)
	}

	revokedReq := httptest.NewRequest(http.MethodGet, "/api/v1/users/resolve?target="+url.QueryEscape(targetPhone), nil)
	revokedReq.Header.Set("Authorization", "Bearer "+validSessionToken)
	revokedRec := httptest.NewRecorder()
	handler.handleUserResolve(revokedRec, revokedReq)

	if revokedRec.Code != http.StatusUnauthorized {
		t.Fatalf("CRITICAL SECURITY FLAW: revoked device session was allowed to resolve user (got %d)", revokedRec.Code)
	}
}
