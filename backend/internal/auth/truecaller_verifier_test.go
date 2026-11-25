package auth

import (
	"bytes"
	"crypto"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha512"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"nexa/wire/internal/identity"
)

func createTestTruecallerPayload(
	t *testing.T,
	privKey *rsa.PrivateKey,
	phone string,
	firstName string,
	lastName string,
	nonce string,
	offsetMs int64,
) (string, string) {
	t.Helper()

	now := time.Now().UnixMilli() + offsetMs
	payloadObj := map[string]interface{}{
		"phoneNumber":  phone,
		"firstName":    firstName,
		"lastName":     lastName,
		"gender":       "M",
		"requestNonce": nonce,
		"timestamp":    now,
	}
	payloadBytes, _ := json.Marshal(payloadObj)
	payloadB64 := base64.StdEncoding.EncodeToString(payloadBytes)

	hash := sha512.Sum512(payloadBytes)
	sigBytes, err := rsa.SignPKCS1v15(rand.Reader, privKey, crypto.SHA512, hash[:])
	if err != nil {
		t.Fatalf("failed to sign truecaller payload: %v", err)
	}
	sigB64 := base64.StdEncoding.EncodeToString(sigBytes)

	return payloadB64, sigB64
}

func TestTruecallerVerifier_ValidPayload(t *testing.T) {
	privKey, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatalf("failed to generate test rsa key: %v", err)
	}

	verifier := NewTruecallerVerifier()
	verifier.SetKeysForTesting(map[string]*rsa.PublicKey{
		"tc_key_0": &privKey.PublicKey,
	})

	nonce := "NX-TC-TEST123"
	payloadB64, sigB64 := createTestTruecallerPayload(t, privKey, "+919876543210", "Aarav", "Sharma", nonce, 0)

	profile, err := verifier.VerifyProfilePayload(payloadB64, sigB64, nonce)
	if err != nil {
		t.Fatalf("expected valid truecaller verification, got: %v", err)
	}

	if profile.PhoneNumber != "+919876543210" {
		t.Errorf("expected phone '+919876543210', got: %s", profile.PhoneNumber)
	}
	if profile.FirstName != "Aarav" {
		t.Errorf("expected first name 'Aarav', got: %s", profile.FirstName)
	}
}

func TestTruecallerVerifier_TamperedPayloadRejected(t *testing.T) {
	privKey, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatalf("failed to generate test rsa key: %v", err)
	}

	verifier := NewTruecallerVerifier()
	verifier.SetKeysForTesting(map[string]*rsa.PublicKey{
		"tc_key_0": &privKey.PublicKey,
	})

	nonce := "NX-TC-TEST123"
	_, sigB64 := createTestTruecallerPayload(t, privKey, "+919876543210", "Aarav", "Sharma", nonce, 0)

	// Tamper payload bytes (change phone number)
	tamperedObj := map[string]interface{}{
		"phoneNumber":  "+919999999999",
		"firstName":    "Attacker",
		"lastName":     "Hacker",
		"requestNonce": nonce,
		"timestamp":    time.Now().UnixMilli(),
	}
	tamperedBytes, _ := json.Marshal(tamperedObj)
	tamperedB64 := base64.StdEncoding.EncodeToString(tamperedBytes)

	_, err = verifier.VerifyProfilePayload(tamperedB64, sigB64, nonce)
	if err == nil {
		t.Fatal("expected signature verification failure for tampered payload, got nil")
	}
	if err != ErrTruecallerSignatureFailed {
		t.Errorf("expected ErrTruecallerSignatureFailed, got: %v", err)
	}
}

func TestTruecallerVerifier_NonceMismatchRejected(t *testing.T) {
	privKey, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatalf("failed to generate test rsa key: %v", err)
	}

	verifier := NewTruecallerVerifier()
	verifier.SetKeysForTesting(map[string]*rsa.PublicKey{
		"tc_key_0": &privKey.PublicKey,
	})

	payloadB64, sigB64 := createTestTruecallerPayload(t, privKey, "+919876543210", "Aarav", "Sharma", "NONCE-A", 0)

	// Verifier expected NONCE-B
	_, err = verifier.VerifyProfilePayload(payloadB64, sigB64, "NONCE-B")
	if err == nil {
		t.Fatal("expected nonce mismatch error, got nil")
	}
}

func TestTruecallerVerifier_ExpiredTimestampRejected(t *testing.T) {
	privKey, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatalf("failed to generate test rsa key: %v", err)
	}

	verifier := NewTruecallerVerifier()
	verifier.SetKeysForTesting(map[string]*rsa.PublicKey{
		"tc_key_0": &privKey.PublicKey,
	})

	nonce := "NX-TC-EXP"
	// 6 minutes in the past
	payloadB64, sigB64 := createTestTruecallerPayload(t, privKey, "+919876543210", "Aarav", "Sharma", nonce, -6*60*1000)

	_, err = verifier.VerifyProfilePayload(payloadB64, sigB64, nonce)
	if err == nil {
		t.Fatal("expected error for expired Truecaller payload, got nil")
	}
	if err != ErrTruecallerExpired {
		t.Errorf("expected ErrTruecallerExpired, got: %v", err)
	}
}

func TestTruecallerVerifier_ReplayRejected(t *testing.T) {
	privKey, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatalf("failed to generate test rsa key: %v", err)
	}

	verifier := NewTruecallerVerifier()
	verifier.SetKeysForTesting(map[string]*rsa.PublicKey{
		"tc_key_0": &privKey.PublicKey,
	})

	nonce := "NX-TC-REPLAY"
	payloadB64, sigB64 := createTestTruecallerPayload(t, privKey, "+919876543210", "Aarav", "Sharma", nonce, 0)

	// First verification succeeds
	_, err = verifier.VerifyProfilePayload(payloadB64, sigB64, nonce)
	if err != nil {
		t.Fatalf("first verification failed: %v", err)
	}

	// Replay verification fails
	_, err = verifier.VerifyProfilePayload(payloadB64, sigB64, nonce)
	if err == nil {
		t.Fatal("expected replay attack to be rejected, got nil")
	}
	if err != ErrTruecallerReplayDetected {
		t.Errorf("expected ErrTruecallerReplayDetected, got: %v", err)
	}
}

func TestTruecallerVerify_HttpHandlerIntegration(t *testing.T) {
	privKey, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatalf("failed to generate test rsa key: %v", err)
	}

	store, err := identity.NewStore("")
	if err != nil {
		t.Fatalf("identity.NewStore: %v", err)
	}
	handler := NewAuthHandler(store, NewOtpService(NewMockSmsProvider()), nil)

	tcVerifier := NewTruecallerVerifier()
	tcVerifier.SetKeysForTesting(map[string]*rsa.PublicKey{
		"tc_key_0": &privKey.PublicKey,
	})
	handler.SetTruecallerVerifier(tcVerifier)

	// 1. Test Nonce generation endpoint
	reqNonce := httptest.NewRequest(http.MethodGet, "/api/v1/auth/truecaller/nonce", nil)
	wNonce := httptest.NewRecorder()
	handler.handleTruecallerNonce(wNonce, reqNonce)
	if wNonce.Code != http.StatusOK {
		t.Fatalf("expected HTTP 200 for nonce, got: %d", wNonce.Code)
	}
	var nonceResp map[string]interface{}
	json.Unmarshal(wNonce.Body.Bytes(), &nonceResp)
	issuedNonce := nonceResp["nonce"].(string)
	if !bytes.HasPrefix([]byte(issuedNonce), []byte("NX-TC-")) {
		t.Errorf("expected nonce starting with NX-TC-, got: %s", issuedNonce)
	}

	// 2. Test Verification with issued nonce
	payloadB64, sigB64 := createTestTruecallerPayload(t, privKey, "+919876543210", "Rohan", "Verma", issuedNonce, 0)
	dummyPubKey := hex.EncodeToString(make([]byte, 32))

	reqBody, _ := json.Marshal(map[string]interface{}{
		"payload":        payloadB64,
		"signature":      sigB64,
		"request_nonce":  issuedNonce,
		"public_key_hex": dummyPubKey,
		"device_id":      "dev_tc_test",
		"device_name":    "OnePlus 12",
	})

	reqVerify := httptest.NewRequest(http.MethodPost, "/api/v1/auth/truecaller/verify", bytes.NewReader(reqBody))
	reqVerify.Header.Set("Content-Type", "application/json")
	wVerify := httptest.NewRecorder()

	handler.handleTruecallerVerify(wVerify, reqVerify)
	if wVerify.Code != http.StatusOK {
		t.Fatalf("expected HTTP 200, got %d: %s", wVerify.Code, wVerify.Body.String())
	}

	var verifyResp map[string]interface{}
	json.Unmarshal(wVerify.Body.Bytes(), &verifyResp)
	if verifyResp["status"] != "VERIFIED" {
		t.Errorf("expected status 'VERIFIED', got: %v", verifyResp["status"])
	}
	if verifyResp["phone"] != "+919876543210" {
		t.Errorf("expected phone '+919876543210', got: %v", verifyResp["phone"])
	}
	if verifyResp["name"] != "Rohan Verma" {
		t.Errorf("expected name 'Rohan Verma', got: %v", verifyResp["name"])
	}
}
