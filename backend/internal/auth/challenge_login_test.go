package auth

import (
	"bytes"
	"crypto/ecdsa"
	"crypto/ed25519"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"

	"nexa/wire/internal/identity"
)

func setupTestAuthHandler(t *testing.T) (*AuthHandler, *identity.Store, func()) {
	tmpDir, err := os.MkdirTemp("", "nexa_auth_chal_*")
	if err != nil {
		t.Fatalf("Failed to create temp dir: %v", err)
	}

	dbPath := filepath.Join(tmpDir, "identity.json")
	store, err := identity.NewStore(dbPath)
	if err != nil {
		t.Fatalf("Failed to create store: %v", err)
	}

	mockSms := NewMockSmsProvider()
	otpService := NewOtpService(mockSms)
	googleVerifier := NewGoogleVerifier()

	handler := NewAuthHandler(store, otpService, googleVerifier)
	cleanup := func() {
		os.RemoveAll(tmpDir)
	}

	return handler, store, cleanup
}

func TestLoginChallenge_P256HardwareSignature(t *testing.T) {
	handler, store, cleanup := setupTestAuthHandler(t)
	defer cleanup()

	// 1. Generate EC P-256 keypair simulating Android Keystore (StrongBox/TEE)
	privKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatalf("Failed to generate EC P-256 key: %v", err)
	}

	derBytes, err := x509.MarshalPKIXPublicKey(&privKey.PublicKey)
	if err != nil {
		t.Fatalf("Failed to marshal PKIX public key: %v", err)
	}
	p256PubHex := hex.EncodeToString(derBytes)

	// 2. Register User and Device with P-256 key
	nxID := "NX-CHAL-001"
	devID := "dev_strongbox_001"

	err = store.CreateAccount(&identity.UserAccount{
		ID:           "usr_chal_001",
		NxID:         nxID,
		PublicKeyHex: p256PubHex,
		ProfileName:  "Hardware Protected User",
		CreatedAt:    time.Now().UnixMilli(),
	})
	if err != nil {
		t.Fatalf("Failed to create account: %v", err)
	}

	err = store.RegisterDevice(&identity.Device{
		DeviceID:     devID,
		AccountID:    "usr_chal_001",
		NxID:         nxID,
		DeviceName:   "Pixel 9 Pro StrongBox",
		PublicKeyHex: p256PubHex,
		IsRevoked:    false,
		CreatedAt:    time.Now().UnixMilli(),
	})
	if err != nil {
		t.Fatalf("Failed to register device: %v", err)
	}

	// 3. Request Challenge: POST /api/v1/auth/login-challenge
	chalReqBody, _ := json.Marshal(map[string]string{
		"nx_id":     nxID,
		"device_id": devID,
	})
	req := httptest.NewRequest(http.MethodPost, "/api/v1/auth/login-challenge", bytes.NewReader(chalReqBody))
	w := httptest.NewRecorder()
	handler.handleLoginChallenge(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("Expected HTTP 200 on login-challenge, got %d: %s", w.Code, w.Body.String())
	}

	var chalResp struct {
		Status      string `json:"status"`
		ChallengeID string `json:"challenge_id"`
		Challenge   string `json:"challenge"`
		NxID        string `json:"nx_id"`
		DeviceID    string `json:"device_id"`
	}
	if err := json.NewDecoder(w.Body).Decode(&chalResp); err != nil {
		t.Fatalf("Failed to decode login-challenge response: %v", err)
	}

	if chalResp.Status != "CHALLENGE_ISSUED" || chalResp.Challenge == "" {
		t.Fatalf("Unexpected challenge response: %+v", chalResp)
	}

	// 4. Sign Challenge: Client-side SHA256withECDSA ASN.1 DER signature
	challengeBytes, err := hex.DecodeString(chalResp.Challenge)
	if err != nil {
		t.Fatalf("Failed to hex-decode challenge: %v", err)
	}

	digest := sha256.Sum256(challengeBytes)
	sigBytes, err := privKey.Sign(rand.Reader, digest[:], nil)
	if err != nil {
		t.Fatalf("Failed to sign challenge: %v", err)
	}
	sigHex := hex.EncodeToString(sigBytes)

	// 5. Verify Challenge: POST /api/v1/auth/login-verify
	verifyReqBody, _ := json.Marshal(map[string]string{
		"challenge_id": chalResp.ChallengeID,
		"challenge":    chalResp.Challenge,
		"nx_id":        nxID,
		"device_id":    devID,
		"signature":    sigHex,
	})
	reqVerify := httptest.NewRequest(http.MethodPost, "/api/v1/auth/login-verify", bytes.NewReader(verifyReqBody))
	wVerify := httptest.NewRecorder()
	handler.handleLoginVerify(wVerify, reqVerify)

	if wVerify.Code != http.StatusOK {
		t.Fatalf("Expected HTTP 200 on login-verify, got %d: %s", wVerify.Code, wVerify.Body.String())
	}

	var verifyResp struct {
		Status           string `json:"status"`
		SessionAuthToken string `json:"session_auth_token"`
		NxID             string `json:"nx_id"`
		DeviceID         string `json:"device_id"`
	}
	if err := json.NewDecoder(wVerify.Body).Decode(&verifyResp); err != nil {
		t.Fatalf("Failed to decode login-verify response: %v", err)
	}

	if verifyResp.Status != "LOGIN_SUCCESS" || verifyResp.SessionAuthToken == "" {
		t.Fatalf("Expected LOGIN_SUCCESS with valid token, got: %+v", verifyResp)
	}

	// 6. Verify Session is active in IdentityStore
	sess, found := store.GetSession(verifyResp.SessionAuthToken)
	if !found || sess == nil || sess.NxID != nxID {
		t.Fatalf("Session token was not properly saved in store")
	}

	// 7. Anti-Replay: Verifying the same challenge a second time MUST fail
	reqReplay := httptest.NewRequest(http.MethodPost, "/api/v1/auth/login-verify", bytes.NewReader(verifyReqBody))
	wReplay := httptest.NewRecorder()
	handler.handleLoginVerify(wReplay, reqReplay)

	if wReplay.Code != http.StatusUnauthorized {
		t.Fatalf("Expected HTTP 401 on replayed challenge, got %d: %s", wReplay.Code, wReplay.Body.String())
	}
}

func TestLoginChallenge_TamperedSignature(t *testing.T) {
	handler, store, cleanup := setupTestAuthHandler(t)
	defer cleanup()

	privKey, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	derBytes, _ := x509.MarshalPKIXPublicKey(&privKey.PublicKey)
	p256PubHex := hex.EncodeToString(derBytes)

	nxID := "NX-CHAL-002"
	devID := "dev_002"

	store.CreateAccount(&identity.UserAccount{
		ID:           "usr_002",
		NxID:         nxID,
		PublicKeyHex: p256PubHex,
		CreatedAt:    time.Now().UnixMilli(),
	})
	store.RegisterDevice(&identity.Device{
		DeviceID:     devID,
		AccountID:    "usr_002",
		NxID:         nxID,
		PublicKeyHex: p256PubHex,
		IsRevoked:    false,
		CreatedAt:    time.Now().UnixMilli(),
	})

	chalReqBody, _ := json.Marshal(map[string]string{
		"nx_id":     nxID,
		"device_id": devID,
	})
	req := httptest.NewRequest(http.MethodPost, "/api/v1/auth/login-challenge", bytes.NewReader(chalReqBody))
	w := httptest.NewRecorder()
	handler.handleLoginChallenge(w, req)

	var chalResp struct {
		ChallengeID string `json:"challenge_id"`
		Challenge   string `json:"challenge"`
	}
	json.NewDecoder(w.Body).Decode(&chalResp)

	// Tampered signature
	corruptSig := hex.EncodeToString(make([]byte, 71))
	verifyReqBody, _ := json.Marshal(map[string]string{
		"challenge_id": chalResp.ChallengeID,
		"challenge":    chalResp.Challenge,
		"nx_id":        nxID,
		"device_id":    devID,
		"signature":    corruptSig,
	})

	reqVerify := httptest.NewRequest(http.MethodPost, "/api/v1/auth/login-verify", bytes.NewReader(verifyReqBody))
	wVerify := httptest.NewRecorder()
	handler.handleLoginVerify(wVerify, reqVerify)

	if wVerify.Code != http.StatusUnauthorized {
		t.Fatalf("Expected HTTP 401 on tampered signature, got %d", wVerify.Code)
	}
}

func TestLoginChallenge_RevokedDevice(t *testing.T) {
	handler, store, cleanup := setupTestAuthHandler(t)
	defer cleanup()

	nxID := "NX-CHAL-003"
	devID := "dev_revoked"

	store.CreateAccount(&identity.UserAccount{
		ID:           "usr_003",
		NxID:         nxID,
		PublicKeyHex: "abcd",
		CreatedAt:    time.Now().UnixMilli(),
	})
	store.RegisterDevice(&identity.Device{
		DeviceID:     devID,
		AccountID:    "usr_003",
		NxID:         nxID,
		PublicKeyHex: "abcd",
		IsRevoked:    true, // REVOKED DEVICE
		CreatedAt:    time.Now().UnixMilli(),
	})

	chalReqBody, _ := json.Marshal(map[string]string{
		"nx_id":     nxID,
		"device_id": devID,
	})
	req := httptest.NewRequest(http.MethodPost, "/api/v1/auth/login-challenge", bytes.NewReader(chalReqBody))
	w := httptest.NewRecorder()
	handler.handleLoginChallenge(w, req)

	if w.Code != http.StatusUnauthorized {
		t.Fatalf("Expected HTTP 401 for revoked device requesting challenge, got %d", w.Code)
	}
}

func TestLoginChallenge_Ed25519Fallback(t *testing.T) {
	handler, store, cleanup := setupTestAuthHandler(t)
	defer cleanup()

	pub, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatalf("Failed to generate Ed25519 key: %v", err)
	}
	pubHex := hex.EncodeToString(pub)

	nxID := "NX-CHAL-ED25519"
	devID := "dev_ed25519"

	store.CreateAccount(&identity.UserAccount{
		ID:           "usr_ed",
		NxID:         nxID,
		PublicKeyHex: pubHex,
		CreatedAt:    time.Now().UnixMilli(),
	})
	store.RegisterDevice(&identity.Device{
		DeviceID:     devID,
		AccountID:    "usr_ed",
		NxID:         nxID,
		PublicKeyHex: pubHex,
		IsRevoked:    false,
		CreatedAt:    time.Now().UnixMilli(),
	})

	chalReqBody, _ := json.Marshal(map[string]string{
		"nx_id":     nxID,
		"device_id": devID,
	})
	req := httptest.NewRequest(http.MethodPost, "/api/v1/auth/login-challenge", bytes.NewReader(chalReqBody))
	w := httptest.NewRecorder()
	handler.handleLoginChallenge(w, req)

	var chalResp struct {
		ChallengeID string `json:"challenge_id"`
		Challenge   string `json:"challenge"`
	}
	json.NewDecoder(w.Body).Decode(&chalResp)

	chalBytes, _ := hex.DecodeString(chalResp.Challenge)
	sig := ed25519.Sign(priv, chalBytes)
	sigHex := hex.EncodeToString(sig)

	verifyReqBody, _ := json.Marshal(map[string]string{
		"challenge_id": chalResp.ChallengeID,
		"challenge":    chalResp.Challenge,
		"nx_id":        nxID,
		"device_id":    devID,
		"signature":    sigHex,
	})
	reqVerify := httptest.NewRequest(http.MethodPost, "/api/v1/auth/login-verify", bytes.NewReader(verifyReqBody))
	wVerify := httptest.NewRecorder()
	handler.handleLoginVerify(wVerify, reqVerify)

	if wVerify.Code != http.StatusOK {
		t.Fatalf("Expected HTTP 200 on Ed25519 login verify, got %d: %s", wVerify.Code, wVerify.Body.String())
	}
}
