package handshake

import (
	"sync"
	"time"
)

type PrekeyBundle struct {
	NxID                   string `json:"nx_id"`
	IdentityKeyHex         string `json:"identity_key"`
	SigningKeyHex          string `json:"signing_key"`
	SignedPrekeyHex        string `json:"signed_prekey"`
	SignedPrekeySignature  string `json:"signed_prekey_sig"`
	OneTimePrekeyHex       string `json:"one_time_prekey,omitempty"`
	OneTimePrekeyID        string `json:"one_time_prekey_id,omitempty"`
}

type QueuedEnvelope struct {
	Envelope  string    `json:"envelope"`
	Timestamp time.Time `json:"timestamp"`
}

// MemoryStore manages in-memory zero-knowledge ephemeral queues and rate-limiting counters.
type MemoryStore struct {
	mu           sync.RWMutex
	prekeys      map[string]*PrekeyBundle
	pendingQueue map[string][]QueuedEnvelope
	rateLimits   map[string][]time.Time
}

func NewMemoryStore() *MemoryStore {
	return &MemoryStore{
		prekeys:      make(map[string]*PrekeyBundle),
		pendingQueue: make(map[string][]QueuedEnvelope),
		rateLimits:   make(map[string][]time.Time),
	}
}

func (s *MemoryStore) PutPrekey(bundle *PrekeyBundle) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.prekeys[bundle.NxID] = bundle
}

func (s *MemoryStore) GetPrekey(nxID string) (*PrekeyBundle, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	bundle, ok := s.prekeys[nxID]
	return bundle, ok
}

// AllowRequest checks whether the given key (IP or NX-ID) has exceeded 15 requests/hour.
func (s *MemoryStore) AllowRequest(key string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()

	now := time.Now()
	oneHourAgo := now.Add(-1 * time.Hour)

	var valid []time.Time
	for _, t := range s.rateLimits[key] {
		if t.After(oneHourAgo) {
			valid = append(valid, t)
		}
	}

	if len(valid) >= 15 {
		s.rateLimits[key] = valid
		return false // Rate limit exceeded (Max 15/hr)
	}

	valid = append(valid, now)
	s.rateLimits[key] = valid
	return true
}

func (s *MemoryStore) Enqueue(recipientNxID string, envelope string) {
	s.mu.Lock()
	defer s.mu.Unlock()

	s.pendingQueue[recipientNxID] = append(s.pendingQueue[recipientNxID], QueuedEnvelope{
		Envelope:  envelope,
		Timestamp: time.Now(),
	})
}

// DrainPending retrieves and purges all pending envelopes for the recipient.
// Expired items (> 7 days) are dropped.
func (s *MemoryStore) DrainPending(recipientNxID string) []string {
	s.mu.Lock()
	defer s.mu.Unlock()

	queue, exists := s.pendingQueue[recipientNxID]
	if !exists || len(queue) == 0 {
		return nil
	}

	now := time.Now()
	sevenDays := 7 * 24 * time.Hour
	var result []string

	for _, item := range queue {
		if now.Sub(item.Timestamp) <= sevenDays {
			result = append(result, item.Envelope)
		}
	}

	delete(s.pendingQueue, recipientNxID)
	return result
}
