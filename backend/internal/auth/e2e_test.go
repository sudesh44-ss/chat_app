package auth

import (
	"bytes"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"sync"
	"testing"
	"time"

	"nexa/wire/internal/identity"
)

// e2eHarness runs the real auth router over a loopback HTTP server with the
// JSON-backed identity store and the mock SMS provider. It exercises the same
// registration, login, session and device endpoints the Android client calls,
// without requiring Postgres or Redis.
type e2eHarness struct {
	t       *testing.T
	server  *httptest.Server
	client  *http.Client
	store   *identity.Store
	sms     *MockSmsProvider
	otp     *OtpService
	handler *AuthHandler
}

type e2eResponse struct {
	Status int
	Body   []byte
	JSON   map[string]interface{}
}

func newE2EHarness(t *testing.T) *e2eHarness {
	t.Helper()

	t.Setenv("APP_ENV", "test")
	t.Setenv("OTP_SERVER_PEPPER", "e2e-test-pepper-not-for-production")

	store, err := identity.NewStore(filepath.Join(t.TempDir(), "identity.json"))
	if err != nil {
		t.Fatalf("identity.NewStore: %v", err)
	}

	sms := NewMockSmsProvider()
	otp := NewOtpService(sms)
	handler := NewAuthHandler(store, otp, nil)

	mux := http.NewServeMux()
	handler.RegisterRoutes(mux)

	server := httptest.NewServer(mux)
	t.Cleanup(server.Close)

	return &e2eHarness{
		t:       t,
		server:  server,
		client:  &http.Client{Timeout: 10 * time.Second},
		store:   store,
		sms:     sms,
		otp:     otp,
		handler: handler,
	}
}

func (h *e2eHarness) do(method, path, token string, body interface{}) e2eResponse {
	h.t.Helper()

	var reader io.Reader
	if body != nil {
		payload, err := json.Marshal(body)
		if err != nil {
			h.t.Fatalf("marshal %s: %v", path, err)
		}
		reader = bytes.NewReader(payload)
	}

	req, err := http.NewRequest(method, h.server.URL+path, reader)
	if err != nil {
		h.t.Fatalf("new request %s: %v", path, err)
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}

	resp, err := h.client.Do(req)
	if err != nil {
		h.t.Fatalf("%s %s: %v", method, path, err)
	}
	defer resp.Body.Close()

	raw, err := io.ReadAll(resp.Body)
	if err != nil {
		h.t.Fatalf("read %s response: %v", path, err)
	}

	parsed := map[string]interface{}{}
	if len(raw) > 0 && json.Unmarshal(raw, &parsed) != nil {
		// Non-JSON bodies are still returned for assertion messages.
		parsed = map[string]interface{}{}
	}

	return e2eResponse{Status: resp.StatusCode, Body: raw, JSON: parsed}
}

func (h *e2eHarness) mustStatus(resp e2eResponse, want int, what string) {
	h.t.Helper()
	if resp.Status != want {
		h.t.Fatalf("%s: expected HTTP %d, got %d: %s", what, want, resp.Status, string(resp.Body))
	}
}

func (h *e2eHarness) requestOtp(phone string) {
	h.t.Helper()
	resp := h.do(http.MethodPost, "/api/v1/auth/otp/request", "", map[string]string{
		"phone_number": phone,
	})
	h.mustStatus(resp, http.StatusOK, "otp request for "+phone)
}

func (h *e2eHarness) otpTicket(phone string) string {
	h.t.Helper()
	h.requestOtp(phone)

	code := h.sms.GetLastOtp(phone)
	if len(code) != 6 {
		h.t.Fatalf("mock SMS provider did not receive a 6-digit OTP for %s (got %q)", phone, code)
	}

	resp := h.do(http.MethodPost, "/api/v1/auth/otp/verify", "", map[string]string{
		"phone_number": phone,
		"otp_code":     code,
	})
	h.mustStatus(resp, http.StatusOK, "otp verify for "+phone)

	ticket, _ := resp.JSON["auth_ticket"].(string)
	if ticket == "" {
		h.t.Fatalf("otp verify did not return an auth ticket: %s", string(resp.Body))
	}
	return ticket
}

func registrationSignature(priv ed25519.PrivateKey, ticket, pubHex, deviceID string) string {
	bound := []byte(fmt.Sprintf("NEXA_REGISTER:%s:%s:%s", ticket, pubHex, deviceID))
	return hex.EncodeToString(ed25519.Sign(priv, bound))
}

func (h *e2eHarness) registerWithTicket(ticket, deviceID, profile string, priv ed25519.PrivateKey) e2eResponse {
	h.t.Helper()

	pubHex := hex.EncodeToString(priv.Public().(ed25519.PublicKey))
	return h.do(http.MethodPost, "/api/v1/auth/register", "", map[string]interface{}{
		"auth_proof_ticket": ticket,
		"auth_type":         "PHONE_OTP",
		"public_key_hex":    pubHex,
		"device_id":         deviceID,
		"device_name":       "E2E Device",
		"profile_name":      profile,
		"signature_hex":     registrationSignature(priv, ticket, pubHex, deviceID),
	})
}

func (h *e2eHarness) mustRegister(phone, deviceID, profile string) (string, string, ed25519.PrivateKey) {
	h.t.Helper()

	_, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		h.t.Fatalf("ed25519 key: %v", err)
	}

	ticket := h.otpTicket(phone)
	resp := h.registerWithTicket(ticket, deviceID, profile, priv)
	h.mustStatus(resp, http.StatusOK, "register "+deviceID)

	nxID, _ := resp.JSON["nx_id"].(string)
	token, _ := resp.JSON["session_auth_token"].(string)
	if nxID == "" || token == "" {
		h.t.Fatalf("registration response missing identity/token: %s", string(resp.Body))
	}
	return nxID, token, priv
}

func (h *e2eHarness) devices(token string) (int, deviceListResponse) {
	h.t.Helper()

	resp := h.do(http.MethodGet, "/api/v1/auth/devices", token, nil)
	var parsed deviceListResponse
	if resp.Status == http.StatusOK {
		if err := json.Unmarshal(resp.Body, &parsed); err != nil {
			h.t.Fatalf("decode devices response: %v (%s)", err, string(resp.Body))
		}
	}
	return resp.Status, parsed
}

type deviceListResponse struct {
	Devices        []identity.Device      `json:"devices"`
	RevokedDevices []identity.Device      `json:"revoked_devices"`
	Events         []identity.DeviceEvent `json:"events"`
}

func TestE2E_PhoneOtpRegistrationDeviceListAndLogout(t *testing.T) {
	h := newE2EHarness(t)
	phone := "+919876540001"

	nxID, token, _ := h.mustRegister(phone, "dev_e2e_1", "E2E One")
	if len(nxID) < 4 || nxID[:3] != "NX-" {
		t.Fatalf("expected NX-XXXXXXXX identity, got %q", nxID)
	}

	status, list := h.devices(token)
	if status != http.StatusOK {
		t.Fatalf("device list failed: HTTP %d", status)
	}
	if len(list.Devices) != 1 || list.Devices[0].DeviceID != "dev_e2e_1" {
		t.Fatalf("expected exactly the registered device, got %+v", list.Devices)
	}
	if len(list.RevokedDevices) != 0 {
		t.Fatalf("fresh account must have no revoked devices, got %+v", list.RevokedDevices)
	}
	if len(list.Events) == 0 {
		t.Fatal("registration should append a device audit event")
	}

	logout := h.do(http.MethodPost, "/api/v1/auth/logout", token, nil)
	h.mustStatus(logout, http.StatusOK, "logout")

	if status, _ := h.devices(token); status != http.StatusUnauthorized {
		t.Fatalf("logged-out session must be rejected, got HTTP %d", status)
	}
}

func TestE2E_ReRegistrationRevokesPriorDeviceAndSessions(t *testing.T) {
	h := newE2EHarness(t)
	phone := "+919876540002"

	nxID, firstToken, priv := h.mustRegister(phone, "dev_e2e_old", "E2E Old")

	// Re-registration keeps the same identity key (the app's stable Ed25519
	// identity) while presenting a new device.
	secondTicket := h.otpTicket(phone)
	second := h.registerWithTicket(secondTicket, "dev_e2e_new", "E2E New", priv)
	h.mustStatus(second, http.StatusOK, "re-register with replacement device")
	secondToken, _ := second.JSON["session_auth_token"].(string)
	if secondToken == "" {
		t.Fatalf("re-registration did not return a session: %s", string(second.Body))
	}

	if status, _ := h.devices(firstToken); status != http.StatusUnauthorized {
		t.Fatalf("replaced device session must be revoked, got HTTP %d", status)
	}

	status, list := h.devices(secondToken)
	if status != http.StatusOK {
		t.Fatalf("replacement device session must work, got HTTP %d", status)
	}
	if len(list.Devices) != 1 || list.Devices[0].DeviceID != "dev_e2e_new" {
		t.Fatalf("expected only the new device active, got %+v", list.Devices)
	}
	if len(list.RevokedDevices) != 1 || list.RevokedDevices[0].DeviceID != "dev_e2e_old" {
		t.Fatalf("expected old device in revocation history, got %+v", list.RevokedDevices)
	}

	foundReplaced := false
	for _, ev := range list.Events {
		if ev.DeviceID == "dev_e2e_old" && ev.EventType == "REVOKED_REPLACED" {
			foundReplaced = true
		}
	}
	if !foundReplaced {
		t.Fatalf("expected REVOKED_REPLACED audit event, got %+v", list.Events)
	}

	// A revoked device cannot obtain a new login challenge.
	chal := h.do(http.MethodPost, "/api/v1/auth/login-challenge", "", map[string]string{
		"nx_id":     nxID,
		"device_id": "dev_e2e_old",
	})
	h.mustStatus(chal, http.StatusUnauthorized, "revoked device challenge")
}

func TestE2E_LoginChallengeRotationAndReplayRejection(t *testing.T) {
	h := newE2EHarness(t)
	phone := "+919876540003"

	nxID, firstToken, priv := h.mustRegister(phone, "dev_e2e_login", "E2E Login")

	chal := h.do(http.MethodPost, "/api/v1/auth/login-challenge", "", map[string]string{
		"nx_id":     nxID,
		"device_id": "dev_e2e_login",
	})
	h.mustStatus(chal, http.StatusOK, "login challenge")

	challengeID, _ := chal.JSON["challenge_id"].(string)
	challengeHex, _ := chal.JSON["challenge"].(string)
	raw, err := hex.DecodeString(challengeHex)
	if err != nil || len(raw) == 0 {
		raw = []byte(challengeHex)
	}

	verifyBody := map[string]string{
		"challenge_id": challengeID,
		"challenge":    challengeHex,
		"nx_id":        nxID,
		"device_id":    "dev_e2e_login",
		"signature":    hex.EncodeToString(ed25519.Sign(priv, raw)),
	}
	verify := h.do(http.MethodPost, "/api/v1/auth/login-verify", "", verifyBody)
	h.mustStatus(verify, http.StatusOK, "login verify")

	secondToken, _ := verify.JSON["session_auth_token"].(string)
	if secondToken == "" || secondToken == firstToken {
		t.Fatalf("login must issue a fresh token, got %q", secondToken)
	}
	if status, _ := h.devices(firstToken); status != http.StatusUnauthorized {
		t.Fatalf("prior device session must be rotated out, got HTTP %d", status)
	}
	if status, _ := h.devices(secondToken); status != http.StatusOK {
		t.Fatalf("rotated login session must be active, got HTTP %d", status)
	}

	// Replaying the consumed challenge must not mint another session.
	replay := h.do(http.MethodPost, "/api/v1/auth/login-verify", "", verifyBody)
	h.mustStatus(replay, http.StatusUnauthorized, "challenge replay")
}

func TestE2E_AuthTicketReplayRejected(t *testing.T) {
	h := newE2EHarness(t)
	phone := "+919876540004"

	_, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatalf("ed25519 key: %v", err)
	}

	ticket := h.otpTicket(phone)
	first := h.registerWithTicket(ticket, "dev_e2e_ticket_1", "E2E Ticket", priv)
	h.mustStatus(first, http.StatusOK, "first registration with ticket")

	replay := h.registerWithTicket(ticket, "dev_e2e_ticket_2", "E2E Ticket Replay", priv)
	h.mustStatus(replay, http.StatusUnauthorized, "ticket replay")

	count, err := h.store.CountAccounts()
	if err != nil {
		t.Fatalf("CountAccounts: %v", err)
	}
	if count != 1 {
		t.Fatalf("ticket replay must not create extra accounts, got %d", count)
	}
}

func TestE2E_BruteForceOtpLockout(t *testing.T) {
	h := newE2EHarness(t)
	phone := "+919876540005"

	h.requestOtp(phone)
	correct := h.sms.GetLastOtp(phone)
	wrong := "000000"
	if correct == wrong {
		wrong = "000001"
	}

	for attempt := 1; attempt <= 3; attempt++ {
		resp := h.do(http.MethodPost, "/api/v1/auth/otp/verify", "", map[string]string{
			"phone_number": phone,
			"otp_code":     wrong,
		})
		h.mustStatus(resp, http.StatusUnauthorized, fmt.Sprintf("wrong OTP attempt %d", attempt))
	}

	locked := h.do(http.MethodPost, "/api/v1/auth/otp/verify", "", map[string]string{
		"phone_number": phone,
		"otp_code":     wrong,
	})
	h.mustStatus(locked, http.StatusTooManyRequests, "OTP attempts exhausted")

	// The record is invalidated, so even the originally correct code fails.
	after := h.do(http.MethodPost, "/api/v1/auth/otp/verify", "", map[string]string{
		"phone_number": phone,
		"otp_code":     correct,
	})
	h.mustStatus(after, http.StatusUnauthorized, "correct OTP after lockout")
}

func TestE2E_OtpResendCooldown(t *testing.T) {
	h := newE2EHarness(t)
	phone := "+919876540006"

	h.requestOtp(phone)

	resp := h.do(http.MethodPost, "/api/v1/auth/otp/request", "", map[string]string{
		"phone_number": phone,
	})
	h.mustStatus(resp, http.StatusTooManyRequests, "immediate OTP resend")

	if code, _ := resp.JSON["code"].(string); code != "RATE_LIMITED" {
		t.Fatalf("expected RATE_LIMITED, got %q (%s)", code, string(resp.Body))
	}
}

func TestE2E_ConcurrentOtpVerificationSingleSuccess(t *testing.T) {
	h := newE2EHarness(t)
	phone := "+919876540007"

	h.requestOtp(phone)
	code := h.sms.GetLastOtp(phone)

	const workers = 8
	statuses := make([]int, workers)
	var wg sync.WaitGroup
	for i := 0; i < workers; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			resp := h.do(http.MethodPost, "/api/v1/auth/otp/verify", "", map[string]string{
				"phone_number": phone,
				"otp_code":     code,
			})
			statuses[idx] = resp.Status
		}(i)
	}
	wg.Wait()

	successes := 0
	for _, status := range statuses {
		if status == http.StatusOK {
			successes++
		}
	}
	// Single-use is the security invariant. Concurrent correct submissions may
	// also trip the attempt counter, so zero successes is acceptable; more than
	// one would mean the same OTP minted multiple tickets.
	if successes > 1 {
		t.Fatalf("at most one concurrent OTP verification may succeed, got %d (statuses=%v)", successes, statuses)
	}
}

func TestE2E_ConcurrentTicketConsumptionSingleRegistration(t *testing.T) {
	h := newE2EHarness(t)
	phone := "+919876540008"

	_, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatalf("ed25519 key: %v", err)
	}
	ticket := h.otpTicket(phone)

	const workers = 8
	statuses := make([]int, workers)
	var wg sync.WaitGroup
	for i := 0; i < workers; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			resp := h.registerWithTicket(ticket, "dev_e2e_race", "E2E Race", priv)
			statuses[idx] = resp.Status
		}(i)
	}
	wg.Wait()

	successes := 0
	for _, status := range statuses {
		if status == http.StatusOK {
			successes++
		}
	}
	if successes != 1 {
		t.Fatalf("exactly one concurrent ticket redemption may succeed, got %d (statuses=%v)", successes, statuses)
	}

	count, err := h.store.CountAccounts()
	if err != nil {
		t.Fatalf("CountAccounts: %v", err)
	}
	if count != 1 {
		t.Fatalf("expected one account after concurrent redemption, got %d", count)
	}
}

func TestE2E_RevokedDeviceSessionRejected(t *testing.T) {
	h := newE2EHarness(t)
	phone := "+919876540009"

	_, token, _ := h.mustRegister(phone, "dev_e2e_revoke", "E2E Revoke")

	revoke := h.do(http.MethodPost, "/api/v1/devices/dev_e2e_revoke/revoke", token, nil)
	h.mustStatus(revoke, http.StatusOK, "revoke own device")

	if status, _ := h.devices(token); status != http.StatusUnauthorized {
		t.Fatalf("revoked device session must be rejected, got HTTP %d", status)
	}

	rotate := h.do(http.MethodPost, "/api/v1/auth/session/rotate", token, nil)
	h.mustStatus(rotate, http.StatusUnauthorized, "rotate revoked device session")
}

func TestE2E_SessionRotateEndpoint(t *testing.T) {
	h := newE2EHarness(t)
	phone := "+919876540010"

	_, token, _ := h.mustRegister(phone, "dev_e2e_rotate", "E2E Rotate")

	rotate := h.do(http.MethodPost, "/api/v1/auth/session/rotate", token, nil)
	h.mustStatus(rotate, http.StatusOK, "session rotate")

	newToken, _ := rotate.JSON["session_auth_token"].(string)
	if newToken == "" || newToken == token {
		t.Fatalf("rotation must return a fresh token, got %q", newToken)
	}

	if status, _ := h.devices(token); status != http.StatusUnauthorized {
		t.Fatalf("old token must be revoked, got HTTP %d", status)
	}
	if status, _ := h.devices(newToken); status != http.StatusOK {
		t.Fatalf("new token must be active, got HTTP %d", status)
	}

	// The alias must behave identically.
	refresh := h.do(http.MethodPost, "/api/v1/auth/session/refresh", newToken, nil)
	h.mustStatus(refresh, http.StatusOK, "session refresh alias")
	refreshed, _ := refresh.JSON["session_auth_token"].(string)
	if refreshed == "" || refreshed == newToken {
		t.Fatalf("refresh alias must return a fresh token, got %q", refreshed)
	}
}
