package auth

import (
	"bytes"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"

	"nexa/wire/internal/identity"
)

func TestOtpRateLimitingAndSecurity(t *testing.T) {
	mockSms := NewMockSmsProvider()
	otpService := NewOtpService(mockSms)

	phone := "+1234567890"
	ip := "192.168.1.100"

	// 1. Initial request should succeed
	_, err := otpService.RequestOtp(phone, ip)
	if err != nil {
		t.Fatalf("first OTP request failed: %v", err)
	}

	// 2. Cooldown check (request within 60s should fail)
	_, err = otpService.RequestOtp(phone, ip)
	if err == nil {
		t.Fatalf("expected error due to 60s cooldown, got nil")
	}

	// 3. Verify OTP code retrieved by mock SMS
	otpCode := mockSms.GetLastOtp(phone)
	if len(otpCode) != 6 {
		t.Fatalf("expected 6-digit OTP code, got: %s", otpCode)
	}

	// 4. Verify invalid OTP code fails and tracks attempts
	_, err = otpService.VerifyOtp(phone, "000000")
	if err == nil {
		t.Fatalf("expected verification failure with wrong code")
	}

	// 5. Correct OTP code succeeds and returns single-use AuthProofTicket
	ticket, err := otpService.VerifyOtp(phone, otpCode)
	if err != nil {
		t.Fatalf("correct OTP verification failed: %v", err)
	}
	if ticket == "" {
		t.Fatalf("expected non-empty AuthProofTicket")
	}

	// 6. Anti-replay: same code cannot be verified again
	_, err = otpService.VerifyOtp(phone, otpCode)
	if err == nil {
		t.Fatalf("expected error verifying already consumed OTP")
	}
}

func TestAuthRegistrationWorkflow(t *testing.T) {
	tmpDir, _ := os.MkdirTemp("", "nexa_test_auth_*")
	defer os.RemoveAll(tmpDir)

	dbPath := filepath.Join(tmpDir, "identity.json")
	store, _ := identity.NewStore(dbPath)
	mockSms := NewMockSmsProvider()
	otpService := NewOtpService(mockSms)
	googleVerifier := NewGoogleVerifier()

	handler := NewAuthHandler(store, otpService, googleVerifier)
	if handler == nil {
		t.Fatalf("failed to create AuthHandler")
	}

	phone := "+919876543210"
	_, _ = otpService.RequestOtp(phone, "127.0.0.1")
	code := mockSms.GetLastOtp(phone)
	ticket, err := otpService.VerifyOtp(phone, code)
	if err != nil {
		t.Fatalf("failed to verify OTP: %v", err)
	}

	pub, _, _ := ed25519.GenerateKey(rand.Reader)
	pubHex := hex.EncodeToString(pub)

	user, session, err := store.RegisterUser(phone, "", pubHex, "dev_pixel_9", "Pixel 9", "Bob")
	if err != nil {
		t.Fatalf("failed to register user: %v", err)
	}

	if user.PhoneNumber != phone || session.Token == "" {
		t.Fatalf("user registration mismatch")
	}

	// Verify session token lookup
	sess, ok := store.GetSession(session.Token)
	if !ok || sess == nil || sess.NxID != user.NxID {
		t.Fatalf("failed to validate active session")
	}

	// Revoke session
	_ = store.RevokeSession(session.Token)
	sessRevoked, ok := store.GetSession(session.Token)
	if ok || sessRevoked != nil {
		t.Fatalf("expected nil session after revocation")
	}
	_ = ticket
}

func TestProfileGetAndPut(t *testing.T) {
	tmpDir, _ := os.MkdirTemp("", "nexa_test_profile_*")
	defer os.RemoveAll(tmpDir)

	dbPath := filepath.Join(tmpDir, "identity.json")
	store, _ := identity.NewStore(dbPath)
	mockSms := NewMockSmsProvider()
	otpService := NewOtpService(mockSms)
	googleVerifier := NewGoogleVerifier()

	handler := NewAuthHandler(store, otpService, googleVerifier)

	pub, _, _ := ed25519.GenerateKey(rand.Reader)
	pubHex := hex.EncodeToString(pub)

	nxID, err := store.AllocateNxID(pubHex)
	if err != nil {
		t.Fatalf("failed to allocate NX-ID: %v", err)
	}

	initialAccount := &identity.UserAccount{
		ID:                    "usr_test123",
		NxID:                  nxID,
		PublicKeyHex:          pubHex,
		PhoneNumber:           "+919876543210",
		PhoneVerified:         true,
		IsDiscoverableByPhone: true,
		GoogleID:              "google_sub_123456",
		Email:                 "testuser@gmail.com",
		ProfileName:           "Test Google User",
		AvatarUrl:             "https://lh3.googleusercontent.com/photo.jpg",
		Age:                   25,
		Gender:                "Male",
		Bio:                   "Exploring NEXA mesh",
		CreatedAt:             1700000000000,
		UpdatedAt:             1700000000000,
	}
	if err := store.CreateAccount(initialAccount); err != nil {
		t.Fatalf("failed to create initial account: %v", err)
	}

	sessionToken := "nexa_sess_test_token_profile_123456"
	session := &identity.Session{
		Token:     sessionToken,
		AccountID: initialAccount.ID,
		NxID:      nxID,
		DeviceID:  "dev_pixel",
		ExpiresAt: 1800000000000,
		CreatedAt: 1700000000000,
	}
	if err := store.CreateSession(session); err != nil {
		t.Fatalf("failed to create session: %v", err)
	}

	// 1. GET /api/v1/profile without auth -> 401
	getReqNoAuth := httptest.NewRequest(http.MethodGet, "/api/v1/profile", nil)
	getRecNoAuth := httptest.NewRecorder()
	handler.handleProfile(getRecNoAuth, getReqNoAuth)
	if getRecNoAuth.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401 Unauthorized for missing token, got %d", getRecNoAuth.Code)
	}

	// 2. GET /api/v1/profile with valid Bearer token -> 200 and matches account
	getReq := httptest.NewRequest(http.MethodGet, "/api/v1/profile", nil)
	getReq.Header.Set("Authorization", "Bearer "+sessionToken)
	getRec := httptest.NewRecorder()
	handler.handleProfile(getRec, getReq)
	if getRec.Code != http.StatusOK {
		t.Fatalf("expected 200 OK from GET /api/v1/profile, got %d: %s", getRec.Code, getRec.Body.String())
	}

	var fetched identity.UserAccount
	if err := json.Unmarshal(getRec.Body.Bytes(), &fetched); err != nil {
		t.Fatalf("failed to unmarshal fetched profile: %v", err)
	}
	if fetched.NxID != nxID || fetched.Email != "testuser@gmail.com" || fetched.Age != 25 || fetched.ProfileName != "Test Google User" {
		t.Fatalf("fetched profile metadata mismatch: %+v", fetched)
	}

	// 3. PUT /api/v1/profile with updated metadata
	newAge := 26
	updatePayload := ProfileUpdateRequest{
		ProfileName: "Updated Name",
		AvatarUrl:   "https://lh3.googleusercontent.com/new_photo.jpg",
		Bio:         "Updated Bio",
		Gender:      "Other",
		Age:         &newAge,
	}
	updateBytes, _ := json.Marshal(updatePayload)
	putReq := httptest.NewRequest(http.MethodPut, "/api/v1/profile", bytes.NewReader(updateBytes))
	putReq.Header.Set("Authorization", "Bearer "+sessionToken)
	putRec := httptest.NewRecorder()
	handler.handleProfile(putRec, putReq)
	if putRec.Code != http.StatusOK {
		t.Fatalf("expected 200 OK from PUT /api/v1/profile, got %d: %s", putRec.Code, putRec.Body.String())
	}

	// 4. Verify updated account in store
	updatedAcc, ok := store.GetAccountByNxID(nxID)
	if !ok {
		t.Fatalf("failed to load updated account from store")
	}
	if updatedAcc.ProfileName != "Updated Name" || updatedAcc.AvatarUrl != "https://lh3.googleusercontent.com/new_photo.jpg" || updatedAcc.Age != 26 || updatedAcc.Bio != "Updated Bio" {
		t.Fatalf("updated account field mismatch in store: %+v", updatedAcc)
	}
}
