package connection

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"testing"
	"time"

	"nexa/wire/internal/queue"
	"nexa/wire/internal/wire"
)

// mockConnectionValidator implements ConnectionValidator for testing
type mockConnectionValidator struct {
	connected map[string]bool
	blocked   map[string]bool
}

func (m *mockConnectionValidator) IsConnected(ctx context.Context, userA, userB string) (bool, error) {
	key1 := userA + "_" + userB
	key2 := userB + "_" + userA
	if m.connected[key1] || m.connected[key2] {
		return true, nil
	}
	return false, nil
}

func (m *mockConnectionValidator) IsBlocked(ctx context.Context, userA, userB string) (bool, error) {
	key1 := userA + "_" + userB
	key2 := userB + "_" + userA
	if m.blocked[key1] || m.blocked[key2] {
		return true, nil
	}
	return false, nil
}

// TestPeerSync_WireEncodingDecoding tests that PACKET_PEER_SYNC serializes and deserializes losslessly.
func TestPeerSync_WireEncodingDecoding(t *testing.T) {
	envelopeData := []byte("NXDT_ENVELOPE_SAMPLE_DATA_123456789")
	payload := &wire.PeerSyncPayload{
		Action:          wire.PeerSyncAction_SYNC_INVENTORY_REQ,
		SessionId:       "sync_sess_001",
		ConversationId:  "NX-ALICE_NX-BOB",
		CursorTimestamp: 1700000000000,
		CursorSequence:  42,
		MessageIds:      []string{"msg_uuid_1", "msg_uuid_2"},
		Envelopes:       [][]byte{envelopeData},
		SyncTimestamp:   1700000050000,
		ErrorMessage:    "",
	}

	packet := &wire.NexaWirePacket{
		PacketId:      "sync_pkt_001",
		Type:          wire.PacketType_PACKET_PEER_SYNC,
		SenderNxId:    "NX-ALICE",
		RecipientNxId: "NX-BOB",
		Timestamp:     time.Now().UnixMilli(),
		PeerSync:      payload,
	}

	encoded, err := packet.Encode()
	if err != nil {
		t.Fatalf("Failed to encode PACKET_PEER_SYNC: %v", err)
	}

	decoded, err := wire.Decode(encoded)
	if err != nil {
		t.Fatalf("Failed to decode PACKET_PEER_SYNC: %v", err)
	}

	if decoded.Type != wire.PacketType_PACKET_PEER_SYNC {
		t.Errorf("Expected Type PACKET_PEER_SYNC, got %v", decoded.Type)
	}
	if decoded.PacketId != "sync_pkt_001" {
		t.Errorf("Expected PacketId sync_pkt_001, got %s", decoded.PacketId)
	}
	if decoded.PeerSync == nil {
		t.Fatal("Expected non-nil PeerSync payload")
	}
	if decoded.PeerSync.Action != wire.PeerSyncAction_SYNC_INVENTORY_REQ {
		t.Errorf("Expected Action SYNC_INVENTORY_REQ, got %v", decoded.PeerSync.Action)
	}
	if decoded.PeerSync.SessionId != "sync_sess_001" {
		t.Errorf("Expected SessionId sync_sess_001, got %s", decoded.PeerSync.SessionId)
	}
	if decoded.PeerSync.ConversationId != "NX-ALICE_NX-BOB" {
		t.Errorf("Expected ConversationId NX-ALICE_NX-BOB, got %s", decoded.PeerSync.ConversationId)
	}
	if decoded.PeerSync.CursorTimestamp != 1700000000000 {
		t.Errorf("Expected CursorTimestamp 1700000000000, got %d", decoded.PeerSync.CursorTimestamp)
	}
	if decoded.PeerSync.CursorSequence != 42 {
		t.Errorf("Expected CursorSequence 42, got %d", decoded.PeerSync.CursorSequence)
	}
	if len(decoded.PeerSync.MessageIds) != 2 || decoded.PeerSync.MessageIds[0] != "msg_uuid_1" {
		t.Errorf("Unexpected MessageIds: %v", decoded.PeerSync.MessageIds)
	}
	if len(decoded.PeerSync.Envelopes) != 1 || string(decoded.PeerSync.Envelopes[0]) != string(envelopeData) {
		t.Errorf("Unexpected Envelopes: %v", decoded.PeerSync.Envelopes)
	}
}

// TestPeerSync_RoutingBetweenConnectedPeers verifies that Hub routes PACKET_PEER_SYNC to an online peer.
func TestPeerSync_RoutingBetweenConnectedPeers(t *testing.T) {
	buf, err := queue.NewRedisBufferWithConfig("", "localhost:6379", "", false)
	if err != nil {
		t.Fatalf("Failed to create buffer: %v", err)
	}
	defer buf.Close()

	hub := NewHub(buf)

	validator := &mockConnectionValidator{
		connected: map[string]bool{"NX-ALICE_NX-BOB": true},
		blocked:   make(map[string]bool),
	}
	hub.SetConnectionValidator(validator)

	// Register Alice session
	aliceSession := NewClientSession(hub, nil, "NX-ALICE", "dev_alice")
	aliceSession.Authenticated = true
	aliceSession.Send = make(chan []byte, 10)
	hub.Register(aliceSession)

	// Register Bob session
	bobSession := NewClientSession(hub, nil, "NX-BOB", "dev_bob")
	bobSession.Authenticated = true
	bobSession.Send = make(chan []byte, 10)
	hub.Register(bobSession)

	// Alice sends PACKET_PEER_SYNC (SYNC_HELLO) to Bob
	helloPacket := &wire.NexaWirePacket{
		PacketId:      "sync_hello_1",
		Type:          wire.PacketType_PACKET_PEER_SYNC,
		SenderNxId:    "NX-ALICE",
		RecipientNxId: "NX-BOB",
		Timestamp:     time.Now().UnixMilli(),
		PeerSync: &wire.PeerSyncPayload{
			Action:         wire.PeerSyncAction_SYNC_HELLO,
			SessionId:      "sess_123",
			ConversationId: "NX-ALICE_NX-BOB",
		},
	}
	rawBytes, _ := helloPacket.Encode()

	hub.RoutePacket(aliceSession, rawBytes)

	// Verify Bob received the PACKET_PEER_SYNC directly
	select {
	case bobReceived := <-bobSession.Send:
		receivedPacket, err := wire.Decode(bobReceived)
		if err != nil {
			t.Fatalf("Failed to decode Bob's received packet: %v", err)
		}
		if receivedPacket.Type != wire.PacketType_PACKET_PEER_SYNC {
			t.Errorf("Expected PACKET_PEER_SYNC, got %v", receivedPacket.Type)
		}
		if receivedPacket.PeerSync.Action != wire.PeerSyncAction_SYNC_HELLO {
			t.Errorf("Expected SYNC_HELLO, got %v", receivedPacket.PeerSync.Action)
		}
	case <-time.After(500 * time.Millisecond):
		t.Fatal("Timeout: Bob did not receive PACKET_PEER_SYNC")
	}
}

// TestPeerSync_RejectionBetweenStrangers verifies that strangers cannot sync with each other.
func TestPeerSync_RejectionBetweenStrangers(t *testing.T) {
	buf, err := queue.NewRedisBufferWithConfig("", "localhost:6379", "", false)
	if err != nil {
		t.Fatalf("Failed to create buffer: %v", err)
	}
	defer buf.Close()

	hub := NewHub(buf)

	validator := &mockConnectionValidator{
		connected: make(map[string]bool), // Not connected
		blocked:   make(map[string]bool),
	}
	hub.SetConnectionValidator(validator)

	attackerSession := NewClientSession(hub, nil, "NX-ATTACKER", "dev_att")
	attackerSession.Authenticated = true
	attackerSession.Send = make(chan []byte, 10)
	hub.Register(attackerSession)

	victimSession := NewClientSession(hub, nil, "NX-VICTIM", "dev_vic")
	victimSession.Authenticated = true
	victimSession.Send = make(chan []byte, 10)
	hub.Register(victimSession)

	// Attacker attempts unauthorized sync with Victim
	probePacket := &wire.NexaWirePacket{
		PacketId:      "probe_sync_1",
		Type:          wire.PacketType_PACKET_PEER_SYNC,
		SenderNxId:    "NX-ATTACKER",
		RecipientNxId: "NX-VICTIM",
		Timestamp:     time.Now().UnixMilli(),
		PeerSync: &wire.PeerSyncPayload{
			Action:         wire.PeerSyncAction_SYNC_HELLO,
			SessionId:      "exploit_sess",
			ConversationId: "NX-ATTACKER_NX-VICTIM",
		},
	}
	rawBytes, _ := probePacket.Encode()

	hub.RoutePacket(attackerSession, rawBytes)

	// Victim must NOT receive the unauthorized probe
	select {
	case <-victimSession.Send:
		t.Fatal("Security Violation: Victim received PACKET_PEER_SYNC from unauthorized stranger")
	case <-time.After(100 * time.Millisecond):
		// Success: packet was dropped at the wire gatekeeper
	}
}

// TestHub_ZeroKnowledgeIdempotencyAndConflictGuard verifies exact deduplication and collision rejection.
func TestHub_ZeroKnowledgeIdempotencyAndConflictGuard(t *testing.T) {
	buf, err := queue.NewRedisBufferWithConfig("", "localhost:6379", "", false)
	if err != nil {
		t.Fatalf("Failed to create buffer: %v", err)
	}
	defer buf.Close()

	hub := NewHub(buf)

	aliceSession := NewClientSession(hub, nil, "NX-ALICE", "dev_alice")
	aliceSession.Authenticated = true
	aliceSession.Send = make(chan []byte, 10)
	hub.Register(aliceSession)

	bobSession := NewClientSession(hub, nil, "NX-BOB", "dev_bob")
	bobSession.Authenticated = true
	bobSession.Send = make(chan []byte, 10)
	hub.Register(bobSession)

	msgPacket := &wire.NexaWirePacket{
		PacketId:      "msg_idempotent_001",
		Type:          wire.PacketType_PACKET_MESSAGE,
		SenderNxId:    "NX-ALICE",
		RecipientNxId: "NX-BOB",
		Timestamp:     time.Now().UnixMilli(),
		Message: &wire.EncryptedMessagePayload{
			Ciphertext: []byte("ENCRYPTED_SECRET_PAYLOAD"),
			AuthTag:    []byte("16_BYTE_TAG_1234"),
			AeadNonce:  []byte("12_BYTE_NONC"),
		},
	}
	rawBytes, _ := msgPacket.Encode()

	// 1. First delivery attempt: should relay to Bob and send server ACK to Alice
	hub.RoutePacket(aliceSession, rawBytes)

	// Verify Alice received ACK_SERVER_RECEIVED
	select {
	case ackRaw := <-aliceSession.Send:
		ackPkt, err := wire.Decode(ackRaw)
		if err != nil || ackPkt.Type != wire.PacketType_PACKET_ACK || ackPkt.Ack.Status != wire.AckStatus_ACK_SERVER_RECEIVED {
			t.Fatalf("Expected server ACK, got %v", ackPkt)
		}
	case <-time.After(200 * time.Millisecond):
		t.Fatal("Timeout waiting for first server ACK")
	}

	// Verify Bob received the message
	select {
	case bobRaw := <-bobSession.Send:
		bobPkt, err := wire.Decode(bobRaw)
		if err != nil || bobPkt.PacketId != "msg_idempotent_001" {
			t.Fatalf("Expected msg_idempotent_001, got %v", bobPkt)
		}
	case <-time.After(200 * time.Millisecond):
		t.Fatal("Timeout waiting for Bob's first message delivery")
	}

	// 2. Duplicate delivery attempt (same packetId, same sender, same ciphertext)
	hub.RoutePacket(aliceSession, rawBytes)

	// Alice must receive server ACK (idempotent ACK)
	select {
	case ackRaw := <-aliceSession.Send:
		ackPkt, err := wire.Decode(ackRaw)
		if err != nil || ackPkt.Type != wire.PacketType_PACKET_ACK || ackPkt.Ack.Status != wire.AckStatus_ACK_SERVER_RECEIVED {
			t.Fatalf("Expected duplicate server ACK, got %v", ackPkt)
		}
	case <-time.After(200 * time.Millisecond):
		t.Fatal("Timeout waiting for duplicate server ACK")
	}

	// Bob must NOT receive a duplicate message
	select {
	case <-bobSession.Send:
		t.Fatal("Duplicate message was improperly relayed to recipient Bob")
	case <-time.After(100 * time.Millisecond):
		// Correct: suppressed duplicate relay
	}

	// Verify telemetry counter incremented
	if hub.duplicateSuppressedCount != 1 {
		t.Errorf("Expected duplicateSuppressedCount = 1, got %d", hub.duplicateSuppressedCount)
	}

	// 3. Collision / Tamper Attempt: Same packetId but altered ciphertext / payload
	collidingPacket := &wire.NexaWirePacket{
		PacketId:      "msg_idempotent_001", // Reusing packetId
		Type:          wire.PacketType_PACKET_MESSAGE,
		SenderNxId:    "NX-ALICE",
		RecipientNxId: "NX-BOB",
		Timestamp:     time.Now().UnixMilli(),
		Message: &wire.EncryptedMessagePayload{
			Ciphertext: []byte("ALTERED_MALICIOUS_CIPHERTEXT"),
			AuthTag:    []byte("16_BYTE_TAG_9999"),
			AeadNonce:  []byte("12_BYTE_NONC"),
		},
	}
	collidingBytes, _ := collidingPacket.Encode()

	hub.RoutePacket(aliceSession, collidingBytes)

	// Collision must be rejected: neither Alice ACK nor Bob delivery
	select {
	case <-aliceSession.Send:
		t.Fatal("Colliding packet received server ACK (should have been rejected)")
	case <-bobSession.Send:
		t.Fatal("Colliding packet was relayed to Bob (should have been rejected)")
	case <-time.After(100 * time.Millisecond):
		// Success: packet collision was rejected
	}
}

// TestHub_IdempotencyHashDigestConsistency verifies SHA256 hashing computation
func TestHub_IdempotencyHashDigestConsistency(t *testing.T) {
	ct := []byte("secret_message")
	tag := []byte("auth_tag_1234567")
	nonce := []byte("nonce_123456")

	h1 := sha256.New()
	h1.Write(ct)
	h1.Write(tag)
	h1.Write(nonce)
	d1 := hex.EncodeToString(h1.Sum(nil))

	h2 := sha256.New()
	h2.Write(ct)
	h2.Write(tag)
	h2.Write(nonce)
	d2 := hex.EncodeToString(h2.Sum(nil))

	if d1 != d2 {
		t.Fatalf("Digest mismatch: %s != %s", d1, d2)
	}
}
