package auth

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"strings"
	"testing"
	"time"
)

func makeSupabaseJWT(t *testing.T, secret, alg string, claims map[string]interface{}) string {
	t.Helper()
	header := map[string]string{"alg": alg, "typ": "JWT"}
	hb, err := json.Marshal(header)
	if err != nil {
		t.Fatalf("marshal header: %v", err)
	}
	cb, err := json.Marshal(claims)
	if err != nil {
		t.Fatalf("marshal claims: %v", err)
	}
	h := base64.RawURLEncoding.EncodeToString(hb)
	p := base64.RawURLEncoding.EncodeToString(cb)
	if alg == "none" {
		return h + "." + p + "."
	}
	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write([]byte(h + "." + p))
	sig := base64.RawURLEncoding.EncodeToString(mac.Sum(nil))
	return h + "." + p + "." + sig
}

func splitByDot(s string) []string {
	var res []string
	cur := ""
	for _, c := range s {
		if c == '.' {
			res = append(res, cur)
			cur = ""
		} else {
			cur += string(c)
		}
	}
	res = append(res, cur)
	return res
}

func mustB64Decode(t *testing.T, s string) []byte {
	t.Helper()
	b, err := base64.RawURLEncoding.DecodeString(s)
	if err != nil {
		t.Fatalf("base64 decode: %v", err)
	}
	return b
}

func TestSupabaseVerifier_ValidSignedToken(t *testing.T) {
	t.Setenv("SUPABASE_JWT_SECRET", "test-secret")
	t.Setenv("SUPABASE_URL", "https://example.supabase.co")
	v := NewSupabaseVerifier()

	claims := map[string]interface{}{
		"sub": "123e4567-e89b-12d3-a456-426614174000",
		"aud": "authenticated",
		"iss": "https://example.supabase.co/auth/v1",
		"exp": time.Now().Add(time.Hour).Unix(),
		"iat": time.Now().Unix(),
	}
	tok := makeSupabaseJWT(t, "test-secret", "HS256", claims)

	got, err := v.VerifyToken(tok)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if got.Subject != "123e4567-e89b-12d3-a456-426614174000" {
		t.Fatalf("subject mismatch: %s", got.Subject)
	}
}

func TestSupabaseVerifier_ForgedModifiedSub(t *testing.T) {
	t.Setenv("SUPABASE_JWT_SECRET", "test-secret")
	v := NewSupabaseVerifier()

	claims := map[string]interface{}{
		"sub": "123e4567-e89b-12d3-a456-426614174000",
		"aud": "authenticated",
		"exp": time.Now().Add(time.Hour).Unix(),
	}
	tok := makeSupabaseJWT(t, "test-secret", "HS256", claims)

	parts := splitByDot(tok)
	var c map[string]interface{}
	if err := json.Unmarshal(mustB64Decode(t, parts[1]), &c); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	c["sub"] = "ffffffff-ffff-ffff-ffff-ffffffffffff"
	cb, _ := json.Marshal(c)
	parts[1] = base64.RawURLEncoding.EncodeToString(cb)
	forged := parts[0] + "." + parts[1] + "." + parts[2]

	_, err := v.VerifyToken(forged)
	if err == nil {
		t.Fatal("expected error, got nil")
	}
	if !strings.Contains(err.Error(), "signature") {
		t.Fatalf("expected signature error, got %v", err)
	}
}

func TestSupabaseVerifier_InvalidSignature(t *testing.T) {
	t.Setenv("SUPABASE_JWT_SECRET", "test-secret")
	v := NewSupabaseVerifier()

	claims := map[string]interface{}{
		"sub": "123e4567-e89b-12d3-a456-426614174000",
		"aud": "authenticated",
		"exp": time.Now().Add(time.Hour).Unix(),
	}
	tok := makeSupabaseJWT(t, "wrong-secret", "HS256", claims)

	_, err := v.VerifyToken(tok)
	if err == nil {
		t.Fatal("expected error, got nil")
	}
	if !strings.Contains(err.Error(), "signature") {
		t.Fatalf("expected signature error, got %v", err)
	}
}

func TestSupabaseVerifier_MissingConfig(t *testing.T) {
	os.Unsetenv("SUPABASE_JWT_SECRET")
	v := NewSupabaseVerifier()

	claims := map[string]interface{}{
		"sub": "123e4567-e89b-12d3-a456-426614174000",
		"aud": "authenticated",
		"exp": time.Now().Add(time.Hour).Unix(),
	}
	tok := makeSupabaseJWT(t, "any-secret", "HS256", claims)

	_, err := v.VerifyToken(tok)
	if err == nil || !errors.Is(err, ErrSupabaseVerifierUnconfigured) {
		t.Fatalf("expected ErrSupabaseVerifierUnconfigured, got %v", err)
	}
}

func TestSupabaseVerifier_RegressionUnsetSecretNeverAuthenticates(t *testing.T) {
	os.Unsetenv("SUPABASE_JWT_SECRET")
	v := NewSupabaseVerifier()

	claims := map[string]interface{}{
		"sub": "123e4567-e89b-12d3-a456-426614174000",
		"aud": "authenticated",
		"exp": time.Now().Add(time.Hour).Unix(),
	}
	tok := makeSupabaseJWT(t, "", "HS256", claims)

	_, err := v.VerifyToken(tok)
	if err == nil || !errors.Is(err, ErrSupabaseVerifierUnconfigured) {
		t.Fatalf("expected ErrSupabaseVerifierUnconfigured, got %v", err)
	}
}

func TestSupabaseVerifier_ExpiredToken(t *testing.T) {
	t.Setenv("SUPABASE_JWT_SECRET", "test-secret")
	v := NewSupabaseVerifier()

	claims := map[string]interface{}{
		"sub": "123e4567-e89b-12d3-a456-426614174000",
		"aud": "authenticated",
		"exp": time.Now().Add(-time.Hour).Unix(),
	}
	tok := makeSupabaseJWT(t, "test-secret", "HS256", claims)

	_, err := v.VerifyToken(tok)
	if err == nil {
		t.Fatal("expected error, got nil")
	}
	if !strings.Contains(err.Error(), "expired") {
		t.Fatalf("expected expired error, got %v", err)
	}
}

func TestSupabaseVerifier_NotYetValid(t *testing.T) {
	t.Setenv("SUPABASE_JWT_SECRET", "test-secret")
	v := NewSupabaseVerifier()

	claims := map[string]interface{}{
		"sub": "123e4567-e89b-12d3-a456-426614174000",
		"aud": "authenticated",
		"exp": time.Now().Add(time.Hour).Unix(),
		"nbf": time.Now().Add(time.Hour).Unix(),
	}
	tok := makeSupabaseJWT(t, "test-secret", "HS256", claims)

	_, err := v.VerifyToken(tok)
	if err == nil {
		t.Fatal("expected error, got nil")
	}
	if !strings.Contains(err.Error(), "not yet valid") {
		t.Fatalf("expected not-yet-valid error, got %v", err)
	}
}

func TestSupabaseVerifier_IncorrectIssuer(t *testing.T) {
	t.Setenv("SUPABASE_JWT_SECRET", "test-secret")
	t.Setenv("SUPABASE_URL", "https://example.supabase.co")
	v := NewSupabaseVerifier()

	claims := map[string]interface{}{
		"sub": "123e4567-e89b-12d3-a456-426614174000",
		"aud": "authenticated",
		"iss": "https://evil.example.com",
		"exp": time.Now().Add(time.Hour).Unix(),
	}
	tok := makeSupabaseJWT(t, "test-secret", "HS256", claims)

	_, err := v.VerifyToken(tok)
	if err == nil {
		t.Fatal("expected error, got nil")
	}
	if !strings.Contains(err.Error(), "issuer") {
		t.Fatalf("expected issuer error, got %v", err)
	}
}

func TestSupabaseVerifier_IncorrectAudience(t *testing.T) {
	t.Setenv("SUPABASE_JWT_SECRET", "test-secret")
	v := NewSupabaseVerifier()

	claims := map[string]interface{}{
		"sub": "123e4567-e89b-12d3-a456-426614174000",
		"aud": "anon",
		"exp": time.Now().Add(time.Hour).Unix(),
	}
	tok := makeSupabaseJWT(t, "test-secret", "HS256", claims)

	_, err := v.VerifyToken(tok)
	if err == nil {
		t.Fatal("expected error, got nil")
	}
	if !strings.Contains(err.Error(), "audience") {
		t.Fatalf("expected audience error, got %v", err)
	}
}

func TestSupabaseVerifier_MissingSub(t *testing.T) {
	t.Setenv("SUPABASE_JWT_SECRET", "test-secret")
	v := NewSupabaseVerifier()

	claims := map[string]interface{}{
		"aud": "authenticated",
		"exp": time.Now().Add(time.Hour).Unix(),
	}
	tok := makeSupabaseJWT(t, "test-secret", "HS256", claims)

	_, err := v.VerifyToken(tok)
	if err == nil {
		t.Fatal("expected error, got nil")
	}
	if !strings.Contains(err.Error(), "missing sub") {
		t.Fatalf("expected missing sub error, got %v", err)
	}
}

func TestSupabaseVerifier_BadSubFormat(t *testing.T) {
	t.Setenv("SUPABASE_JWT_SECRET", "test-secret")
	v := NewSupabaseVerifier()

	claims := map[string]interface{}{
		"sub": "not-a-uuid",
		"aud": "authenticated",
		"exp": time.Now().Add(time.Hour).Unix(),
	}
	tok := makeSupabaseJWT(t, "test-secret", "HS256", claims)

	_, err := v.VerifyToken(tok)
	if err == nil {
		t.Fatal("expected error, got nil")
	}
	if !strings.Contains(err.Error(), "format") {
		t.Fatalf("expected format error, got %v", err)
	}
}

func TestSupabaseVerifier_UnsupportedAlgorithm(t *testing.T) {
	t.Setenv("SUPABASE_JWT_SECRET", "test-secret")
	v := NewSupabaseVerifier()

	claims := map[string]interface{}{
		"sub": "123e4567-e89b-12d3-a456-426614174000",
		"aud": "authenticated",
		"exp": time.Now().Add(time.Hour).Unix(),
	}
	tok := makeSupabaseJWT(t, "test-secret", "RS256", claims)

	_, err := v.VerifyToken(tok)
	if err == nil {
		t.Fatal("expected error, got nil")
	}
	if !strings.Contains(err.Error(), "unsupported JWT algorithm") {
		t.Fatalf("expected algorithm error, got %v", err)
	}
}

func TestSupabaseVerifier_AlgNone(t *testing.T) {
	t.Setenv("SUPABASE_JWT_SECRET", "test-secret")
	v := NewSupabaseVerifier()

	claims := map[string]interface{}{
		"sub": "123e4567-e89b-12d3-a456-426614174000",
		"aud": "authenticated",
		"exp": time.Now().Add(time.Hour).Unix(),
	}
	tok := makeSupabaseJWT(t, "test-secret", "none", claims)

	_, err := v.VerifyToken(tok)
	if err == nil {
		t.Fatal("expected error, got nil")
	}
}

func TestSupabaseVerifier_MalformedJWT(t *testing.T) {
	t.Setenv("SUPABASE_JWT_SECRET", "test-secret")
	v := NewSupabaseVerifier()

	if _, err := v.VerifyToken("not.a.valid.jwt.token"); err == nil {
		t.Fatal("expected error, got nil")
	}
	if _, err := v.VerifyToken("onlyonepart"); err == nil {
		t.Fatal("expected error, got nil")
	}
}

func TestSupabaseVerifier_EmptyToken(t *testing.T) {
	t.Setenv("SUPABASE_JWT_SECRET", "test-secret")
	v := NewSupabaseVerifier()

	if _, err := v.VerifyToken(""); err == nil {
		t.Fatal("expected error, got nil")
	}
}

// Handler-level regression: missing config yields 503, not 200/401.
func TestSupabaseVerifyHandler_MissingConfigReturns503(t *testing.T) {
	os.Unsetenv("SUPABASE_JWT_SECRET")
	t.Setenv("APP_ENV", "test")
	h, _, _ := newRegisterTestHandler(t)
	h.supabaseVerifier = NewSupabaseVerifier()

	w := postAuthJSON(t, h.handleSupabaseVerify, "", `{"access_token":"a.b.c"}`)
	if w.Code != 503 {
		t.Fatalf("expected 503, got %d body=%s", w.Code, w.Body.String())
	}
}

func TestSupabaseVerifyHandler_InvalidSignatureReturns401(t *testing.T) {
	t.Setenv("SUPABASE_JWT_SECRET", "test-secret")
	t.Setenv("APP_ENV", "test")
	h, _, _ := newRegisterTestHandler(t)
	h.supabaseVerifier = NewSupabaseVerifier()

	claims := map[string]interface{}{
		"sub": "123e4567-e89b-12d3-a456-426614174000",
		"aud": "authenticated",
		"exp": time.Now().Add(time.Hour).Unix(),
	}
	tok := makeSupabaseJWT(t, "wrong-secret", "HS256", claims)
	body := fmt.Sprintf(`{"access_token":%q}`, tok)
	w := postAuthJSON(t, h.handleSupabaseVerify, "", body)
	if w.Code != 401 {
		t.Fatalf("expected 401, got %d body=%s", w.Code, w.Body.String())
	}
}