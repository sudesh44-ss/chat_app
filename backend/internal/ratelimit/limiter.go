package ratelimit

import (
	"context"
	"errors"
	"fmt"
	"log"
	"sync"
	"time"

	"github.com/redis/go-redis/v9"
)

var (
	ErrRateLimitExceeded = errors.New("rate limit exceeded")
	ErrLimiterDown       = errors.New("rate limiter unavailable (production fail-closed)")
)

// Limiter defines the rate limiting interface.
type Limiter interface {
	Allow(ctx context.Context, key string, limit int, window time.Duration) (bool, error)
}

// slidingWindowScript performs atomic sliding window rate limiting in Redis using a sorted set.
// KEYS[1] = rate limit key
// ARGV[1] = current timestamp (ms)
// ARGV[2] = window size (ms)
// ARGV[3] = max allowed requests in window
// ARGV[4] = unique member identifier
var slidingWindowScript = redis.NewScript(`
local key = KEYS[1]
local now = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local limit = tonumber(ARGV[3])
local member = ARGV[4]

local clearBefore = now - window
redis.call('ZREMRANGEBYSCORE', key, '-inf', clearBefore)
local currentCount = redis.call('ZCARD', key)

if currentCount < limit then
    redis.call('ZADD', key, now, member)
    redis.call('PEXPIRE', key, window)
    return 1
else
    return 0
end
`)

// MemoryLimiter provides an in-memory sliding window fallback for dev/test environments.
type MemoryLimiter struct {
	mu      sync.Mutex
	history map[string][]int64
}

func NewMemoryLimiter() *MemoryLimiter {
	return &MemoryLimiter{
		history: make(map[string][]int64),
	}
}

func (m *MemoryLimiter) Allow(key string, limit int, window time.Duration) bool {
	m.mu.Lock()
	defer m.mu.Unlock()

	now := time.Now().UnixMilli()
	threshold := now - window.Milliseconds()

	timestamps := m.history[key]
	var valid []int64
	for _, ts := range timestamps {
		if ts > threshold {
			valid = append(valid, ts)
		}
	}

	if len(valid) >= limit {
		m.history[key] = valid
		return false
	}

	valid = append(valid, now)
	m.history[key] = valid
	return true
}

// DistributedLimiter provides distributed sliding-window rate limiting backed by Redis.
type DistributedLimiter struct {
	client       *redis.Client
	isProduction bool
	memFallback  *MemoryLimiter
}

func NewDistributedLimiter(client *redis.Client, isProduction bool) *DistributedLimiter {
	return &DistributedLimiter{
		client:       client,
		isProduction: isProduction,
		memFallback:  NewMemoryLimiter(),
	}
}

// SetRedisClient updates the Redis client dynamically.
func (l *DistributedLimiter) SetRedisClient(client *redis.Client) {
	l.client = client
}

// Allow evaluates whether an action identified by key is permitted under limit/window.
func (l *DistributedLimiter) Allow(ctx context.Context, key string, limit int, window time.Duration) (bool, error) {
	if l.client != nil {
		now := time.Now().UnixMilli()
		windowMs := window.Milliseconds()
		member := fmt.Sprintf("%d_%d", now, time.Now().UnixNano())

		res, err := slidingWindowScript.Run(ctx, l.client, []string{key}, now, windowMs, limit, member).Int()
		if err == nil {
			return res == 1, nil
		}

		if l.isProduction {
			log.Printf("[RateLimiter] CRITICAL: Redis error in production for key %s: %v (FAIL-CLOSED)", key, err)
			return false, fmt.Errorf("%w: %v", ErrLimiterDown, err)
		}
		log.Printf("[RateLimiter] WARNING: Redis error in dev/test for key %s: %v, falling back to memory", key, err)
	} else if l.isProduction {
		log.Printf("[RateLimiter] CRITICAL: No Redis client available in production for key %s (FAIL-CLOSED)", key)
		return false, ErrLimiterDown
	}

	// Dev / test fallback
	return l.memFallback.Allow(key, limit, window), nil
}
