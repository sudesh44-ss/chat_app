package auth

import (
	"context"
	"crypto"
	"crypto/rsa"
	"crypto/sha512"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/redis/go-redis/v9"
)

var (
	ErrMissingTruecallerKey      = errors.New("truecaller verification failed: TRUECALLER_APP_KEY is not configured")
	ErrInvalidTruecallerPayload  = errors.New("truecaller verification failed: malformed payload")
	ErrTruecallerSignatureFailed = errors.New("truecaller verification failed: cryptographic signature check failed")
	ErrTruecallerExpired         = errors.New("truecaller verification failed: request timestamp has expired")
	ErrTruecallerNonceMismatch   = errors.New("truecaller verification failed: nonce mismatch or empty")
	ErrTruecallerReplayDetected  = errors.New("truecaller verification failed: verification payload already consumed (replay detected)")
	ErrTruecallerPhoneUnverified = errors.New("truecaller verification failed: phone number is unverified")
)

type TruecallerProfilePayload struct {
	PhoneNumber  string `json:"phoneNumber"`
	FirstName    string `json:"firstName,omitempty"`
	LastName     string `json:"lastName,omitempty"`
	Gender       string `json:"gender,omitempty"`
	AvatarURL    string `json:"avatarUrl,omitempty"`
	RequestNonce string `json:"requestNonce"`
	Timestamp    int64  `json:"timestamp"`
}

type TruecallerOAuthUserInfo struct {
	Subject             string `json:"sub"`
	PhoneNumber         string `json:"phone_number"`
	PhoneNumberVerified bool   `json:"phone_number_verified"`
	GivenName           string `json:"given_name,omitempty"`
	FamilyName          string `json:"family_name,omitempty"`
	Picture             string `json:"picture,omitempty"`
	Gender              string `json:"gender,omitempty"`
}

type TruecallerVerifier struct {
	mu          sync.RWMutex
	appKey      string
	httpClient  *http.Client
	cachedKeys  map[string]*rsa.PublicKey
	keysExpiry  time.Time
	redisClient *redis.Client
	usedNonces  map[string]int64 // in-memory anti-replay fallback: nonce -> expiry
}

func NewTruecallerVerifier() *TruecallerVerifier {
	appKey := strings.TrimSpace(os.Getenv("TRUECALLER_APP_KEY"))
	if appKey == "" {
		appKey = strings.TrimSpace(os.Getenv("TRUECALLER_CLIENT_ID"))
	}
	return &TruecallerVerifier{
		appKey:     appKey,
		httpClient: &http.Client{Timeout: 10 * time.Second},
		cachedKeys: make(map[string]*rsa.PublicKey),
		usedNonces: make(map[string]int64),
	}
}

func (v *TruecallerVerifier) SetAppKeyForTesting(appKey string) {
	v.mu.Lock()
	defer v.mu.Unlock()
	v.appKey = appKey
}

func (v *TruecallerVerifier) SetKeysForTesting(keys map[string]*rsa.PublicKey) {
	v.mu.Lock()
	defer v.mu.Unlock()
	v.cachedKeys = keys
	v.keysExpiry = time.Now().Add(24 * time.Hour)
}

func (v *TruecallerVerifier) SetRedisClient(client *redis.Client) {
	v.mu.Lock()
	defer v.mu.Unlock()
	v.redisClient = client
}

// VerifyProfilePayload verifies a Truecaller Android SDK profile payload and RSA signature.
//
// Security guarantees:
// 1. Checks payload signature against Truecaller's official public keys using SHA512withRSA.
// 2. Asserts expectedNonce == payload.requestNonce (prevents request substitution).
// 3. Asserts timestamp is within 5 minutes of current server clock (prevents replay).
// 4. Enforces single-use consumption of requestNonce.
// 5. Normalizes verified phone number to E.164.
func (v *TruecallerVerifier) VerifyProfilePayload(
	payloadBase64 string,
	signatureBase64 string,
	expectedNonce string,
) (*TruecallerProfilePayload, error) {
	if strings.TrimSpace(payloadBase64) == "" || strings.TrimSpace(signatureBase64) == "" {
		return nil, ErrInvalidTruecallerPayload
	}

	payloadBytes, err := base64.StdEncoding.DecodeString(payloadBase64)
	if err != nil {
		// Try raw URL encoding as fallback
		var urlErr error
		payloadBytes, urlErr = base64.RawURLEncoding.DecodeString(payloadBase64)
		if urlErr != nil {
			return nil, fmt.Errorf("invalid payload base64: %w", err)
		}
	}

	var profile TruecallerProfilePayload
	if err := json.Unmarshal(payloadBytes, &profile); err != nil {
		return nil, fmt.Errorf("malformed profile payload JSON: %w", err)
	}

	if expectedNonce != "" && profile.RequestNonce != expectedNonce {
		return nil, fmt.Errorf("%w: expected '%s', got '%s'", ErrTruecallerNonceMismatch, expectedNonce, profile.RequestNonce)
	}

	// 5-minute freshness window
	nowMs := time.Now().UnixMilli()
	if profile.Timestamp <= 0 || (nowMs-profile.Timestamp) > (5*60*1000) || (profile.Timestamp-nowMs) > (60*1000) {
		return nil, ErrTruecallerExpired
	}

	// Replay defense
	nonceToConsume := profile.RequestNonce
	if nonceToConsume == "" {
		nonceToConsume = payloadBase64
	}
	if err := v.consumeNonce(nonceToConsume); err != nil {
		return nil, err
	}

	// Cryptographic signature check
	sigBytes, err := base64.StdEncoding.DecodeString(signatureBase64)
	if err != nil {
		sigBytes, err = base64.RawURLEncoding.DecodeString(signatureBase64)
		if err != nil {
			return nil, fmt.Errorf("invalid signature base64: %w", err)
		}
	}

	pubKeys, err := v.getPublicKeys()
	if err != nil {
		return nil, fmt.Errorf("failed to retrieve Truecaller public keys: %w", err)
	}

	hash := sha512.Sum512(payloadBytes)
	verified := false
	for _, pubKey := range pubKeys {
		if err := rsa.VerifyPKCS1v15(pubKey, crypto.SHA512, hash[:], sigBytes); err == nil {
			verified = true
			break
		}
	}

	if !verified {
		return nil, ErrTruecallerSignatureFailed
	}

	canonicalPhone, err := CanonicalizePhone(profile.PhoneNumber)
	if err != nil {
		return nil, fmt.Errorf("invalid phone format in Truecaller payload: %w", err)
	}
	profile.PhoneNumber = canonicalPhone

	return &profile, nil
}

// VerifyOAuthToken exchanges a Truecaller OAuth Access Token at the UserInfo endpoint.
func (v *TruecallerVerifier) VerifyOAuthToken(accessToken string) (*TruecallerOAuthUserInfo, error) {
	cleanToken := strings.TrimSpace(accessToken)
	if cleanToken == "" {
		return nil, errors.New("missing Truecaller access token")
	}

	req, err := http.NewRequest(http.MethodGet, "https://oauth-account-noneu.truecaller.com/v1/userinfo", nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Authorization", "Bearer "+cleanToken)

	resp, err := v.httpClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("Truecaller UserInfo network error: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("Truecaller UserInfo returned HTTP %d", resp.StatusCode)
	}

	var userInfo TruecallerOAuthUserInfo
	if err := json.NewDecoder(resp.Body).Decode(&userInfo); err != nil {
		return nil, fmt.Errorf("malformed UserInfo JSON: %w", err)
	}

	if !userInfo.PhoneNumberVerified {
		return nil, ErrTruecallerPhoneUnverified
	}

	canonicalPhone, err := CanonicalizePhone(userInfo.PhoneNumber)
	if err != nil {
		return nil, fmt.Errorf("invalid phone format in Truecaller userinfo: %w", err)
	}
	userInfo.PhoneNumber = canonicalPhone

	return &userInfo, nil
}

// ExchangeCodeAndVerify exchanges a Truecaller OAuth authorization code + codeVerifier for an access token
// and retrieves the verified phone number from Truecaller's UserInfo endpoint.
func (v *TruecallerVerifier) ExchangeCodeAndVerify(authorizationCode, codeVerifier string) (*TruecallerOAuthUserInfo, error) {
	code := strings.TrimSpace(authorizationCode)
	verifier := strings.TrimSpace(codeVerifier)
	if code == "" || verifier == "" {
		return nil, errors.New("missing Truecaller authorizationCode or codeVerifier")
	}

	appKey := strings.TrimSpace(v.appKey)
	if appKey == "" {
		appKey = strings.TrimSpace(os.Getenv("TRUECALLER_CLIENT_ID"))
	}
	if appKey == "" {
		return nil, ErrMissingTruecallerKey
	}

	form := url.Values{}
	form.Set("grant_type", "authorization_code")
	form.Set("client_id", appKey)
	form.Set("code", code)
	form.Set("code_verifier", verifier)

	req, err := http.NewRequest(http.MethodPost, "https://oauth-account-noneu.truecaller.com/v1/token", strings.NewReader(form.Encode()))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")

	resp, err := v.httpClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("Truecaller token exchange network error: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("Truecaller token exchange returned HTTP %d", resp.StatusCode)
	}

	var tokenResp struct {
		AccessToken string `json:"access_token"`
		TokenType   string `json:"token_type"`
		ExpiresIn   int    `json:"expires_in"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&tokenResp); err != nil {
		return nil, fmt.Errorf("malformed Truecaller token response: %w", err)
	}

	return v.VerifyOAuthToken(tokenResp.AccessToken)
}

func (v *TruecallerVerifier) consumeNonce(nonce string) error {
	v.mu.Lock()
	defer v.mu.Unlock()

	if v.redisClient != nil {
		ctx := context.Background()
		set, err := v.redisClient.SetNX(ctx, "consumed_tc_nonce:"+nonce, "1", 10*time.Minute).Result()
		if err == nil && !set {
			return ErrTruecallerReplayDetected
		}
	}

	now := time.Now().Unix()
	for k, exp := range v.usedNonces {
		if exp <= now {
			delete(v.usedNonces, k)
		}
	}

	if _, exists := v.usedNonces[nonce]; exists {
		return ErrTruecallerReplayDetected
	}
	v.usedNonces[nonce] = now + 600
	return nil
}

func (v *TruecallerVerifier) getPublicKeys() ([]*rsa.PublicKey, error) {
	v.mu.RLock()
	keys := make([]*rsa.PublicKey, 0, len(v.cachedKeys))
	for _, k := range v.cachedKeys {
		keys = append(keys, k)
	}
	expired := time.Now().After(v.keysExpiry)
	v.mu.RUnlock()

	if len(keys) > 0 && !expired {
		return keys, nil
	}

	v.mu.Lock()
	defer v.mu.Unlock()

	if len(v.cachedKeys) > 0 && !time.Now().After(v.keysExpiry) {
		res := make([]*rsa.PublicKey, 0, len(v.cachedKeys))
		for _, k := range v.cachedKeys {
			res = append(res, k)
		}
		return res, nil
	}

	if err := v.refreshKeys(); err != nil {
		if len(keys) > 0 {
			return keys, nil
		}
		return nil, err
	}

	res := make([]*rsa.PublicKey, 0, len(v.cachedKeys))
	for _, k := range v.cachedKeys {
		res = append(res, k)
	}
	return res, nil
}

func (v *TruecallerVerifier) refreshKeys() error {
	keyURL := "https://api4.truecaller.com/v1/key"
	resp, err := v.httpClient.Get(keyURL)
	if err != nil {
		return fmt.Errorf("network error fetching Truecaller keys: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("unexpected HTTP status %d fetching Truecaller keys", resp.StatusCode)
	}

	var keyList []struct {
		KeyType string `json:"keyType"`
		Key     string `json:"key"`
	}

	if err := json.NewDecoder(resp.Body).Decode(&keyList); err != nil {
		return fmt.Errorf("malformed Truecaller key JSON: %w", err)
	}

	newKeys := make(map[string]*rsa.PublicKey)
	for i, item := range keyList {
		keyBytes, err := base64.StdEncoding.DecodeString(item.Key)
		if err != nil {
			continue
		}
		pub, err := x509.ParsePKIXPublicKey(keyBytes)
		if err != nil {
			continue
		}
		if rsaPub, ok := pub.(*rsa.PublicKey); ok {
			newKeys[fmt.Sprintf("tc_key_%d", i)] = rsaPub
		}
	}

	if len(newKeys) == 0 {
		return errors.New("no valid RSA public keys parsed from Truecaller API")
	}

	v.cachedKeys = newKeys
	v.keysExpiry = time.Now().Add(24 * time.Hour)
	return nil
}
