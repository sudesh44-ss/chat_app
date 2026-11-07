package ratelimit

import (
	"context"
	"sync"
	"testing"
	"time"
)

func TestMemoryLimiter_Basic(t *testing.T) {
	limiter := NewMemoryLimiter()
	key := "test_user_1"
	limit := 3
	window := 100 * time.Millisecond

	// First 3 requests allowed
	for i := 0; i < 3; i++ {
		if !limiter.Allow(key, limit, window) {
			t.Fatalf("Request %d should be allowed", i+1)
		}
	}

	// 4th request within window must be rejected
	if limiter.Allow(key, limit, window) {
		t.Fatalf("4th request within window should be rejected")
	}

	// Wait for window to expire
	time.Sleep(120 * time.Millisecond)

	// Now should be allowed again
	if !limiter.Allow(key, limit, window) {
		t.Fatalf("Request after window expiration should be allowed")
	}
}

func TestMemoryLimiter_Concurrent(t *testing.T) {
	limiter := NewMemoryLimiter()
	key := "test_concurrent"
	limit := 50
	window := 1 * time.Second

	var wg sync.WaitGroup
	var allowedCount int
	var mu sync.Mutex

	for i := 0; i < 100; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if limiter.Allow(key, limit, window) {
				mu.Lock()
				allowedCount++
				mu.Unlock()
			}
		}()
	}

	wg.Wait()

	if allowedCount != limit {
		t.Fatalf("Expected exactly %d allowed requests under concurrent load, got %d", limit, allowedCount)
	}
}

func TestDistributedLimiter_DevFallback(t *testing.T) {
	// Dev mode: nil redis client falls back to memory limiter without failing
	limiter := NewDistributedLimiter(nil, false)
	ctx := context.Background()

	allowed, err := limiter.Allow(ctx, "dev_test", 2, time.Second)
	if err != nil || !allowed {
		t.Fatalf("First request in dev should be allowed: err=%v", err)
	}

	allowed, err = limiter.Allow(ctx, "dev_test", 2, time.Second)
	if err != nil || !allowed {
		t.Fatalf("Second request in dev should be allowed: err=%v", err)
	}

	allowed, err = limiter.Allow(ctx, "dev_test", 2, time.Second)
	if err != nil {
		t.Fatalf("Unexpected error: %v", err)
	}
	if allowed {
		t.Fatalf("Third request exceeding limit must be rejected")
	}
}

func TestDistributedLimiter_ProductionFailClosed(t *testing.T) {
	// Production mode: nil redis client must fail closed
	limiter := NewDistributedLimiter(nil, true)
	ctx := context.Background()

	allowed, err := limiter.Allow(ctx, "prod_test", 10, time.Minute)
	if allowed {
		t.Fatalf("Production limiter with missing Redis MUST NOT allow request (fail closed)")
	}
	if err == nil {
		t.Fatalf("Production limiter with missing Redis must return error")
	}
}
