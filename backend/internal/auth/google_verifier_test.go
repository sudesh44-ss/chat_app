package auth

import (
	"crypto"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"strings"
	"testing"
	"time"
)

// Helper to construct and sign an RSA JWT for testing
func createTestGoogleToken(privKey *rsa.PrivateKey, kid, iss, aud, sub, email string, exp, iat int64, alg string) (string, error) {
	header := map[string]string{
		"alg": alg,
		"kid": kid,
	}
	headerJSON, _ := json.Marshal(header)
	headerB64 := base64.RawURLEncoding.EncodeToString(headerJSON)

	claims := GoogleClaims{
		Issuer:        iss,
		Audience:      aud,
		Subject:       sub,
		Email:         email,
		EmailVerified: true,
		Name:          "Test User",
		ExpiresAt:     exp,
		IssuedAt:      iat,
	}
	claimsJSON, _ := json.Marshal(claims)
	claimsB64 := base64.RawURLEncoding.EncodeToString(claimsJSON)

	signedContent := headerB64 + "." + claimsB64
	hasher := sha256.New()
	hasher.Write([]byte(signedContent))
	hashed := hasher.Sum(nil)

	sigBytes, err := rsa.SignPKCS1v15(rand.Reader, privKey, crypto.SHA256, hashed)
	if err != nil {
		return "", err
	}
	sigB64 := base64.RawURLEncoding.EncodeToString(sigBytes)

	return signedContent + "." + sigB64, nil
}

func TestGoogleVerifier(t *testing.T) {
	privKey, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatalf("failed to generate test RSA key: %v", err)
	}

	kid := "test_key_id_1"
	clientID := "123456789-nexa-app.apps.googleusercontent.com"

	verifier := NewGoogleVerifier()
	verifier.SetClientIDForTesting(clientID)
	verifier.SetKeysForTesting(map[string]*rsa.PublicKey{
		kid: &privKey.PublicKey,
	})

	now := time.Now().Unix()

	t.Run("Valid token with correct RS256 signature succeeds", func(t *testing.T) {
		token, err := createTestGoogleToken(
			privKey, kid,
			"https://accounts.google.com", clientID, "google_sub_1001",
			"user@gmail.com", now+3600, now-60, "RS256",
		)
		if err != nil {
			t.Fatalf("failed to create test token: %v", err)
		}

		claims, err := verifier.VerifyIdToken(token)
		if err != nil {
			t.Fatalf("expected valid token to verify successfully, got: %v", err)
		}
		if claims.Subject != "google_sub_1001" || claims.Email != "user@gmail.com" {
			t.Fatalf("claims mismatch: %+v", claims)
		}
	})

	t.Run("Expired token fails verification", func(t *testing.T) {
		token, err := createTestGoogleToken(
			privKey, kid,
			"https://accounts.google.com", clientID, "google_sub_1001",
			"user@gmail.com", now-100, now-500, "RS256", // expired 100 seconds ago
		)
		if err != nil {
			t.Fatalf("failed to create test token: %v", err)
		}

		_, err = verifier.VerifyIdToken(token)
		if err == nil || !strings.Contains(err.Error(), "expired") {
			t.Fatalf("expected token expired error, got: %v", err)
		}
	})

	t.Run("Audience mismatch fails verification", func(t *testing.T) {
		token, err := createTestGoogleToken(
			privKey, kid,
			"https://accounts.google.com", "wrong-client-id.apps.googleusercontent.com", "google_sub_1001",
			"user@gmail.com", now+3600, now-60, "RS256",
		)
		if err != nil {
			t.Fatalf("failed to create test token: %v", err)
		}

		_, err = verifier.VerifyIdToken(token)
		if err == nil || !strings.Contains(err.Error(), "audience mismatch") {
			t.Fatalf("expected audience mismatch error, got: %v", err)
		}
	})

	t.Run("Invalid/tampered signature fails verification", func(t *testing.T) {
		otherPrivKey, _ := rsa.GenerateKey(rand.Reader, 2048)
		// Signed by a different private key that does not match the cached public key
		token, err := createTestGoogleToken(
			otherPrivKey, kid,
			"https://accounts.google.com", clientID, "google_sub_1001",
			"user@gmail.com", now+3600, now-60, "RS256",
		)
		if err != nil {
			t.Fatalf("failed to create test token: %v", err)
		}

		_, err = verifier.VerifyIdToken(token)
		if err == nil || !strings.Contains(err.Error(), "signature") {
			t.Fatalf("expected signature verification failure, got: %v", err)
		}
	})

	t.Run("Missing GOOGLE_CLIENT_ID fails closed", func(t *testing.T) {
		unconfiguredVerifier := NewGoogleVerifier()
		unconfiguredVerifier.SetClientIDForTesting("") // empty client ID

		token, err := createTestGoogleToken(
			privKey, kid,
			"https://accounts.google.com", clientID, "google_sub_1001",
			"user@gmail.com", now+3600, now-60, "RS256",
		)
		if err != nil {
			t.Fatalf("failed to create test token: %v", err)
		}

		_, err = unconfiguredVerifier.VerifyIdToken(token)
		if err == nil || err != ErrMissingGoogleClientID {
			t.Fatalf("expected ErrMissingGoogleClientID, got: %v", err)
		}
	})

	t.Run("Invalid issuer fails verification", func(t *testing.T) {
		token, err := createTestGoogleToken(
			privKey, kid,
			"https://attacker-fake-oauth.com", clientID, "google_sub_1001",
			"user@gmail.com", now+3600, now-60, "RS256",
		)
		if err != nil {
			t.Fatalf("failed to create test token: %v", err)
		}

		_, err = verifier.VerifyIdToken(token)
		if err == nil || !strings.Contains(err.Error(), "invalid token issuer") {
			t.Fatalf("expected invalid issuer error, got: %v", err)
		}
	})
}
