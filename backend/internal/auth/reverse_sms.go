package auth

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"math/big"
	"os"
	"regexp"
	"strings"
	"sync"
	"time"

	"github.com/redis/go-redis/v9"
	"nexa/wire/internal/identity"
	"nexa/wire/internal/ratelimit"
)

var (
	ErrInvalidDomesticPhone   = errors.New("invalid domestic mobile number: must be valid 10-digit Indian mobile starting with 6-9 (+91)")
	ErrReverseRateLimited     = errors.New("rate limit exceeded for reverse verification: maximum attempts reached")
	ErrNonceNotFound          = errors.New("verification nonce not found or already consumed")
	ErrNonceExpired           = errors.New("verification nonce has expired (validity: 90 seconds)")
	ErrPhoneMismatch          = errors.New("sender phone number does not match registered initiation number")
	ErrPhoneAlreadyRegistered = errors.New("phone number is already associated with an existing account")
)

var (
	domesticIndiaRegex  = regexp.MustCompile(`^(\+91)?[6-9]\d{9}$`)
	nonceExtractorRegex = regexp.MustCompile(`NX-[2-9A-HJ-NP-Z0-9]{4,10}`)
)

// CanonicalizeDomesticPhone enforces strict domestic Indian mobile validation (+91 series 6-9).
// Foreign and premium numbers are rejected outright.
func CanonicalizeDomesticPhone(raw string) (string, error) {
	trimmed := strings.TrimSpace(raw)
	clean := strings.ReplaceAll(trimmed, " ", "")
	clean = strings.ReplaceAll(clean, "-", "")
	clean = strings.ReplaceAll(clean, "(", "")
	clean = strings.ReplaceAll(clean, ")", "")

	if !domesticIndiaRegex.MatchString(clean) {
		return "", fmt.Errorf("%w: '%s'", ErrInvalidDomesticPhone, raw)
	}

	// Canonicalize to +91XXXXXXXXXX
	if strings.HasPrefix(clean, "+91") {
		return clean, nil
	}
	if strings.HasPrefix(clean, "91") && len(clean) == 12 {
		return "+" + clean, nil
	}
	if len(clean) == 10 {
		return "+91" + clean, nil
	}

	return clean, nil
}

type InitiateReverseVerifyRequest struct {
	GoogleUID       string `json:"google_uid"`
	GoogleAuthToken string `json:"google_auth_token,omitempty"`
	Email           string `json:"email,omitempty"`
	PhoneNumber     string `json:"phone_number"`
	Name            string `json:"name"`
	AvatarURL       string `json:"avatar_url,omitempty"`
	Gender          string `json:"gender,omitempty"`
	Age             int    `json:"age,omitempty"`
	Bio             string `json:"bio,omitempty"`
	PublicKeyHex    string `json:"public_key_hex"`
	DhPublicKeyHex  string `json:"dh_public_key_hex,omitempty"`
	DeviceID        string `json:"device_id,omitempty"`
	DeviceName      string `json:"device_name,omitempty"`
	IsReverify      bool   `json:"is_reverify,omitempty"`
}

type InitiateReverseVerifyResponse struct {
	Status                string                `json:"status"`
	Nonce                 string                `json:"nonce"`
	SessionID             string                `json:"session_id,omitempty"`
	InboundReceiverNumber string                `json:"inbound_receiver_number"`
	InboundNumber         string                `json:"inbound_number,omitempty"`
	SmsPayload            string                `json:"sms_payload"`
	ExpiresInSeconds      int                   `json:"expires_in_seconds"`
	NxID                  string                `json:"nx_id,omitempty"`
	SessionAuthToken      string                `json:"session_auth_token,omitempty"`
	SessionToken          string                `json:"session_token,omitempty"`
	VerificationMethod    string                `json:"verification_method,omitempty"`
	VerifiedAt            int64                 `json:"verified_at,omitempty"`
	User                  *identity.UserAccount `json:"user,omitempty"`
}

type PendingVerification struct {
	Nonce              string `json:"nonce"`
	SessionID          string `json:"session_id,omitempty"`
	GoogleUID          string `json:"google_uid"`
	Email              string `json:"email"`
	PhoneNumber        string `json:"phone_number"`
	Name               string `json:"name"`
	AvatarURL          string `json:"avatar_url"`
	Gender             string `json:"gender"`
	Age                int    `json:"age"`
	Bio                string `json:"bio"`
	PublicKeyHex       string `json:"public_key_hex"`
	DhPublicKeyHex     string `json:"dh_public_key_hex"`
	DeviceID           string `json:"device_id"`
	DeviceName         string `json:"device_name"`
	IsReverify         bool   `json:"is_reverify"`
	Status             string `json:"status"` // "PENDING", "VERIFIED", "CONSUMED", "EXPIRED"
	VerificationMethod string `json:"verification_method,omitempty"`
	VerifiedAt         int64  `json:"verified_at,omitempty"`
	CreatedAt          int64  `json:"created_at"`
	ExpiresAt          int64  `json:"expires_at"`
	AllocatedNxID      string `json:"allocated_nx_id,omitempty"`
	SessionAuthToken   string `json:"session_auth_token,omitempty"`
}

type VerificationStatusResponse struct {
	Status             string                `json:"status"` // "PENDING", "VERIFIED", "EXPIRED"
	IsVerified         bool                  `json:"is_verified"`
	VerificationMethod string                `json:"verification_method,omitempty"`
	VerifiedAt         int64                 `json:"verified_at,omitempty"`
	ExpiresInSeconds   int                   `json:"expires_in_seconds,omitempty"`
	NxID               string                `json:"nx_id,omitempty"`
	SessionAuthToken   string                `json:"session_auth_token,omitempty"`
	SessionToken       string                `json:"session_token,omitempty"`
	Name               string                `json:"name,omitempty"`
	Phone              string                `json:"phone,omitempty"`
	AvatarURL          string                `json:"avatar_url,omitempty"`
	User               *identity.UserAccount `json:"user,omitempty"`
}

type UserResolveResponse struct {
	NxID      string `json:"nx_id"`
	Name      string `json:"name"`
	AvatarURL string `json:"avatar_url,omitempty"`
	Bio       string `json:"bio,omitempty"`
	IsFriend  bool   `json:"is_friend"`
}

type ReverseSmsService struct {
	mu                 sync.RWMutex
	store              identity.IdentityRepository
	redisClient        *redis.Client
	limiter            ratelimit.Limiter
	pending            map[string]*PendingVerification // nonce -> record
	bySession          map[string]string               // sessionId -> nonce
	byPhone            map[string]string               // phone -> active nonce
	ipRate             map[string][]int64              // ip -> timestamps
	userRate           map[string][]int64              // googleUid/device -> timestamps
	inboundReceiverNum string
	trustedRegistry    *TrustedPhoneRegistry
	googleVerifier     *GoogleVerifier
}

func NewReverseSmsService(store identity.IdentityRepository) *ReverseSmsService {
	inbound := strings.TrimSpace(os.Getenv("INBOUND_SMS_RECEIVER_NUMBER"))
	env := strings.ToLower(os.Getenv("APP_ENV"))
	if env == "" {
		env = strings.ToLower(os.Getenv("ENV"))
	}
	isProduction := env == "production"

	if inbound == "" {
		if isProduction {
			log.Println("[ReverseSMS] CRITICAL: INBOUND_SMS_RECEIVER_NUMBER is not configured in production!")
		} else {
			// Default automated inbound virtual receiver / GSM gateway number for local dev/testing
			inbound = "+919800000000"
		}
	} else if isProduction && (inbound == "+919800000000" || inbound == "+910000000000") {
		log.Printf("[ReverseSMS] CRITICAL: Dummy or placeholder INBOUND_SMS_RECEIVER_NUMBER (%s) detected in production!", inbound)
	}

	return &ReverseSmsService{
		store:              store,
		limiter:            ratelimit.NewDistributedLimiter(nil, isProduction),
		pending:            make(map[string]*PendingVerification),
		bySession:          make(map[string]string),
		byPhone:            make(map[string]string),
		ipRate:             make(map[string][]int64),
		userRate:           make(map[string][]int64),
		inboundReceiverNum: inbound,
		trustedRegistry:    NewTrustedPhoneRegistry(),
	}
}

// SetTrustedRegistry injects a custom or mocked trusted phone registry (e.g. for testing).
func (s *ReverseSmsService) SetTrustedRegistry(registry *TrustedPhoneRegistry) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.trustedRegistry = registry
}

// GetTrustedRegistry returns the active trusted phone registry.
func (s *ReverseSmsService) GetTrustedRegistry() *TrustedPhoneRegistry {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.trustedRegistry
}

// SetGoogleVerifier attaches a Google token verifier for authenticating Google identity tokens.
func (s *ReverseSmsService) SetGoogleVerifier(v *GoogleVerifier) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.googleVerifier = v
}

// SetRedisClient attaches a Redis client for cluster-wide nonce distribution, persistence, and distributed rate limiting.
func (s *ReverseSmsService) SetRedisClient(client *redis.Client) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.redisClient = client
	env := strings.ToLower(os.Getenv("APP_ENV"))
	if env == "" {
		env = strings.ToLower(os.Getenv("ENV"))
	}
	isProduction := env == "production"
	if client != nil {
		s.limiter = ratelimit.NewDistributedLimiter(client, isProduction)
		log.Println("[ReverseSMS] Distributed Redis nonce caching and rate limiting enabled.")
	}
}

// SetRateLimiter allows injection of a custom or mocked rate limiter (e.g. for testing).
func (s *ReverseSmsService) SetRateLimiter(l ratelimit.Limiter) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.limiter = l
}

func (s *ReverseSmsService) checkRateLimit(key string, tracker map[string][]int64, windowMs int64, maxAttempts int) bool {
	now := time.Now().UnixMilli()
	threshold := now - windowMs

	timestamps := tracker[key]
	var valid []int64
	for _, ts := range timestamps {
		if ts > threshold {
			valid = append(valid, ts)
		}
	}

	if len(valid) >= maxAttempts {
		tracker[key] = valid
		return false
	}

	valid = append(valid, now)
	tracker[key] = valid
	return true
}

func (s *ReverseSmsService) generateCryptoNonce() (string, error) {
	const charset = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
	charsetLen := big.NewInt(int64(len(charset)))
	buf := make([]byte, 5)

	for i := 0; i < 5; i++ {
		idx, err := rand.Int(rand.Reader, charsetLen)
		if err != nil {
			return "", err
		}
		buf[i] = charset[idx.Int64()]
	}

	return "NX-" + string(buf), nil
}

// InitiateVerification creates a verification session.
// If the phone number is recognized as a server-controlled trusted number via NEXA_TRUSTED_PHONE_NUMBERS,
// the Client-Initiated Reverse SMS verification challenge is bypassed and the user continues through
// the authentic account registration pipeline immediately (with verification_method: "trusted_allowlist").
// Non-trusted phone numbers continue through the existing Reverse SMS flow without alteration.
func (s *ReverseSmsService) InitiateVerification(req *InitiateReverseVerifyRequest, clientIP string) (*InitiateReverseVerifyResponse, error) {
	isTrusted := false
	var canonicalPhone string
	var phoneErr error

	// 1. Check whether submitted phone number exists in server-controlled trusted allowlist
	if s.trustedRegistry != nil && s.trustedRegistry.IsTrusted(req.PhoneNumber) {
		isTrusted = true
		canonicalPhone, phoneErr = CanonicalizePhone(req.PhoneNumber)
		if phoneErr != nil {
			return nil, phoneErr
		}
	} else {
		// Non-trusted number: enforce strict domestic Indian mobile validation for Reverse SMS
		canonicalPhone, phoneErr = CanonicalizeDomesticPhone(req.PhoneNumber)
		if phoneErr != nil {
			return nil, phoneErr
		}
	}

	env := strings.ToLower(os.Getenv("APP_ENV"))
	if env == "" {
		env = strings.ToLower(os.Getenv("ENV"))
	}
	isProduction := env == "production"

	// If not trusted, require configured inbound SMS receiver number in production
	if !isTrusted && isProduction && (s.inboundReceiverNum == "" || s.inboundReceiverNum == "+919800000000" || s.inboundReceiverNum == "+910000000000") {
		return nil, fmt.Errorf("reverse verification unavailable: real INBOUND_SMS_RECEIVER_NUMBER is required in production environment (dummy placeholder rejected)")
	}

	// 2. Validate Google Identity Token if provided (common to both Route A and Route B)
	if req.GoogleAuthToken != "" && s.googleVerifier != nil {
		claims, err := s.googleVerifier.VerifyIdToken(req.GoogleAuthToken)
		if err != nil {
			return nil, fmt.Errorf("google token verification failed: %w", err)
		}
		if claims != nil {
			req.GoogleUID = claims.Subject
			if req.Email == "" {
				req.Email = claims.Email
			}
			if req.Name == "" && claims.Name != "" {
				req.Name = claims.Name
			}
			if req.AvatarURL == "" && claims.Picture != "" {
				req.AvatarURL = claims.Picture
			}
		}
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	// Rate Limiting: Max 5 attempts / 15 minutes per IP
	if clientIP != "" {
		if s.limiter != nil {
			allowed, rErr := s.limiter.Allow(context.Background(), "ratelimit:reversesms:ip:"+clientIP, 5, 15*time.Minute)
			if rErr != nil {
				return nil, fmt.Errorf("rate limiter error: %w", rErr)
			}
			if !allowed {
				return nil, fmt.Errorf("%w: maximum 5 attempts per 15 minutes per IP", ErrReverseRateLimited)
			}
		} else if !s.checkRateLimit(clientIP, s.ipRate, 15*60*1000, 5) {
			return nil, fmt.Errorf("%w: maximum 5 attempts per 15 minutes per IP", ErrReverseRateLimited)
		}
	}

	// Rate Limiting: Max 3 attempts / 24 hours per Google UID / Device
	rateKey := req.GoogleUID
	if rateKey == "" {
		rateKey = req.DeviceID
	}
	if rateKey != "" {
		if s.limiter != nil {
			allowed, rErr := s.limiter.Allow(context.Background(), "ratelimit:reversesms:user:"+rateKey, 3, 24*time.Hour)
			if rErr != nil {
				return nil, fmt.Errorf("rate limiter error: %w", rErr)
			}
			if !allowed {
				return nil, fmt.Errorf("%w: maximum 3 verification attempts per 24 hours", ErrReverseRateLimited)
			}
		} else if !s.checkRateLimit(rateKey, s.userRate, 24*60*60*1000, 3) {
			return nil, fmt.Errorf("%w: maximum 3 verification attempts per 24 hours", ErrReverseRateLimited)
		}
	}

	nonce, err := s.generateCryptoNonce()
	if err != nil {
		return nil, fmt.Errorf("failed to generate cryptographic nonce: %w", err)
	}

	now := time.Now().UnixMilli()
	sessionBytes := make([]byte, 16)
	rand.Read(sessionBytes)
	sessionID := hex.EncodeToString(sessionBytes)

	// Route A: Trusted Auto-Verification Route
	if isTrusted {
		var finalNxID string
		var sessionToken string
		var committedAccount *identity.UserAccount

		existingAcc, foundByPhone := s.store.GetAccountByPhone(canonicalPhone)

		if req.IsReverify {
			// Re-verification flow: logging into existing account on new device / SIM
			if !foundByPhone && req.GoogleUID != "" {
				existingAcc, foundByPhone = s.store.GetAccountByGoogleID(req.GoogleUID)
			}
			if !foundByPhone || existingAcc == nil {
				return nil, fmt.Errorf("re-verification failed: existing account not found for phone %s", maskPhone(canonicalPhone))
			}

			finalNxID = existingAcc.NxID
			committedAccount = existingAcc

			// Register new device
			devID := req.DeviceID
			if devID == "" {
				devID = fmt.Sprintf("dev_%d", now)
			}
			devName := req.DeviceName
			if devName == "" {
				devName = "Android Device"
			}

			device := &identity.Device{
				DeviceID:     devID,
				AccountID:    existingAcc.ID,
				NxID:         existingAcc.NxID,
				DeviceName:   devName,
				PublicKeyHex: req.PublicKeyHex,
				DhKeyHex:     req.DhPublicKeyHex,
				IsRevoked:    false,
				CreatedAt:    now,
				LastActiveAt: now,
			}
			if err := s.store.RegisterDevice(device); err != nil {
				return nil, fmt.Errorf("failed to register device: %w", err)
			}

			// Create 30-day session token
			tokenBytes := make([]byte, 32)
			rand.Read(tokenBytes)
			sessionToken = "nexa_sess_" + hex.EncodeToString(tokenBytes)

			session := &identity.Session{
				Token:     sessionToken,
				AccountID: existingAcc.ID,
				NxID:      existingAcc.NxID,
				DeviceID:  devID,
				ExpiresAt: now + (30 * 24 * 60 * 60 * 1000),
				IsRevoked: false,
				CreatedAt: now,
			}
			if err := s.store.CreateSession(session); err != nil {
				return nil, fmt.Errorf("failed to create session: %w", err)
			}
		} else {
			// Fresh Registration Flow:
			// 1. Account Uniqueness & Takeover Protection: reject duplicate registrations
			if foundByPhone {
				return nil, fmt.Errorf("%w: %s", ErrPhoneAlreadyRegistered, maskPhone(canonicalPhone))
			}

			// Prevent duplicate public key registration
			if req.PublicKeyHex != "" {
				if _, exists := s.store.GetAccountByPublicKey(req.PublicKeyHex); exists {
					return nil, fmt.Errorf("public key is already registered to an existing account")
				}
			}

			// Prevent duplicate Google ID registration
			if req.GoogleUID != "" {
				if _, exists := s.store.GetAccountByGoogleID(req.GoogleUID); exists {
					return nil, fmt.Errorf("google account is already associated with an existing account")
				}
			}

			// Validate Public Key format (32 bytes Ed25519)
			pubBytes, err := hex.DecodeString(strings.TrimSpace(req.PublicKeyHex))
			if err != nil || len(pubBytes) != 32 {
				return nil, fmt.Errorf("invalid 32-byte Ed25519 public key hex")
			}

			// 2. Allocate authentic NX-ID (locked NX-XXXXXXXX format)
			allocatedNxID, err := s.store.AllocateNxID(req.PublicKeyHex)
			if err != nil {
				return nil, fmt.Errorf("failed to allocate NX-ID: %w", err)
			}
			finalNxID = allocatedNxID

			usrIDPrefix := "usr_rand"
			if len(pubBytes) >= 8 {
				usrIDPrefix = fmt.Sprintf("usr_%s", hex.EncodeToString(pubBytes[:8]))
			}

			account := &identity.UserAccount{
				ID:                    usrIDPrefix,
				NxID:                  finalNxID,
				PublicKeyHex:          req.PublicKeyHex,
				DhKeyHex:              req.DhPublicKeyHex,
				PhoneNumber:           canonicalPhone,
				PhoneVerified:         true,
				IsDiscoverableByPhone: true,
				GoogleID:              req.GoogleUID,
				Email:                 req.Email,
				ProfileName:           req.Name,
				AvatarUrl:             req.AvatarURL,
				Bio:                   req.Bio,
				Gender:                req.Gender,
				Age:                   req.Age,
				CreatedAt:             now,
				UpdatedAt:             now,
			}

			if err := s.store.CreateAccount(account); err != nil {
				return nil, fmt.Errorf("failed to commit account: %w", err)
			}
			committedAccount = account

			// 3. Register Device
			devID := req.DeviceID
			if devID == "" {
				devID = fmt.Sprintf("dev_%d", now)
			}
			devName := req.DeviceName
			if devName == "" {
				devName = "Android Device"
			}

			device := &identity.Device{
				DeviceID:     devID,
				AccountID:    account.ID,
				NxID:         account.NxID,
				DeviceName:   devName,
				PublicKeyHex: req.PublicKeyHex,
				DhKeyHex:     req.DhPublicKeyHex,
				IsRevoked:    false,
				CreatedAt:    now,
				LastActiveAt: now,
			}
			if err := s.store.RegisterDevice(device); err != nil {
				return nil, fmt.Errorf("failed to register device: %w", err)
			}

			// 4. Create 30-Day Session Token
			tokenBytes := make([]byte, 32)
			rand.Read(tokenBytes)
			sessionToken = "nexa_sess_" + hex.EncodeToString(tokenBytes)

			session := &identity.Session{
				Token:     sessionToken,
				AccountID: account.ID,
				NxID:      account.NxID,
				DeviceID:  devID,
				ExpiresAt: now + (30 * 24 * 60 * 60 * 1000),
				IsRevoked: false,
				CreatedAt: now,
			}
			if err := s.store.CreateSession(session); err != nil {
				return nil, fmt.Errorf("failed to create session: %w", err)
			}
		}

		// Store completed auto-verified record
		record := &PendingVerification{
			Nonce:              nonce,
			SessionID:          sessionID,
			GoogleUID:          req.GoogleUID,
			Email:              req.Email,
			PhoneNumber:        canonicalPhone,
			Name:               req.Name,
			AvatarURL:          req.AvatarURL,
			Gender:             req.Gender,
			Age:                req.Age,
			Bio:                req.Bio,
			PublicKeyHex:       req.PublicKeyHex,
			DhPublicKeyHex:     req.DhPublicKeyHex,
			DeviceID:           req.DeviceID,
			DeviceName:         req.DeviceName,
			IsReverify:         req.IsReverify,
			Status:             "VERIFIED",
			VerificationMethod: "trusted_allowlist",
			VerifiedAt:         now,
			CreatedAt:          now,
			ExpiresAt:          now + (90 * 1000),
			AllocatedNxID:      finalNxID,
			SessionAuthToken:   sessionToken,
		}

		s.pending[nonce] = record
		s.byPhone[canonicalPhone] = nonce
		s.bySession[sessionID] = nonce

		if s.redisClient != nil {
			if recordJSON, err := json.Marshal(record); err == nil {
				ttl := 5 * time.Minute
				ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
				pipe := s.redisClient.Pipeline()
				pipe.Set(ctx, "reverse_sms:nonce:"+nonce, recordJSON, ttl)
				pipe.Set(ctx, "reverse_sms:session:"+sessionID, nonce, ttl)
				pipe.Set(ctx, "reverse_sms:phone:"+canonicalPhone, nonce, ttl)
				_, _ = pipe.Exec(ctx)
				cancel()
			}
		}

		log.Printf("[ReverseSMS] Trusted phone auto-verified: Phone=%s, Nonce=%s, NxID=%s, Method=trusted_allowlist",
			maskPhone(canonicalPhone), nonce, finalNxID)

		return &InitiateReverseVerifyResponse{
			Status:                "VERIFIED",
			Nonce:                 nonce,
			SessionID:             sessionID,
			InboundReceiverNumber: s.inboundReceiverNum,
			InboundNumber:         s.inboundReceiverNum,
			SmsPayload:            fmt.Sprintf("NEXA-VERIFY:%s:%s", nonce, sessionID),
			ExpiresInSeconds:      90,
			NxID:                  finalNxID,
			SessionAuthToken:      sessionToken,
			SessionToken:          sessionToken,
			VerificationMethod:    "trusted_allowlist",
			VerifiedAt:            now,
			User:                  committedAccount,
		}, nil
	}

	// Route B: Normal Reverse SMS Verification Route
	record := &PendingVerification{
		Nonce:          nonce,
		SessionID:      sessionID,
		GoogleUID:      req.GoogleUID,
		Email:          req.Email,
		PhoneNumber:    canonicalPhone,
		Name:           req.Name,
		AvatarURL:      req.AvatarURL,
		Gender:         req.Gender,
		Age:            req.Age,
		Bio:            req.Bio,
		PublicKeyHex:   req.PublicKeyHex,
		DhPublicKeyHex: req.DhPublicKeyHex,
		DeviceID:       req.DeviceID,
		DeviceName:     req.DeviceName,
		IsReverify:     req.IsReverify,
		Status:         "PENDING",
		CreatedAt:      now,
		ExpiresAt:      now + (90 * 1000), // 90 seconds strict TTL
	}

	s.pending[nonce] = record
	s.byPhone[canonicalPhone] = nonce
	s.bySession[sessionID] = nonce

	if s.redisClient != nil {
		if recordJSON, err := json.Marshal(record); err == nil {
			ttl := 95 * time.Second
			ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
			pipe := s.redisClient.Pipeline()
			pipe.Set(ctx, "reverse_sms:nonce:"+nonce, recordJSON, ttl)
			pipe.Set(ctx, "reverse_sms:session:"+sessionID, nonce, ttl)
			pipe.Set(ctx, "reverse_sms:phone:"+canonicalPhone, nonce, ttl)
			_, _ = pipe.Exec(ctx)
			cancel()
		}
	}

	log.Printf("[ReverseSMS] Initiated reverse verification for %s (Nonce: %s, Inbound: %s, TTL: 90s)",
		maskPhone(canonicalPhone), nonce, s.inboundReceiverNum)

	return &InitiateReverseVerifyResponse{
		Status:                "PENDING",
		Nonce:                 nonce,
		SessionID:             sessionID,
		InboundReceiverNumber: s.inboundReceiverNum,
		InboundNumber:         s.inboundReceiverNum,
		SmsPayload:            fmt.Sprintf("NEXA-VERIFY:%s:%s", nonce, sessionID),
		ExpiresInSeconds:      90,
	}, nil
}

// ProcessInboundWebhook matches the incoming SMS sender number and nonce payload.
func (s *ReverseSmsService) ProcessInboundWebhook(senderNumber, body string) (*PendingVerification, error) {
	canonicalSender, err := CanonicalizeDomesticPhone(senderNumber)
	if err != nil {
		return nil, fmt.Errorf("inbound webhook: invalid sender phone %s: %w", senderNumber, err)
	}

	// Extract Nonce from message body:
	// Format 1: "NEXA-VERIFY:<nonce>:<sessionId>" or "NEXA-VERIFY:<nonce>"
	// Format 2: "NX-VERIFY <nonce>"
	// Format 3: Raw NX-XXXXX regex
	cleanBody := strings.TrimSpace(body)
	var nonce string
	if strings.Contains(cleanBody, ":") {
		parts := strings.Split(cleanBody, ":")
		if len(parts) >= 2 {
			nonce = strings.TrimSpace(parts[1])
		}
	}
	if nonce == "" {
		match := nonceExtractorRegex.FindString(cleanBody)
		if match != "" {
			nonce = match
		}
	}
	if nonce == "" && strings.HasPrefix(cleanBody, "NX-VERIFY") {
		fields := strings.Fields(cleanBody)
		if len(fields) >= 2 {
			nonce = fields[1]
		}
	}
	if nonce == "" {
		return nil, fmt.Errorf("inbound webhook: no valid verification nonce found in message body: '%s'", body)
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	record, exists := s.pending[nonce]
	if !exists && s.redisClient != nil {
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		data, err := s.redisClient.Get(ctx, "reverse_sms:nonce:"+nonce).Bytes()
		cancel()
		if err == nil {
			var r PendingVerification
			if json.Unmarshal(data, &r) == nil {
				record = &r
				exists = true
				s.pending[nonce] = record
			}
		}
	}
	if !exists {
		return nil, fmt.Errorf("%w: %s", ErrNonceNotFound, nonce)
	}

	// Single-use anti-replay defense: Replay attack rejected if already verified
	if record.Status == "VERIFIED" {
		return nil, fmt.Errorf("inbound webhook: nonce %s has already been verified (replay attack rejected)", nonce)
	}

	now := time.Now().UnixMilli()
	if now > record.ExpiresAt {
		record.Status = "EXPIRED"
		return nil, fmt.Errorf("%w: nonce expired at %d, current: %d", ErrNonceExpired, record.ExpiresAt, now)
	}

	if record.PhoneNumber != canonicalSender {
		return nil, fmt.Errorf("%w: expected %s, got %s", ErrPhoneMismatch, maskPhone(record.PhoneNumber), maskPhone(canonicalSender))
	}

	// Verification confirmed! Commit or update account in Identity Store
	var finalNxID string
	var sessionToken string

	if record.IsReverify {
		// Existing account logging in on new device / SIM
		var existingAcc *identity.UserAccount
		var found bool

		if record.PhoneNumber != "" {
			existingAcc, found = s.store.GetAccountByPhone(record.PhoneNumber)
		}
		if !found && record.GoogleUID != "" {
			existingAcc, found = s.store.GetAccountByGoogleID(record.GoogleUID)
		}

		if !found || existingAcc == nil {
			return nil, fmt.Errorf("re-verification failed: existing account not found for phone %s", maskPhone(record.PhoneNumber))
		}

		finalNxID = existingAcc.NxID

		// Register new device
		devID := record.DeviceID
		if devID == "" {
			devID = fmt.Sprintf("dev_%d", now)
		}
		devName := record.DeviceName
		if devName == "" {
			devName = "Android Device"
		}

		device := &identity.Device{
			DeviceID:     devID,
			AccountID:    existingAcc.ID,
			NxID:         existingAcc.NxID,
			DeviceName:   devName,
			PublicKeyHex: record.PublicKeyHex,
			DhKeyHex:     record.DhPublicKeyHex,
			IsRevoked:    false,
			CreatedAt:    now,
			LastActiveAt: now,
		}
		s.store.RegisterDevice(device)

		// Create 30-day session
		tokenBytes := make([]byte, 32)
		rand.Read(tokenBytes)
		sessionToken = "nexa_sess_" + hex.EncodeToString(tokenBytes)

		session := &identity.Session{
			Token:     sessionToken,
			AccountID: existingAcc.ID,
			NxID:      existingAcc.NxID,
			DeviceID:  devID,
			ExpiresAt: now + (30 * 24 * 60 * 60 * 1000),
			IsRevoked: false,
			CreatedAt: now,
		}
		s.store.CreateSession(session)

	} else {
		// Brand new account creation
		allocatedNxID, err := s.store.AllocateNxID(record.PublicKeyHex)
		if err != nil {
			return nil, fmt.Errorf("failed to allocate NX-ID: %w", err)
		}
		finalNxID = allocatedNxID

		pubBytes, _ := hex.DecodeString(record.PublicKeyHex)
		usrIDPrefix := "usr_rand"
		if len(pubBytes) >= 8 {
			usrIDPrefix = fmt.Sprintf("usr_%s", hex.EncodeToString(pubBytes[:8]))
		}

		account := &identity.UserAccount{
			ID:                    usrIDPrefix,
			NxID:                  finalNxID,
			PublicKeyHex:          record.PublicKeyHex,
			DhKeyHex:              record.DhPublicKeyHex,
			PhoneNumber:           record.PhoneNumber,
			PhoneVerified:         true,
			IsDiscoverableByPhone: true,
			GoogleID:              record.GoogleUID,
			Email:                 record.Email,
			ProfileName:           record.Name,
			AvatarUrl:             record.AvatarURL,
			Bio:                   record.Bio,
			Gender:                record.Gender,
			Age:                   record.Age,
			CreatedAt:             now,
			UpdatedAt:             now,
		}

		if err := s.store.CreateAccount(account); err != nil {
			// If already created, retrieve it
			if existing, found := s.store.GetAccountByNxID(finalNxID); found {
				account = existing
			} else {
				return nil, fmt.Errorf("failed to commit account: %w", err)
			}
		}

		// Register Device
		devID := record.DeviceID
		if devID == "" {
			devID = fmt.Sprintf("dev_%d", now)
		}
		devName := record.DeviceName
		if devName == "" {
			devName = "Android Device"
		}

		device := &identity.Device{
			DeviceID:     devID,
			AccountID:    account.ID,
			NxID:         account.NxID,
			DeviceName:   devName,
			PublicKeyHex: record.PublicKeyHex,
			DhKeyHex:     record.DhPublicKeyHex,
			IsRevoked:    false,
			CreatedAt:    now,
			LastActiveAt: now,
		}
		s.store.RegisterDevice(device)

		// Create 30-Day Session Token
		tokenBytes := make([]byte, 32)
		rand.Read(tokenBytes)
		sessionToken = "nexa_sess_" + hex.EncodeToString(tokenBytes)

		session := &identity.Session{
			Token:     sessionToken,
			AccountID: account.ID,
			NxID:      account.NxID,
			DeviceID:  devID,
			ExpiresAt: now + (30 * 24 * 60 * 60 * 1000),
			IsRevoked: false,
			CreatedAt: now,
		}
		s.store.CreateSession(session)
	}

	record.Status = "VERIFIED"
	record.VerificationMethod = "reverse_sms"
	record.VerifiedAt = now
	record.AllocatedNxID = finalNxID
	record.SessionAuthToken = sessionToken

	if s.redisClient != nil {
		if recordJSON, err := json.Marshal(record); err == nil {
			ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
			pipe := s.redisClient.Pipeline()
			pipe.Set(ctx, "reverse_sms:nonce:"+nonce, recordJSON, 5*time.Minute)
			if record.SessionID != "" {
				pipe.Set(ctx, "reverse_sms:session:"+record.SessionID, nonce, 5*time.Minute)
			}
			_, _ = pipe.Exec(ctx)
			cancel()
		}
	}

	log.Printf("[ReverseSMS] Inbound SMS verified successfully: Sender=%s, Nonce=%s, NxID=%s",
		maskPhone(canonicalSender), nonce, finalNxID)

	return record, nil
}

// GetVerificationStatus queries the state of a pending verification nonce or sessionId.
func (s *ReverseSmsService) GetVerificationStatus(key string) (*VerificationStatusResponse, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	nonce := key
	if n, found := s.bySession[key]; found {
		nonce = n
	}

	record, exists := s.pending[nonce]
	if !exists && s.redisClient != nil {
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		// Check if key is a session ID in Redis
		if resolvedNonce, err := s.redisClient.Get(ctx, "reverse_sms:session:"+key).Result(); err == nil && resolvedNonce != "" {
			nonce = resolvedNonce
		}
		data, err := s.redisClient.Get(ctx, "reverse_sms:nonce:"+nonce).Bytes()
		cancel()
		if err == nil {
			var r PendingVerification
			if json.Unmarshal(data, &r) == nil {
				record = &r
				exists = true
				s.pending[nonce] = record
			}
		}
	}

	if !exists {
		return nil, ErrNonceNotFound
	}

	now := time.Now().UnixMilli()
	if record.Status == "PENDING" && now > record.ExpiresAt {
		record.Status = "EXPIRED"
		return &VerificationStatusResponse{
			Status:     "EXPIRED",
			IsVerified: false,
		}, nil
	}

	if record.Status == "VERIFIED" {
		account, _ := s.store.GetAccountByNxID(record.AllocatedNxID)
		resp := &VerificationStatusResponse{
			Status:             "VERIFIED",
			IsVerified:         true,
			VerificationMethod: record.VerificationMethod,
			VerifiedAt:         record.VerifiedAt,
			NxID:               record.AllocatedNxID,
			SessionAuthToken:   record.SessionAuthToken,
			SessionToken:       record.SessionAuthToken,
			Name:             record.Name,
			Phone:            record.PhoneNumber,
			AvatarURL:        record.AvatarURL,
			User:             account,
		}

		// Single-use token consumption: transition to CONSUMED so subsequent queries cannot leak token
		record.Status = "CONSUMED"
		if s.redisClient != nil {
			if recordJSON, err := json.Marshal(record); err == nil {
				ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
				_ = s.redisClient.Set(ctx, "reverse_sms:nonce:"+nonce, recordJSON, 2*time.Minute).Err()
				cancel()
			}
		}

		return resp, nil
	}

	if record.Status == "CONSUMED" {
		return &VerificationStatusResponse{
			Status:     "CONSUMED",
			IsVerified: false,
		}, nil
	}

	remainingSeconds := int((record.ExpiresAt - now) / 1000)
	if remainingSeconds < 0 {
		remainingSeconds = 0
	}

	return &VerificationStatusResponse{
		Status:           "PENDING",
		IsVerified:       false,
		ExpiresInSeconds: remainingSeconds,
	}, nil
}

// ResolveUser looks up a user by NxID or Phone Number for friend discovery & QR scanner.
func (s *ReverseSmsService) ResolveUser(target string, callerNxID string) (*UserResolveResponse, error) {
	cleanTarget := strings.TrimSpace(target)

	var targetAccount *identity.UserAccount
	var found bool

	if strings.HasPrefix(cleanTarget, "NX-") {
		targetAccount, found = s.store.GetAccountByNxID(cleanTarget)
	} else {
		canonicalPhone, err := CanonicalizeDomesticPhone(cleanTarget)
		if err == nil {
			targetAccount, found = s.store.GetAccountByPhone(canonicalPhone)
		}
	}

	if !found || targetAccount == nil {
		return nil, fmt.Errorf("user not found for target '%s'", target)
	}

	// Privacy check: only discoverable users or existing contacts
	if !targetAccount.PhoneVerified {
		return nil, fmt.Errorf("target account is not verified")
	}

	isFriend := false
	// Caller check: if caller is the target user
	if callerNxID != "" && callerNxID == targetAccount.NxID {
		isFriend = true
	}

	return &UserResolveResponse{
		NxID:      targetAccount.NxID,
		Name:      targetAccount.ProfileName,
		AvatarURL: targetAccount.AvatarUrl,
		Bio:       targetAccount.Bio,
		IsFriend:  isFriend,
	}, nil
}
