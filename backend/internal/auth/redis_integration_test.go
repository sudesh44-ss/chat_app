//go:build integration

package auth

import (
	"context"
	"errors"
	"fmt"
	"os"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/redis/go-redis/v9"
)

// Live Redis integration tests for distributed OTP state, tickets and rate
// limiting. They are build-tagged and skipped unless TEST_REDIS_ADDR is set.
//
// The tests use a dedicated logical database (TEST_REDIS_DB, default 15) and
// flush it before and after each test. Never point this at a database that
// holds real data.
//
// Run with:
//   $env:TEST_REDIS_ADDR = "127.0.0.1:56379"
//   $env:TEST_REDIS_DB = "15"
//   go test -tags=integration ./internal/auth -run TestIntegration -v

func newIntegrationRedis(t *testing.T) *redis.Client {
	t.Helper()

	addr := strings.TrimSpace(os.Getenv("TEST_REDIS_ADDR"))
	if addr == "" {
		t.Skip("TEST_REDIS_ADDR is not set; skipping live Redis integration tests")
	}

	db := 15
	if raw := strings.TrimSpace(os.Getenv("TEST_REDIS_DB")); raw != "" {
		parsed, err := strconv.Atoi(raw)
		if err != nil || parsed < 0 {
			t.Fatalf("TEST_REDIS_DB must be a non-negative integer, got %q", raw)
		}
		db = parsed
	}

	client := redis.NewClient(&redis.Options{
		Addr:     addr,
		Password: os.Getenv("TEST_REDIS_PASSWORD"),
		DB:       db,
	})

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	if err := client.Ping(ctx).Err(); err != nil {
		client.Close()
		t.Skipf("Redis is not reachable at %s: %v", addr, err)
	}
	if err := client.FlushDB(ctx).Err(); err != nil {
		client.Close()
		t.Fatalf("flush integration Redis DB %d: %v", db, err)
	}

	t.Cleanup(func() {
		cleanupCtx, cleanupCancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cleanupCancel()
		_ = client.FlushDB(cleanupCtx).Err()
		_ = client.Close()
	})

	return client
}

func newIntegrationOtpService(t *testing.T, client *redis.Client) (*OtpService, *MockSmsProvider) {
	t.Helper()

	t.Setenv("OTP_SERVER_PEPPER", "integration-test-otp-pepper")

	sms := NewMockSmsProvider()
	service := NewOtpService(sms)
	service.SetDistributedState(client, true)
	return service, sms
}

func TestIntegration_RedisOtpLifecycleAndSingleUseTicket(t *testing.T) {
	client := newIntegrationRedis(t)
	service, sms := newIntegrationOtpService(t, client)

	ctx := context.Background()
	phone := "+919876570001"

	cooldown, err := service.RequestOtp(phone, "203.0.113.50")
	if err != nil {
		t.Fatalf("RequestOtp: %v", err)
	}
	if cooldown != 60 {
		t.Fatalf("expected 60s cooldown, got %d", cooldown)
	}

	ttl, err := client.TTL(ctx, otpRecordKeyPrefix+phone).Result()
	if err != nil {
		t.Fatalf("read record TTL: %v", err)
	}
	if ttl <= 0 || ttl > 5*time.Minute {
		t.Fatalf("OTP record TTL out of range: %v", ttl)
	}

	code := sms.GetLastOtp(phone)
	if len(code) != 6 {
		t.Fatalf("mock provider did not receive a 6-digit OTP, got %q", code)
	}

	// Immediate resend is blocked by the distributed cooldown.
	if _, err := service.RequestOtp(phone, "203.0.113.50"); !errors.Is(err, ErrCooldownActive) {
		t.Fatalf("expected cooldown on immediate resend, got %v", err)
	}

	wrong := "000000"
	if code == wrong {
		wrong = "000001"
	}
	if _, err := service.VerifyOtp(phone, wrong); !errors.Is(err, ErrInvalidOtp) {
		t.Fatalf("expected ErrInvalidOtp, got %v", err)
	}

	ticketToken, err := service.VerifyOtp(phone, code)
	if err != nil {
		t.Fatalf("VerifyOtp: %v", err)
	}

	if exists, err := client.Exists(ctx, otpRecordKeyPrefix+phone).Result(); err != nil || exists != 0 {
		t.Fatalf("verified OTP record must be deleted: exists=%d err=%v", exists, err)
	}

	ticket, err := service.ConsumeTicket(ticketToken)
	if err != nil {
		t.Fatalf("ConsumeTicket: %v", err)
	}
	if ticket.PhoneNumber != phone || ticket.Provider != "PHONE_OTP" {
		t.Fatalf("unexpected ticket payload: %+v", ticket)
	}

	if _, err := service.ConsumeTicket(ticketToken); err == nil {
		t.Fatal("auth ticket must be single use")
	}
	if exists, err := client.Exists(ctx, otpTicketKeyPrefix+ticketToken).Result(); err != nil || exists != 0 {
		t.Fatalf("consumed ticket must be deleted: exists=%d err=%v", exists, err)
	}
}

func TestIntegration_RedisBruteForceLockout(t *testing.T) {
	client := newIntegrationRedis(t)
	service, sms := newIntegrationOtpService(t, client)

	ctx := context.Background()
	phone := "+919876570002"

	if _, err := service.RequestOtp(phone, "203.0.113.51"); err != nil {
		t.Fatalf("RequestOtp: %v", err)
	}
	correct := sms.GetLastOtp(phone)
	wrong := "000000"
	if correct == wrong {
		wrong = "000001"
	}

	for attempt := 1; attempt <= 3; attempt++ {
		if _, err := service.VerifyOtp(phone, wrong); !errors.Is(err, ErrInvalidOtp) {
			t.Fatalf("attempt %d: expected ErrInvalidOtp, got %v", attempt, err)
		}
	}

	if _, err := service.VerifyOtp(phone, wrong); !errors.Is(err, ErrMaxAttemptsExceeded) {
		t.Fatalf("expected ErrMaxAttemptsExceeded on fourth attempt, got %v", err)
	}
	if exists, err := client.Exists(ctx, otpRecordKeyPrefix+phone).Result(); err != nil || exists != 0 {
		t.Fatalf("lockout must delete the record: exists=%d err=%v", exists, err)
	}
	if _, err := service.VerifyOtp(phone, correct); !errors.Is(err, ErrInvalidOtp) {
		t.Fatalf("correct OTP after lockout must fail, got %v", err)
	}
}

func TestIntegration_RedisPerPhoneRequestRateLimit(t *testing.T) {
	client := newIntegrationRedis(t)
	service, _ := newIntegrationOtpService(t, client)

	ctx := context.Background()
	phone := "+919876570003"
	ip := "203.0.113.52"

	for i := 0; i < 5; i++ {
		if _, err := service.RequestOtp(phone, ip); err != nil {
			t.Fatalf("request %d should be allowed: %v", i+1, err)
		}
		// Simulate the cooldown elapsing without waiting a minute.
		if err := client.Del(ctx, otpRecordKeyPrefix+phone).Err(); err != nil {
			t.Fatalf("clear record: %v", err)
		}
	}

	if _, err := service.RequestOtp(phone, ip); !errors.Is(err, ErrRateLimitExceeded) {
		t.Fatalf("sixth request within an hour must be rate limited, got %v", err)
	}
}

func TestIntegration_RedisPerIPRequestRateLimit(t *testing.T) {
	client := newIntegrationRedis(t)
	service, _ := newIntegrationOtpService(t, client)

	ctx := context.Background()
	ip := "203.0.113.53"

	for i := 0; i < 15; i++ {
		phone := fmt.Sprintf("+91987658%04d", i)
		if _, err := service.RequestOtp(phone, ip); err != nil {
			t.Fatalf("request %d from the same IP should be allowed: %v", i+1, err)
		}
		if err := client.Del(ctx, otpRecordKeyPrefix+phone).Err(); err != nil {
			t.Fatalf("clear record: %v", err)
		}
	}

	if _, err := service.RequestOtp("+919876589999", ip); !errors.Is(err, ErrRateLimitExceeded) {
		t.Fatalf("sixteenth request from the same IP must be rate limited, got %v", err)
	}
}

func TestIntegration_RedisConcurrentOtpVerification(t *testing.T) {
	client := newIntegrationRedis(t)
	service, sms := newIntegrationOtpService(t, client)

	phone := "+919876570004"
	if _, err := service.RequestOtp(phone, "203.0.113.54"); err != nil {
		t.Fatalf("RequestOtp: %v", err)
	}
	code := sms.GetLastOtp(phone)

	const workers = 8
	results := make([]error, workers)
	var wg sync.WaitGroup
	for i := 0; i < workers; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			_, err := service.VerifyOtp(phone, code)
			results[idx] = err
		}(i)
	}
	wg.Wait()

	successes := 0
	for _, err := range results {
		if err == nil {
			successes++
		}
	}
	if successes > 1 {
		t.Fatalf("at most one concurrent verification may succeed, got %d", successes)
	}
}

func TestIntegration_RedisConcurrentTicketConsumption(t *testing.T) {
	client := newIntegrationRedis(t)
	service, _ := newIntegrationOtpService(t, client)

	now := time.Now().UnixMilli()
	ticket := &AuthTicket{
		PhoneNumber: "+919876570005",
		Provider:    "PHONE_OTP",
		VerifiedAt:  now,
		ExpiresAt:   now + (10 * 60 * 1000),
		TicketToken: "ticket_integration_concurrent",
	}
	if err := service.IssueTicket(ticket); err != nil {
		t.Fatalf("IssueTicket: %v", err)
	}

	const workers = 8
	results := make([]error, workers)
	var wg sync.WaitGroup
	for i := 0; i < workers; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			_, err := service.ConsumeTicket(ticket.TicketToken)
			results[idx] = err
		}(i)
	}
	wg.Wait()

	successes := 0
	for _, err := range results {
		if err == nil {
			successes++
		}
	}
	if successes != 1 {
		t.Fatalf("exactly one concurrent ticket redemption may succeed, got %d", successes)
	}
}
