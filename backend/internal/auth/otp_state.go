package auth

import (
	"context"
	"crypto/rand"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"math/big"
	"time"

	"github.com/redis/go-redis/v9"

	"nexa/wire/internal/ratelimit"
)

// Distributed OTP state (Phase 3).
//
// OTP records, resend cooldowns and AuthTickets are security-sensitive, single
// use state. Keeping them in process memory means a multi-instance deployment
// can verify a code or redeem a ticket on the wrong node, and it silently loses
// pending codes on restart. When Redis is configured all of that state is
// stored there with a TTL, and production refuses to issue OTPs without it.

const (
	otpRecordKeyPrefix = "auth:otp:record:"
	otpTicketKeyPrefix = "auth:otp:ticket:"
)

// SetDistributedState attaches the cluster-wide state backend used for OTP
// records, rate limiting and auth tickets. Passing a nil client in production
// makes OTP issuance/verification fail closed instead of falling back to
// per-instance memory. In development a nil client keeps the in-memory fallback.
func (s *OtpService) SetDistributedState(client *redis.Client, isProduction bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.redisClient = client
	s.isProduction = isProduction
	s.limiter = ratelimit.NewDistributedLimiter(client, isProduction)
}

// stateBackend returns the configured Redis client (nil in dev/test fallback).
func (s *OtpService) stateBackend() *redis.Client {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.redisClient
}

func (s *OtpService) inProduction() bool {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.isProduction
}

func (s *OtpService) rateLimiter() ratelimit.Limiter {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.limiter
}

// otpAttemptScript increments the attempt counter atomically and hands the
// stored salt/hash back to Go for a constant-time comparison. Redis is never
// asked to compare the secret; it only enforces expiry, attempt and single-use
// bookkeeping.
//
// KEYS[1] = record key
// ARGV[1] = now (ms)
// ARGV[2] = maximum attempts
// Returns {code, record-json} where code is:
//
//	0  ok
//	-1 record missing
//	-2 record expired
//	-3 attempts exhausted
var otpAttemptScript = redis.NewScript(`
local raw = redis.call('GET', KEYS[1])
if not raw then
  return {-1, ''}
end
local rec = cjson.decode(raw)
if tonumber(ARGV[1]) > tonumber(rec.expires_at) then
  redis.call('DEL', KEYS[1])
  return {-2, ''}
end
rec.attempts = tonumber(rec.attempts) + 1
if rec.attempts > tonumber(ARGV[2]) then
  redis.call('DEL', KEYS[1])
  return {-3, ''}
end
redis.call('SET', KEYS[1], cjson.encode(rec), 'KEEPTTL')
return {0, cjson.encode(rec)}
`)

// otpConsumeScript deletes a record only if it is still the exact record the
// caller verified, making successful verification single-use even when two
// requests race.
//
// KEYS[1] = record key
// ARGV[1] = salt
// ARGV[2] = hashed otp
var otpConsumeScript = redis.NewScript(`
local raw = redis.call('GET', KEYS[1])
if not raw then
  return 0
end
local rec = cjson.decode(raw)
if rec.salt ~= ARGV[1] or rec.hashed_otp ~= ARGV[2] then
  return 0
end
redis.call('DEL', KEYS[1])
return 1
`)

// ticketConsumeScript reads and deletes a ticket atomically.
//
// KEYS[1] = ticket key
var ticketConsumeScript = redis.NewScript(`
local raw = redis.call('GET', KEYS[1])
if raw then
  redis.call('DEL', KEYS[1])
end
return raw
`)

func (s *OtpService) requestOtpDistributed(phone, clientIP, boundUser string, client *redis.Client) (int, error) {
	ctx := context.Background()
	now := time.Now().UnixMilli()

	if limiter := s.rateLimiter(); limiter != nil {
		allowed, err := limiter.Allow(ctx, "ratelimit:otp:phone:"+phone, 5, time.Hour)
		if err != nil {
			return 0, fmt.Errorf("%w: %v", ErrOtpStateUnavailable, err)
		}
		if !allowed {
			return 0, fmt.Errorf("%w: maximum 5 OTP requests per hour for this phone number", ErrRateLimitExceeded)
		}

		if clientIP != "" {
			allowed, err = limiter.Allow(ctx, "ratelimit:otp:ip:"+clientIP, 15, time.Hour)
			if err != nil {
				return 0, fmt.Errorf("%w: %v", ErrOtpStateUnavailable, err)
			}
			if !allowed {
				return 0, fmt.Errorf("%w: maximum 15 OTP requests per hour from this IP", ErrRateLimitExceeded)
			}
		}
	}

	key := otpRecordKeyPrefix + phone
	raw, err := client.Get(ctx, key).Result()
	switch {
	case err == nil:
		var existing otpRecord
		if json.Unmarshal([]byte(raw), &existing) == nil && now-existing.LastSentAt < 60*1000 {
			remainingSeconds := int((60*1000 - (now - existing.LastSentAt)) / 1000)
			return remainingSeconds, fmt.Errorf("%w: please wait %d seconds before requesting another OTP", ErrCooldownActive, remainingSeconds)
		}
	case errors.Is(err, redis.Nil):
		// No pending record.
	default:
		return 0, fmt.Errorf("%w: %v", ErrOtpStateUnavailable, err)
	}

	otpCode, record, err := s.newOtpRecord(now, boundUser)
	if err != nil {
		return 0, err
	}

	data, err := json.Marshal(record)
	if err != nil {
		return 0, fmt.Errorf("failed to encode OTP record: %w", err)
	}

	// The record TTL covers both the 60s resend cooldown and the 5 minute code
	// validity, so Redis expires the state even if no request arrives again.
	if err := client.Set(ctx, key, data, 5*time.Minute).Err(); err != nil {
		return 0, fmt.Errorf("%w: %v", ErrOtpStateUnavailable, err)
	}

	provider := s.provider
	if err := provider.SendOtp(phone, otpCode); err != nil {
		// Delivery failed: drop the pending record. Consumed rate-limit slots
		// intentionally remain so a broken provider cannot be used to spam.
		client.Del(ctx, key)
		return 0, fmt.Errorf("%w: %v", ErrSmsProviderUnavailable, err)
	}

	return 60, nil
}

// newOtpRecord builds the salted/HMAC record for a fresh code.
func (s *OtpService) newOtpRecord(now int64, boundUser string) (string, *otpRecord, error) {
	n, err := rand.Int(rand.Reader, big.NewInt(900000))
	if err != nil {
		return "", nil, fmt.Errorf("failed to generate secure random OTP: %w", err)
	}
	otpCode := fmt.Sprintf("%06d", 100000+n.Int64())

	saltBytes := make([]byte, 16)
	if _, err := rand.Read(saltBytes); err != nil {
		return "", nil, fmt.Errorf("failed to generate salt: %w", err)
	}
	salt := hex.EncodeToString(saltBytes)

	hashedOtp, err := s.hashOtp(otpCode, salt)
	if err != nil {
		return "", nil, err
	}

	return otpCode, &otpRecord{
		Salt:       salt,
		HashedOtp:  hashedOtp,
		ExpiresAt:  now + (5 * 60 * 1000),
		Attempts:   0,
		LastSentAt: now,
		BoundUser:  boundUser,
	}, nil
}

func (s *OtpService) verifyOtpDistributed(phone, cleanOtp string, client *redis.Client) (string, error) {
	ctx := context.Background()
	now := time.Now().UnixMilli()

	if limiter := s.rateLimiter(); limiter != nil {
		allowed, err := limiter.Allow(ctx, "ratelimit:otp:verify:"+phone, 15, 15*time.Minute)
		if err != nil {
			return "", fmt.Errorf("%w: %v", ErrOtpStateUnavailable, err)
		}
		if !allowed {
			return "", fmt.Errorf("%w: too many verification attempts for this phone number", ErrMaxAttemptsExceeded)
		}
	}

	key := otpRecordKeyPrefix + phone
	res, err := otpAttemptScript.Run(ctx, client, []string{key}, now, 3).Slice()
	if err != nil {
		return "", fmt.Errorf("%w: %v", ErrOtpStateUnavailable, err)
	}
	if len(res) != 2 {
		return "", fmt.Errorf("%w: malformed OTP state response", ErrOtpStateUnavailable)
	}

	code, ok := res[0].(int64)
	if !ok {
		return "", fmt.Errorf("%w: malformed OTP state code", ErrOtpStateUnavailable)
	}

	switch code {
	case -1:
		return "", fmt.Errorf("%w: no pending OTP request found for this phone number. Please request a new code.", ErrInvalidOtp)
	case -2:
		return "", fmt.Errorf("%w: OTP code has expired. Please request a new code.", ErrOtpExpired)
	case -3:
		return "", fmt.Errorf("%w: maximum verification attempts exceeded (3). OTP has been invalidated.", ErrMaxAttemptsExceeded)
	case 0:
		// continue
	default:
		return "", fmt.Errorf("%w: unexpected OTP state code %d", ErrOtpStateUnavailable, code)
	}

	recordJSON, ok := res[1].(string)
	if !ok || recordJSON == "" {
		return "", fmt.Errorf("%w: malformed OTP record", ErrOtpStateUnavailable)
	}

	var record otpRecord
	if err := json.Unmarshal([]byte(recordJSON), &record); err != nil {
		return "", fmt.Errorf("%w: %v", ErrOtpStateUnavailable, err)
	}

	expectedHash, err := s.hashOtp(cleanOtp, record.Salt)
	if err != nil {
		return "", err
	}
	if subtle.ConstantTimeCompare([]byte(expectedHash), []byte(record.HashedOtp)) != 1 {
		remaining := 3 - record.Attempts
		if remaining < 0 {
			remaining = 0
		}
		return "", fmt.Errorf("%w: incorrect OTP code. %d attempts remaining", ErrInvalidOtp, remaining)
	}

	consumed, err := otpConsumeScript.Run(ctx, client, []string{key}, record.Salt, record.HashedOtp).Int()
	if err != nil {
		return "", fmt.Errorf("%w: %v", ErrOtpStateUnavailable, err)
	}
	if consumed != 1 {
		// Another request verified the same code first: the ticket is single use.
		return "", fmt.Errorf("%w: OTP code has already been used", ErrInvalidOtp)
	}

	ticketTok, err := generateTicketToken("ticket_")
	if err != nil {
		return "", err
	}

	ticket := &AuthTicket{
		PhoneNumber: phone,
		BoundUser:   record.BoundUser,
		Provider:    "PHONE_OTP",
		VerifiedAt:  now,
		ExpiresAt:   now + (10 * 60 * 1000),
		TicketToken: ticketTok,
	}
	if err := s.IssueTicket(ticket); err != nil {
		return "", err
	}

	return ticketTok, nil
}

// IssueTicket persists a server-issued AuthTicket. Redis is authoritative when
// configured so tickets issued on one node can be redeemed on another.
func (s *OtpService) IssueTicket(ticket *AuthTicket) error {
	if ticket == nil || ticket.TicketToken == "" {
		return errors.New("auth ticket token is required")
	}

	client := s.stateBackend()
	if client == nil {
		if s.inProduction() {
			return ErrOtpStateUnavailable
		}
		s.mu.Lock()
		s.tickets[ticket.TicketToken] = ticket
		s.mu.Unlock()
		return nil
	}

	ttl := time.Until(time.UnixMilli(ticket.ExpiresAt))
	if ttl <= 0 {
		return errors.New("auth ticket is already expired")
	}

	data, err := json.Marshal(ticket)
	if err != nil {
		return fmt.Errorf("failed to encode auth ticket: %w", err)
	}
	ctx := context.Background()
	if err := client.Set(ctx, otpTicketKeyPrefix+ticket.TicketToken, data, ttl).Err(); err != nil {
		return fmt.Errorf("%w: %v", ErrOtpStateUnavailable, err)
	}
	return nil
}

func (s *OtpService) consumeTicketDistributed(token string, client *redis.Client) (*AuthTicket, error) {
	ctx := context.Background()
	key := otpTicketKeyPrefix + token

	res, err := ticketConsumeScript.Run(ctx, client, []string{key}).Result()
	if err != nil {
		if errors.Is(err, redis.Nil) {
			return nil, errors.New("invalid or already consumed auth ticket")
		}
		return nil, fmt.Errorf("%w: %v", ErrOtpStateUnavailable, err)
	}
	if res == nil {
		return nil, errors.New("invalid or already consumed auth ticket")
	}

	strVal, ok := res.(string)
	if !ok || strVal == "" {
		return nil, errors.New("invalid or already consumed auth ticket")
	}

	var ticket AuthTicket
	if err := json.Unmarshal([]byte(strVal), &ticket); err != nil {
		return nil, fmt.Errorf("failed to decode auth ticket: %w", err)
	}
	if time.Now().UnixMilli() > ticket.ExpiresAt {
		return nil, errors.New("auth ticket has expired")
	}

	return &ticket, nil
}

func generateTicketToken(prefix string) (string, error) {
	ticketBytes := make([]byte, 32)
	if _, err := rand.Read(ticketBytes); err != nil {
		return "", fmt.Errorf("failed to generate auth ticket: %w", err)
	}
	return prefix + hex.EncodeToString(ticketBytes), nil
}
