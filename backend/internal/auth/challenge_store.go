package auth

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"sync"
	"time"

	"github.com/redis/go-redis/v9"
)

var (
	ErrChallengeNotFound = errors.New("challenge not found or already consumed")
	ErrChallengeExpired  = errors.New("challenge has expired")
	ErrChallengeMismatch = errors.New("challenge identity or device mismatch")
)

// AuthChallenge represents a cryptographically secure, single-use authentication challenge.
type AuthChallenge struct {
	ChallengeID string `json:"challenge_id"`
	Challenge   string `json:"challenge"` // Hex-encoded 32 crypto random bytes
	NxID        string `json:"nx_id"`
	DeviceID    string `json:"device_id"`
	CreatedAt   int64  `json:"created_at"`
	ExpiresAt   int64  `json:"expires_at"`
}

// ChallengeStore manages lifecycle and atomic single-use consumption of authentication challenges.
type ChallengeStore interface {
	CreateChallenge(ctx context.Context, nxID, deviceID string, ttl time.Duration) (*AuthChallenge, error)
	ConsumeChallenge(ctx context.Context, challengeID string) (*AuthChallenge, error)
}

// MemoryChallengeStore is an in-memory, thread-safe implementation of ChallengeStore.
type MemoryChallengeStore struct {
	mu         sync.Mutex
	challenges map[string]*AuthChallenge
}

func NewMemoryChallengeStore() *MemoryChallengeStore {
	return &MemoryChallengeStore{
		challenges: make(map[string]*AuthChallenge),
	}
}

func (m *MemoryChallengeStore) CreateChallenge(_ context.Context, nxID, deviceID string, ttl time.Duration) (*AuthChallenge, error) {
	if nxID == "" || deviceID == "" {
		return nil, errors.New("nxID and deviceID are required to create a challenge")
	}

	randomBytes := make([]byte, 32)
	if _, err := rand.Read(randomBytes); err != nil {
		return nil, fmt.Errorf("crypto rand failed: %w", err)
	}
	challengeHex := hex.EncodeToString(randomBytes)
	challengeID := "chal_" + challengeHex[:16]

	now := time.Now().UnixMilli()
	expiresAt := now + int64(ttl/time.Millisecond)

	chal := &AuthChallenge{
		ChallengeID: challengeID,
		Challenge:   challengeHex,
		NxID:        nxID,
		DeviceID:    deviceID,
		CreatedAt:   now,
		ExpiresAt:   expiresAt,
	}

	m.mu.Lock()
	defer m.mu.Unlock()

	// Lazy cleanup of expired challenges to keep memory footprint bounded
	for k, v := range m.challenges {
		if now > v.ExpiresAt {
			delete(m.challenges, k)
		}
	}

	m.challenges[challengeID] = chal
	// Also index by raw challenge hex for flexible client verification
	m.challenges[challengeHex] = chal

	return chal, nil
}

func (m *MemoryChallengeStore) ConsumeChallenge(_ context.Context, challengeKey string) (*AuthChallenge, error) {
	m.mu.Lock()
	defer m.mu.Unlock()

	chal, found := m.challenges[challengeKey]
	if !found {
		return nil, ErrChallengeNotFound
	}

	// Invalidate both challengeID and challengeHex keys atomically (single use)
	delete(m.challenges, chal.ChallengeID)
	delete(m.challenges, chal.Challenge)

	now := time.Now().UnixMilli()
	if now > chal.ExpiresAt {
		return nil, ErrChallengeExpired
	}

	return chal, nil
}

// RedisChallengeStore is an atomic Redis-backed implementation with Lua script single-use extraction.
type RedisChallengeStore struct {
	client    *redis.Client
	keyPrefix string
	consumeScript *redis.Script
}

func NewRedisChallengeStore(client *redis.Client) *RedisChallengeStore {
	script := redis.NewScript(`
		local val = redis.call('GET', KEYS[1])
		if val then
			redis.call('DEL', KEYS[1])
			if #KEYS > 1 then
				redis.call('DEL', KEYS[2])
			end
		end
		return val
	`)

	return &RedisChallengeStore{
		client:        client,
		keyPrefix:     "auth:challenge:",
		consumeScript: script,
	}
}

func (r *RedisChallengeStore) CreateChallenge(ctx context.Context, nxID, deviceID string, ttl time.Duration) (*AuthChallenge, error) {
	if nxID == "" || deviceID == "" {
		return nil, errors.New("nxID and deviceID are required to create a challenge")
	}

	randomBytes := make([]byte, 32)
	if _, err := rand.Read(randomBytes); err != nil {
		return nil, fmt.Errorf("crypto rand failed: %w", err)
	}
	challengeHex := hex.EncodeToString(randomBytes)
	challengeID := "chal_" + challengeHex[:16]

	now := time.Now().UnixMilli()
	expiresAt := now + int64(ttl/time.Millisecond)

	chal := &AuthChallenge{
		ChallengeID: challengeID,
		Challenge:   challengeHex,
		NxID:        nxID,
		DeviceID:    deviceID,
		CreatedAt:   now,
		ExpiresAt:   expiresAt,
	}

	data, err := json.Marshal(chal)
	if err != nil {
		return nil, fmt.Errorf("failed to marshal challenge: %w", err)
	}

	pipe := r.client.Pipeline()
	pipe.Set(ctx, r.keyPrefix+challengeID, data, ttl)
	pipe.Set(ctx, r.keyPrefix+challengeHex, data, ttl)
	if _, err := pipe.Exec(ctx); err != nil {
		return nil, fmt.Errorf("failed to save challenge in Redis: %w", err)
	}

	return chal, nil
}

func (r *RedisChallengeStore) ConsumeChallenge(ctx context.Context, challengeKey string) (*AuthChallenge, error) {
	key := r.keyPrefix + challengeKey
	res, err := r.consumeScript.Run(ctx, r.client, []string{key}).Result()
	if err != nil {
		if errors.Is(err, redis.Nil) {
			return nil, ErrChallengeNotFound
		}
		return nil, fmt.Errorf("redis script execution failed: %w", err)
	}

	if res == nil {
		return nil, ErrChallengeNotFound
	}

	strVal, ok := res.(string)
	if !ok || strVal == "" {
		return nil, ErrChallengeNotFound
	}

	var chal AuthChallenge
	if err := json.Unmarshal([]byte(strVal), &chal); err != nil {
		return nil, fmt.Errorf("failed to unmarshal consumed challenge: %w", err)
	}

	// Clean up alternate key if present
	if challengeKey == chal.ChallengeID {
		r.client.Del(ctx, r.keyPrefix+chal.Challenge)
	} else {
		r.client.Del(ctx, r.keyPrefix+chal.ChallengeID)
	}

	now := time.Now().UnixMilli()
	if now > chal.ExpiresAt {
		return nil, ErrChallengeExpired
	}

	return &chal, nil
}

// NewChallengeStore returns an appropriate ChallengeStore depending on whether Redis is available.
func NewChallengeStore(client *redis.Client) ChallengeStore {
	if client != nil {
		return NewRedisChallengeStore(client)
	}
	return NewMemoryChallengeStore()
}
