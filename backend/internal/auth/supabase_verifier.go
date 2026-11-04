package auth

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"os"
	"strings"
	"time"
)

type SupabaseClaims struct {
	Subject       string                 `json:"sub"`
	Email         string                 `json:"email"`
	Phone         string                 `json:"phone"`
	Role          string                 `json:"role"`
	Audience      string                 `json:"aud"`
	Issuer        string                 `json:"iss"`
	ExpiresAt     int64                  `json:"exp"`
	IssuedAt      int64                  `json:"iat"`
	AppMetadata   map[string]interface{} `json:"app_metadata"`
	UserMetadata  map[string]interface{} `json:"user_metadata"`
}

type SupabaseVerifier struct {
	jwtSecret   []byte
	projectURL  string
	enforceHMAC bool
}

func NewSupabaseVerifier() *SupabaseVerifier {
	secret := os.Getenv("SUPABASE_JWT_SECRET")
	projectURL := os.Getenv("SUPABASE_URL")
	enforceHMAC := secret != ""

	return &SupabaseVerifier{
		jwtSecret:   []byte(secret),
		projectURL:  projectURL,
		enforceHMAC: enforceHMAC,
	}
}

// VerifyToken validates a Supabase Auth JWT and returns its claims.
// Supports HS256 HMAC verification when SUPABASE_JWT_SECRET is configured,
// and strictly validates token expiration, format, and subject claim.
func (v *SupabaseVerifier) VerifyToken(tokenString string) (*SupabaseClaims, error) {
	parts := strings.Split(tokenString, ".")
	if len(parts) != 3 {
		return nil, fmt.Errorf("malformed JWT: expected 3 segment dot-separated token")
	}

	headerSegment, payloadSegment, signatureSegment := parts[0], parts[1], parts[2]

	// 1. Decode Header
	headerBytes, err := base64.RawURLEncoding.DecodeString(headerSegment)
	if err != nil {
		return nil, fmt.Errorf("invalid base64url header: %w", err)
	}

	var header struct {
		Alg string `json:"alg"`
		Typ string `json:"typ"`
		Kid string `json:"kid,omitempty"`
	}
	if err := json.Unmarshal(headerBytes, &header); err != nil {
		return nil, fmt.Errorf("invalid JWT header JSON: %w", err)
	}

	// 2. Cryptographic Signature Verification (HS256)
	if v.enforceHMAC {
		if header.Alg != "HS256" {
			return nil, fmt.Errorf("unsupported JWT algorithm %s: expected HS256", header.Alg)
		}

		mac := hmac.New(sha256.New, v.jwtSecret)
		mac.Write([]byte(headerSegment + "." + payloadSegment))
		expectedSig := mac.Sum(nil)

		actualSig, err := base64.RawURLEncoding.DecodeString(signatureSegment)
		if err != nil {
			return nil, fmt.Errorf("invalid signature base64: %w", err)
		}

		if !hmac.Equal(actualSig, expectedSig) {
			return nil, fmt.Errorf("cryptographic signature verification failed: invalid Supabase JWT signature")
		}
	}

	// 3. Decode Payload
	payloadBytes, err := base64.RawURLEncoding.DecodeString(payloadSegment)
	if err != nil {
		return nil, fmt.Errorf("invalid base64url payload: %w", err)
	}

	var claims SupabaseClaims
	if err := json.Unmarshal(payloadBytes, &claims); err != nil {
		return nil, fmt.Errorf("invalid JWT claims JSON: %w", err)
	}

	// 4. Validate Expiry and Temporal Claims
	now := time.Now().Unix()
	if claims.ExpiresAt > 0 && now >= claims.ExpiresAt {
		return nil, fmt.Errorf("supabase access token expired at %d (current time %d)", claims.ExpiresAt, now)
	}
	if claims.IssuedAt > 0 && claims.IssuedAt > now+60 {
		return nil, fmt.Errorf("supabase token issued in the future (iat %d, current time %d)", claims.IssuedAt, now)
	}

	// 5. Validate Audience
	if claims.Audience != "" && claims.Audience != "authenticated" {
		return nil, fmt.Errorf("invalid token audience: expected 'authenticated', got '%s'", claims.Audience)
	}

	// 6. Validate Issuer
	if v.projectURL != "" && claims.Issuer != "" {
		expectedPrefix := strings.TrimRight(v.projectURL, "/")
		if !strings.HasPrefix(claims.Issuer, expectedPrefix) {
			return nil, fmt.Errorf("invalid token issuer: expected prefix '%s', got '%s'", expectedPrefix, claims.Issuer)
		}
	}

	// 7. Validate Subject (Supabase User ID)
	if claims.Subject == "" {
		return nil, fmt.Errorf("missing sub (Supabase user ID) in token claims")
	}

	return &claims, nil
}
