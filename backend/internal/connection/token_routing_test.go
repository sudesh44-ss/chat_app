package connection

import (
	"context"
	"fmt"
	"sync"
	"testing"
	"time"

	"nexa/wire/internal/queue"

	"github.com/redis/go-redis/v9"
)

// mockBuffer implements queue.Buffer for testing FlushTokensQueue and offline buffering
type mockItem struct {
	id      string
	payload []byte
}

type mockBuffer struct {
	mu       sync.Mutex
	pending  map[string][]mockItem
	inFlight map[string]map[string]mockItem
	counter  int
}

func newMockBuffer() *mockBuffer {
	return &mockBuffer{
		pending:  make(map[string][]mockItem),
		inFlight: make(map[string]map[string]mockItem),
	}
}

func (m *mockBuffer) PushPending(ctx context.Context, recipientNxId string, packetBytes []byte) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.counter++
	id := fmt.Sprintf("mock_msg_%d", m.counter)
	m.pending[recipientNxId] = append(m.pending[recipientNxId], mockItem{id: id, payload: packetBytes})
	return nil
}

func (m *mockBuffer) PopAllPending(ctx context.Context, recipientNxId string) ([][]byte, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	items := m.pending[recipientNxId]
	delete(m.pending, recipientNxId)
	res := make([][]byte, len(items))
	for i, it := range items {
		res[i] = it.payload
	}
	return res, nil
}

func (m *mockBuffer) Enqueue(ctx context.Context, queueKey, messageID string, payload []byte, expiresAt int64) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	for _, it := range m.pending[queueKey] {
		if it.id == messageID {
			return nil
		}
	}
	m.pending[queueKey] = append(m.pending[queueKey], mockItem{id: messageID, payload: payload})
	return nil
}

func (m *mockBuffer) ClaimPending(ctx context.Context, queueKey string, batchSize int, ackTimeout time.Duration) ([]*queue.QueueEntry, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.inFlight[queueKey] == nil {
		m.inFlight[queueKey] = make(map[string]mockItem)
	}
	pendingList := m.pending[queueKey]
	var claimed []*queue.QueueEntry
	var remaining []mockItem

	for _, it := range pendingList {
		if len(claimed) < batchSize {
			claimed = append(claimed, &queue.QueueEntry{
				MessageID:        it.id,
				QueueKey:         queueKey,
				Payload:          it.payload,
				Status:           queue.StatusInFlight,
				DeliveryAttempts: 1,
			})
			m.inFlight[queueKey][it.id] = it
		} else {
			remaining = append(remaining, it)
		}
	}
	m.pending[queueKey] = remaining
	return claimed, nil
}

func (m *mockBuffer) Acknowledge(ctx context.Context, queueKey, messageID string) (bool, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if flightMap, ok := m.inFlight[queueKey]; ok {
		if _, found := flightMap[messageID]; found {
			delete(flightMap, messageID)
			return true, nil
		}
	}
	pendingList := m.pending[queueKey]
	for i, it := range pendingList {
		if it.id == messageID {
			m.pending[queueKey] = append(pendingList[:i], pendingList[i+1:]...)
			return true, nil
		}
	}
	return false, nil
}

func (m *mockBuffer) RevertInFlight(ctx context.Context, queueKey, messageID string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if flightMap, ok := m.inFlight[queueKey]; ok {
		if it, found := flightMap[messageID]; found {
			delete(flightMap, messageID)
			m.pending[queueKey] = append([]mockItem{it}, m.pending[queueKey]...)
		}
	}
	return nil
}

func (m *mockBuffer) GetQueueMetrics(ctx context.Context, queueKey string) (int64, int64, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	return int64(len(m.pending[queueKey])), int64(len(m.inFlight[queueKey])), nil
}

func (m *mockBuffer) IsConnected() bool        { return true }
func (m *mockBuffer) IsDegraded() bool         { return false }
func (m *mockBuffer) GetClient() *redis.Client { return nil }
func (m *mockBuffer) Close() error             { return nil }

func (m *mockBuffer) getPendingCount(key string) int {
	m.mu.Lock()
	defer m.mu.Unlock()
	return len(m.pending[key])
}

func TestHub_RegisterRoutingTokens_Authenticated(t *testing.T) {
	hub := NewHub(nil)
	sessionAuth := NewClientSession(hub, nil, "NX-ALICE", "dev_phone")
	sessionAuth.Authenticated = true

	token1 := "11111111111111111111111111111111"
	token2 := "22222222222222222222222222222222"

	hub.RegisterRoutingTokens([]string{token1, token2}, sessionAuth)

	hub.blindTokensMu.RLock()
	s1 := hub.blindTokens[token1]
	s2 := hub.blindTokens[token2]
	hub.blindTokensMu.RUnlock()

	if s1 != sessionAuth || s2 != sessionAuth {
		t.Fatalf("Expected tokens to map to sessionAuth, got s1=%v, s2=%v", s1, s2)
	}

	// Unauthenticated session attempts to register
	sessionUnauth := NewClientSession(hub, nil, "NX-ATTACKER", "dev_rogue")
	sessionUnauth.Authenticated = false

	token3 := "33333333333333333333333333333333"
	hub.RegisterRoutingTokens([]string{token3}, sessionUnauth)

	hub.blindTokensMu.RLock()
	s3 := hub.blindTokens[token3]
	hub.blindTokensMu.RUnlock()

	if s3 != nil {
		t.Fatalf("Expected unauthenticated token registration to be rejected, but found mapping to %v", s3)
	}
}

func TestHub_RegisterRoutingTokens_AntiHijacking(t *testing.T) {
	hub := NewHub(nil)
	alice := NewClientSession(hub, nil, "NX-ALICE", "dev_phone_a")
	alice.Authenticated = true

	bob := NewClientSession(hub, nil, "NX-BOB", "dev_phone_b")
	bob.Authenticated = true

	tokenAlice := "4d434cacd96a31d95db3d8305e5ac679"

	// Alice registers token
	hub.RegisterRoutingTokens([]string{tokenAlice}, alice)

	hub.blindTokensMu.RLock()
	if hub.blindTokens[tokenAlice] != alice {
		t.Fatalf("Expected tokenAlice to be mapped to alice")
	}
	hub.blindTokensMu.RUnlock()

	// Bob attempts to hijack Alice's token
	hub.RegisterRoutingTokens([]string{tokenAlice}, bob)

	hub.blindTokensMu.RLock()
	currentOwner := hub.blindTokens[tokenAlice]
	hub.blindTokensMu.RUnlock()

	if currentOwner != alice {
		t.Fatalf("Anti-hijacking failure: Bob overwrote Alice's token! Current owner: %v", currentOwner)
	}

	// Alice's second device registers the same token -> allowed
	aliceTablet := NewClientSession(hub, nil, "NX-ALICE", "dev_tablet_a")
	aliceTablet.Authenticated = true

	hub.RegisterRoutingTokens([]string{tokenAlice}, aliceTablet)

	hub.blindTokensMu.RLock()
	updatedOwner := hub.blindTokens[tokenAlice]
	hub.blindTokensMu.RUnlock()

	if updatedOwner != aliceTablet {
		t.Fatalf("Expected Alice's second device to update token mapping, got %v", updatedOwner)
	}
}

func TestHub_RegisterRoutingTokens_BoundsAndMaxLimit(t *testing.T) {
	hub := NewHub(nil)
	session := NewClientSession(hub, nil, "NX-CHARLIE", "dev_phone")
	session.Authenticated = true

	// Mix invalid length tokens with valid ones
	invalidShort := "short"
	invalidLong := "111111111111111111111111111111110000000000000000"
	validToken := "aabbccddeeff00112233445566778899"

	hub.RegisterRoutingTokens([]string{invalidShort, invalidLong, validToken}, session)

	hub.blindTokensMu.RLock()
	if _, found := hub.blindTokens[invalidShort]; found {
		t.Fatalf("Short token should not have been registered")
	}
	if _, found := hub.blindTokens[invalidLong]; found {
		t.Fatalf("Long token should not have been registered")
	}
	if hub.blindTokens[validToken] != session {
		t.Fatalf("Valid token should have been registered")
	}
	hub.blindTokensMu.RUnlock()

	// Test max cap of 500 tokens
	tokens600 := make([]string, 600)
	for i := 0; i < 600; i++ {
		tokens600[i] = fmt.Sprintf("%032x", i+1)
	}

	hubClean := NewHub(nil)
	sessionClean := NewClientSession(hubClean, nil, "NX-CHARLIE", "dev_phone")
	sessionClean.Authenticated = true

	hubClean.RegisterRoutingTokens(tokens600, sessionClean)

	hubClean.blindTokensMu.RLock()
	registeredCount := len(hubClean.blindTokens)
	hubClean.blindTokensMu.RUnlock()

	if registeredCount != 500 {
		t.Fatalf("Expected exactly 500 tokens registered, got %d", registeredCount)
	}
}

func TestHub_Unregister_CleansUpTokens(t *testing.T) {
	hub := NewHub(nil)
	alice := NewClientSession(hub, nil, "NX-ALICE", "dev_phone")
	alice.Authenticated = true

	bob := NewClientSession(hub, nil, "NX-BOB", "dev_phone")
	bob.Authenticated = true

	t1 := "11111111111111111111111111111111"
	t2 := "22222222222222222222222222222222"
	tb := "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

	hub.Register(alice)
	hub.Register(bob)
	hub.RegisterRoutingTokens([]string{t1, t2}, alice)
	hub.RegisterRoutingTokens([]string{tb}, bob)

	hub.Unregister(alice)

	hub.blindTokensMu.RLock()
	_, foundT1 := hub.blindTokens[t1]
	_, foundT2 := hub.blindTokens[t2]
	ownerTb := hub.blindTokens[tb]
	hub.blindTokensMu.RUnlock()

	if foundT1 || foundT2 {
		t.Fatalf("Expected Alice's tokens to be evicted upon unregister")
	}
	if ownerTb != bob {
		t.Fatalf("Bob's token should remain untouched, got %v", ownerTb)
	}
}

func TestHub_FlushTokensQueue_LosslessRebuffering(t *testing.T) {
	buf := newMockBuffer()
	hub := NewHub(buf)

	session := NewClientSession(hub, nil, "NX-RECIPIENT", "dev_phone")
	session.Authenticated = true
	// Limit session Send channel capacity to 1 so the second packet blocks/fails SafeSend
	session.Send = make(chan []byte, 1)

	token := "4d434cacd96a31d95db3d8305e5ac679"
	dtnKey := "dtn_" + token

	packet1 := []byte("DTN_PACKET_1")
	packet2 := []byte("DTN_PACKET_2")
	packet3 := []byte("DTN_PACKET_3")

	// Pre-fill buffer with 3 envelopes
	_ = buf.PushPending(context.Background(), dtnKey, packet1)
	_ = buf.PushPending(context.Background(), dtnKey, packet2)
	_ = buf.PushPending(context.Background(), dtnKey, packet3)

	if buf.getPendingCount(dtnKey) != 3 {
		t.Fatalf("Setup failure: buffer should have 3 pending packets")
	}

	// Execute synchronous flush
	hub.FlushTokensQueue([]string{token}, session)

	// Verify session received packet 1
	select {
	case delivered := <-session.Send:
		if string(delivered) != "DTN_PACKET_1" {
			t.Fatalf("Expected DTN_PACKET_1, got %s", string(delivered))
		}
	default:
		t.Fatalf("Expected packet 1 in session.Send")
	}

	// Verify lossless re-buffering: packet 2 and packet 3 must be preserved in buffer
	remainingCount := buf.getPendingCount(dtnKey)
	if remainingCount != 2 {
		t.Fatalf("Lossless delivery failure: expected 2 re-buffered packets, got %d", remainingCount)
	}

	// Verify content of re-buffered packets
	remaining, _ := buf.PopAllPending(context.Background(), dtnKey)
	if string(remaining[0]) != "DTN_PACKET_2" || string(remaining[1]) != "DTN_PACKET_3" {
		t.Fatalf("Re-buffered packet order or contents corrupted: %s, %s", string(remaining[0]), string(remaining[1]))
	}
}
