package auth

import (
	"bytes"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"nexa/wire/internal/identity"
)

// faultInjectingRepository wraps a real repository and injects failures at the
// session/device boundaries. This lets the HTTP handlers be exercised with the
// same code paths they use in production while simulating a database outage.
type faultInjectingRepository struct {
	identity.IdentityRepository

	mu sync.Mutex

	createSessionErr  error
	revokeDeviceErr   error
	revokeSessionsErr error

	createSessionCalls int
}

func (r *faultInjectingRepository) CreateSession(sess *identity.Session) error {
	r.mu.Lock()
	r.createSessionCalls++
	err := r.createSessionErr
	r.mu.Unlock()

	if err != nil {
		return err
	}
	return r.IdentityRepository.CreateSession(sess)
}

func (r *faultInjectingRepository) RevokeDevice(deviceID string) error {
	r.mu.Lock()
	err := r.revokeDeviceErr
	r.mu.Unlock()

	if err != nil {
		return err
	}
	return r.IdentityRepository.RevokeDevice(deviceID)
}

func (r *faultInjectingRepository) RevokeSessionsForDevice(nxID, deviceID, exceptToken string, createdBefore int64) (int, error) {
	r.mu.Lock()
	err := r.revokeSessionsErr
	r.mu.Unlock()

	if err != nil {
		return 0, err
	}
	return r.IdentityRepository.RevokeSessionsForDevice(nxID, deviceID, exceptToken, createdBefore)
}

func (r *faultInjectingRepository) sessionCreateCalls() int {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.createSessionCalls
}

func newFailureStore(t *testing.T) *identity.Store {
	t.Helper()
	store, err := identity.NewStore("")
	if err != nil {
		t.Fatalf("identity.NewStore: %v", err)
	}
	return store
}

func newFailureHandler(t *testing.T, repo identity.IdentityRepository, otp *OtpService) *AuthHandler {
	t.Helper()
	return NewAuthHandler(repo, otp, nil)
}

func postAuthJSON(t *testing.T, fn http.HandlerFunc, token, body string) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(http.MethodPost, "/", strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	rec := httptest.NewRecorder()
	fn(rec, req)
	return rec
}

func TestFailure_RegistrationSessionErrorReturns500WithoutFakeToken(t *testing.T) {
	t.Setenv("APP_ENV", "test")

	store := newFailureStore(t)
	repo := &faultInjectingRepository{
		IdentityRepository: store,
		createSessionErr:   errors.New("simulated database write failure"),
	}
	otp := NewOtpService(NewMockSmsProvider())
	handler := newFailureHandler(t, repo, otp)

	phone := "+919876550001"
	ticket := issueTestTicket(t, otp, "PHONE_OTP", "", "", phone)

	pub, _, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatalf("ed25519 key: %v", err)
	}

	w, resp := postRegister(t, handler, RegisterRequest{
		AuthProofTicket: ticket,
		AuthType:        "PHONE_OTP",
		PublicKeyHex:    hex.EncodeToString(pub),
		DeviceID:        "dev_fail_session",
		DeviceName:      "Fault Device",
		ProfileName:     "Fault User",
	})

	if w.Code != http.StatusInternalServerError {
		t.Fatalf("expected HTTP 500 when session persistence fails, got %d: %s", w.Code, w.Body.String())
	}
	if _, ok := resp["session_auth_token"]; ok {
		t.Fatalf("failed registration must not return a session token: %s", w.Body.String())
	}
	if strings.Contains(w.Body.String(), "nexa_sess_") {
		t.Fatalf("failed registration leaked a session token: %s", w.Body.String())
	}
	if repo.sessionCreateCalls() != 1 {
		t.Fatalf("expected exactly one session write attempt, got %d", repo.sessionCreateCalls())
	}
	if _, ok := store.GetAccountByPhone(phone); !ok {
		t.Fatal("registration should still have created the account before the session write failed")
	}
}

func TestFailure_LoginSessionErrorKeepsPriorSessionActive(t *testing.T) {
	t.Setenv("APP_ENV", "test")

	store := newFailureStore(t)

	pub, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatalf("ed25519 key: %v", err)
	}
	pubHex := hex.EncodeToString(pub)

	now := time.Now().UnixMilli()
	account := &identity.UserAccount{
		ID:           "usr_fail_login",
		NxID:         "NX-FAILLOG1",
		PublicKeyHex: pubHex,
		ProfileName:  "Failing Login",
		CreatedAt:    now,
		UpdatedAt:    now,
	}
	if err := store.CreateAccount(account); err != nil {
		t.Fatalf("CreateAccount: %v", err)
	}
	if err := store.RegisterDevice(&identity.Device{
		DeviceID:     "dev_fail_login",
		AccountID:    account.ID,
		NxID:         account.NxID,
		DeviceName:   "Failing Login Device",
		PublicKeyHex: pubHex,
		CreatedAt:    now,
		LastActiveAt: now,
	}); err != nil {
		t.Fatalf("RegisterDevice: %v", err)
	}
	createTestSession(t, store, "sess_fail_login_old", account.ID, account.NxID, "dev_fail_login")

	repo := &faultInjectingRepository{
		IdentityRepository: store,
		createSessionErr:   errors.New("simulated login session write failure"),
	}
	handler := newFailureHandler(t, repo, NewOtpService(NewMockSmsProvider()))

	challengeBody, _ := json.Marshal(map[string]string{"nx_id": account.NxID, "device_id": "dev_fail_login"})
	challengeReq := httptest.NewRequest(http.MethodPost, "/api/v1/auth/login-challenge", bytes.NewReader(challengeBody))
	challengeRec := httptest.NewRecorder()
	handler.handleLoginChallenge(challengeRec, challengeReq)
	if challengeRec.Code != http.StatusOK {
		t.Fatalf("login-challenge failed: %d %s", challengeRec.Code, challengeRec.Body.String())
	}

	var challenge struct {
		ChallengeID string `json:"challenge_id"`
		Challenge   string `json:"challenge"`
	}
	if err := json.NewDecoder(challengeRec.Body).Decode(&challenge); err != nil {
		t.Fatalf("decode challenge: %v", err)
	}

	rawChallenge, err := hex.DecodeString(challenge.Challenge)
	if err != nil || len(rawChallenge) == 0 {
		rawChallenge = []byte(challenge.Challenge)
	}

	verifyBody, _ := json.Marshal(map[string]string{
		"challenge_id": challenge.ChallengeID,
		"challenge":    challenge.Challenge,
		"nx_id":        account.NxID,
		"device_id":    "dev_fail_login",
		"signature":    hex.EncodeToString(ed25519.Sign(priv, rawChallenge)),
	})
	verifyReq := httptest.NewRequest(http.MethodPost, "/api/v1/auth/login-verify", bytes.NewReader(verifyBody))
	verifyRec := httptest.NewRecorder()
	handler.handleLoginVerify(verifyRec, verifyReq)

	if verifyRec.Code != http.StatusInternalServerError {
		t.Fatalf("expected HTTP 500 when login cannot persist a session, got %d: %s", verifyRec.Code, verifyRec.Body.String())
	}
	if _, ok := store.GetSession("sess_fail_login_old"); !ok {
		t.Fatal("a failed login must not revoke the device's existing session")
	}
}

func TestFailure_SessionRotationCreateErrorKeepsOldSessionActive(t *testing.T) {
	t.Setenv("APP_ENV", "test")

	store := newFailureStore(t)
	repo := &faultInjectingRepository{
		IdentityRepository: store,
		createSessionErr:   errors.New("simulated session write failure"),
	}
	handler := newFailureHandler(t, repo, NewOtpService(NewMockSmsProvider()))

	account := seedRotationAccount(t, store, "NX-FAILROT1", "dev_fail_rot")
	createTestSession(t, store, "sess_fail_old", account.ID, account.NxID, "dev_fail_rot")

	w := postAuthJSON(t, handler.handleSessionRotate, "sess_fail_old", "")
	if w.Code != http.StatusInternalServerError {
		t.Fatalf("expected HTTP 500 when rotation cannot create a replacement, got %d: %s", w.Code, w.Body.String())
	}

	if _, ok := store.GetSession("sess_fail_old"); !ok {
		t.Fatal("a failed rotation must not revoke the caller's still-valid session")
	}
}

func TestFailure_SessionRotationRevokeErrorStillReturnsReplacement(t *testing.T) {
	t.Setenv("APP_ENV", "test")

	store := newFailureStore(t)
	repo := &faultInjectingRepository{
		IdentityRepository: store,
		revokeSessionsErr:  errors.New("simulated session revoke failure"),
	}
	handler := newFailureHandler(t, repo, NewOtpService(NewMockSmsProvider()))

	account := seedRotationAccount(t, store, "NX-FAILROT2", "dev_fail_rot2")
	createTestSession(t, store, "sess_fail_old2", account.ID, account.NxID, "dev_fail_rot2")

	w := postAuthJSON(t, handler.handleSessionRotate, "sess_fail_old2", "")
	if w.Code != http.StatusOK {
		t.Fatalf("rotation should still return a usable replacement, got %d: %s", w.Code, w.Body.String())
	}

	var resp struct {
		NewToken string `json:"session_auth_token"`
	}
	if err := json.NewDecoder(w.Body).Decode(&resp); err != nil {
		t.Fatalf("decode rotation response: %v", err)
	}
	if resp.NewToken == "" {
		t.Fatal("rotation response is missing the replacement token")
	}
	if _, ok := store.GetSession(resp.NewToken); !ok {
		t.Fatal("replacement token must be active even when sibling revocation fails")
	}
	if _, ok := store.GetSession("sess_fail_old2"); ok {
		t.Fatal("the presented token is always revoked explicitly after rotation")
	}
}

func TestFailure_SmsProviderOutageReturns503AndStoresNoOtp(t *testing.T) {
	t.Setenv("APP_ENV", "test")

	store := newFailureStore(t)
	otp := NewOtpService(&UnavailableSmsProvider{reason: "simulated carrier outage"})
	handler := newFailureHandler(t, store, otp)

	phone := "+919876550002"
	w := postAuthJSON(t, handler.handleOtpRequest, "", `{"phone_number":"`+phone+`"}`)
	if w.Code != http.StatusServiceUnavailable {
		t.Fatalf("expected HTTP 503 on SMS outage, got %d: %s", w.Code, w.Body.String())
	}

	var resp map[string]interface{}
	if err := json.Unmarshal(w.Body.Bytes(), &resp); err != nil {
		t.Fatalf("decode error response: %v", err)
	}
	if code, _ := resp["code"].(string); code != "SMS_UNAVAILABLE" {
		t.Fatalf("expected SMS_UNAVAILABLE, got %q", code)
	}

	if _, err := otp.VerifyOtp(phone, "123456"); !errors.Is(err, ErrInvalidOtp) {
		t.Fatalf("no OTP record may survive a provider outage, got %v", err)
	}
}

func TestFailure_ProductionWithoutDistributedOtpStateFailsClosed(t *testing.T) {
	t.Setenv("APP_ENV", "production")
	t.Setenv("OTP_SERVER_PEPPER", "production-test-pepper")

	store := newFailureStore(t)
	otp := NewOtpService(NewMockSmsProvider())
	otp.SetDistributedState(nil, true)
	handler := newFailureHandler(t, store, otp)

	phone := "+919876550003"

	request := postAuthJSON(t, handler.handleOtpRequest, "", `{"phone_number":"`+phone+`"}`)
	if request.Code != http.StatusServiceUnavailable {
		t.Fatalf("OTP request must fail closed in production without Redis, got %d: %s", request.Code, request.Body.String())
	}
	if !strings.Contains(request.Body.String(), "AUTH_STATE_UNAVAILABLE") {
		t.Fatalf("expected AUTH_STATE_UNAVAILABLE, got %s", request.Body.String())
	}

	verify := postAuthJSON(t, handler.handleOtpVerify, "", `{"phone_number":"`+phone+`","otp_code":"123456"}`)
	if verify.Code != http.StatusServiceUnavailable {
		t.Fatalf("OTP verification must fail closed in production without Redis, got %d: %s", verify.Code, verify.Body.String())
	}

	register := postAuthJSON(t, handler.handleRegister, "", `{"auth_proof_ticket":"ticket_missing_state","public_key_hex":"`+strings.Repeat("a", 64)+`","device_id":"dev_prod"}`)
	if register.Code != http.StatusServiceUnavailable {
		t.Fatalf("registration must report a state outage as 503, got %d: %s", register.Code, register.Body.String())
	}
	if !strings.Contains(register.Body.String(), "AUTH_STATE_UNAVAILABLE") {
		t.Fatalf("expected AUTH_STATE_UNAVAILABLE on registration, got %s", register.Body.String())
	}
}

func TestFailure_MalformedRegisterRequestCreatesNothing(t *testing.T) {
	t.Setenv("APP_ENV", "test")

	store := newFailureStore(t)
	handler := newFailureHandler(t, store, NewOtpService(NewMockSmsProvider()))

	w := postAuthJSON(t, handler.handleRegister, "", `{"auth_proof_ticket":`)
	if w.Code != http.StatusBadRequest {
		t.Fatalf("expected HTTP 400 for malformed JSON, got %d: %s", w.Code, w.Body.String())
	}

	count, err := store.CountAccounts()
	if err != nil {
		t.Fatalf("CountAccounts: %v", err)
	}
	if count != 0 {
		t.Fatalf("malformed request must not create an account, got %d", count)
	}
}

func TestFailure_DeviceRevokeErrorReturns500(t *testing.T) {
	t.Setenv("APP_ENV", "test")

	store := newFailureStore(t)
	account := seedRotationAccount(t, store, "NX-FAILREVOKE", "dev_fail_revoke")
	createTestSession(t, store, "sess_fail_revoke", account.ID, account.NxID, "dev_fail_revoke")

	repo := &faultInjectingRepository{
		IdentityRepository: store,
		revokeDeviceErr:    errors.New("simulated revoke failure"),
	}
	handler := newFailureHandler(t, repo, NewOtpService(NewMockSmsProvider()))

	req := httptest.NewRequest(http.MethodPost, "/api/v1/devices/dev_fail_revoke/revoke", bytes.NewReader(nil))
	req.Header.Set("Authorization", "Bearer sess_fail_revoke")
	rec := httptest.NewRecorder()
	handler.handleDeviceAction(rec, req)

	if rec.Code != http.StatusInternalServerError {
		t.Fatalf("expected HTTP 500 when device revocation fails, got %d: %s", rec.Code, rec.Body.String())
	}

	device, ok := store.GetDevice("dev_fail_revoke")
	if !ok || device.IsRevoked {
		t.Fatal("failed revocation must not mark the device revoked")
	}
}

func TestFailure_OtpStateOutageDuringLinkReturns503(t *testing.T) {
	t.Setenv("APP_ENV", "production")
	t.Setenv("OTP_SERVER_PEPPER", "production-test-pepper")

	store := newFailureStore(t)
	account := seedRotationAccount(t, store, "NX-FAILLINK", "dev_fail_link")
	createTestSession(t, store, "sess_fail_link", account.ID, account.NxID, "dev_fail_link")

	otp := NewOtpService(NewMockSmsProvider())
	otp.SetDistributedState(nil, true)
	handler := newFailureHandler(t, store, otp)

	w := postAuthJSON(t, handler.handleAccountLink, "sess_fail_link", `{"link_type":"PHONE_OTP","auth_proof_ticket":"ticket_missing_state"}`)
	if w.Code != http.StatusServiceUnavailable {
		t.Fatalf("expected HTTP 503 on link state outage, got %d: %s", w.Code, w.Body.String())
	}
	if !strings.Contains(w.Body.String(), "AUTH_STATE_UNAVAILABLE") {
		t.Fatalf("expected AUTH_STATE_UNAVAILABLE, got %s", w.Body.String())
	}
}
