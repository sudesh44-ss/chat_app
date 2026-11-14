package auth

import (
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"nexa/wire/internal/identity"
)

func newAccountSecurityTestHarness(t *testing.T, phone string) (*AuthHandler, *OtpService, *MockSmsProvider, *identity.Store, string) {
	t.Helper()

	store, err := identity.NewStore(filepath.Join(t.TempDir(), "identity.json"))
	if err != nil {
		t.Fatalf("failed to create identity store: %v", err)
	}

	account := &identity.UserAccount{
		ID:                    "usr_test",
		NxID:                  "NX-TEST000001",
		PublicKeyHex:          strings.Repeat("a", 64),
		PhoneNumber:           phone,
		PhoneVerified:         true,
		IsDiscoverableByPhone: true,
		ProfileName:           "Test User",
		CreatedAt:             time.Now().UnixMilli(),
		UpdatedAt:             time.Now().UnixMilli(),
	}
	if err := store.CreateAccount(account); err != nil {
		t.Fatalf("failed to create account: %v", err)
	}

	sessionToken := "nexa_sess_test_token"
	if err := store.CreateSession(&identity.Session{
		Token:     sessionToken,
		AccountID: account.ID,
		NxID:      account.NxID,
		DeviceID:  "dev_test",
		ExpiresAt: time.Now().Add(time.Hour).UnixMilli(),
		CreatedAt: time.Now().UnixMilli(),
	}); err != nil {
		t.Fatalf("failed to create session: %v", err)
	}

	mockSms := NewMockSmsProvider()
	otpService := NewOtpService(mockSms)
	handler := NewAuthHandler(store, otpService, nil)
	return handler, otpService, mockSms, store, sessionToken
}

func performAuthedJSON(t *testing.T, handler http.HandlerFunc, sessionToken, body string) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(http.MethodPost, "/", strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+sessionToken)
	rec := httptest.NewRecorder()
	handler(rec, req)
	return rec
}

func TestPhoneChange_HappyPathCommitsServerSide(t *testing.T) {
	handler, _, mockSms, store, token := newAccountSecurityTestHarness(t, "+919876500001")
	newPhone := "+919876500002"

	rec := performAuthedJSON(t, handler.handlePhoneChangeRequest, token, `{"new_phone":"`+newPhone+`"}`)
	if rec.Code != http.StatusOK {
		t.Fatalf("request failed: %d %s", rec.Code, rec.Body.String())
	}

	otp := mockSms.GetLastOtp(newPhone)
	if len(otp) != 6 {
		t.Fatalf("expected 6-digit OTP, got %q", otp)
	}

	rec = performAuthedJSON(t, handler.handlePhoneChangeVerify, token,
		`{"new_phone":"`+newPhone+`","otp_code":"`+otp+`"}`)
	if rec.Code != http.StatusOK {
		t.Fatalf("verify failed: %d %s", rec.Code, rec.Body.String())
	}

	account, ok := store.GetAccountByNxID("NX-TEST000001")
	if !ok {
		t.Fatal("account disappeared")
	}
	if account.PhoneNumber != newPhone {
		t.Fatalf("expected phone %s, got %s", newPhone, account.PhoneNumber)
	}
	if !account.PhoneVerified {
		t.Fatal("expected phone to be marked verified")
	}
}

func TestPhoneChange_WrongOtpRejected(t *testing.T) {
	handler, _, _, store, token := newAccountSecurityTestHarness(t, "+919876500003")
	newPhone := "+919876500004"

	rec := performAuthedJSON(t, handler.handlePhoneChangeRequest, token, `{"new_phone":"`+newPhone+`"}`)
	if rec.Code != http.StatusOK {
		t.Fatalf("request failed: %d %s", rec.Code, rec.Body.String())
	}

	rec = performAuthedJSON(t, handler.handlePhoneChangeVerify, token,
		`{"new_phone":"`+newPhone+`","otp_code":"000000"}`)
	if rec.Code == http.StatusOK {
		t.Fatal("expected wrong OTP to be rejected")
	}

	account, _ := store.GetAccountByNxID("NX-TEST000001")
	if account.PhoneNumber == newPhone {
		t.Fatal("phone must not change when OTP verification fails")
	}
}

func TestPhoneChange_RejectsPhoneOwnedByAnotherAccount(t *testing.T) {
	handler, _, _, store, token := newAccountSecurityTestHarness(t, "+919876500005")
	conflictPhone := "+919876500006"

	if err := store.CreateAccount(&identity.UserAccount{
		ID:            "usr_other",
		NxID:          "NX-TEST000002",
		PublicKeyHex:  strings.Repeat("b", 64),
		PhoneNumber:   conflictPhone,
		PhoneVerified: true,
		ProfileName:   "Other",
		CreatedAt:     time.Now().UnixMilli(),
		UpdatedAt:     time.Now().UnixMilli(),
	}); err != nil {
		t.Fatalf("failed to seed conflicting account: %v", err)
	}

	rec := performAuthedJSON(t, handler.handlePhoneChangeRequest, token, `{"new_phone":"`+conflictPhone+`"}`)
	if rec.Code != http.StatusConflict {
		t.Fatalf("expected 409 conflict, got %d %s", rec.Code, rec.Body.String())
	}
}

func TestPhoneChange_RequiresValidSession(t *testing.T) {
	handler, _, _, _, _ := newAccountSecurityTestHarness(t, "+919876500007")

	req := httptest.NewRequest(http.MethodPost, "/", strings.NewReader(`{"new_phone":"+919876500008"}`))
	req.Header.Set("Authorization", "Bearer not-a-real-token")
	rec := httptest.NewRecorder()
	handler.handlePhoneChangeRequest(rec, req)

	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401, got %d", rec.Code)
	}
}

func TestAccountDeletion_VerifyRequiresOtpOwnership(t *testing.T) {
	handler, _, mockSms, _, token := newAccountSecurityTestHarness(t, "+919876500009")

	rec := performAuthedJSON(t, handler.handleAccountDeletionRequest, token, `{}`)
	if rec.Code != http.StatusOK {
		t.Fatalf("deletion request failed: %d %s", rec.Code, rec.Body.String())
	}

	// Any six digits must not be accepted; only the server-issued OTP works.
	rec = performAuthedJSON(t, handler.handleAccountDeletionVerify, token, `{"otp_code":"123456"}`)
	if rec.Code == http.StatusOK {
		t.Fatal("expected arbitrary 6-digit code to be rejected")
	}

	otp := mockSms.GetLastOtp("+919876500009")
	rec = performAuthedJSON(t, handler.handleAccountDeletionVerify, token, `{"otp_code":"`+otp+`"}`)
	if rec.Code != http.StatusOK {
		t.Fatalf("expected valid OTP to succeed, got %d %s", rec.Code, rec.Body.String())
	}

	var payload map[string]interface{}
	if err := json.Unmarshal(rec.Body.Bytes(), &payload); err != nil {
		t.Fatalf("invalid JSON response: %v", err)
	}
	if payload["status"] != "IDENTITY_VERIFIED" {
		t.Fatalf("expected IDENTITY_VERIFIED, got %v", payload["status"])
	}
}

func TestAccountDeletion_RequiresVerifiedPhone(t *testing.T) {
	handler, _, _, store, token := newAccountSecurityTestHarness(t, "")

	account, _ := store.GetAccountByNxID("NX-TEST000001")
	account.PhoneVerified = false
	_ = store.UpdateAccount(account)

	rec := performAuthedJSON(t, handler.handleAccountDeletionRequest, token, `{}`)
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("expected 400, got %d %s", rec.Code, rec.Body.String())
	}
}

type alwaysSucceedsSmsProvider struct{}

func (alwaysSucceedsSmsProvider) SendOtp(phoneNumber string, otpCode string) error { return nil }
func (alwaysSucceedsSmsProvider) Name() string                                     { return "test-stub" }

func TestOtpPepper_ProductionWithoutSecretFailsClosed(t *testing.T) {
	t.Setenv("APP_ENV", "production")
	t.Setenv("OTP_SERVER_PEPPER", "")

	svc := NewOtpService(alwaysSucceedsSmsProvider{})
	_, err := svc.RequestOtp("+919876500010", "127.0.0.1")
	if err == nil {
		t.Fatal("expected OTP request to fail when production pepper is missing")
	}
	if !errors.Is(err, ErrOtpSecretMissing) {
		t.Fatalf("expected ErrOtpSecretMissing, got %v", err)
	}
}

func TestOtpPepper_ProductionWithSecretSucceeds(t *testing.T) {
	t.Setenv("APP_ENV", "production")
	t.Setenv("OTP_SERVER_PEPPER", "unit-test-production-pepper")

	svc := NewOtpService(alwaysSucceedsSmsProvider{})
	if _, err := svc.RequestOtp("+919876500011", "127.0.0.1"); err != nil {
		t.Fatalf("expected OTP request to succeed with configured pepper, got %v", err)
	}
}

func TestOtpPepper_DevelopmentAllowsFallback(t *testing.T) {
	t.Setenv("APP_ENV", "development")
	t.Setenv("OTP_SERVER_PEPPER", "")

	svc := NewOtpService(NewMockSmsProvider())
	if _, err := svc.RequestOtp("+919876500012", "127.0.0.1"); err != nil {
		t.Fatalf("expected development fallback to work, got %v", err)
	}
}
