package auth

import (
	"crypto/hmac"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"regexp"
	"strings"
	"sync"
	"time"

	"github.com/redis/go-redis/v9"

	"nexa/wire/internal/ratelimit"
)

var (
	ErrSmsProviderUnavailable = errors.New("SMS provider unavailable or delivery failed")
	ErrRateLimitExceeded      = errors.New("rate limit exceeded")
	ErrCooldownActive         = errors.New("cooldown active")
	ErrInvalidPhone           = errors.New("invalid phone number")
	ErrInvalidOtp             = errors.New("invalid verification code")
	ErrOtpExpired             = errors.New("verification code expired")
	ErrMaxAttemptsExceeded    = errors.New("maximum verification attempts exceeded")
	ErrOtpSecretMissing       = errors.New("OTP server secret is not configured")
	ErrOtpStateUnavailable    = errors.New("OTP state backend unavailable")
)

var e164Regex = regexp.MustCompile(`^\+[1-9]\d{7,14}$`)

type otpRecord struct {
	Salt       string `json:"salt"`
	HashedOtp  string `json:"hashed_otp"`
	ExpiresAt  int64  `json:"expires_at"`
	Attempts   int    `json:"attempts"`
	LastSentAt int64  `json:"last_sent_at"`
	BoundUser  string `json:"bound_user,omitempty"`
}

type AuthTicket struct {
	PhoneNumber string
	// BoundUser is the verified external subject (Google `sub` or Supabase
	// user id) that produced this ticket. It is never client-supplied.
	BoundUser string
	// Provider records which verifier issued the ticket so registration can
	// bind the identity to the correct account column.
	Provider    string // PHONE_OTP, GOOGLE, SUPABASE
	Email       string
	VerifiedAt  int64
	ExpiresAt   int64
	TicketToken string
}

type OtpService struct {
	mu        sync.RWMutex
	provider  SmsProvider
	records   map[string]*otpRecord  // phone -> record
	phoneRate map[string][]int64     // phone -> timestamps
	ipRate    map[string][]int64     // ip -> timestamps
	tickets   map[string]*AuthTicket // ticketToken -> ticket

	// Distributed state (Phase 3). When redisClient is set, records, tickets and
	// rate limits live in Redis; the maps above stay as the dev/test fallback.
	redisClient  *redis.Client
	limiter      ratelimit.Limiter
	isProduction bool
}

func NewOtpService(provider SmsProvider) *OtpService {
	return &OtpService{
		provider:  provider,
		records:   make(map[string]*otpRecord),
		phoneRate: make(map[string][]int64),
		ipRate:    make(map[string][]int64),
		tickets:   make(map[string]*AuthTicket),
	}
}

// CanonicalizePhone normalizes phone numbers into E.164 standard.
func CanonicalizePhone(phone string) (string, error) {
	trimmed := strings.TrimSpace(phone)
	clean := strings.ReplaceAll(trimmed, " ", "")
	clean = strings.ReplaceAll(clean, "-", "")
	clean = strings.ReplaceAll(clean, "(", "")
	clean = strings.ReplaceAll(clean, ")", "")

	// Handle Indian 10-digit mobile without country code
	if len(clean) == 10 && (clean[0] == '6' || clean[0] == '7' || clean[0] == '8' || clean[0] == '9') {
		clean = "+91" + clean
	}

	if !strings.HasPrefix(clean, "+") {
		clean = "+" + clean
	}

	if !e164Regex.MatchString(clean) {
		return "", fmt.Errorf("invalid E.164 phone number format: %s", phone)
	}

	return clean, nil
}

// resolveOtpPepper returns the server-side HMAC pepper. In production a missing
// secret is a hard failure: there is no shipped fallback that could be extracted
// from the binary and used to forge OTP hashes.
func resolveOtpPepper() (string, error) {
	pepper := os.Getenv("OTP_SERVER_PEPPER")
	if pepper != "" {
		return pepper, nil
	}

	env := strings.ToLower(os.Getenv("APP_ENV"))
	if env == "" {
		env = strings.ToLower(os.Getenv("ENV"))
	}
	if env == "production" {
		return "", ErrOtpSecretMissing
	}

	// Development/test only. Never used when APP_ENV=production/ENV=production.
	return "nexa_dev_only_otp_pepper_not_for_production", nil
}

// ValidateOtpConfig performs startup checks for OTP secrets. Without this, a
// production deployment missing OTP_SERVER_PEPPER only discovers the problem on
// the first OTP request instead of failing fast at boot.
func ValidateOtpConfig(isProduction bool) error {
	if !isProduction {
		return nil
	}
	if strings.TrimSpace(os.Getenv("OTP_SERVER_PEPPER")) == "" {
		return ErrOtpSecretMissing
	}
	return nil
}

// hashOtp computes a Server-Secret Keyed HMAC-SHA256 hash of the OTP and salt.
func (s *OtpService) hashOtp(otp string, salt string) (string, error) {
	pepper, err := resolveOtpPepper()
	if err != nil {
		return "", err
	}
	mac := hmac.New(sha256.New, []byte(pepper))
	mac.Write([]byte(salt + ":" + otp + ":nexa_secure_otp_domain"))
	return hex.EncodeToString(mac.Sum(nil)), nil
}

func (s *OtpService) checkRateLimit(key string, tracker map[string][]int64, maxPerHour int) bool {
	now := time.Now().UnixMilli()
	oneHourAgo := now - (60 * 60 * 1000)

	timestamps := tracker[key]
	var valid []int64
	for _, ts := range timestamps {
		if ts > oneHourAgo {
			valid = append(valid, ts)
		}
	}

	if len(valid) >= maxPerHour {
		tracker[key] = valid
		return false
	}

	valid = append(valid, now)
	tracker[key] = valid
	return true
}

// RequestOtp generates a secure 6-digit OTP, stores its salted hash, and sends via SMS provider.
func (s *OtpService) RequestOtp(phone string, clientIP string) (int, error) {
	return s.RequestOtpWithUser(phone, clientIP, "")
}

// RequestOtpWithUser associates the OTP request with a bound user identity (e.g., Google ID or Supabase UID).
// Note: Mutex lock is NOT held during SMS provider network transmission to prevent system-wide contention.
func (s *OtpService) RequestOtpWithUser(phone string, clientIP string, boundUser string) (int, error) {
	canonicalPhone, err := CanonicalizePhone(phone)
	if err != nil {
		return 0, fmt.Errorf("%w: %s", ErrInvalidPhone, err.Error())
	}

	if client := s.stateBackend(); client != nil {
		return s.requestOtpDistributed(canonicalPhone, clientIP, boundUser, client)
	}
	if s.inProduction() {
		return 0, fmt.Errorf("%w: distributed OTP state is not configured", ErrOtpStateUnavailable)
	}

	return s.requestOtpMemory(canonicalPhone, clientIP, boundUser)
}

// requestOtpMemory is the single-instance dev/test fallback. Production never
// reaches it when a Redis backend is required because the wrapper fails closed.
func (s *OtpService) requestOtpMemory(canonicalPhone string, clientIP string, boundUser string) (int, error) {
	s.mu.Lock()

	// Rate limiting: Max 5 per phone per hour
	if !s.checkRateLimit(canonicalPhone, s.phoneRate, 5) {
		s.mu.Unlock()
		return 0, fmt.Errorf("%w: maximum 5 OTP requests per hour for this phone number", ErrRateLimitExceeded)
	}

	// Rate limiting: Max 15 per IP per hour
	if clientIP != "" && !s.checkRateLimit(clientIP, s.ipRate, 15) {
		s.mu.Unlock()
		return 0, fmt.Errorf("%w: maximum 15 OTP requests per hour from this IP", ErrRateLimitExceeded)
	}

	now := time.Now().UnixMilli()

	// Resend cooldown: 60 seconds
	if existing, exists := s.records[canonicalPhone]; exists {
		if now-existing.LastSentAt < 60*1000 {
			remainingSeconds := int((60*1000 - (now - existing.LastSentAt)) / 1000)
			s.mu.Unlock()
			return remainingSeconds, fmt.Errorf("%w: please wait %d seconds before requesting another OTP", ErrCooldownActive, remainingSeconds)
		}
	}

	// Generate a cryptographically secure OTP and its salted HMAC record.
	otpCode, record, err := s.newOtpRecord(now, boundUser)
	if err != nil {
		s.mu.Unlock()
		return 0, err
	}
	s.records[canonicalPhone] = record

	provider := s.provider
	s.mu.Unlock()

	// Dispatch SMS provider network request WITHOUT holding mutex
	if err := provider.SendOtp(canonicalPhone, otpCode); err != nil {
		// Provider failure: immediately roll back the pending record and rate limits
		s.mu.Lock()
		delete(s.records, canonicalPhone)
		if list := s.phoneRate[canonicalPhone]; len(list) > 0 {
			s.phoneRate[canonicalPhone] = list[:len(list)-1]
		}
		if clientIP != "" {
			if list := s.ipRate[clientIP]; len(list) > 0 {
				s.ipRate[clientIP] = list[:len(list)-1]
			}
		}
		s.mu.Unlock()
		return 0, fmt.Errorf("%w: %v", ErrSmsProviderUnavailable, err)
	}

	return 60, nil // 60s cooldown
}

// VerifyOtp checks the submitted 6-digit OTP against the stored salted hash using constant-time comparison.
func (s *OtpService) VerifyOtp(phone string, submittedOtp string) (string, error) {
	canonicalPhone, err := CanonicalizePhone(phone)
	if err != nil {
		return "", fmt.Errorf("%w: %s", ErrInvalidPhone, err.Error())
	}

	cleanOtp := strings.TrimSpace(submittedOtp)
	if len(cleanOtp) != 6 {
		return "", fmt.Errorf("%w: OTP must be exactly 6 digits", ErrInvalidOtp)
	}

	if client := s.stateBackend(); client != nil {
		return s.verifyOtpDistributed(canonicalPhone, cleanOtp, client)
	}
	if s.inProduction() {
		return "", fmt.Errorf("%w: distributed OTP state is not configured", ErrOtpStateUnavailable)
	}

	return s.verifyOtpMemory(canonicalPhone, cleanOtp)
}

// verifyOtpMemory is the single-instance dev/test fallback.
func (s *OtpService) verifyOtpMemory(canonicalPhone string, cleanOtp string) (string, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	record, exists := s.records[canonicalPhone]
	if !exists {
		return "", fmt.Errorf("%w: no pending OTP request found for this phone number. Please request a new code.", ErrInvalidOtp)
	}

	now := time.Now().UnixMilli()
	if now > record.ExpiresAt {
		delete(s.records, canonicalPhone)
		return "", fmt.Errorf("%w: OTP code has expired. Please request a new code.", ErrOtpExpired)
	}

	record.Attempts++
	if record.Attempts > 3 {
		delete(s.records, canonicalPhone)
		return "", fmt.Errorf("%w: maximum verification attempts exceeded (3). OTP has been invalidated.", ErrMaxAttemptsExceeded)
	}

	expectedHash, err := s.hashOtp(cleanOtp, record.Salt)
	if err != nil {
		return "", err
	}
	// Cryptographically secure constant-time compare to mitigate timing attacks
	if subtle.ConstantTimeCompare([]byte(expectedHash), []byte(record.HashedOtp)) != 1 {
		remaining := 3 - record.Attempts
		return "", fmt.Errorf("%w: incorrect OTP code. %d attempts remaining", ErrInvalidOtp, remaining)
	}

	// Verification succeeded: delete OTP immediately (single use)
	boundUser := record.BoundUser
	delete(s.records, canonicalPhone)

	// Issue 10-minute AuthTicket for registration or account linking
	ticketToken, err := generateTicketToken("ticket_")
	if err != nil {
		return "", err
	}

	s.tickets[ticketToken] = &AuthTicket{
		PhoneNumber: canonicalPhone,
		BoundUser:   boundUser,
		Provider:    "PHONE_OTP",
		VerifiedAt:  now,
		ExpiresAt:   now + (10 * 60 * 1000), // 10 minutes
		TicketToken: ticketToken,
	}

	return ticketToken, nil
}

// ConsumeTicket validates and consumes an AuthTicket (single-use).
func (s *OtpService) ConsumeTicket(ticketToken string) (*AuthTicket, error) {
	if client := s.stateBackend(); client != nil {
		return s.consumeTicketDistributed(ticketToken, client)
	}
	if s.inProduction() {
		return nil, fmt.Errorf("%w: distributed auth ticket state is not configured", ErrOtpStateUnavailable)
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	ticket, exists := s.tickets[ticketToken]
	if !exists {
		return nil, fmt.Errorf("invalid or already consumed auth ticket")
	}

	if time.Now().UnixMilli() > ticket.ExpiresAt {
		delete(s.tickets, ticketToken)
		return nil, fmt.Errorf("auth ticket has expired")
	}

	delete(s.tickets, ticketToken)
	return ticket, nil
}
