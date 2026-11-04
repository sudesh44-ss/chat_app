package wire

import (
	"bytes"
	"testing"
)

func TestNexaWirePacket_Message_EncodeDecode(t *testing.T) {
	orig := &NexaWirePacket{
		PacketId:      "pkt_12345",
		Type:          PacketType_PACKET_MESSAGE,
		SenderNxId:    "NX-ALICE",
		RecipientNxId: "NX-BOB",
		Timestamp:     1729482000000,
		Message: &EncryptedMessagePayload{
			ConversationSeq: 42,
			Ciphertext:      []byte("CiphertextPayloadBytes"),
			Nonce:           bytes.Repeat([]byte{0x01}, 32),
			AuthTag:         bytes.Repeat([]byte{0xAA}, 16),
		},
	}

	encoded, err := orig.Encode()
	if err != nil {
		t.Fatalf("Encode failed: %v", err)
	}

	decoded, err := Decode(encoded)
	if err != nil {
		t.Fatalf("Decode failed: %v", err)
	}

	if decoded.PacketId != orig.PacketId {
		t.Errorf("PacketId mismatch: got %s, want %s", decoded.PacketId, orig.PacketId)
	}
	if decoded.Type != orig.Type {
		t.Errorf("Type mismatch: got %v, want %v", decoded.Type, orig.Type)
	}
	if decoded.SenderNxId != orig.SenderNxId {
		t.Errorf("SenderNxId mismatch: got %s, want %s", decoded.SenderNxId, orig.SenderNxId)
	}
	if decoded.RecipientNxId != orig.RecipientNxId {
		t.Errorf("RecipientNxId mismatch: got %s, want %s", decoded.RecipientNxId, orig.RecipientNxId)
	}
	if decoded.Timestamp != orig.Timestamp {
		t.Errorf("Timestamp mismatch: got %d, want %d", decoded.Timestamp, orig.Timestamp)
	}
	if decoded.Message == nil {
		t.Fatal("Decoded Message payload is nil")
	}
	if decoded.Message.ConversationSeq != 42 {
		t.Errorf("ConversationSeq mismatch: got %d, want 42", decoded.Message.ConversationSeq)
	}
	if !bytes.Equal(decoded.Message.Ciphertext, orig.Message.Ciphertext) {
		t.Errorf("Ciphertext mismatch")
	}
	if !bytes.Equal(decoded.Message.Nonce, orig.Message.Nonce) {
		t.Errorf("Nonce mismatch")
	}
	if !bytes.Equal(decoded.Message.AuthTag, orig.Message.AuthTag) {
		t.Errorf("AuthTag mismatch")
	}
}

func TestNexaWirePacket_AuthChallenge_EncodeDecode(t *testing.T) {
	nonce := bytes.Repeat([]byte{0x42}, 32)
	orig := &NexaWirePacket{
		PacketId:      "chal_99",
		Type:          PacketType_PACKET_AUTH_CHALLENGE,
		SenderNxId:    "SERVER",
		RecipientNxId: "NX-USER-99",
		Timestamp:     1000,
		AuthChallenge: &AuthChallengePayload{
			ChallengeNonce: nonce,
			Timestamp:      1000,
			ExpiresAt:      61000,
		},
	}

	encoded, err := orig.Encode()
	if err != nil {
		t.Fatalf("Encode failed: %v", err)
	}

	decoded, err := Decode(encoded)
	if err != nil {
		t.Fatalf("Decode failed: %v", err)
	}

	if decoded.Type != PacketType_PACKET_AUTH_CHALLENGE {
		t.Errorf("Type mismatch: got %v", decoded.Type)
	}
	if decoded.AuthChallenge == nil {
		t.Fatal("AuthChallenge is nil")
	}
	if !bytes.Equal(decoded.AuthChallenge.ChallengeNonce, nonce) {
		t.Errorf("ChallengeNonce mismatch")
	}
	if decoded.AuthChallenge.ExpiresAt != 61000 {
		t.Errorf("ExpiresAt mismatch: got %d, want 61000", decoded.AuthChallenge.ExpiresAt)
	}
}

func TestNexaWirePacket_AuthResponse_EncodeDecode(t *testing.T) {
	sig := bytes.Repeat([]byte{0xEE}, 64)
	pub := bytes.Repeat([]byte{0x11}, 32)
	orig := &NexaWirePacket{
		PacketId:      "resp_99",
		Type:          PacketType_PACKET_AUTH_RESPONSE,
		SenderNxId:    "NX-USER-99",
		RecipientNxId: "SERVER",
		Timestamp:     2000,
		AuthResponse: &AuthResponsePayload{
			Signature: sig,
			PublicKey: pub,
			Timestamp: 2000,
		},
	}

	encoded, err := orig.Encode()
	if err != nil {
		t.Fatalf("Encode failed: %v", err)
	}

	decoded, err := Decode(encoded)
	if err != nil {
		t.Fatalf("Decode failed: %v", err)
	}

	if decoded.Type != PacketType_PACKET_AUTH_RESPONSE {
		t.Errorf("Type mismatch: got %v", decoded.Type)
	}
	if decoded.AuthResponse == nil {
		t.Fatal("AuthResponse is nil")
	}
	if !bytes.Equal(decoded.AuthResponse.Signature, sig) {
		t.Errorf("Signature mismatch")
	}
	if !bytes.Equal(decoded.AuthResponse.PublicKey, pub) {
		t.Errorf("PublicKey mismatch")
	}
}
