package rules

import (
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"sync"
	"time"
)

/**
 * RulesStore holds compiled YARA rule definitions and Bloom filter delta blobs.
 * Operates purely in-memory with zero disk persistence.
 */
type RulesStore struct {
	mu           sync.RWMutex
	version      string
	bloomEntries int
	yaraBytes    []byte
	bloomBytes   []byte
	etag         string
	updatedAt    time.Time
}

func NewRulesStore() *RulesStore {
	store := &RulesStore{
		version:      "2026.11.03",
		bloomEntries: 120000,
		updatedAt:    time.Now(),
	}

	// Embedded default lightweight rule definitions
	store.yaraBytes = []byte("rule NexaEmbeddedShellcode { strings: $nop = { 90 90 90 90 } condition: $nop }")
	store.bloomBytes = make([]byte, 1024) // Initial seed delta blob
	store.recomputeEtag()

	return store
}

func (s *RulesStore) GetVersion() (string, int, time.Time) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.version, s.bloomEntries, s.updatedAt
}

func (s *RulesStore) GetEtag() string {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.etag
}

func (s *RulesStore) GetBundle() ([]byte, string, string) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	// Pack bundle wire format: [4 bytes YARA len][YARA bytes][Bloom bytes]
	yaraLen := len(s.yaraBytes)
	bundle := make([]byte, 4+yaraLen+len(s.bloomBytes))
	binary.BigEndian.PutUint32(bundle[0:4], uint32(yaraLen))
	copy(bundle[4:4+yaraLen], s.yaraBytes)
	copy(bundle[4+yaraLen:], s.bloomBytes)

	return bundle, s.etag, s.version
}

func (s *RulesStore) UpdateBundle(version string, yaraData, bloomData []byte, entries int) {
	s.mu.Lock()
	defer s.mu.Unlock()

	s.version = version
	s.yaraBytes = yaraData
	s.bloomBytes = bloomData
	s.bloomEntries = entries
	s.updatedAt = time.Now()
	s.recomputeEtag()
}

func (s *RulesStore) recomputeEtag() {
	h := sha256.New()
	h.Write([]byte(s.version))
	h.Write(s.yaraBytes)
	h.Write(s.bloomBytes)
	s.etag = `"` + hex.EncodeToString(h.Sum(nil))[:16] + `"`
}
