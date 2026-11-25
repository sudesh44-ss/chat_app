package auth

import (
	"context"
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

	"github.com/redis/go-redis/v9"
)

var (
	ErrMissingFirebaseProjectID = errors.New("firebase verification failed: FIREBASE_PROJECT_ID is not configured")
	ErrInvalidFirebaseIssuer    = errors.New("firebase verification failed: invalid token issuer")
	ErrFirebaseAudienceMismatch = errors.New("firebase verification failed: audience mismatch")
	ErrFirebaseTokenExpired     = errors.New("firebase verification failed: token has expired")
	ErrMissingFirebasePhone     = errors.New("firebase verification failed: token does not contain a verified phone number claim")
	ErrFirebaseReplayDetected   = errors.New("firebase verification failed: token has already been consumed (replay attack detected)")
)

type FirebaseClaims struct {
	Issuer        string `json:"iss"`
	Audience      string `json:"aud"`
	Subject       string `json:"sub"` // Firebase UID
	PhoneNumber   string `json:"phone_number"`
	Email         string `json:"email,omitempty"`
	EmailVerified bool   `json:"email_verified,omitempty"`
	AuthTime      int64  `json:"auth_time"`
	IssuedAt      int64  `json:"iat"`
	ExpiresAt     int64  `json:"exp"`
	Firebase      struct {
		Identities struct {
			Phone []string `json:"phone,omitempty"`
			Email []string `json:"email,omitempty"`
		} `json:"identities"`
		SignInProvider string `json:"sign_in_provider"`
	} `json:"firebase"`
}

type FirebaseVerifier struct {
	mu          sync.RWMutex
	projectID   string
	httpClient  *http.Client
	cachedKeys  map[string]*rsa.PublicKey
	keysExpiry  time.Time
	redisClient *redis.Client
	usedTokens  map[string]int64 // in-memory anti-replay fallback: tokenHash -> expiryTimestamp
}

func NewFirebaseVerifier() *FirebaseVerifier {
	projectID := strings.TrimSpace(os.Getenv("FIREBASE_PROJECT_ID"))
	if projectID == "" {
		// Default to project ID from google-services.json if present
		projectID = "nexa-665c5"
	}
	return &FirebaseVerifier{
		projectID:  projectID,
		httpClient: &http.Client{Timeout: 10 * time.Second},
		cachedKeys: make(map[string]*rsa.PublicKey),
		usedTokens: make(map[string]int64),
	}
}

func (v *FirebaseVerifier) SetProjectIDForTesting(projectID string) {
	v.mu.Lock()
	defer v.mu.Unlock()
	v.projectID = projectID
}

func (v *FirebaseVerifier) SetKeysForTesting(keys map[string]*rsa.PublicKey) {
	v.mu.Lock()
	defer v.mu.Unlock()
	v.cachedKeys = keys
	v.keysExpiry = time.Now().Add(24 * time.Hour)
}

func (v *FirebaseVerifier) SetRedisClient(client *redis.Client) {
	v.mu.Lock()
	defer v.mu.Unlock()
	v.redisClient = client
}

// VerifyIdToken cryptographically verifies a Firebase Authentication Phone ID Token (JWT).
//
// Security guarantees:
// 1. Validates RS256 signature against Google's authoritative securetoken JWKS endpoint.
// 2. Asserts issuer == "https://securetoken.google.com/<project_id>"
// 3. Asserts audience == project_id
// 4. Asserts token is not expired (exp > now)
// 5. Asserts phone_number claim is present and authoritative (E.164)
// 6. Enforces anti-replay protection so a token cannot be consumed twice.
func (v *FirebaseVerifier) VerifyIdToken(tokenString string) (*FirebaseClaims, error) {
	v.mu.RLock()
	projectID := v.projectID
	v.mu.RUnlock()

	if projectID == "" {
		return nil, ErrMissingFirebaseProjectID
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
		return nil, fmt.Errorf("malformed header json: %w", err)
	}
	if header.Algorithm != "RS256" {
		return nil, fmt.Errorf("unsupported algorithm: %s, expected RS256", header.Algorithm)
	}
	if header.KeyID == "" {
		return nil, errors.New("missing kid in token header")
	}

	// 2. Decode Claims
	payloadBytes, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return nil, fmt.Errorf("invalid payload base64: %w", err)
	}
	var claims FirebaseClaims
	if err := json.Unmarshal(payloadBytes, &claims); err != nil {
		return nil, fmt.Errorf("malformed payload json: %w", err)
	}

	now := time.Now().Unix()
	if claims.ExpiresAt <= now {
		return nil, ErrFirebaseTokenExpired
	}

	expectedIssuer := "https://securetoken.google.com/" + projectID
	if claims.Issuer != expectedIssuer {
		return nil, fmt.Errorf("%w: expected '%s', got '%s'", ErrInvalidFirebaseIssuer, expectedIssuer, claims.Issuer)
	}

	if claims.Audience != projectID {
		return nil, fmt.Errorf("%w: expected '%s', got '%s'", ErrFirebaseAudienceMismatch, projectID, claims.Audience)
	}

	// Authoritative phone claim verification
	cleanPhone := strings.TrimSpace(claims.PhoneNumber)
	if cleanPhone == "" && len(claims.Firebase.Identities.Phone) > 0 {
		cleanPhone = strings.TrimSpace(claims.Firebase.Identities.Phone[0])
	}
	if cleanPhone == "" {
		return nil, ErrMissingFirebasePhone
	}
	canonicalPhone, err := CanonicalizePhone(cleanPhone)
	if err != nil {
		return nil, fmt.Errorf("invalid phone format in token: %w", err)
	}
	claims.PhoneNumber = canonicalPhone

	// 3. Replay Protection: Token Hash Check
	tokenHash := fmt.Sprintf("%x", sha256.Sum256([]byte(tokenString)))
	if err := v.consumeToken(tokenHash, claims.ExpiresAt); err != nil {
		return nil, err
	}

	// 4. Cryptographic Signature Verification
	pubKey, err := v.getPublicKey(header.KeyID)
	if err != nil {
		return nil, fmt.Errorf("failed to get public key '%s': %w", header.KeyID, err)
	}

	sigBytes, err := base64.RawURLEncoding.DecodeString(parts[2])
	if err != nil {
		return nil, fmt.Errorf("invalid signature base64: %w", err)
	}

	signedContent := parts[0] + "." + parts[1]
	hash := sha256.Sum256([]byte(signedContent))
	if err := rsa.VerifyPKCS1v15(pubKey, crypto.SHA256, hash[:], sigBytes); err != nil {
		return nil, fmt.Errorf("cryptographic signature verification failed: %w", err)
	}

	return &claims, nil
}

func (v *FirebaseVerifier) consumeToken(tokenHash string, exp int64) error {
	v.mu.Lock()
	defer v.mu.Unlock()

	// Check Redis if available
	if v.redisClient != nil {
		ctx := context.Background()
		ttl := time.Until(time.Unix(exp, 0))
		if ttl <= 0 {
			ttl = 1 * time.Minute
		}
		set, err := v.redisClient.SetNX(ctx, "consumed_firebase_token:"+tokenHash, "1", ttl).Result()
		if err == nil && !set {
			return ErrFirebaseReplayDetected
		}
	}

	// In-memory fallback
	now := time.Now().Unix()
	// Purge expired entries
	for k, expiry := range v.usedTokens {
		if expiry <= now {
			delete(v.usedTokens, k)
		}
	}

	if _, exists := v.usedTokens[tokenHash]; exists {
		return ErrFirebaseReplayDetected
	}
	v.usedTokens[tokenHash] = exp
	return nil
}

func (v *FirebaseVerifier) getPublicKey(kid string) (*rsa.PublicKey, error) {
	v.mu.RLock()
	key, exists := v.cachedKeys[kid]
	expired := time.Now().After(v.keysExpiry)
	v.mu.RUnlock()

	if exists && !expired {
		return key, nil
	}

	v.mu.Lock()
	defer v.mu.Unlock()

	// Double-check under write lock
	if key, exists := v.cachedKeys[kid]; exists && !time.Now().After(v.keysExpiry) {
		return key, nil
	}

	if err := v.refreshKeys(); err != nil {
		if exists {
			return key, nil // Use stale key if network fetch fails
		}
		return nil, err
	}

	key, exists = v.cachedKeys[kid]
	if !exists {
		return nil, fmt.Errorf("public key with kid '%s' not found in Google Firebase JWKS", kid)
	}
	return key, nil
}

func (v *FirebaseVerifier) refreshKeys() error {
	jwksURL := "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com"
	resp, err := v.httpClient.Get(jwksURL)
	if err != nil {
		return fmt.Errorf("network error fetching Firebase JWKS: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("unexpected HTTP status %d fetching Firebase JWKS", resp.StatusCode)
	}

	var jwks struct {
		Keys []struct {
			Kty string `json:"kty"`
			Kid string `json:"kid"`
			N   string `json:"n"`
			E   string `json:"e"`
		} `json:"keys"`
	}

	if err := json.NewDecoder(resp.Body).Decode(&jwks); err != nil {
		return fmt.Errorf("malformed Firebase JWKS JSON: %w", err)
	}

	newKeys := make(map[string]*rsa.PublicKey)
	for _, key := range jwks.Keys {
		if key.Kty != "RSA" || key.N == "" || key.E == "" {
			continue
		}

		nBytes, err := base64.RawURLEncoding.DecodeString(key.N)
		if err != nil {
			continue
		}
		eBytes, err := base64.RawURLEncoding.DecodeString(key.E)
		if err != nil {
			continue
		}

		n := new(big.Int).SetBytes(nBytes)
		var eInt int
		for _, b := range eBytes {
			eInt = (eInt << 8) | int(b)
		}

		newKeys[key.Kid] = &rsa.PublicKey{
			N: n,
			E: eInt,
		}
	}

	if len(newKeys) == 0 {
		return errors.New("no valid RSA public keys found in Firebase JWKS")
	}

	v.cachedKeys = newKeys
	v.keysExpiry = time.Now().Add(6 * time.Hour)
	return nil
}
