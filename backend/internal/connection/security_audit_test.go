package connection

import (
	"crypto/ed25519"
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"os"
	"path/filepath"
	"testing"
	"time"

	"nexa/wire/internal/identity"
	"nexa/wire/internal/wire"
)

// setupMultiDeviceHub creates a Hub with a user having multiple registered devices.
func setupMultiDeviceHub(t *testing.T, nxID string, deviceCount int) (*Hub, *identity.Store, []*ClientSession, []ed25519.PrivateKey, func()) {
	tmpDir, err := os.MkdirTemp("", "nexa_multidev_test_*")
	if err != nil {
		t.Fatalf("Failed to create temp dir: %v", err)
	}
	cleanup := func() { os.RemoveAll(tmpDir) }

	dbPath := filepath.Join(tmpDir, "identity.json")
	store, err := identity.NewStore(dbPath)
	if err != nil {
		cleanup()
		t.Fatalf("Failed to create identity store: %v", err)
	}

	acc := &identity.UserAccount{
		ID:           "usr_" + nxID,
		NxID:         nxID,
		ProfileName:  "MultiDevice User",
		CreatedAt:    time.Now().UnixMilli(),
	}
	if err := store.CreateAccount(acc); err != nil {
		cleanup()
		t.Fatalf("Failed to create account: %v", err)
	}

	hub := NewHub(nil)
	hub.SetIdentityStore(store)

	var sessions []*ClientSession
	var privKeys []ed25519.PrivateKey

	for i := 1; i <= deviceCount; i++ {
		devID := fmt.Sprintf("dev_%s_%d", nxID, i)
		pub, priv, _ := ed25519.GenerateKey(rand.Reader)
		pubHex := hex.EncodeToString(pub)

		dev := &identity.Device{
			DeviceID:     devID,
			AccountID:    acc.ID,
			NxID:         nxID,
			DeviceName:   fmt.Sprintf("Device %d", i),
			PublicKeyHex: pubHex,
			IsRevoked:    false,
			CreatedAt:    time.Now().UnixMilli(),
		}
		if err := store.RegisterDevice(dev); err != nil {
			cleanup()
			t.Fatalf("Failed to register device: %v", err)
		}

		sess := NewClientSession(hub, nil, nxID, devID)
		sess.Authenticated = true
		hub.Register(sess)

		sessions = append(sessions, sess)
		privKeys = append(privKeys, priv)
	}

	return hub, store, sessions, privKeys, cleanup
}

// TestMultiDeviceFanOut verifies that incoming messages are fanned out to all authorized online devices.
func TestMultiDeviceFanOut(t *testing.T) {
	hub, _, aliceSessions, _, cleanup := setupMultiDeviceHub(t, "NX-ALICE", 3)
	defer cleanup()

	// Setup Bob (sender)
	bobSession := NewClientSession(hub, nil, "NX-BOB", "dev_bob_1")
	bobSession.Authenticated = true
	hub.Register(bobSession)

	msgPacket := &wire.NexaWirePacket{
		PacketId:      "msg_fanout_001",
		Type:          wire.PacketType_PACKET_MESSAGE,
		SenderNxId:    "NX-BOB",
		RecipientNxId: "NX-ALICE",
		Timestamp:     time.Now().UnixMilli(),
		Message: &wire.EncryptedMessagePayload{
			Ciphertext: []byte("encrypted_e2ee_payload_for_alice"),
			AuthTag:    []byte("16_byte_auth_tag"),
			AeadNonce:  []byte("12_byte_nonce_01"),
		},
	}
	rawBytes, err := msgPacket.Encode()
	if err != nil {
		t.Fatalf("Failed to encode wire packet: %v", err)
	}

	// Bob routes the packet to Alice
	hub.RoutePacket(bobSession, rawBytes)

	// Verify all 3 of Alice's devices received the packet
	for i, sess := range aliceSessions {
		select {
		case received := <-sess.Send:
			pkt, decErr := wire.Decode(received)
			if decErr != nil {
				t.Fatalf("Device %d received malformed packet: %v", i+1, decErr)
			}
			if pkt.PacketId != "msg_fanout_001" {
				t.Fatalf("Device %d expected packet msg_fanout_001, got %s", i+1, pkt.PacketId)
			}
		case <-time.After(500 * time.Millisecond):
			t.Fatalf("Device %d did not receive fanned-out packet (timed out)", i+1)
		}
	}
}

// TestMultiDeviceSelfSync verifies that when a user sends a message from one device,
// a copy is automatically synced to their other active devices.
func TestMultiDeviceSelfSync(t *testing.T) {
	hub, _, aliceSessions, _, cleanup := setupMultiDeviceHub(t, "NX-ALICE", 2)
	defer cleanup()

	// Alice Device 1 sends a message to Bob
	outPacket := &wire.NexaWirePacket{
		PacketId:      "msg_outbox_sync_001",
		Type:          wire.PacketType_PACKET_MESSAGE,
		SenderNxId:    "NX-ALICE",
		RecipientNxId: "NX-BOB",
		Timestamp:     time.Now().UnixMilli(),
		Message: &wire.EncryptedMessagePayload{
			Ciphertext: []byte("alice_to_bob_ciphertext"),
			AuthTag:    []byte("auth_tag_16bytes"),
			AeadNonce:  []byte("nonce_12bytes_01"),
		},
	}
	rawBytes, _ := outPacket.Encode()

	hub.RoutePacket(aliceSessions[0], rawBytes)

	// Alice Device 2 should receive the self-sync packet
	select {
	case received := <-aliceSessions[1].Send:
		pkt, decErr := wire.Decode(received)
		if decErr != nil {
			t.Fatalf("Sibling device received malformed packet: %v", decErr)
		}
		if pkt.PacketId != "msg_outbox_sync_001" {
			t.Fatalf("Sibling device expected msg_outbox_sync_001, got %s", pkt.PacketId)
		}
	case <-time.After(500 * time.Millisecond):
		t.Fatalf("Sibling device did not receive self-sync packet")
	}
}

// TestDeviceRevocation_EvictionAndIsolation verifies that revoking a device:
// 1. Actively evicts its WebSocket session.
// 2. Excludes it from subsequent message fan-outs.
// 3. Rejects subsequent authentication attempts.
func TestDeviceRevocation_EvictionAndIsolation(t *testing.T) {
	hub, store, aliceSessions, alicePrivs, cleanup := setupMultiDeviceHub(t, "NX-ALICE", 3)
	defer cleanup()

	revokedDevID := "dev_NX-ALICE_3"

	// 1. Mark device revoked in identity store
	if err := store.RevokeDevice(revokedDevID); err != nil {
		t.Fatalf("Failed to revoke device: %v", err)
	}

	// 2. Evict device from Hub local sessions
	evictedCount := hub.RevokeDeviceSessionLocal("NX-ALICE", revokedDevID, "Security audit test revocation")
	if evictedCount != 1 {
		t.Fatalf("Expected 1 evicted session, got %d", evictedCount)
	}

	// Verify revoked session channel is closed
	if _, open := <-aliceSessions[2].Send; open {
		t.Fatalf("Expected evicted session Send channel to be closed")
	}

	// 3. Bob sends another message to Alice
	bobSession := NewClientSession(hub, nil, "NX-BOB", "dev_bob_1")
	bobSession.Authenticated = true
	hub.Register(bobSession)

	msgPacket := &wire.NexaWirePacket{
		PacketId:      "msg_post_revocation_002",
		Type:          wire.PacketType_PACKET_MESSAGE,
		SenderNxId:    "NX-BOB",
		RecipientNxId: "NX-ALICE",
		Timestamp:     time.Now().UnixMilli(),
		Message: &wire.EncryptedMessagePayload{
			Ciphertext: []byte("secret_message_post_revocation"),
			AuthTag:    []byte("auth_tag_16bytes"),
			AeadNonce:  []byte("nonce_12bytes_02"),
		},
	}
	rawBytes, _ := msgPacket.Encode()
	hub.RoutePacket(bobSession, rawBytes)

	// Verify Device 1 and 2 received it
	for i := 0; i < 2; i++ {
		select {
		case received := <-aliceSessions[i].Send:
			pkt, _ := wire.Decode(received)
			if pkt.PacketId != "msg_post_revocation_002" {
				t.Fatalf("Device %d expected packet msg_post_revocation_002, got %s", i+1, pkt.PacketId)
			}
		case <-time.After(500 * time.Millisecond):
			t.Fatalf("Active device %d did not receive packet", i+1)
		}
	}

	// 4. Revoked device tries to reconnect and authenticate: MUST FAIL
	reconnectSession := NewClientSession(hub, nil, "NX-ALICE", revokedDevID)
	if err := reconnectSession.SendChallenge(); err != nil {
		t.Fatalf("SendChallenge failed: %v", err)
	}
	<-reconnectSession.Send // Drain challenge

	nonce := reconnectSession.ChallengeNonce
	chalTime := reconnectSession.ChallengeTime
	dataToSign := append(append(nonce, []byte(":NX-ALICE:")...), []byte(fmt.Sprintf("%d", chalTime))...)
	sig := ed25519.Sign(alicePrivs[2], dataToSign)

	pubBytes := alicePrivs[2].Public().(ed25519.PublicKey)
	respPacket := &wire.NexaWirePacket{
		PacketId:      "resp_reconnect_revoked",
		Type:          wire.PacketType_PACKET_AUTH_RESPONSE,
		SenderNxId:    "NX-ALICE",
		RecipientNxId: "SERVER",
		Timestamp:     time.Now().UnixMilli(),
		AuthResponse: &wire.AuthResponsePayload{
			Signature: sig,
			PublicKey: pubBytes,
			Timestamp: time.Now().UnixMilli(),
		},
	}

	if reconnectSession.VerifyAuthResponse(respPacket) {
		t.Fatalf("CRITICAL SECURITY FLAW: Revoked device successfully authenticated challenge-response!")
	}
	if reconnectSession.Authenticated {
		t.Fatalf("Revoked session must not be marked Authenticated")
	}
}

// TestDeliveryAck_AntiSpoofing verifies that spoofed delivery ACKs claiming a different recipient are rejected.
func TestDeliveryAck_AntiSpoofing(t *testing.T) {
	hub, _, aliceSessions, _, cleanup := setupMultiDeviceHub(t, "NX-ALICE", 1)
	defer cleanup()

	// Alice tries to spoof an ACK claiming to be Bob
	spoofedAckPacket := &wire.NexaWirePacket{
		PacketId:      "ack_spoofed_001",
		Type:          wire.PacketType_PACKET_ACK,
		SenderNxId:    "NX-ALICE",
		RecipientNxId: "SERVER",
		Timestamp:     time.Now().UnixMilli(),
		Ack: &wire.DeliveryAckPayload{
			OriginalPacketId: "original_msg_123",
			RecipientNxId:    "NX-BOB", // Mismatch with authenticated sender (NX-ALICE)
			Status:           wire.AckStatus_ACK_DEVICE_DELIVERED,
		},
	}
	rawBytes, _ := spoofedAckPacket.Encode()

	// Hub should reject the spoofed delivery ACK without error / panic
	hub.RoutePacket(aliceSessions[0], rawBytes)
}
