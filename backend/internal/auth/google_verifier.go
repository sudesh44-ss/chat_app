package auth

import (
	"crypto"
	"crypto/rsa"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"math/big"
	"net/http"
	"os"
	"strings"
	"sync"
	"time"
)

var (
	ErrMissingGoogleClientID = errors.New("google token verification failed: GOOGLE_CLIENT_ID is not configured on server")
	ErrInvalidGoogleIssuer   = errors.New("google token verification failed: invalid token issuer")
	ErrTokenAudienceMismatch = errors.New("google token verification failed: audience mismatch")
	ErrGoogleTokenExpired    = errors.New("google token verification failed: token has expired")
	ErrMissingGoogleSubject  = errors.New("google token verification failed: missing subject (Google user ID)")
)

type GoogleClaims struct {
	Issuer        string `json:"iss"`
	Audience      string `json:"aud"`
	Subject       string `json:"sub"` // Unique Google User ID
	Email         string `json:"email"`
	EmailVerified bool   `json:"email_verified"`
	Name          string `json:"name"`
	Picture       string `json:"picture"`
	ExpiresAt     int64  `json:"exp"`
	IssuedAt      int64  `json:"iat"`
}

type GoogleVerifier struct {
	mu         sync.RWMutex
	clientID   string
	httpClient *http.Client
	cachedKeys map[string]*rsa.PublicKey
	keysExpiry time.Time
}

func NewGoogleVerifier() *GoogleVerifier {
	clientID := strings.TrimSpace(os.Getenv("GOOGLE_CLIENT_ID"))
	return &GoogleVerifier{
		clientID:   clientID,
		httpClient: &http.Client{Timeout: 10 * time.Second},
		cachedKeys: make(map[string]*rsa.PublicKey),
	}
}

// SetClientIDForTesting sets the client ID for deterministic unit tests.
func (v *GoogleVerifier) SetClientIDForTesting(clientID string) {
	v.mu.Lock()
	defer v.mu.Unlock()
	v.clientID = clientID
}

// SetKeysForTesting sets pre-generated RSA public keys for unit testing without network calls.
func (v *GoogleVerifier) SetKeysForTesting(keys map[string]*rsa.PublicKey) {
	v.mu.Lock()
	defer v.mu.Unlock()
	v.cachedKeys = keys
	v.keysExpiry = time.Now().Add(24 * time.Hour)
}

// VerifyIdToken parses, validates, and cryptographically verifies a Google OAuth ID token.
//
// Security guarantees:
// 1. Fail-closed: Rejects immediately if GOOGLE_CLIENT_ID is unset or empty.
// 2. Cryptographic signature check: Always verifies RS256 signature against Google JWKS public keys.
// 3. Claims validation: Validates expiry, issuer, audience, and subject.
func (v *GoogleVerifier) VerifyIdToken(tokenString string) (*GoogleClaims, error) {
	v.mu.RLock()
	clientID := v.clientID
	v.mu.RUnlock()

	// Fail closed if server has no GOOGLE_CLIENT_ID configured
	if clientID == "" {
		return nil, ErrMissingGoogleClientID
	}

	parts := strings.Split(tokenString, ".")
	if len(parts) != 3 {
		return nil, fmt.Errorf("malformed JWT: expected 3 parts, got %d", len(parts))
	}

	// 1. Decode Header
	headerBytes, err := base64.RawURLEncoding.DecodeString(parts[0])
	if err != nil {
		return nil, fmt.Errorf("invalid header base64: %w", err)
	}
	var header struct {
		Algorithm string `json:"alg"`
		KeyID     string `json:"kid"`
	}
	if err := json.Unmarshal(headerBytes, &header); err != nil {
		return nil, fmt.Errorf("invalid header JSON: %w", err)
	}

	if header.Algorithm != "RS256" {
		return nil, fmt.Errorf("unsupported JWT algorithm: %s (expected RS256)", header.Algorithm)
	}

	// 2. Decode Claims
	claimsBytes, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return nil, fmt.Errorf("invalid claims base64: %w", err)
	}
	var claims GoogleClaims
	if err := json.Unmarshal(claimsBytes, &claims); err != nil {
		return nil, fmt.Errorf("invalid claims JSON: %w", err)
	}

	now := time.Now().Unix()

	// 3. Validate Expiration
	if claims.ExpiresAt <= now {
		return nil, fmt.Errorf("%w: exp=%d, now=%d", ErrGoogleTokenExpired, claims.ExpiresAt, now)
	}

	// 4. Validate Issuer
	if claims.Issuer != "accounts.google.com" && claims.Issuer != "https://accounts.google.com" {
		return nil, fmt.Errorf("%w: %s", ErrInvalidGoogleIssuer, claims.Issuer)
	}

	// 5. Validate Audience
	if claims.Audience != clientID {
		return nil, fmt.Errorf("%w: got '%s', expected '%s'", ErrTokenAudienceMismatch, claims.Audience, clientID)
	}

	// 6. Validate Subject
	if strings.TrimSpace(claims.Subject) == "" {
		return nil, ErrMissingGoogleSubject
	}

	// 7. Mandatory RS256 Cryptographic Signature Verification
	signedContent := parts[0] + "." + parts[1]
	if err := v.verifySignature(header.KeyID, signedContent, parts[2]); err != nil {
		return nil, fmt.Errorf("cryptographic token signature verification failed: %w", err)
	}

	return &claims, nil
}

func (v *GoogleVerifier) verifySignature(keyID, signedContent, signatureB64 string) error {
	v.mu.RLock()
	pubKey, exists := v.cachedKeys[keyID]
	expired := time.Now().After(v.keysExpiry)
	v.mu.RUnlock()

	if !exists || expired {
		if err := v.refreshGoogleCerts(); err != nil {
			return err
		}
		v.mu.RLock()
		pubKey, exists = v.cachedKeys[keyID]
		v.mu.RUnlock()
		if !exists {
			return fmt.Errorf("Google signing key ID '%s' not found in public JWKS certs", keyID)
		}
	}

	sigBytes, err := base64.RawURLEncoding.DecodeString(signatureB64)
	if err != nil {
		return fmt.Errorf("invalid signature base64 encoding: %w", err)
	}

	hasher := sha256.New()
	hasher.Write([]byte(signedContent))
	hashed := hasher.Sum(nil)

	if err := rsa.VerifyPKCS1v15(pubKey, crypto.SHA256, hashed, sigBytes); err != nil {
		return fmt.Errorf("RSA PKCS1v15 signature mismatch: %w", err)
	}

	return nil
}

func (v *GoogleVerifier) refreshGoogleCerts() error {
	resp, err := v.httpClient.Get("https://www.googleapis.com/oauth2/v3/certs")
	if err != nil {
		return fmt.Errorf("failed to fetch Google public certs: %w", err)
	}
	defer resp.Body.Close()

	var jwks struct {
		Keys []struct {
			Kid string `json:"kid"`
			N   string `json:"n"`
			E   string `json:"e"`
		} `json:"keys"`
	}

	if err := json.NewDecoder(resp.Body).Decode(&jwks); err != nil {
		return fmt.Errorf("failed to decode Google JWKS JSON: %w", err)
	}

	v.mu.Lock()
	defer v.mu.Unlock()

	for _, k := range jwks.Keys {
		nBytes, err := base64.RawURLEncoding.DecodeString(k.N)
		if err != nil {
			continue
		}
		eBytes, err := base64.RawURLEncoding.DecodeString(k.E)
		if err != nil {
			continue
		}

		n := new(big.Int).SetBytes(nBytes)
		var e int
		for _, b := range eBytes {
			e = (e << 8) | int(b)
		}

		v.cachedKeys[k.Kid] = &rsa.PublicKey{
			N: n,
			E: e,
		}
	}
	v.keysExpiry = time.Now().Add(6 * time.Hour)
	return nil
}
