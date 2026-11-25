package connection

import (
	"context"
	"fmt"
	"testing"
	"time"

	"nexa/wire/internal/queue"
	"nexa/wire/internal/wire"
)

func TestGroupMessageFanOut_OnlineAndOffline(t *testing.T) {
	buffer, _ := queue.NewRedisBufferWithConfig("", "localhost:6379", "", false)
	defer buffer.Close()
	hub := NewHub(buffer)
	groupProvider := NewInMemoryGroupProvider()
	hub.SetGroupProvider(groupProvider)

	ctx := context.Background()
	groupId := "GRP-FAMILY-100"
	_ = groupProvider.AddMember(ctx, groupId, "NX-ALICE", "ADMIN")
	_ = groupProvider.AddMember(ctx, groupId, "NX-BOB", "MEMBER")
	_ = groupProvider.AddMember(ctx, groupId, "NX-CHARLIE", "MEMBER")

	// Alice and Bob are online; Charlie is offline
	aliceSession := &ClientSession{
		Hub:           hub,
		NxId:          "NX-ALICE",
		DeviceId:      "dev_alice",
		Send:          make(chan []byte, 10),
		Authenticated: true,
	}
	bobSession := &ClientSession{
		Hub:           hub,
		NxId:          "NX-BOB",
		DeviceId:      "dev_bob",
		Send:          make(chan []byte, 10),
		Authenticated: true,
	}

	hub.Register(aliceSession)
	hub.Register(bobSession)

	// Alice sends group message
	msgPacket := &wire.NexaWirePacket{
		PacketId:      "msg_grp_001",
		Type:          wire.PacketType_PACKET_MESSAGE,
		SenderNxId:    "NX-ALICE",
		RecipientNxId: groupId,
		GroupId:       groupId,
		Timestamp:     time.Now().UnixMilli(),
		Message: &wire.EncryptedMessagePayload{
			Ciphertext: []byte("group ciphertext"),
		},
	}
	raw, err := msgPacket.Encode()
	if err != nil {
		t.Fatalf("failed to encode packet: %v", err)
	}

	hub.RouteDecodedPacket(aliceSession, msgPacket, raw)

	// Verify Alice received ACK_SERVER_RECEIVED
	select {
	case ackRaw := <-aliceSession.Send:
		ackPacket, err := wire.Decode(ackRaw)
		if err != nil {
			t.Fatalf("failed to decode alice ack: %v", err)
		}
		if ackPacket.Type != wire.PacketType_PACKET_ACK || ackPacket.Ack.Status != wire.AckStatus_ACK_SERVER_RECEIVED {
			t.Fatalf("expected ACK_SERVER_RECEIVED for alice, got %v", ackPacket.Ack.Status)
		}
	case <-time.After(time.Second):
		t.Fatal("timed out waiting for alice server ack")
	}

	// Verify Bob received the group message directly in memory
	select {
	case bobRaw := <-bobSession.Send:
		bobPacket, err := wire.Decode(bobRaw)
		if err != nil {
			t.Fatalf("failed to decode bob packet: %v", err)
		}
		if bobPacket.PacketId != "msg_grp_001" || bobPacket.GroupId != groupId {
			t.Fatalf("unexpected packet received by bob: %+v", bobPacket)
		}
	case <-time.After(time.Second):
		t.Fatal("timed out waiting for bob group packet")
	}

	// Verify Charlie (offline) has message buffered in queue
	charlieClaimed, err := buffer.ClaimPending(ctx, "NX-CHARLIE", 10, 10*time.Second)
	if err != nil {
		t.Fatalf("failed to claim charlie pending queue: %v", err)
	}
	if len(charlieClaimed) != 1 {
		t.Fatalf("expected 1 buffered message for charlie, got %d", len(charlieClaimed))
	}
	charliePacket, err := wire.Decode(charlieClaimed[0].Payload)
	if err != nil {
		t.Fatalf("failed to decode charlie payload: %v", err)
	}
	if charliePacket.PacketId != "msg_grp_001" {
		t.Fatalf("expected packet msg_grp_001 for charlie, got %s", charliePacket.PacketId)
	}
}

func TestGroupMembershipAuthorization_NonMemberRejected(t *testing.T) {
	buffer, _ := queue.NewRedisBufferWithConfig("", "localhost:6379", "", false)
	defer buffer.Close()
	hub := NewHub(buffer)
	groupProvider := NewInMemoryGroupProvider()
	hub.SetGroupProvider(groupProvider)

	ctx := context.Background()
	groupId := "GRP-SECRET-999"
	_ = groupProvider.AddMember(ctx, groupId, "NX-ALICE", "ADMIN")
	_ = groupProvider.AddMember(ctx, groupId, "NX-BOB", "MEMBER")

	// Attacker Eve is online but not a member
	eveSession := &ClientSession{
		Hub:           hub,
		NxId:          "NX-EVE",
		DeviceId:      "dev_eve",
		Send:          make(chan []byte, 10),
		Authenticated: true,
	}
	bobSession := &ClientSession{
		Hub:           hub,
		NxId:          "NX-BOB",
		DeviceId:      "dev_bob",
		Send:          make(chan []byte, 10),
		Authenticated: true,
	}

	hub.Register(eveSession)
	hub.Register(bobSession)

	msgPacket := &wire.NexaWirePacket{
		PacketId:      "msg_eve_hack",
		Type:          wire.PacketType_PACKET_MESSAGE,
		SenderNxId:    "NX-EVE",
		RecipientNxId: groupId,
		GroupId:       groupId,
		Timestamp:     time.Now().UnixMilli(),
		Message: &wire.EncryptedMessagePayload{
			Ciphertext: []byte("unauthorized text"),
		},
	}
	raw, _ := msgPacket.Encode()

	hub.RouteDecodedPacket(eveSession, msgPacket, raw)

	// Bob should NOT receive anything
	select {
	case <-bobSession.Send:
		t.Fatal("bob received message from unauthorized non-member eve!")
	case <-time.After(100 * time.Millisecond):
		// Expected: nothing received
	}

	// Eve should NOT receive server ACK
	select {
	case <-eveSession.Send:
		t.Fatal("eve received server ACK for unauthorized group post!")
	case <-time.After(100 * time.Millisecond):
		// Expected
	}
}

func TestMessageEdit_OriginalSenderValidated(t *testing.T) {
	buffer, _ := queue.NewRedisBufferWithConfig("", "localhost:6379", "", false)
	defer buffer.Close()
	hub := NewHub(buffer)

	aliceSession := &ClientSession{
		Hub:           hub,
		NxId:          "NX-ALICE",
		DeviceId:      "dev_alice",
		Send:          make(chan []byte, 10),
		Authenticated: true,
	}
	bobSession := &ClientSession{
		Hub:           hub,
		NxId:          "NX-BOB",
		DeviceId:      "dev_bob",
		Send:          make(chan []byte, 10),
		Authenticated: true,
	}
	eveSession := &ClientSession{
		Hub:           hub,
		NxId:          "NX-EVE",
		DeviceId:      "dev_eve",
		Send:          make(chan []byte, 10),
		Authenticated: true,
	}

	hub.Register(aliceSession)
	hub.Register(bobSession)
	hub.Register(eveSession)

	// 1. Alice sends original message
	origPacket := &wire.NexaWirePacket{
		PacketId:      "msg_original_001",
		Type:          wire.PacketType_PACKET_MESSAGE,
		SenderNxId:    "NX-ALICE",
		RecipientNxId: "NX-BOB",
		Timestamp:     time.Now().UnixMilli(),
		Message: &wire.EncryptedMessagePayload{
			Ciphertext: []byte("original message text"),
		},
	}
	origRaw, _ := origPacket.Encode()
	hub.RouteDecodedPacket(aliceSession, origPacket, origRaw)

	// Drain acks
	<-aliceSession.Send
	<-bobSession.Send

	// 2. Eve tries to edit Alice's message -> Must be rejected!
	eveEditPacket := &wire.NexaWirePacket{
		PacketId:      "msg_edit_eve_hack",
		Type:          wire.PacketType_PACKET_MESSAGE_EDIT,
		SenderNxId:    "NX-EVE",
		RecipientNxId: "NX-BOB",
		Timestamp:     time.Now().UnixMilli(),
		MessageEdit: &wire.MessageEditPayload{
			TargetPacketId: "msg_original_001",
			NewCiphertext:  []byte("hacked text"),
			EditedAt:       time.Now().UnixMilli(),
		},
	}
	eveRaw, _ := eveEditPacket.Encode()
	hub.RouteDecodedPacket(eveSession, eveEditPacket, eveRaw)

	// Bob must NOT receive Eve's edit
	select {
	case <-bobSession.Send:
		t.Fatal("bob received forged message edit from eve!")
	case <-time.After(100 * time.Millisecond):
		// Success
	}

	// 3. Alice edits her own message -> Accepted & relayed to Bob
	aliceEditPacket := &wire.NexaWirePacket{
		PacketId:      "msg_edit_alice_legit",
		Type:          wire.PacketType_PACKET_MESSAGE_EDIT,
		SenderNxId:    "NX-ALICE",
		RecipientNxId: "NX-BOB",
		Timestamp:     time.Now().UnixMilli(),
		MessageEdit: &wire.MessageEditPayload{
			TargetPacketId: "msg_original_001",
			NewCiphertext:  []byte("alice edited text"),
			EditedAt:       time.Now().UnixMilli(),
		},
	}
	aliceRaw, _ := aliceEditPacket.Encode()
	hub.RouteDecodedPacket(aliceSession, aliceEditPacket, aliceRaw)

	// Alice gets ACK_SERVER_RECEIVED
	select {
	case ackRaw := <-aliceSession.Send:
		ack, _ := wire.Decode(ackRaw)
		if ack.Ack.Status != wire.AckStatus_ACK_SERVER_RECEIVED {
			t.Fatalf("expected ACK_SERVER_RECEIVED for edit, got %v", ack.Ack.Status)
		}
	case <-time.After(time.Second):
		t.Fatal("alice timed out waiting for edit ack")
	}

	// Bob receives the edit packet
	select {
	case editRaw := <-bobSession.Send:
		editPacket, _ := wire.Decode(editRaw)
		if editPacket.Type != wire.PacketType_PACKET_MESSAGE_EDIT || editPacket.MessageEdit.TargetPacketId != "msg_original_001" {
			t.Fatalf("bob received unexpected packet: %+v", editPacket)
		}
	case <-time.After(time.Second):
		t.Fatal("bob timed out waiting for edit packet")
	}
}

func TestPresence_ReciprocityEnforced(t *testing.T) {
	pm := NewPresenceManager(nil)
	ctx := context.Background()

	// Alice is HIDDEN (Show Active Status: OFF)
	// Bob is ONLINE (Show Active Status: ON)
	_ = pm.SetPresence(ctx, "NX-ALICE", wire.PresenceStatus_PRESENCE_HIDDEN, time.Minute)
	_ = pm.SetPresence(ctx, "NX-BOB", wire.PresenceStatus_PRESENCE_ONLINE, time.Minute)

	// Reciprocity Rule: Alice is HIDDEN, so Alice CANNOT view Bob's presence
	canAliceViewBob, err := pm.CanViewPeerPresence(ctx, "NX-ALICE", "NX-BOB")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if canAliceViewBob {
		t.Fatal("reciprocity violation: hidden user alice was allowed to view bob's presence")
	}

	// Bob CANNOT view Alice's presence because Alice is hidden
	canBobViewAlice, err := pm.CanViewPeerPresence(ctx, "NX-BOB", "NX-ALICE")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if canBobViewAlice {
		t.Fatal("privacy violation: bob was allowed to view hidden user alice's presence")
	}

	// Charlie is ONLINE (Show Active Status: ON)
	_ = pm.SetPresence(ctx, "NX-CHARLIE", wire.PresenceStatus_PRESENCE_ONLINE, time.Minute)

	// Bob can view Charlie's presence, and Charlie can view Bob's presence
	canBobViewCharlie, _ := pm.CanViewPeerPresence(ctx, "NX-BOB", "NX-CHARLIE")
	canCharlieViewBob, _ := pm.CanViewPeerPresence(ctx, "NX-CHARLIE", "NX-BOB")
	if !canBobViewCharlie || !canCharlieViewBob {
		t.Fatalf("expected mutual presence visibility between online users bob and charlie")
	}
}

func TestTypingIndicator_RateLimiting(t *testing.T) {
	buffer, _ := queue.NewRedisBufferWithConfig("", "localhost:6379", "", false)
	defer buffer.Close()
	hub := NewHub(buffer)

	aliceSession := &ClientSession{
		Hub:           hub,
		NxId:          "NX-ALICE",
		DeviceId:      "dev_alice",
		Send:          make(chan []byte, 20),
		Authenticated: true,
	}
	bobSession := &ClientSession{
		Hub:           hub,
		NxId:          "NX-BOB",
		DeviceId:      "dev_bob",
		Send:          make(chan []byte, 20),
		Authenticated: true,
	}

	hub.Register(aliceSession)
	hub.Register(bobSession)

	// Alice bursts 10 typing packets in quick succession (limit is 5/sec)
	receivedCount := 0
	for i := 0; i < 10; i++ {
		tp := &wire.NexaWirePacket{
			PacketId:      fmt.Sprintf("typing_%d", i),
			Type:          wire.PacketType_PACKET_TYPING,
			SenderNxId:    "NX-ALICE",
			RecipientNxId: "NX-BOB",
			Timestamp:     time.Now().UnixMilli(),
			Typing: &wire.TypingPayload{
				ConversationId: "conv_alice_bob",
				IsTyping:       true,
			},
		}
		raw, _ := tp.Encode()
		hub.RouteDecodedPacket(aliceSession, tp, raw)
	}

	// Check how many Bob received (must be at most 5)
	for {
		select {
		case <-bobSession.Send:
			receivedCount++
		case <-time.After(50 * time.Millisecond):
			goto done
		}
	}
done:
	if receivedCount > 5 {
		t.Fatalf("rate limiting failed: bob received %d typing packets (max allowable is 5)", receivedCount)
	}
	if receivedCount == 0 {
		t.Fatal("bob did not receive any typing packets")
	}
}
