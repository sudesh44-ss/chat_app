package auth

import (
	"bytes"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"nexa/wire/internal/identity"
)

func seedRotationAccount(t *testing.T, store *identity.Store, nxID, deviceID string) *identity.UserAccount {
	t.Helper()

	now := time.Now().UnixMilli()
	account := &identity.UserAccount{
		ID:           "usr_" + nxID,
		NxID:         nxID,
		PublicKeyHex: "abcd",
		ProfileName:  "Rotation Test",
		CreatedAt:    now,
		UpdatedAt:    now,
	}
	if err := store.CreateAccount(account); err != nil {
		t.Fatalf("CreateAccount: %v", err)
	}
	if err := store.RegisterDevice(&identity.Device{
		DeviceID:     deviceID,
		AccountID:    account.ID,
		NxID:         nxID,
		DeviceName:   "Rotation Test Device",
		PublicKeyHex: "abcd",
		CreatedAt:    now,
		LastActiveAt: now,
	}); err != nil {
		t.Fatalf("RegisterDevice: %v", err)
	}
	return account
}

func createTestSession(t *testing.T, store *identity.Store, token, accountID, nxID, deviceID string) {
	t.Helper()
	if err := store.CreateSession(&identity.Session{
		Token:     token,
		AccountID: accountID,
		NxID:      nxID,
		DeviceID:  deviceID,
		ExpiresAt: time.Now().Add(time.Hour).UnixMilli(),
		CreatedAt: time.Now().UnixMilli(),
	}); err != nil {
		t.Fatalf("CreateSession(%s): %v", token, err)
	}
}

func TestSessionRotate_RevokesOldAndSiblingSessions(t *testing.T) {
	handler, store, cleanup := setupTestAuthHandler(t)
	defer cleanup()

	account := seedRotationAccount(t, store, "NX-ROT0001", "dev_rot_1")
	createTestSession(t, store, "sess_old_1", account.ID, account.NxID, "dev_rot_1")
	createTestSession(t, store, "sess_old_2", account.ID, account.NxID, "dev_rot_1")

	req := httptest.NewRequest(http.MethodPost, "/api/v1/auth/session/rotate", nil)
	req.Header.Set("Authorization", "Bearer sess_old_1")
	w := httptest.NewRecorder()
	handler.handleSessionRotate(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("expected HTTP 200, got %d: %s", w.Code, w.Body.String())
	}

	var resp struct {
		Status    string `json:"status"`
		NewToken  string `json:"session_auth_token"`
		NxID      string `json:"nx_id"`
		DeviceID  string `json:"device_id"`
		ExpiresAt int64  `json:"expires_at"`
	}
	if err := json.NewDecoder(w.Body).Decode(&resp); err != nil {
		t.Fatalf("decode response: %v", err)
	}

	if resp.Status != "SESSION_ROTATED" || resp.NewToken == "" {
		t.Fatalf("unexpected rotation response: %+v", resp)
	}
	if resp.NewToken == "sess_old_1" || resp.NewToken == "sess_old_2" {
		t.Fatal("rotation must issue a fresh token")
	}
	if resp.NxID != account.NxID || resp.DeviceID != "dev_rot_1" {
		t.Fatalf("rotation response identity mismatch: %+v", resp)
	}
	if resp.ExpiresAt <= time.Now().UnixMilli() {
		t.Fatalf("rotated session must have a future expiry: %d", resp.ExpiresAt)
	}

	if _, ok := store.GetSession("sess_old_1"); ok {
		t.Fatal("presented session token must be revoked after rotation")
	}
	if _, ok := store.GetSession("sess_old_2"); ok {
		t.Fatal("sibling sessions for the same device must be revoked after rotation")
	}
	if _, ok := store.GetSession(resp.NewToken); !ok {
		t.Fatal("rotated session token must be active")
	}
}

func TestSessionRotate_RejectsMissingToken(t *testing.T) {
	handler, _, cleanup := setupTestAuthHandler(t)
	defer cleanup()

	req := httptest.NewRequest(http.MethodPost, "/api/v1/auth/session/rotate", nil)
	w := httptest.NewRecorder()
	handler.handleSessionRotate(w, req)

	if w.Code != http.StatusUnauthorized {
		t.Fatalf("expected HTTP 401, got %d", w.Code)
	}
}

func TestSessionRotate_RejectsRevokedDevice(t *testing.T) {
	handler, store, cleanup := setupTestAuthHandler(t)
	defer cleanup()

	account := seedRotationAccount(t, store, "NX-ROT0003", "dev_rot_3")
	createTestSession(t, store, "sess_revoked_dev", account.ID, account.NxID, "dev_rot_3")

	if err := store.RevokeDevice("dev_rot_3"); err != nil {
		t.Fatalf("RevokeDevice: %v", err)
	}

	req := httptest.NewRequest(http.MethodPost, "/api/v1/auth/session/rotate", nil)
	req.Header.Set("Authorization", "Bearer sess_revoked_dev")
	w := httptest.NewRecorder()
	handler.handleSessionRotate(w, req)

	if w.Code != http.StatusUnauthorized {
		t.Fatalf("expected HTTP 401 for revoked device, got %d: %s", w.Code, w.Body.String())
	}
}

func TestLoginVerify_RevokesPriorSessionForSameDevice(t *testing.T) {
	handler, store, cleanup := setupTestAuthHandler(t)
	defer cleanup()

	pub, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatalf("ed25519 keygen: %v", err)
	}
	pubHex := hex.EncodeToString(pub)

	now := time.Now().UnixMilli()
	nxID := "NX-ROT-LOGIN"
	deviceID := "dev_rot_login"
	account := &identity.UserAccount{
		ID:           "usr_rot_login",
		NxID:         nxID,
		PublicKeyHex: pubHex,
		ProfileName:  "Rotation Login",
		CreatedAt:    now,
		UpdatedAt:    now,
	}
	if err := store.CreateAccount(account); err != nil {
		t.Fatalf("CreateAccount: %v", err)
	}
	if err := store.RegisterDevice(&identity.Device{
		DeviceID:     deviceID,
		AccountID:    account.ID,
		NxID:         nxID,
		PublicKeyHex: pubHex,
		CreatedAt:    now,
		LastActiveAt: now,
	}); err != nil {
		t.Fatalf("RegisterDevice: %v", err)
	}
	createTestSession(t, store, "sess_prior_login", account.ID, nxID, deviceID)

	// Request a device-bound challenge.
	chalBody, _ := json.Marshal(map[string]string{"nx_id": nxID, "device_id": deviceID})
	chalReq := httptest.NewRequest(http.MethodPost, "/api/v1/auth/login-challenge", bytes.NewReader(chalBody))
	chalW := httptest.NewRecorder()
	handler.handleLoginChallenge(chalW, chalReq)
	if chalW.Code != http.StatusOK {
		t.Fatalf("login-challenge failed: %d %s", chalW.Code, chalW.Body.String())
	}

	var chalResp struct {
		ChallengeID string `json:"challenge_id"`
		Challenge   string `json:"challenge"`
	}
	if err := json.NewDecoder(chalW.Body).Decode(&chalResp); err != nil {
		t.Fatalf("decode challenge: %v", err)
	}

	chalBytes, err := hex.DecodeString(chalResp.Challenge)
	if err != nil {
		t.Fatalf("decode challenge hex: %v", err)
	}
	sigHex := hex.EncodeToString(ed25519.Sign(priv, chalBytes))

	verifyBody, _ := json.Marshal(map[string]string{
		"challenge_id": chalResp.ChallengeID,
		"challenge":    chalResp.Challenge,
		"nx_id":        nxID,
		"device_id":    deviceID,
		"signature":    sigHex,
	})
	verifyReq := httptest.NewRequest(http.MethodPost, "/api/v1/auth/login-verify", bytes.NewReader(verifyBody))
	verifyW := httptest.NewRecorder()
	handler.handleLoginVerify(verifyW, verifyReq)
	if verifyW.Code != http.StatusOK {
		t.Fatalf("login-verify failed: %d %s", verifyW.Code, verifyW.Body.String())
	}

	var verifyResp struct {
		SessionAuthToken string `json:"session_auth_token"`
	}
	if err := json.NewDecoder(verifyW.Body).Decode(&verifyResp); err != nil {
		t.Fatalf("decode verify response: %v", err)
	}

	if _, ok := store.GetSession("sess_prior_login"); ok {
		t.Fatal("re-authentication must revoke the device's previous session")
	}
	if _, ok := store.GetSession(verifyResp.SessionAuthToken); !ok {
		t.Fatal("fresh login session must be active")
	}
}
