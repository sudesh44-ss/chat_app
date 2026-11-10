package connection

import (
	"context"
	"testing"
	"time"

	"nexa/wire/internal/queue"
	"nexa/wire/internal/wire"
)

func TestQueueReconciliation_EndToEndLifecycle(t *testing.T) {
	// 1. Initialize Buffer and Hub
	buf, err := queue.NewRedisBufferWithConfig("", "localhost:6379", "", false)
	if err != nil {
		t.Fatalf("Failed to create RedisBuffer: %v", err)
	}
	defer buf.Close()

	hub := NewHub(buf)

	// Sender is connected & authenticated
	sender := NewClientSession(hub, nil, "NX-ALICE", "dev_alice")
	sender.Authenticated = true
	sender.Send = make(chan []byte, 10)
	hub.Register(sender)

	// Recipient NX-BOB is OFFLINE initially
	msgPacket := &wire.NexaWirePacket{
		PacketId:      "msg_alice_to_bob_001",
		Type:          wire.PacketType_PACKET_MESSAGE,
		SenderNxId:    "NX-ALICE",
		RecipientNxId: "NX-BOB",
		Timestamp:     time.Now().UnixMilli(),
		Message: &wire.EncryptedMessagePayload{
			ConversationSeq: 1,
			Ciphertext:      []byte("encrypted_e2ee_payload_data"),
			Nonce:           []byte("nonce_12_bytes"),
			AuthTag:         []byte("tag_16_bytes_"),
		},
	}
	rawMsg, err := msgPacket.Encode()
	if err != nil {
		t.Fatalf("Failed to encode message packet: %v", err)
	}

	// 2. Alice sends message while Bob is offline
	hub.RoutePacket(sender, rawMsg)

	// Verify Alice received ACK_SERVER_RECEIVED immediately
	select {
	case ackRaw := <-sender.Send:
		ackPkt, err := wire.Decode(ackRaw)
		if err != nil {
			t.Fatalf("Failed to decode server ack: %v", err)
		}
		if ackPkt.Type != wire.PacketType_PACKET_ACK || ackPkt.Ack.Status != wire.AckStatus_ACK_SERVER_RECEIVED {
			t.Fatalf("Expected ACK_SERVER_RECEIVED, got type=%v status=%v", ackPkt.Type, ackPkt.Ack.Status)
		}
	default:
		t.Fatalf("Expected immediate ACK_SERVER_RECEIVED in Alice's Send channel")
	}

	// Verify message is queued in Bob's pending queue
	pending, inFlight, err := buf.GetQueueMetrics(context.Background(), "NX-BOB")
	if err != nil {
		t.Fatalf("GetQueueMetrics failed: %v", err)
	}
	if pending != 1 || inFlight != 0 {
		t.Fatalf("Expected 1 pending and 0 in-flight for NX-BOB, got pending=%d inFlight=%d", pending, inFlight)
	}

	// 3. Bob comes online: Connects and registers session
	bob := NewClientSession(hub, nil, "NX-BOB", "dev_bob_phone")
	bob.Authenticated = true
	bob.Send = make(chan []byte, 10)

	// Register flushes pending queue in goroutine
	hub.Register(bob)

	// Wait for Bob's session to receive the queued message
	var receivedRaw []byte
	select {
	case receivedRaw = <-bob.Send:
	case <-time.After(500 * time.Millisecond):
		t.Fatalf("Timeout waiting for Bob to receive flushed message")
	}

	receivedPkt, err := wire.Decode(receivedRaw)
	if err != nil {
		t.Fatalf("Failed to decode Bob's received packet: %v", err)
	}
	if receivedPkt.PacketId != "msg_alice_to_bob_001" {
		t.Fatalf("Expected msg_alice_to_bob_001, got %s", receivedPkt.PacketId)
	}

	// Verify message is now IN_FLIGHT, NOT deleted from Redis!
	pending, inFlight, err = buf.GetQueueMetrics(context.Background(), "NX-BOB")
	if err != nil {
		t.Fatalf("GetQueueMetrics failed: %v", err)
	}
	if pending != 0 || inFlight != 1 {
		t.Fatalf("Message must remain IN_FLIGHT prior to ACK! Got pending=%d inFlight=%d", pending, inFlight)
	}

	// 4. Bob decrypts and transmits PACKET_ACK with ACK_DEVICE_DELIVERED
	deliveryAck := &wire.NexaWirePacket{
		PacketId:      "ack_bob_001",
		Type:          wire.PacketType_PACKET_ACK,
		SenderNxId:    "NX-BOB",
		RecipientNxId: "NX-ALICE",
		Timestamp:     time.Now().UnixMilli(),
		Ack: &wire.DeliveryAckPayload{
			OriginalPacketId: "msg_alice_to_bob_001",
			Status:           wire.AckStatus_ACK_DEVICE_DELIVERED,
		},
	}
	rawAck, err := deliveryAck.Encode()
	if err != nil {
		t.Fatalf("Failed to encode delivery ack: %v", err)
	}

	// Route Bob's delivery ACK through Hub
	hub.RoutePacket(bob, rawAck)

	// 5. Verify message is now PERMANENTLY REMOVED from Bob's offline queue
	pending, inFlight, err = buf.GetQueueMetrics(context.Background(), "NX-BOB")
	if err != nil {
		t.Fatalf("GetQueueMetrics failed: %v", err)
	}
	if pending != 0 || inFlight != 0 {
		t.Fatalf("Expected queue to be empty after delivery ACK! Got pending=%d inFlight=%d", pending, inFlight)
	}

	// Verify Alice received the forwarded ACK_DEVICE_DELIVERED
	select {
	case forwardedAckRaw := <-sender.Send:
		forwardedPkt, err := wire.Decode(forwardedAckRaw)
		if err != nil {
			t.Fatalf("Failed to decode forwarded ack: %v", err)
		}
		if forwardedPkt.Ack.Status != wire.AckStatus_ACK_DEVICE_DELIVERED {
			t.Fatalf("Expected forwarded ACK_DEVICE_DELIVERED to Alice, got %v", forwardedPkt.Ack.Status)
		}
	default:
		t.Fatalf("Expected forwarded delivery ACK in Alice's Send channel")
	}

	// Check telemetry metrics
	metrics := hub.GetTelemetryMetrics()
	if metrics["acknowledged_delivery_count"].(uint64) < 1 {
		t.Fatalf("Expected acknowledged_delivery_count >= 1, got %v", metrics["acknowledged_delivery_count"])
	}
}

func TestQueueReconciliation_SocketWriteFailure_RevertsInFlight(t *testing.T) {
	buf, _ := queue.NewRedisBufferWithConfig("", "localhost:6379", "", false)
	defer buf.Close()

	hub := NewHub(buf)

	// Pre-queue a message for Charlie
	ctx := context.Background()
	msg := &wire.NexaWirePacket{
		PacketId:      "msg_revert_001",
		Type:          wire.PacketType_PACKET_MESSAGE,
		SenderNxId:    "NX-ALICE",
		RecipientNxId: "NX-CHARLIE",
		Timestamp:     time.Now().UnixMilli(),
	}
	raw, _ := msg.Encode()
	_ = buf.Enqueue(ctx, "NX-CHARLIE", "msg_revert_001", raw, 0)

	// Charlie connects with a blocked/zero-capacity channel to simulate socket write failure
	charlie := NewClientSession(hub, nil, "NX-CHARLIE", "dev_charlie")
	charlie.Authenticated = true
	charlie.Send = make(chan []byte, 1)
	charlie.Send <- []byte("already_full") // fill buffer so next write fails

	// Call flush synchronously
	hub.flushPendingQueue("NX-CHARLIE", charlie)

	// Since SafeSend failed, message must be REVERTED to pending
	pending, inFlight, err := buf.GetQueueMetrics(ctx, "NX-CHARLIE")
	if err != nil {
		t.Fatalf("GetQueueMetrics failed: %v", err)
	}
	if pending != 1 || inFlight != 0 {
		t.Fatalf("Expected failed send to revert to pending! Got pending=%d, inFlight=%d", pending, inFlight)
	}

	metrics := hub.GetTelemetryMetrics()
	if metrics["reverted_delivery_count"].(uint64) < 1 {
		t.Fatalf("Expected reverted_delivery_count >= 1, got %v", metrics["reverted_delivery_count"])
	}
}

func TestQueueReconciliation_AntiSpoofingRejection(t *testing.T) {
	buf, _ := queue.NewRedisBufferWithConfig("", "localhost:6379", "", false)
	defer buf.Close()

	hub := NewHub(buf)
	ctx := context.Background()

	// Legitimate message queued for Bob
	msg := &wire.NexaWirePacket{
		PacketId:      "msg_bob_secret_001",
		Type:          wire.PacketType_PACKET_MESSAGE,
		SenderNxId:    "NX-ALICE",
		RecipientNxId: "NX-BOB",
		Timestamp:     time.Now().UnixMilli(),
	}
	raw, _ := msg.Encode()
	_ = buf.Enqueue(ctx, "NX-BOB", "msg_bob_secret_001", raw, 0)

	// Move to IN_FLIGHT
	claimed, _ := buf.ClaimPending(ctx, "NX-BOB", 10, 30*time.Second)
	if len(claimed) != 1 {
		t.Fatalf("Failed to claim message for test setup")
	}

	// Attacker tries to send an ACK claiming to acknowledge Bob's message
	attacker := NewClientSession(hub, nil, "NX-ATTACKER", "dev_rogue")
	attacker.Authenticated = true
	attacker.Send = make(chan []byte, 10)
	hub.Register(attacker)

	spoofedAck := &wire.NexaWirePacket{
		PacketId:      "ack_spoof_001",
		Type:          wire.PacketType_PACKET_ACK,
		SenderNxId:    "NX-BOB", // Attacker claims sender is NX-BOB
		RecipientNxId: "NX-ALICE",
		Timestamp:     time.Now().UnixMilli(),
		Ack: &wire.DeliveryAckPayload{
			OriginalPacketId: "msg_bob_secret_001",
			Status:           wire.AckStatus_ACK_DEVICE_DELIVERED,
		},
	}
	rawSpoofed, _ := spoofedAck.Encode()

	// Attacker's session.NxId is NX-ATTACKER, while packet.SenderNxId is NX-BOB
	hub.RoutePacket(attacker, rawSpoofed)

	// Bob's message must NOT be removed by attacker's spoofed ACK!
	_, inFlight, err := buf.GetQueueMetrics(ctx, "NX-BOB")
	if err != nil {
		t.Fatalf("GetQueueMetrics failed: %v", err)
	}
	if inFlight != 1 {
		t.Fatalf("Security failure: spoofed ACK removed message from queue! inFlight=%d", inFlight)
	}
}

func TestQueueReconciliation_MultiDeviceDedup(t *testing.T) {
	buf, _ := queue.NewRedisBufferWithConfig("", "localhost:6379", "", false)
	defer buf.Close()

	hub := NewHub(buf)
	ctx := context.Background()

	// Alice sends message to Bob while all Bob's devices are offline
	msg := &wire.NexaWirePacket{
		PacketId:      "msg_multi_dev_001",
		Type:          wire.PacketType_PACKET_MESSAGE,
		SenderNxId:    "NX-ALICE",
		RecipientNxId: "NX-BOB",
		Timestamp:     time.Now().UnixMilli(),
	}
	rawMsg, _ := msg.Encode()

	sender := NewClientSession(hub, nil, "NX-ALICE", "dev_alice")
	sender.Authenticated = true
	sender.Send = make(chan []byte, 10)
	hub.Register(sender)

	hub.RoutePacket(sender, rawMsg)

	// Bob's phone connects first
	bobPhone := NewClientSession(hub, nil, "NX-BOB", "dev_bob_phone")
	bobPhone.Authenticated = true
	bobPhone.Send = make(chan []byte, 10)
	hub.Register(bobPhone)

	// Wait for phone to receive message
	select {
	case delivered := <-bobPhone.Send:
		p, _ := wire.Decode(delivered)
		if p.PacketId != "msg_multi_dev_001" {
			t.Fatalf("Expected msg_multi_dev_001, got %s", p.PacketId)
		}
	case <-time.After(500 * time.Millisecond):
		t.Fatalf("Timeout waiting for phone to receive message")
	}

	// Phone sends ACK_DEVICE_DELIVERED
	ack := &wire.NexaWirePacket{
		PacketId:      "ack_phone_001",
		Type:          wire.PacketType_PACKET_ACK,
		SenderNxId:    "NX-BOB",
		RecipientNxId: "NX-ALICE",
		Timestamp:     time.Now().UnixMilli(),
		Ack: &wire.DeliveryAckPayload{
			OriginalPacketId: "msg_multi_dev_001",
			Status:           wire.AckStatus_ACK_DEVICE_DELIVERED,
		},
	}
	rawAck, _ := ack.Encode()
	hub.RoutePacket(bobPhone, rawAck)

	// Queue must now be empty
	pending, inFlight, _ := buf.GetQueueMetrics(ctx, "NX-BOB")
	if pending != 0 || inFlight != 0 {
		t.Fatalf("Queue not empty after phone ACK: pending=%d, inFlight=%d", pending, inFlight)
	}

	// Later, Bob's tablet connects
	bobTablet := NewClientSession(hub, nil, "NX-BOB", "dev_bob_tablet")
	bobTablet.Authenticated = true
	bobTablet.Send = make(chan []byte, 10)
	hub.Register(bobTablet)

	// Tablet MUST NOT receive a duplicate copy from offline queue
	select {
	case dup := <-bobTablet.Send:
		t.Fatalf("Tablet received duplicate message: %v", string(dup))
	case <-time.After(100 * time.Millisecond):
		// Clean pass: no duplicate delivered
	}
}

func TestQueueReconciliation_DtnEnvelopeDeliveryAndAck(t *testing.T) {
	buf, _ := queue.NewRedisBufferWithConfig("", "localhost:6379", "", false)
	defer buf.Close()

	hub := NewHub(buf)
	ctx := context.Background()

	tokenHex := "abcdef0123456789abcdef0123456789"
	dtnKey := "dtn_" + tokenHex

	// Build a valid NXDT envelope header (at least 36 bytes)
	// byte 0..3: "NXDT"
	// byte 20..35: 16-byte message UUID
	envelope := make([]byte, 100)
	copy(envelope[0:4], []byte("NXDT"))
	copy(envelope[20:36], []byte("1234567890abcdef")) // 16 bytes msg UUID

	msgID := queue.ExtractMessageID(envelope)
	if msgID != "31323334353637383930616263646566" {
		t.Fatalf("Unexpected msgID: %s", msgID)
	}

	// Route DTN envelope while recipient is offline
	hub.RouteDtnEnvelope(tokenHex, envelope)

	// Verify DTN envelope is queued under dtn_<token>
	pending, inFlight, _ := buf.GetQueueMetrics(ctx, dtnKey)
	if pending != 1 || inFlight != 0 {
		t.Fatalf("Expected 1 pending DTN envelope, got pending=%d inFlight=%d", pending, inFlight)
	}

	// Recipient connects and registers the routing token
	bob := NewClientSession(hub, nil, "NX-BOB", "dev_bob")
	bob.Authenticated = true
	bob.Send = make(chan []byte, 10)
	hub.Register(bob)
	hub.RegisterRoutingTokens([]string{tokenHex}, bob)

	// Wait for recipient to receive DTN envelope
	select {
	case delivered := <-bob.Send:
		if len(delivered) < 4 || string(delivered[0:4]) != "NXDT" {
			t.Fatalf("Delivered payload is not NXDT envelope")
		}
	case <-time.After(500 * time.Millisecond):
		t.Fatalf("Timeout waiting for Bob to receive DTN envelope")
	}

	// In-flight check
	pending, inFlight, _ = buf.GetQueueMetrics(ctx, dtnKey)
	if inFlight != 1 {
		t.Fatalf("Expected DTN envelope to be in-flight, got inFlight=%d", inFlight)
	}

	// Bob acknowledges delivery with ACK_DEVICE_DELIVERED
	ack := &wire.NexaWirePacket{
		PacketId:      "ack_dtn_001",
		Type:          wire.PacketType_PACKET_ACK,
		SenderNxId:    "NX-BOB",
		RecipientNxId: "SERVER",
		Timestamp:     time.Now().UnixMilli(),
		Ack: &wire.DeliveryAckPayload{
			OriginalPacketId: msgID,
			Status:           wire.AckStatus_ACK_DEVICE_DELIVERED,
		},
	}
	rawAck, _ := ack.Encode()
	hub.RoutePacket(bob, rawAck)

	// Envelope must now be removed from DTN queue
	pending, inFlight, _ = buf.GetQueueMetrics(ctx, dtnKey)
	if pending != 0 || inFlight != 0 {
		t.Fatalf("Expected DTN queue to be empty after ACK, got pending=%d inFlight=%d", pending, inFlight)
	}
}
