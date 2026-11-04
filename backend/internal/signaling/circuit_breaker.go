package signaling

import (
	"errors"
	"sync"
	"time"
)

var (
	ErrRelayCapExceeded = errors.New("ERR_RELAY_CAP_EXCEEDED: file exceeds 100MB TURN relay limit")
	ErrRateThrottled    = errors.New("ERR_RATE_THROTTLED: active bandwidth exceeds 1.5MB/s ceiling")
)

const (
	MaxTurnFileSizeBytes   int64 = 100 * 1024 * 1024 // 100 MB hard ceiling
	MaxTurnThroughputBytes int64 = 1536 * 1024       // 1.5 MB/s rate limit
)

type TurnCircuitBreaker struct {
	mu           sync.Mutex
	activeBytes  map[string]int64
	lastInterval time.Time
}

func NewTurnCircuitBreaker() *TurnCircuitBreaker {
	return &TurnCircuitBreaker{
		activeBytes:  make(map[string]int64),
		lastInterval: time.Now(),
	}
}

func (cb *TurnCircuitBreaker) ValidateRelayRequest(peerID string, fileSizeBytes int64) error {
	cb.mu.Lock()
	defer cb.mu.Unlock()

	// 1. Enforce 100 MB file size ceiling over developer TURN relay
	if fileSizeBytes > MaxTurnFileSizeBytes {
		return ErrRelayCapExceeded
	}

	// 2. Sliding 1-second throughput governor
	now := time.Now()
	if now.Sub(cb.lastInterval) >= time.Second {
		cb.activeBytes = make(map[string]int64)
		cb.lastInterval = now
	}

	currentUsage := cb.activeBytes[peerID]
	if currentUsage >= MaxTurnThroughputBytes {
		return ErrRateThrottled
	}

	return nil
}

func (cb *TurnCircuitBreaker) RecordBytes(peerID string, bytes int64) {
	cb.mu.Lock()
	defer cb.mu.Unlock()
	cb.activeBytes[peerID] += bytes
}
