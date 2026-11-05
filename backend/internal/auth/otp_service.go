package auth

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"errors"
	"fmt"
	"math/big"
	"os"
	"regexp"
	"strings"
	"sync"
	"time"
)

var (
	ErrSmsProviderUnavailable = errors.New("SMS provider unavailable or delivery failed")
	ErrRateLimitExceeded      = errors.New("rate limit exceeded")
	ErrCooldownActive         = errors.New("cooldown active")
	ErrInvalidPhone           = errors.New("invalid phone number")
	ErrInvalidOtp             = errors.New("invalid verification code")
	ErrOtpExpired             = errors.New("verification code expired")
	ErrMaxAttemptsExceeded    = errors.New("maximum verification attempts exceeded")
)

var e164Regex = regexp.MustCompile(`^\+[1-9]\d{7,14}$`)

type otpRecord struct {
	Salt       string
	HashedOtp  string
	ExpiresAt  int64
	Attempts   int
	LastSentAt int64
	BoundUser  string
}

type AuthTicket struct {
	PhoneNumber string
	BoundUser   string
	VerifiedAt  int64
	ExpiresAt   int64
	TicketToken string
}

type OtpService struct {
	mu           sync.RWMutex
	provider     SmsProvider
	records      map[string]*otpRecord  // phone -> record
	phoneRate    map[string][]int64     // phone -> timestamps
	ipRate       map[string][]int64     // ip -> timestamps
	tickets      map[string]*AuthTicket // ticketToken -> ticket
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

// hashOtp computes a Server-Secret Keyed HMAC-SHA256 hash of the OTP and salt.
func (s *OtpService) hashOtp(otp string, salt string) string {
	pepper := os.Getenv("OTP_SERVER_PEPPER")
	if pepper == "" {
		pepper = "nexa_production_otp_hmac_secret_pepper_2026_secured"
	}
	mac := hmac.New(sha256.New, []byte(pepper))
	mac.Write([]byte(salt + ":" + otp + ":nexa_secure_otp_domain"))
	return hex.EncodeToString(mac.Sum(nil))
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

	// Generate cryptographically secure random 6-digit OTP
	n, err := rand.Int(rand.Reader, big.NewInt(900000))
	if err != nil {
		s.mu.Unlock()
		return 0, fmt.Errorf("failed to generate secure random OTP: %w", err)
	}
	otpCode := fmt.Sprintf("%06d", 100000+n.Int64())

	// Generate 16-byte random salt
	saltBytes := make([]byte, 16)
	if _, err := rand.Read(saltBytes); err != nil {
		s.mu.Unlock()
		return 0, fmt.Errorf("failed to generate salt: %w", err)
	}
	salt := hex.EncodeToString(saltBytes)

	hashedOtp := s.hashOtp(otpCode, salt)
	expiresAt := now + (5 * 60 * 1000) // 5 minutes TTL

	s.records[canonicalPhone] = &otpRecord{
		Salt:       salt,
		HashedOtp:  hashedOtp,
		ExpiresAt:  expiresAt,
		Attempts:   0,
		LastSentAt: now,
		BoundUser:  boundUser,
	}

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

	expectedHash := s.hashOtp(cleanOtp, record.Salt)
	// Cryptographically secure constant-time compare to mitigate timing attacks
	if subtle.ConstantTimeCompare([]byte(expectedHash), []byte(record.HashedOtp)) != 1 {
		remaining := 3 - record.Attempts
		return "", fmt.Errorf("%w: incorrect OTP code. %d attempts remaining", ErrInvalidOtp, remaining)
	}

	// Verification succeeded: delete OTP immediately (single use)
	boundUser := record.BoundUser
	delete(s.records, canonicalPhone)

	// Issue 10-minute AuthTicket for registration or account linking
	ticketBytes := make([]byte, 32)
	rand.Read(ticketBytes)
	ticketToken := "ticket_" + hex.EncodeToString(ticketBytes)

	s.tickets[ticketToken] = &AuthTicket{
		PhoneNumber: canonicalPhone,
		BoundUser:   boundUser,
		VerifiedAt:  now,
		ExpiresAt:   now + (10 * 60 * 1000), // 10 minutes
		TicketToken: ticketToken,
	}

	return ticketToken, nil
}

// ConsumeTicket validates and consumes an AuthTicket (single-use).
func (s *OtpService) ConsumeTicket(ticketToken string) (*AuthTicket, error) {
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
