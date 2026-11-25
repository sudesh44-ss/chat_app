package auth

import (
	"bytes"
	"crypto"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"nexa/wire/internal/identity"
)

func createTestFirebaseToken(
	t *testing.T,
	privKey *rsa.PrivateKey,
	kid string,
	projectID string,
	phone string,
	sub string,
	expOffsetSec int64,
) string {
	t.Helper()

	header := map[string]interface{}{
		"alg": "RS256",
		"kid": kid,
	}
	headerBytes, _ := json.Marshal(header)
	headerB64 := base64.RawURLEncoding.EncodeToString(headerBytes)

	now := time.Now().Unix()
	claims := map[string]interface{}{
		"iss":          "https://securetoken.google.com/" + projectID,
		"aud":          projectID,
		"sub":          sub,
		"user_id":      sub,
		"auth_time":    now,
		"iat":          now,
		"exp":          now + expOffsetSec,
		"phone_number": phone,
		"firebase": map[string]interface{}{
			"identities": map[string]interface{}{
				"phone": []string{phone},
			},
			"sign_in_provider": "phone",
		},
	}
	claimsBytes, _ := json.Marshal(claims)
	claimsB64 := base64.RawURLEncoding.EncodeToString(claimsBytes)

	signedContent := headerB64 + "." + claimsB64
	hash := sha256.Sum256([]byte(signedContent))
	sigBytes, err := rsa.SignPKCS1v15(rand.Reader, privKey, crypto.SHA256, hash[:])
	if err != nil {
		t.Fatalf("failed to sign test firebase token: %v", err)
	}
	sigB64 := base64.RawURLEncoding.EncodeToString(sigBytes)

	return signedContent + "." + sigB64
}

func TestFirebaseVerifier_ValidToken(t *testing.T) {
	privKey, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatalf("failed to generate test rsa key: %v", err)
	}

	verifier := NewFirebaseVerifier()
	verifier.SetProjectIDForTesting("test-nexa-project")
	verifier.SetKeysForTesting(map[string]*rsa.PublicKey{
		"test-kid-1": &privKey.PublicKey,
	})

	token := createTestFirebaseToken(t, privKey, "test-kid-1", "test-nexa-project", "+919876543210", "fb_uid_123", 3600)

	claims, err := verifier.VerifyIdToken(token)
	if err != nil {
		t.Fatalf("expected valid token verification, got error: %v", err)
	}

	if claims.PhoneNumber != "+919876543210" {
		t.Errorf("expected phone '+919876543210', got: %s", claims.PhoneNumber)
	}
	if claims.Subject != "fb_uid_123" {
		t.Errorf("expected subject 'fb_uid_123', got: %s", claims.Subject)
	}
}

func TestFirebaseVerifier_ExpiredTokenRejected(t *testing.T) {
	privKey, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatalf("failed to generate rsa key: %v", err)
	}

	verifier := NewFirebaseVerifier()
	verifier.SetProjectIDForTesting("test-nexa-project")
	verifier.SetKeysForTesting(map[string]*rsa.PublicKey{
		"test-kid-1": &privKey.PublicKey,
	})

	// Expired 60s ago
	token := createTestFirebaseToken(t, privKey, "test-kid-1", "test-nexa-project", "+919876543210", "fb_uid_123", -60)

	_, err = verifier.VerifyIdToken(token)
	if err == nil {
		t.Fatal("expected error for expired token, got nil")
	}
	if err != ErrFirebaseTokenExpired {
		t.Errorf("expected ErrFirebaseTokenExpired, got: %v", err)
	}
}

func TestFirebaseVerifier_AudienceMismatchRejected(t *testing.T) {
	privKey, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatalf("failed to generate rsa key: %v", err)
	}

	verifier := NewFirebaseVerifier()
	verifier.SetProjectIDForTesting("configured-project")
	verifier.SetKeysForTesting(map[string]*rsa.PublicKey{
		"test-kid-1": &privKey.PublicKey,
	})

	// Token issued for a different project
	token := createTestFirebaseToken(t, privKey, "test-kid-1", "attacker-project", "+919876543210", "fb_uid_123", 3600)

	_, err = verifier.VerifyIdToken(token)
	if err == nil {
		t.Fatal("expected error for audience/project mismatch, got nil")
	}
}

func TestFirebaseVerifier_MissingPhoneRejected(t *testing.T) {
	privKey, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatalf("failed to generate rsa key: %v", err)
	}

	verifier := NewFirebaseVerifier()
	verifier.SetProjectIDForTesting("test-project")
	verifier.SetKeysForTesting(map[string]*rsa.PublicKey{
		"test-kid-1": &privKey.PublicKey,
	})

	token := createTestFirebaseToken(t, privKey, "test-kid-1", "test-project", "", "fb_uid_no_phone", 3600)

	_, err = verifier.VerifyIdToken(token)
	if err == nil {
		t.Fatal("expected error for missing phone number claim, got nil")
	}
	if err != ErrMissingFirebasePhone {
		t.Errorf("expected ErrMissingFirebasePhone, got: %v", err)
	}
}

func TestFirebaseVerifier_ReplayRejected(t *testing.T) {
	privKey, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatalf("failed to generate rsa key: %v", err)
	}

	verifier := NewFirebaseVerifier()
	verifier.SetProjectIDForTesting("test-project")
	verifier.SetKeysForTesting(map[string]*rsa.PublicKey{
		"test-kid-1": &privKey.PublicKey,
	})

	token := createTestFirebaseToken(t, privKey, "test-kid-1", "test-project", "+919876543210", "fb_uid_123", 3600)

	// First verification succeeds
	_, err = verifier.VerifyIdToken(token)
	if err != nil {
		t.Fatalf("first token verification failed: %v", err)
	}

	// Replay verification fails
	_, err = verifier.VerifyIdToken(token)
	if err == nil {
		t.Fatal("expected replay attack to be rejected, got nil")
	}
	if err != ErrFirebaseReplayDetected {
		t.Errorf("expected ErrFirebaseReplayDetected, got: %v", err)
	}
}

func TestFirebaseVerify_HttpHandlerIntegration(t *testing.T) {
	privKey, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatalf("failed to generate rsa key: %v", err)
	}

	store, err := identity.NewStore("")
	if err != nil {
		t.Fatalf("identity.NewStore: %v", err)
	}
	handler := NewAuthHandler(store, NewOtpService(NewMockSmsProvider()), nil)

	fbVerifier := NewFirebaseVerifier()
	fbVerifier.SetProjectIDForTesting("nexa-prod-project")
	fbVerifier.SetKeysForTesting(map[string]*rsa.PublicKey{
		"test-kid-fb": &privKey.PublicKey,
	})
	handler.SetFirebaseVerifier(fbVerifier)

	dummyPubKey := hex.EncodeToString(make([]byte, 32))

	// 1. Successful verification creates new account
	token := createTestFirebaseToken(t, privKey, "test-kid-fb", "nexa-prod-project", "+919876543210", "fb_user_1", 3600)
	reqBody, _ := json.Marshal(map[string]interface{}{
		"firebase_id_token": token,
		"public_key_hex":    dummyPubKey,
		"device_id":         "dev_firebase_test",
		"device_name":       "Pixel 8",
		"name":              "Firebase User",
		"email":             "fb@domain.com",
	})

	req := httptest.NewRequest(http.MethodPost, "/api/v1/auth/firebase/verify", bytes.NewReader(reqBody))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()

	handler.handleFirebaseVerify(w, req)
	if w.Code != http.StatusOK {
		t.Fatalf("expected HTTP 200, got %d: %s", w.Code, w.Body.String())
	}

	var resp map[string]interface{}
	json.Unmarshal(w.Body.Bytes(), &resp)
	if resp["status"] != "VERIFIED" {
		t.Errorf("expected status 'VERIFIED', got: %v", resp["status"])
	}
	nxID, ok := resp["nx_id"].(string)
	if !ok || nxID == "" {
		t.Errorf("expected non-empty nx_id in response")
	}

	// 2. Re-verification with same phone recovers existing account (using fresh token to avoid replay rejection)
	token2 := createTestFirebaseToken(t, privKey, "test-kid-fb", "nexa-prod-project", "+919876543210", "fb_user_1", 3601)
	dummyPubKey2 := hex.EncodeToString(bytes.Repeat([]byte{1}, 32))
	reqBody2, _ := json.Marshal(map[string]interface{}{
		"firebase_id_token": token2,
		"public_key_hex":    dummyPubKey2,
		"device_id":         "dev_firebase_test2",
		"device_name":       "Pixel 9",
		"is_reverify":       true,
	})

	req2 := httptest.NewRequest(http.MethodPost, "/api/v1/auth/firebase/verify", bytes.NewReader(reqBody2))
	req2.Header.Set("Content-Type", "application/json")
	w2 := httptest.NewRecorder()

	handler.handleFirebaseVerify(w2, req2)
	if w2.Code != http.StatusOK {
		t.Fatalf("expected HTTP 200 on reverify, got %d: %s", w2.Code, w2.Body.String())
	}
	var resp2 map[string]interface{}
	json.Unmarshal(w2.Body.Bytes(), &resp2)
	if resp2["nx_id"] != nxID {
		t.Errorf("expected same nx_id '%s' on reverify, got: %v", nxID, resp2["nx_id"])
	}
	if resp2["is_existing_user"] != true {
		t.Errorf("expected is_existing_user == true, got: %v", resp2["is_existing_user"])
	}
}
