package connection

import (
	"crypto/ed25519"
	"crypto/rand"
	"fmt"
	"testing"
	"time"

	"nexa/wire/internal/wire"
)

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

	session := NewClientSession(nil, nil, "NX-ALICE-123", "dev_test")
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

	session := NewClientSession(nil, nil, "NX-BOB-456", "dev_test")
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

	session := NewClientSession(nil, nil, "NX-REPLAY-USER", "dev_test")
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

	session := NewClientSession(nil, nil, "NX-TIMEOUT-USER", "dev_test")
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

func TestVerifyAuthResponse_ImpersonationBlocked(t *testing.T) {
	pub, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatalf("Failed to generate key: %v", err)
	}

	// Session registered as VICTIM
	session := NewClientSession(nil, nil, "NX-VICTIM-999", "dev_test")
	session.SendChallenge()
	<-session.Send

	dataToSign := append(append(session.ChallengeNonce, []byte(":NX-ATTACKER-000:")...), []byte(fmt.Sprintf("%d", session.ChallengeTime))...)
	signature := ed25519.Sign(priv, dataToSign)

	// Attacker tries to present their own NX-ID on victim's session
	respPacket := &wire.NexaWirePacket{
		PacketId:      "resp_impersonate",
		Type:          wire.PacketType_PACKET_AUTH_RESPONSE,
		SenderNxId:    "NX-ATTACKER-000", // Mismatched Sender
		RecipientNxId: "SERVER",
		Timestamp:     time.Now().UnixMilli(),
		AuthResponse: &wire.AuthResponsePayload{
			Signature: signature,
			PublicKey: pub,
			Timestamp: time.Now().UnixMilli(),
		},
	}

	if session.VerifyAuthResponse(respPacket) {
		t.Fatal("Impersonation attempt with mismatched sender NX-ID must be rejected")
	}
}
