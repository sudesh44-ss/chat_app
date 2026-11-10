package connection

import (
	"bytes"
	"context"
	"testing"
	"time"

	"nexa/wire/internal/queue"
	"nexa/wire/internal/wire"
)

func TestCloudQueueAcceptance_DoesNotImplyDelivery(t *testing.T) {
	buf, err := queue.NewRedisBufferWithConfig("", "localhost:6379", "", false)
	if err != nil {
		t.Fatalf("Failed to create RedisBuffer: %v", err)
	}
	defer buf.Close()

	hub := NewHub(buf)

	sender := NewClientSession(hub, nil, "NX-ALICE", "dev_alice")
	sender.Authenticated = true
	sender.Send = make(chan []byte, 10)
	hub.Register(sender)

	msgPacket := &wire.NexaWirePacket{
		PacketId:      "msg_cloud_queue_test_001",
		Type:          wire.PacketType_PACKET_MESSAGE,
		SenderNxId:    "NX-ALICE",
		RecipientNxId: "NX-BOB",
		Timestamp:     time.Now().UnixMilli(),
		Message: &wire.EncryptedMessagePayload{
			Ciphertext: []byte("cipher_secret"),
			AuthTag:    bytes.Repeat([]byte{0x01}, 16),
			AeadNonce:  bytes.Repeat([]byte{0x02}, 12),
		},
	}
	rawMsg, _ := msgPacket.Encode()
	hub.RoutePacket(sender, rawMsg)

	// Verify Alice received ACK_SERVER_RECEIVED (QUEUED state), NOT ACK_DEVICE_DELIVERED
	select {
	case ackRaw := <-sender.Send:
		ackPkt, err := wire.Decode(ackRaw)
		if err != nil {
			t.Fatalf("Failed to decode ack: %v", err)
		}
		if ackPkt.Type != wire.PacketType_PACKET_ACK {
			t.Fatalf("Expected PACKET_ACK, got %v", ackPkt.Type)
		}
		if ackPkt.Ack.Status != wire.AckStatus_ACK_SERVER_RECEIVED {
			t.Fatalf("Expected ACK_SERVER_RECEIVED, got %v (Server acceptance must NOT imply delivery!)", ackPkt.Ack.Status)
		}
	case <-time.After(500 * time.Millisecond):
		t.Fatal("Timeout waiting for server ACK")
	}

	// Verify message remains queued in Bob's buffer
	ctx := context.Background()
	pending, err := buf.ClaimPending(ctx, "NX-BOB", 10, 30*time.Second)
	if err != nil || len(pending) == 0 {
		t.Fatalf("Message was not queued in Bob's offline buffer: len=%d, err=%v", len(pending), err)
	}
}

func TestCustodyAck_DoesNotImplyDelivery(t *testing.T) {
	buf, err := queue.NewRedisBufferWithConfig("", "localhost:6379", "", false)
	if err != nil {
		t.Fatalf("Failed to create RedisBuffer: %v", err)
	}
	defer buf.Close()

	hub := NewHub(buf)

	// Pre-populate message in Bob's offline queue
	ctx := context.Background()
	_ = buf.Enqueue(ctx, "NX-BOB", "msg_dtn_relayed_001", []byte("raw_msg_data"), 0)

	alice := NewClientSession(hub, nil, "NX-ALICE", "dev_alice")
	alice.Authenticated = true
	alice.Send = make(chan []byte, 10)
	hub.Register(alice)

	relaySession := NewClientSession(hub, nil, "NX-RELAY-1", "dev_relay")
	relaySession.Authenticated = true
	relaySession.Send = make(chan []byte, 10)
	hub.Register(relaySession)

	// Relay sends a PACKET_CUSTODY_ACK back to Alice
	custodyPacket := &wire.NexaWirePacket{
		PacketId:      "custody_ack_rel1",
		Type:          wire.PacketType_PACKET_CUSTODY_ACK,
		SenderNxId:    "NX-RELAY-1",
		RecipientNxId: "NX-ALICE",
		Timestamp:     time.Now().UnixMilli(),
		CustodyAck: &wire.CustodyAckPayload{
			OriginalPacketId: "msg_dtn_relayed_001",
			CustodianNxId:    "NX-RELAY-1",
			Status:           wire.CustodyStatus_CUSTODY_ACCEPTED,
			Timestamp:        time.Now().UnixMilli(),
			Signature:        bytes.Repeat([]byte{0x77}, 64),
		},
	}
	rawCustody, _ := custodyPacket.Encode()
	hub.RoutePacket(relaySession, rawCustody)

	// Alice receives the Custody ACK via RAM routing
	select {
	case ackRaw := <-alice.Send:
		ackPkt, err := wire.Decode(ackRaw)
		if err != nil {
			t.Fatalf("Failed to decode custody ack: %v", err)
		}
		if ackPkt.Type != wire.PacketType_PACKET_CUSTODY_ACK {
			t.Fatalf("Expected PACKET_CUSTODY_ACK, got %v", ackPkt.Type)
		}
		if ackPkt.CustodyAck.Status != wire.CustodyStatus_CUSTODY_ACCEPTED {
			t.Fatalf("Expected CUSTODY_ACCEPTED, got %v", ackPkt.CustodyAck.Status)
		}
	case <-time.After(500 * time.Millisecond):
		t.Fatal("Timeout waiting for Alice to receive custody ACK")
	}

	// Crucial check: Bob's offline queue MUST NOT be deleted by a relay custody ACK!
	pending, err := buf.ClaimPending(ctx, "NX-BOB", 10, 30*time.Second)
	if err != nil || len(pending) == 0 {
		t.Fatalf("SECURITY VIOLATION: Bob's message was prematurely cleared by relay custody ACK! len=%d", len(pending))
	}
}

func TestValidRecipientDeliveryAck_ReconcilesQueue(t *testing.T) {
	buf, err := queue.NewRedisBufferWithConfig("", "localhost:6379", "", false)
	if err != nil {
		t.Fatalf("Failed to create RedisBuffer: %v", err)
	}
	defer buf.Close()

	hub := NewHub(buf)

	alice := NewClientSession(hub, nil, "NX-ALICE", "dev_alice")
	alice.Authenticated = true
	alice.Send = make(chan []byte, 10)
	hub.Register(alice)

	bob := NewClientSession(hub, nil, "NX-BOB", "dev_bob")
	bob.Authenticated = true
	bob.Send = make(chan []byte, 10)
	hub.Register(bob)

	// Enqueue message for Bob
	ctx := context.Background()
	_ = buf.Enqueue(ctx, "NX-BOB", "msg_e2e_reconcile_001", []byte("raw_msg_data"), 0)

	// Bob sends valid authenticated Delivery ACK
	deliveryAck := &wire.NexaWirePacket{
		PacketId:      "ack_delivery_valid_001",
		Type:          wire.PacketType_PACKET_ACK,
		SenderNxId:    "NX-BOB",
		RecipientNxId: "NX-ALICE",
		Timestamp:     time.Now().UnixMilli(),
		Ack: &wire.DeliveryAckPayload{
			OriginalPacketId: "msg_e2e_reconcile_001",
			Status:           wire.AckStatus_ACK_DEVICE_DELIVERED,
			RecipientNxId:    "NX-BOB",
			DeviceId:         "dev_bob",
			Timestamp:        time.Now().UnixMilli(),
			Signature:        bytes.Repeat([]byte{0xAA}, 64),
		},
	}
	rawAck, _ := deliveryAck.Encode()
	hub.RoutePacket(bob, rawAck)

	// Check Alice receives the Delivery ACK
	select {
	case ackRaw := <-alice.Send:
		ackPkt, err := wire.Decode(ackRaw)
		if err != nil {
			t.Fatalf("Failed to decode delivery ack: %v", err)
		}
		if ackPkt.Type != wire.PacketType_PACKET_ACK || ackPkt.Ack.Status != wire.AckStatus_ACK_DEVICE_DELIVERED {
			t.Fatalf("Expected ACK_DEVICE_DELIVERED, got type=%v status=%v", ackPkt.Type, ackPkt.Ack.Status)
		}
	case <-time.After(500 * time.Millisecond):
		t.Fatal("Timeout waiting for Alice to receive Delivery ACK")
	}

	// Verify message was successfully reconciled (removed) from Bob's queue
	pending, _ := buf.ClaimPending(ctx, "NX-BOB", 10, 30*time.Second)
	if len(pending) != 0 {
		t.Fatalf("Expected message to be removed from Bob's queue, found %d entries", len(pending))
	}
}

func TestDeliveryAck_AntiSpoofingAndMismatchedRecipient(t *testing.T) {
	buf, err := queue.NewRedisBufferWithConfig("", "localhost:6379", "", false)
	if err != nil {
		t.Fatalf("Failed to create RedisBuffer: %v", err)
	}
	defer buf.Close()

	hub := NewHub(buf)

	// Enqueue message for Bob
	ctx := context.Background()
	_ = buf.Enqueue(ctx, "NX-BOB", "msg_protected_001", []byte("raw_msg_data"), 0)

	attacker := NewClientSession(hub, nil, "NX-ATTACKER", "dev_attacker")
	attacker.Authenticated = true
	attacker.Send = make(chan []byte, 10)
	hub.Register(attacker)

	// Attacker tries to forge Delivery ACK for Bob's message claiming RecipientNxId is NX-BOB
	forgedAck := &wire.NexaWirePacket{
		PacketId:      "ack_forged_001",
		Type:          wire.PacketType_PACKET_ACK,
		SenderNxId:    "NX-ATTACKER",
		RecipientNxId: "NX-ALICE",
		Timestamp:     time.Now().UnixMilli(),
		Ack: &wire.DeliveryAckPayload{
			OriginalPacketId: "msg_protected_001",
			Status:           wire.AckStatus_ACK_DEVICE_DELIVERED,
			RecipientNxId:    "NX-BOB", // Mismatch with session NX-ATTACKER
			DeviceId:         "dev_attacker",
			Timestamp:        time.Now().UnixMilli(),
		},
	}
	rawForged, _ := forgedAck.Encode()
	hub.RoutePacket(attacker, rawForged)

	// Bob's message MUST remain safely queued
	pending, err := buf.ClaimPending(ctx, "NX-BOB", 10, 30*time.Second)
	if err != nil || len(pending) == 0 {
		t.Fatalf("SECURITY ALERT: Forged ACK cleared Bob's message! len=%d", len(pending))
	}
}

func TestDeliveryAck_AckTypeConfusionRejected(t *testing.T) {
	buf, err := queue.NewRedisBufferWithConfig("", "localhost:6379", "", false)
	if err != nil {
		t.Fatalf("Failed to create RedisBuffer: %v", err)
	}
	defer buf.Close()

	hub := NewHub(buf)

	ctx := context.Background()
	_ = buf.Enqueue(ctx, "NX-BOB", "msg_confusion_test_001", []byte("raw_msg_data"), 0)

	bob := NewClientSession(hub, nil, "NX-BOB", "dev_bob")
	bob.Authenticated = true
	bob.Send = make(chan []byte, 10)
	hub.Register(bob)

	// Bob sends PACKET_CUSTODY_ACK instead of PACKET_ACK with OriginalPacketId
	confusionPacket := &wire.NexaWirePacket{
		PacketId:      "custody_confusion_001",
		Type:          wire.PacketType_PACKET_CUSTODY_ACK,
		SenderNxId:    "NX-BOB",
		RecipientNxId: "NX-ALICE",
		Timestamp:     time.Now().UnixMilli(),
		CustodyAck: &wire.CustodyAckPayload{
			OriginalPacketId: "msg_confusion_test_001",
			CustodianNxId:    "NX-BOB",
			Status:           wire.CustodyStatus_CUSTODY_ACCEPTED,
		},
	}
	rawConfusion, _ := confusionPacket.Encode()
	hub.RoutePacket(bob, rawConfusion)

	// Message MUST remain queued because PACKET_CUSTODY_ACK is not PACKET_ACK
	pending, _ := buf.ClaimPending(ctx, "NX-BOB", 10, 30*time.Second)
	if len(pending) == 0 {
		t.Fatal("Type confusion: PACKET_CUSTODY_ACK purged message from recipient offline queue!")
	}
}

func TestDeliveryAck_IdempotentDuplicateProcessing(t *testing.T) {
	buf, err := queue.NewRedisBufferWithConfig("", "localhost:6379", "", false)
	if err != nil {
		t.Fatalf("Failed to create RedisBuffer: %v", err)
	}
	defer buf.Close()

	hub := NewHub(buf)

	ctx := context.Background()
	_ = buf.Enqueue(ctx, "NX-BOB", "msg_idemp_ack_001", []byte("raw_msg_data"), 0)

	bob := NewClientSession(hub, nil, "NX-BOB", "dev_bob")
	bob.Authenticated = true
	bob.Send = make(chan []byte, 10)
	hub.Register(bob)

	ackPacket := &wire.NexaWirePacket{
		PacketId:      "ack_idemp_001",
		Type:          wire.PacketType_PACKET_ACK,
		SenderNxId:    "NX-BOB",
		RecipientNxId: "NX-ALICE",
		Timestamp:     time.Now().UnixMilli(),
		Ack: &wire.DeliveryAckPayload{
			OriginalPacketId: "msg_idemp_ack_001",
			Status:           wire.AckStatus_ACK_DEVICE_DELIVERED,
			RecipientNxId:    "NX-BOB",
		},
	}
	rawAck, _ := ackPacket.Encode()

	// First ACK reconciles queue
	hub.RoutePacket(bob, rawAck)

	// Second duplicate ACK must be idempotent and not cause panics or state corruption
	hub.RoutePacket(bob, rawAck)

	// Third duplicate ACK
	hub.RoutePacket(bob, rawAck)
}
