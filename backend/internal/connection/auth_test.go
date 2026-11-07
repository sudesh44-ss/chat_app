package connection

import (
	"context"
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

func createTestHubWithAccount(t *testing.T, nxID string, pubKeyHex string, deviceID string) (*Hub, *identity.Store, func()) {
	tmpDir, err := os.MkdirTemp("", "nexa_hub_test_*")
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
		PublicKeyHex: pubKeyHex,
		ProfileName:  "Test User",
		CreatedAt:    time.Now().UnixMilli(),
	}
	if err := store.CreateAccount(acc); err != nil {
		cleanup()
		t.Fatalf("Failed to create account: %v", err)
	}

	if deviceID != "" {
		dev := &identity.Device{
			DeviceID:     deviceID,
			AccountID:    acc.ID,
			NxID:         nxID,
			DeviceName:   "Test Device",
			PublicKeyHex: pubKeyHex,
			IsRevoked:    false,
			CreatedAt:    time.Now().UnixMilli(),
		}
		if err := store.RegisterDevice(dev); err != nil {
			cleanup()
			t.Fatalf("Failed to register device: %v", err)
		}
	}

	hub := NewHub(nil)
	hub.SetIdentityStore(store)
	return hub, store, cleanup
}

func TestSendChallenge(t *testing.T) {
	session := NewClientSession(nil, nil, "NX-ALICE-123", "dev_test")
	if err := session.SendChallenge(); err != nil {
		t.Fatalf("SendChallenge failed: %v", err)
	}

	if len(session.ChallengeNonce) != 32 {
		t.Errorf("Expected 32-byte challenge nonce, got %d", len(session.ChallengeNonce))
	}

	if session.ChallengeExpiry <= session.ChallengeTime {
		t.Errorf("Expiry (%d) must be greater than creation time (%d)", session.ChallengeExpiry, session.ChallengeTime)
	}

	if len(session.Send) != 1 {
		t.Fatalf("Expected 1 packet queued in Send channel, got %d", len(session.Send))
	}

	rawPacket := <-session.Send
	packet, err := wire.Decode(rawPacket)
	if err != nil {
		t.Fatalf("Failed to decode challenge packet: %v", err)
	}

	if packet.Type != wire.PacketType_PACKET_AUTH_CHALLENGE {
		t.Errorf("Expected PACKET_AUTH_CHALLENGE, got %v", packet.Type)
	}

	if packet.RecipientNxId != "NX-ALICE-123" {
		t.Errorf("Expected recipient NX-ALICE-123, got %s", packet.RecipientNxId)
	}

	if packet.AuthChallenge == nil {
		t.Fatal("AuthChallenge payload is nil")
	}

	if len(packet.AuthChallenge.ChallengeNonce) != 32 {
		t.Errorf("Payload nonce length mismatch: %d", len(packet.AuthChallenge.ChallengeNonce))
	}
}

func TestVerifyAuthResponse_Valid(t *testing.T) {
	pub, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatalf("Failed to generate Ed25519 keypair: %v", err)
	}
	pubHex := hex.EncodeToString(pub)

	hub, _, cleanup := createTestHubWithAccount(t, "NX-ALICE-123", pubHex, "dev_alice")
	defer cleanup()

	session := NewClientSession(hub, nil, "NX-ALICE-123", "dev_alice")
	if err := session.SendChallenge(); err != nil {
		t.Fatalf("SendChallenge failed: %v", err)
	}
	<-session.Send // Drain challenge

	nonce := session.ChallengeNonce
	chalTime := session.ChallengeTime

	// Construct valid signature over nonce:nxId:chalTime
	dataToSign := append(append(nonce, []byte(":NX-ALICE-123:")...), []byte(fmt.Sprintf("%d", chalTime))...)
	signature := ed25519.Sign(priv, dataToSign)

	respPacket := &wire.NexaWirePacket{
		PacketId:      "resp_1",
		Type:          wire.PacketType_PACKET_AUTH_RESPONSE,
		SenderNxId:    "NX-ALICE-123",
		RecipientNxId: "SERVER",
		Timestamp:     time.Now().UnixMilli(),
		AuthResponse: &wire.AuthResponsePayload{
			Signature: signature,
			PublicKey: pub,
			Timestamp: time.Now().UnixMilli(),
		},
	}

	if !session.VerifyAuthResponse(respPacket) {
		t.Fatal("Expected valid Ed25519 signature to be verified successfully")
	}

	if !session.Authenticated {
		t.Fatal("Expected session.Authenticated to be true")
	}
}

func TestVerifyAuthResponse_InvalidSignature(t *testing.T) {
	pub, _, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatalf("Failed to generate key: %v", err)
	}
	pubHex := hex.EncodeToString(pub)

	hub, _, cleanup := createTestHubWithAccount(t, "NX-BOB-456", pubHex, "dev_bob")
	defer cleanup()

	session := NewClientSession(hub, nil, "NX-BOB-456", "dev_bob")
	if err := session.SendChallenge(); err != nil {
		t.Fatalf("SendChallenge failed: %v", err)
	}
	<-session.Send

	// Tampered signature
	invalidSig := make([]byte, 64)

	respPacket := &wire.NexaWirePacket{
		PacketId:      "resp_bad",
		Type:          wire.PacketType_PACKET_AUTH_RESPONSE,
		SenderNxId:    "NX-BOB-456",
		RecipientNxId: "SERVER",
		Timestamp:     time.Now().UnixMilli(),
		AuthResponse: &wire.AuthResponsePayload{
			Signature: invalidSig,
			PublicKey: pub,
			Timestamp: time.Now().UnixMilli(),
		},
	}

	if session.VerifyAuthResponse(respPacket) {
		t.Fatal("Expected invalid signature to fail verification")
	}

	if session.Authenticated {
		t.Fatal("Expected session.Authenticated to remain false")
	}
}

func TestVerifyAuthResponse_ReplayAttack(t *testing.T) {
	pub, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatalf("Failed to generate key: %v", err)
	}
	pubHex := hex.EncodeToString(pub)

	hub, _, cleanup := createTestHubWithAccount(t, "NX-REPLAY-USER", pubHex, "dev_replay")
	defer cleanup()

	session := NewClientSession(hub, nil, "NX-REPLAY-USER", "dev_replay")
	session.SendChallenge()
	<-session.Send

	dataToSign := append(append(session.ChallengeNonce, []byte(":NX-REPLAY-USER:")...), []byte(fmt.Sprintf("%d", session.ChallengeTime))...)
	signature := ed25519.Sign(priv, dataToSign)

	respPacket := &wire.NexaWirePacket{
		PacketId:      "resp_replay",
		Type:          wire.PacketType_PACKET_AUTH_RESPONSE,
		SenderNxId:    "NX-REPLAY-USER",
		RecipientNxId: "SERVER",
		Timestamp:     time.Now().UnixMilli(),
		AuthResponse: &wire.AuthResponsePayload{
			Signature: signature,
			PublicKey: pub,
			Timestamp: time.Now().UnixMilli(),
		},
	}

	// First attempt must succeed
	if !session.VerifyAuthResponse(respPacket) {
		t.Fatal("First auth verification must succeed")
	}

	// Replay attempt must fail because ChallengeNonce was invalidated (single-use)
	if session.VerifyAuthResponse(respPacket) {
		t.Fatal("Replay attack must fail due to single-use token invalidation")
	}
}

func TestVerifyAuthResponse_ExpiredChallenge(t *testing.T) {
	pub, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatalf("Failed to generate key: %v", err)
	}
	pubHex := hex.EncodeToString(pub)

	hub, _, cleanup := createTestHubWithAccount(t, "NX-TIMEOUT-USER", pubHex, "dev_timeout")
	defer cleanup()

	session := NewClientSession(hub, nil, "NX-TIMEOUT-USER", "dev_timeout")
	session.SendChallenge()
	<-session.Send

	// Force challenge expiration in the past
	session.ChallengeExpiry = time.Now().UnixMilli() - 1000

	dataToSign := append(append(session.ChallengeNonce, []byte(":NX-TIMEOUT-USER:")...), []byte(fmt.Sprintf("%d", session.ChallengeTime))...)
	signature := ed25519.Sign(priv, dataToSign)

	respPacket := &wire.NexaWirePacket{
		PacketId:      "resp_expired",
		Type:          wire.PacketType_PACKET_AUTH_RESPONSE,
		SenderNxId:    "NX-TIMEOUT-USER",
		RecipientNxId: "SERVER",
		Timestamp:     time.Now().UnixMilli(),
		AuthResponse: &wire.AuthResponsePayload{
			Signature: signature,
			PublicKey: pub,
			Timestamp: time.Now().UnixMilli(),
		},
	}

	if session.VerifyAuthResponse(respPacket) {
		t.Fatal("Expired challenge must be rejected")
	}
}

func TestVerifyAuthResponse_FailClosedWhenStoreNil(t *testing.T) {
	pub, priv, _ := ed25519.GenerateKey(rand.Reader)

	// Hub is nil -> Fail closed, no trust of arbitrary keys
	session := NewClientSession(nil, nil, "NX-UNTRUSTED", "dev_untrusted")
	session.SendChallenge()
	<-session.Send

	dataToSign := append(append(session.ChallengeNonce, []byte(":NX-UNTRUSTED:")...), []byte(fmt.Sprintf("%d", session.ChallengeTime))...)
	signature := ed25519.Sign(priv, dataToSign)

	respPacket := &wire.NexaWirePacket{
		PacketId:      "resp_untrusted",
		Type:          wire.PacketType_PACKET_AUTH_RESPONSE,
		SenderNxId:    "NX-UNTRUSTED",
		RecipientNxId: "SERVER",
		Timestamp:     time.Now().UnixMilli(),
		AuthResponse: &wire.AuthResponsePayload{
			Signature: signature,
			PublicKey: pub,
			Timestamp: time.Now().UnixMilli(),
		},
	}

	if session.VerifyAuthResponse(respPacket) {
		t.Fatal("Verification without authoritative identity store MUST fail closed")
	}
}

func TestVerifyAuthResponse_UnknownNxID(t *testing.T) {
	pub, priv, _ := ed25519.GenerateKey(rand.Reader)
	pubHex := hex.EncodeToString(pub)

	hub, _, cleanup := createTestHubWithAccount(t, "NX-KNOWN-USER", pubHex, "dev_known")
	defer cleanup()

	// Attempt to connect as unknown NX-ID
	session := NewClientSession(hub, nil, "NX-NONEXISTENT", "dev_known")
	session.SendChallenge()
	<-session.Send

	dataToSign := append(append(session.ChallengeNonce, []byte(":NX-NONEXISTENT:")...), []byte(fmt.Sprintf("%d", session.ChallengeTime))...)
	signature := ed25519.Sign(priv, dataToSign)

	respPacket := &wire.NexaWirePacket{
		PacketId:      "resp_nonexistent",
		Type:          wire.PacketType_PACKET_AUTH_RESPONSE,
		SenderNxId:    "NX-NONEXISTENT",
		RecipientNxId: "SERVER",
		Timestamp:     time.Now().UnixMilli(),
		AuthResponse: &wire.AuthResponsePayload{
			Signature: signature,
			PublicKey: pub,
			Timestamp: time.Now().UnixMilli(),
		},
	}

	if session.VerifyAuthResponse(respPacket) {
		t.Fatal("Unknown NX-ID MUST be rejected")
	}
}

func TestVerifyAuthResponse_UnknownDevice(t *testing.T) {
	pub, priv, _ := ed25519.GenerateKey(rand.Reader)
	pubHex := hex.EncodeToString(pub)

	hub, _, cleanup := createTestHubWithAccount(t, "NX-DEVICE-USER", pubHex, "dev_registered_1")
	defer cleanup()

	// Connect claiming to be an unregistered rogue device
	session := NewClientSession(hub, nil, "NX-DEVICE-USER", "dev_rogue_unknown")
	session.SendChallenge()
	<-session.Send

	dataToSign := append(append(session.ChallengeNonce, []byte(":NX-DEVICE-USER:")...), []byte(fmt.Sprintf("%d", session.ChallengeTime))...)
	signature := ed25519.Sign(priv, dataToSign)

	respPacket := &wire.NexaWirePacket{
		PacketId:      "resp_rogue",
		Type:          wire.PacketType_PACKET_AUTH_RESPONSE,
		SenderNxId:    "NX-DEVICE-USER",
		RecipientNxId: "SERVER",
		Timestamp:     time.Now().UnixMilli(),
		AuthResponse: &wire.AuthResponsePayload{
			Signature: signature,
			PublicKey: pub,
			Timestamp: time.Now().UnixMilli(),
		},
	}

	if session.VerifyAuthResponse(respPacket) {
		t.Fatal("Unknown device ID for registered account MUST be rejected")
	}
}

func TestVerifyAuthResponse_RevokedDevice(t *testing.T) {
	pub, priv, _ := ed25519.GenerateKey(rand.Reader)
	pubHex := hex.EncodeToString(pub)

	hub, store, cleanup := createTestHubWithAccount(t, "NX-REVOKED-USER", pubHex, "dev_compromised")
	defer cleanup()

	// Revoke the device
	if err := store.RevokeDevice("dev_compromised"); err != nil {
		t.Fatalf("Failed to revoke device: %v", err)
	}

	session := NewClientSession(hub, nil, "NX-REVOKED-USER", "dev_compromised")
	session.SendChallenge()
	<-session.Send

	dataToSign := append(append(session.ChallengeNonce, []byte(":NX-REVOKED-USER:")...), []byte(fmt.Sprintf("%d", session.ChallengeTime))...)
	signature := ed25519.Sign(priv, dataToSign)

	respPacket := &wire.NexaWirePacket{
		PacketId:      "resp_revoked",
		Type:          wire.PacketType_PACKET_AUTH_RESPONSE,
		SenderNxId:    "NX-REVOKED-USER",
		RecipientNxId: "SERVER",
		Timestamp:     time.Now().UnixMilli(),
		AuthResponse: &wire.AuthResponsePayload{
			Signature: signature,
			PublicKey: pub,
			Timestamp: time.Now().UnixMilli(),
		},
	}

	if session.VerifyAuthResponse(respPacket) {
		t.Fatal("Revoked device MUST be rejected from WebSocket connection")
	}
}

func TestVerifyAuthResponse_CrossAccountDeviceImpersonation(t *testing.T) {
	alicePub, alicePriv, _ := ed25519.GenerateKey(rand.Reader)
	alicePubHex := hex.EncodeToString(alicePub)

	bobPub, _, _ := ed25519.GenerateKey(rand.Reader)
	bobPubHex := hex.EncodeToString(bobPub)

	tmpDir, _ := os.MkdirTemp("", "nexa_cross_test_*")
	defer os.RemoveAll(tmpDir)

	dbPath := filepath.Join(tmpDir, "identity.json")
	store, _ := identity.NewStore(dbPath)

	store.CreateAccount(&identity.UserAccount{
		ID:           "usr_alice",
		NxID:         "NX-ALICE",
		PublicKeyHex: alicePubHex,
	})
	store.RegisterDevice(&identity.Device{
		DeviceID:     "dev_alice_phone",
		AccountID:    "usr_alice",
		NxID:         "NX-ALICE",
		PublicKeyHex: alicePubHex,
	})

	store.CreateAccount(&identity.UserAccount{
		ID:           "usr_bob",
		NxID:         "NX-BOB",
		PublicKeyHex: bobPubHex,
	})

	hub := NewHub(nil)
	hub.SetIdentityStore(store)

	// Bob's session tries to present Alice's device ID
	session := NewClientSession(hub, nil, "NX-BOB", "dev_alice_phone")
	session.SendChallenge()
	<-session.Send

	dataToSign := append(append(session.ChallengeNonce, []byte(":NX-BOB:")...), []byte(fmt.Sprintf("%d", session.ChallengeTime))...)
	signature := ed25519.Sign(alicePriv, dataToSign)

	respPacket := &wire.NexaWirePacket{
		PacketId:      "resp_impersonate",
		Type:          wire.PacketType_PACKET_AUTH_RESPONSE,
		SenderNxId:    "NX-BOB",
		RecipientNxId: "SERVER",
		Timestamp:     time.Now().UnixMilli(),
		AuthResponse: &wire.AuthResponsePayload{
			Signature: signature,
			PublicKey: alicePub,
			Timestamp: time.Now().UnixMilli(),
		},
	}

	if session.VerifyAuthResponse(respPacket) {
		t.Fatal("Device belonging to another account MUST be rejected")
	}
}

func TestVerifyAuthResponse_IdentityStoreValidation(t *testing.T) {
	tmpDir, err := os.MkdirTemp("", "nexa_conn_test_*")
	if err != nil {
		t.Fatalf("Failed to create temp dir: %v", err)
	}
	defer os.RemoveAll(tmpDir)

	dbPath := filepath.Join(tmpDir, "identity.json")
	store, err := identity.NewStore(dbPath)
	if err != nil {
		t.Fatalf("Failed to create identity store: %v", err)
	}

	// Legitimate user Bob with registered Ed25519 key
	bobPub, bobPriv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatalf("Failed to generate Bob's key: %v", err)
	}
	bobPubHex := hex.EncodeToString(bobPub)
	bobNx := "NX-BOB-REGISTERED"

	err = store.CreateAccount(&identity.UserAccount{
		ID:           "usr_bob",
		NxID:         bobNx,
		PublicKeyHex: bobPubHex,
		ProfileName:  "Bob",
		CreatedAt:    time.Now().UnixMilli(),
	})
	if err != nil {
		t.Fatalf("Failed to create Bob's account: %v", err)
	}

	err = store.RegisterDevice(&identity.Device{
		DeviceID:     "dev_bob",
		AccountID:    "usr_bob",
		NxID:         bobNx,
		DeviceName:   "Bob Phone",
		PublicKeyHex: bobPubHex,
		IsRevoked:    false,
		CreatedAt:    time.Now().UnixMilli(),
	})
	if err != nil {
		t.Fatalf("Failed to register Bob's device: %v", err)
	}

	hub := NewHub(nil)
	hub.SetIdentityStore(store)

	// Attacker generates their own keypair
	attPub, attPriv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatalf("Failed to generate attacker key: %v", err)
	}

	// 1. Attacker connects claiming to be Bob, but signing with attacker's key
	attSession := NewClientSession(hub, nil, bobNx, "dev_attacker")
	attSession.SendChallenge()
	<-attSession.Send

	attDataToSign := append(append(attSession.ChallengeNonce, []byte(":"+bobNx+":")...), []byte(fmt.Sprintf("%d", attSession.ChallengeTime))...)
	attSig := ed25519.Sign(attPriv, attDataToSign)

	spoofPacket := &wire.NexaWirePacket{
		PacketId:      "resp_spoof",
		Type:          wire.PacketType_PACKET_AUTH_RESPONSE,
		SenderNxId:    bobNx,
		RecipientNxId: "SERVER",
		Timestamp:     time.Now().UnixMilli(),
		AuthResponse: &wire.AuthResponsePayload{
			Signature: attSig,
			PublicKey: attPub, // Attacker's own public key!
			Timestamp: time.Now().UnixMilli(),
		},
	}

	if attSession.VerifyAuthResponse(spoofPacket) {
		t.Fatal("Attacker presenting non-matching public key MUST be rejected by identity store validation")
	}

	if attSession.Authenticated {
		t.Fatal("Attacker session should remain unauthenticated")
	}

	// 2. Legitimate Bob connects with registered key
	bobSession := NewClientSession(hub, nil, bobNx, "dev_bob")
	bobSession.SendChallenge()
	<-bobSession.Send

	bobDataToSign := append(append(bobSession.ChallengeNonce, []byte(":"+bobNx+":")...), []byte(fmt.Sprintf("%d", bobSession.ChallengeTime))...)
	bobSig := ed25519.Sign(bobPriv, bobDataToSign)

	legitPacket := &wire.NexaWirePacket{
		PacketId:      "resp_legit",
		Type:          wire.PacketType_PACKET_AUTH_RESPONSE,
		SenderNxId:    bobNx,
		RecipientNxId: "SERVER",
		Timestamp:     time.Now().UnixMilli(),
		AuthResponse: &wire.AuthResponsePayload{
			Signature: bobSig,
			PublicKey: bobPub, // Bob's true registered public key
			Timestamp: time.Now().UnixMilli(),
		},
	}

	if !bobSession.VerifyAuthResponse(legitPacket) {
		t.Fatal("Legitimate user with matching registered public key MUST succeed")
	}

	if !bobSession.Authenticated {
		t.Fatal("Legitimate session must be marked authenticated")
	}
}

type mockConnValidator struct {
	connected map[string]bool
	blocked   map[string]bool
}

func (m *mockConnValidator) IsBlocked(ctx context.Context, uA, uB string) (bool, error) {
	return m.blocked[uA+":"+uB] || m.blocked[uB+":"+uA], nil
}

func (m *mockConnValidator) IsConnected(ctx context.Context, uA, uB string) (bool, error) {
	return m.connected[uA+":"+uB] || m.connected[uB+":"+uA], nil
}

func TestWireMessageRoutingAuthorization(t *testing.T) {
	aliceNx := "NX-ALICE111"
	bobNx := "NX-BOB22222"

	pub, _, _ := ed25519.GenerateKey(rand.Reader)
	pubHex := hex.EncodeToString(pub)

	hub, _, cleanup := createTestHubWithAccount(t, aliceNx, pubHex, "dev_alice")
	defer cleanup()

	validator := &mockConnValidator{
		connected: make(map[string]bool),
		blocked:   make(map[string]bool),
	}
	hub.SetConnectionValidator(validator)

	aliceSession := NewClientSession(hub, nil, aliceNx, "dev_alice")
	aliceSession.Authenticated = true
	hub.Register(aliceSession)

	bobSession := NewClientSession(hub, nil, bobNx, "dev_bob")
	bobSession.Authenticated = true
	hub.Register(bobSession)

	msgPacket := &wire.NexaWirePacket{
		PacketId:      "msg_test_001",
		Type:          wire.PacketType_PACKET_MESSAGE,
		SenderNxId:    aliceNx,
		RecipientNxId: bobNx,
		Timestamp:     time.Now().UnixMilli(),
		Message: &wire.EncryptedMessagePayload{
			ConversationSeq: 1,
			Ciphertext:      []byte("encrypted_blind_payload"),
			Nonce:           []byte("test_nonce_32_bytes_long_123456"),
			AuthTag:         []byte("tag_16_bytes_long"),
		},
	}
	rawMsg, _ := msgPacket.Encode()

	// 1. When NOT connected: packet must be dropped (Bob receives nothing, Alice receives no server ack)
	hub.RouteDecodedPacket(aliceSession, msgPacket, rawMsg)

	select {
	case <-bobSession.Send:
		t.Fatal("Bob should NOT receive message when not connected with Alice")
	default:
		// Passed: dropped
	}

	// 2. When MUTUALLY CONNECTED: packet must be routed to Bob, and Alice receives ACK_SERVER_RECEIVED
	validator.connected[aliceNx+":"+bobNx] = true
	hub.RouteDecodedPacket(aliceSession, msgPacket, rawMsg)

	// Alice receives ACK
	select {
	case ackRaw := <-aliceSession.Send:
		ackPacket, err := wire.Decode(ackRaw)
		if err != nil || ackPacket.Type != wire.PacketType_PACKET_ACK {
			t.Fatalf("Alice should receive ACK packet, got %v", ackPacket)
		}
	default:
		t.Fatal("Alice should receive ACK_SERVER_RECEIVED")
	}

	// Bob receives message
	select {
	case routedRaw := <-bobSession.Send:
		routedPacket, err := wire.Decode(routedRaw)
		if err != nil || routedPacket.PacketId != msgPacket.PacketId {
			t.Fatalf("Bob should receive routed message packet, got %v", routedPacket)
		}
	default:
		t.Fatal("Bob should receive routed message packet")
	}

	// 3. When BLOCKED: packet must be dropped even if previously connected
	validator.blocked[bobNx+":"+aliceNx] = true
	msgPacket2 := &wire.NexaWirePacket{
		PacketId:      "msg_test_002",
		Type:          wire.PacketType_PACKET_MESSAGE,
		SenderNxId:    aliceNx,
		RecipientNxId: bobNx,
		Timestamp:     time.Now().UnixMilli(),
		Message: &wire.EncryptedMessagePayload{
			ConversationSeq: 2,
			Ciphertext:      []byte("blocked_payload"),
		},
	}
	rawMsg2, _ := msgPacket2.Encode()
	hub.RouteDecodedPacket(aliceSession, msgPacket2, rawMsg2)

	select {
	case <-bobSession.Send:
		t.Fatal("Bob should NOT receive message when Alice is blocked")
	default:
		// Passed: dropped
	}
}

