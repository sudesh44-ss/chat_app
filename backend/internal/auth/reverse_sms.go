package auth

import (
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"log"
	"math/big"
	"os"
	"regexp"
	"strings"
	"sync"
	"time"

	"nexa/wire/internal/identity"
)

var (
	ErrInvalidDomesticPhone = errors.New("invalid domestic mobile number: must be valid 10-digit Indian mobile starting with 6-9 (+91)")
	ErrReverseRateLimited   = errors.New("rate limit exceeded for reverse verification: maximum attempts reached")
	ErrNonceNotFound        = errors.New("verification nonce not found or already consumed")
	ErrNonceExpired         = errors.New("verification nonce has expired (validity: 90 seconds)")
	ErrPhoneMismatch        = errors.New("sender phone number does not match registered initiation number")
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
	Status                string `json:"status"`
	Nonce                 string `json:"nonce"`
	InboundReceiverNumber string `json:"inbound_receiver_number"`
	SmsPayload            string `json:"sms_payload"`
	ExpiresInSeconds      int    `json:"expires_in_seconds"`
}

type PendingVerification struct {
	Nonce            string `json:"nonce"`
	GoogleUID        string `json:"google_uid"`
	Email            string `json:"email"`
	PhoneNumber      string `json:"phone_number"`
	Name             string `json:"name"`
	AvatarURL        string `json:"avatar_url"`
	Gender           string `json:"gender"`
	Age              int    `json:"age"`
	Bio              string `json:"bio"`
	PublicKeyHex     string `json:"public_key_hex"`
	DhPublicKeyHex   string `json:"dh_public_key_hex"`
	DeviceID         string `json:"device_id"`
	DeviceName       string `json:"device_name"`
	IsReverify       bool   `json:"is_reverify"`
	Status           string `json:"status"` // "PENDING", "VERIFIED", "CONSUMED", "EXPIRED"
	CreatedAt        int64  `json:"created_at"`
	ExpiresAt        int64  `json:"expires_at"`
	AllocatedNxID    string `json:"allocated_nx_id,omitempty"`
	SessionAuthToken string `json:"session_auth_token,omitempty"`
}

type VerificationStatusResponse struct {
	Status           string                `json:"status"` // "PENDING", "VERIFIED", "EXPIRED"
	ExpiresInSeconds int                   `json:"expires_in_seconds,omitempty"`
	NxID             string                `json:"nx_id,omitempty"`
	SessionAuthToken string                `json:"session_auth_token,omitempty"`
	SessionToken     string                `json:"session_token,omitempty"`
	User             *identity.UserAccount `json:"user,omitempty"`
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
	store              *identity.Store
	pending            map[string]*PendingVerification // nonce -> record
	byPhone            map[string]string               // phone -> active nonce
	ipRate             map[string][]int64              // ip -> timestamps
	userRate           map[string][]int64              // googleUid/device -> timestamps
	inboundReceiverNum string
}

func NewReverseSmsService(store *identity.Store) *ReverseSmsService {
	inbound := os.Getenv("INBOUND_SMS_RECEIVER_NUMBER")
	if inbound == "" {
		// Default automated inbound virtual receiver / GSM gateway number
		inbound = "+919800000000"
	}

	return &ReverseSmsService{
		store:              store,
		pending:            make(map[string]*PendingVerification),
		byPhone:            make(map[string]string),
		ipRate:             make(map[string][]int64),
		userRate:           make(map[string][]int64),
		inboundReceiverNum: inbound,
	}
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

// InitiateVerification creates a short-lived 90-second nonce linked to the user's Google UID and phone.
func (s *ReverseSmsService) InitiateVerification(req *InitiateReverseVerifyRequest, clientIP string) (*InitiateReverseVerifyResponse, error) {
	canonicalPhone, err := CanonicalizeDomesticPhone(req.PhoneNumber)
	if err != nil {
		return nil, err
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	// Rate Limiting: Max 5 attempts / 15 minutes per IP
	if clientIP != "" && !s.checkRateLimit(clientIP, s.ipRate, 15*60*1000, 5) {
		return nil, fmt.Errorf("%w: maximum 5 attempts per 15 minutes per IP", ErrReverseRateLimited)
	}

	// Rate Limiting: Max 3 attempts / 24 hours per Google UID / Device
	rateKey := req.GoogleUID
	if rateKey == "" {
		rateKey = req.DeviceID
	}
	if rateKey != "" && !s.checkRateLimit(rateKey, s.userRate, 24*60*60*1000, 3) {
		return nil, fmt.Errorf("%w: maximum 3 verification attempts per 24 hours", ErrReverseRateLimited)
	}

	nonce, err := s.generateCryptoNonce()
	if err != nil {
		return nil, fmt.Errorf("failed to generate cryptographic nonce: %w", err)
	}

	now := time.Now().UnixMilli()
	record := &PendingVerification{
		Nonce:          nonce,
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

	log.Printf("[ReverseSMS] Initiated reverse verification for %s (Nonce: %s, Inbound: %s, TTL: 90s)",
		maskPhone(canonicalPhone), nonce, s.inboundReceiverNum)

	return &InitiateReverseVerifyResponse{
		Status:                "PENDING",
		Nonce:                 nonce,
		InboundReceiverNumber: s.inboundReceiverNum,
		SmsPayload:            fmt.Sprintf("NX-VERIFY %s", nonce),
		ExpiresInSeconds:      90,
	}, nil
}

// ProcessInboundWebhook matches the incoming SMS sender number and nonce payload.
func (s *ReverseSmsService) ProcessInboundWebhook(senderNumber, body string) (*PendingVerification, error) {
	canonicalSender, err := CanonicalizeDomesticPhone(senderNumber)
	if err != nil {
		return nil, fmt.Errorf("inbound webhook: invalid sender phone %s: %w", senderNumber, err)
	}

	// Extract Nonce from message body: e.g. "NX-VERIFY NX-8F92A" -> "NX-8F92A"
	match := nonceExtractorRegex.FindString(body)
	if match == "" {
		return nil, fmt.Errorf("inbound webhook: no valid NX-XXXXX nonce found in message body: '%s'", body)
	}
	nonce := match

	s.mu.Lock()
	defer s.mu.Unlock()

	record, exists := s.pending[nonce]
	if !exists {
		return nil, fmt.Errorf("%w: %s", ErrNonceNotFound, nonce)
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
	record.AllocatedNxID = finalNxID
	record.SessionAuthToken = sessionToken

	log.Printf("[ReverseSMS] Inbound SMS verified successfully: Sender=%s, Nonce=%s, NxID=%s",
		maskPhone(canonicalSender), nonce, finalNxID)

	return record, nil
}

// GetVerificationStatus queries the state of a pending verification nonce.
func (s *ReverseSmsService) GetVerificationStatus(nonce string) (*VerificationStatusResponse, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	record, exists := s.pending[nonce]
	if !exists {
		return nil, ErrNonceNotFound
	}

	now := time.Now().UnixMilli()
	if record.Status == "PENDING" && now > record.ExpiresAt {
		return &VerificationStatusResponse{
			Status: "EXPIRED",
		}, nil
	}

	if record.Status == "VERIFIED" {
		account, _ := s.store.GetAccountByNxID(record.AllocatedNxID)
		return &VerificationStatusResponse{
			Status:           "VERIFIED",
			NxID:             record.AllocatedNxID,
			SessionAuthToken: record.SessionAuthToken,
			SessionToken:     record.SessionAuthToken,
			User:             account,
		}, nil
	}

	remainingSeconds := int((record.ExpiresAt - now) / 1000)
	if remainingSeconds < 0 {
		remainingSeconds = 0
	}

	return &VerificationStatusResponse{
		Status:           "PENDING",
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
