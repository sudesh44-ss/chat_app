package auth

import (
	"crypto/ed25519"
	"crypto/rand"
	"encoding/hex"
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
