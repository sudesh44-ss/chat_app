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

func TestNexaWirePacket_Signaling_EncodeDecode(t *testing.T) {
	orig := &NexaWirePacket{
		PacketId:      "sig_777",
		Type:          PacketType_PACKET_SIGNALING,
		SenderNxId:    "NX-ALICE",
		RecipientNxId: "NX-BOB",
		Timestamp:     1731568200000,
		Signaling: &SignalingPayload{
			TransferId: 987654321,
			Action:     "OFFER",
			Data:       "v=0\r\no=- 12345 2 IN IP4 127.0.0.1\r\n",
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

	if decoded.Type != PacketType_PACKET_SIGNALING {
		t.Errorf("Type mismatch: got %v, want %v", decoded.Type, PacketType_PACKET_SIGNALING)
	}
	if decoded.Signaling == nil {
		t.Fatal("Decoded Signaling is nil")
	}
	if decoded.Signaling.TransferId != 987654321 {
		t.Errorf("TransferId mismatch: got %d, want 987654321", decoded.Signaling.TransferId)
	}
	if decoded.Signaling.Action != "OFFER" {
		t.Errorf("Action mismatch: got %s, want OFFER", decoded.Signaling.Action)
	}
	if decoded.Signaling.Data != orig.Signaling.Data {
		t.Errorf("Data mismatch: got %s, want %s", decoded.Signaling.Data, orig.Signaling.Data)
	}
}

func TestNexaWirePacket_RegisterRoutingTokens_EncodeDecode(t *testing.T) {
	orig := &NexaWirePacket{
		PacketId:      "reg_tokens_1",
		Type:          PacketType_PACKET_REGISTER_ROUTING_TOKENS,
		SenderNxId:    "NX-ALICE",
		RecipientNxId: "SERVER",
		Timestamp:     1731568200000,
		RoutingTokens: &RoutingTokenRegistrationPayload{
			Tokens: []string{
				"4d434cacd96a31d95db3d8305e5ac679",
				"a355822d9171cea964df4d1564d4ebea",
			},
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

	if decoded.Type != PacketType_PACKET_REGISTER_ROUTING_TOKENS {
		t.Errorf("Type mismatch: got %v, want %v", decoded.Type, PacketType_PACKET_REGISTER_ROUTING_TOKENS)
	}
	if decoded.RoutingTokens == nil {
		t.Fatal("Decoded RoutingTokens is nil")
	}
	if len(decoded.RoutingTokens.Tokens) != 2 {
		t.Fatalf("Expected 2 tokens, got %d", len(decoded.RoutingTokens.Tokens))
	}
	if decoded.RoutingTokens.Tokens[0] != "4d434cacd96a31d95db3d8305e5ac679" {
		t.Errorf("Token 0 mismatch: got %s", decoded.RoutingTokens.Tokens[0])
	}
	if decoded.RoutingTokens.Tokens[1] != "a355822d9171cea964df4d1564d4ebea" {
		t.Errorf("Token 1 mismatch: got %s", decoded.RoutingTokens.Tokens[1])
	}
}

func TestNexaWirePacket_DeliveryAck_Extended_EncodeDecode(t *testing.T) {
	sig := bytes.Repeat([]byte{0xEE}, 64)
	orig := &NexaWirePacket{
		PacketId:      "ack_pkt_100",
		Type:          PacketType_PACKET_ACK,
		SenderNxId:    "NX-BOB",
		RecipientNxId: "NX-ALICE",
		Timestamp:     1729483000000,
		Ack: &DeliveryAckPayload{
			OriginalPacketId: "msg_original_001",
			Status:           AckStatus_ACK_DEVICE_DELIVERED,
			RecipientNxId:    "NX-BOB",
			DeviceId:         "DEV-BOB-PIXEL-9",
			Timestamp:        1729483000000,
			Signature:        sig,
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

	if decoded.Type != PacketType_PACKET_ACK {
		t.Errorf("Type mismatch: got %v, want %v", decoded.Type, PacketType_PACKET_ACK)
	}
	if decoded.Ack == nil {
		t.Fatal("Ack payload is nil")
	}
	if decoded.Ack.OriginalPacketId != "msg_original_001" {
		t.Errorf("OriginalPacketId mismatch: got %s", decoded.Ack.OriginalPacketId)
	}
	if decoded.Ack.Status != AckStatus_ACK_DEVICE_DELIVERED {
		t.Errorf("Status mismatch: got %v", decoded.Ack.Status)
	}
	if decoded.Ack.RecipientNxId != "NX-BOB" {
		t.Errorf("RecipientNxId mismatch: got %s", decoded.Ack.RecipientNxId)
	}
	if decoded.Ack.DeviceId != "DEV-BOB-PIXEL-9" {
		t.Errorf("DeviceId mismatch: got %s", decoded.Ack.DeviceId)
	}
	if decoded.Ack.Timestamp != 1729483000000 {
		t.Errorf("Timestamp mismatch: got %d", decoded.Ack.Timestamp)
	}
	if !bytes.Equal(decoded.Ack.Signature, sig) {
		t.Errorf("Signature mismatch")
	}
}

func TestNexaWirePacket_CustodyAck_EncodeDecode(t *testing.T) {
	sig := bytes.Repeat([]byte{0xCC}, 64)
	orig := &NexaWirePacket{
		PacketId:      "custody_ack_001",
		Type:          PacketType_PACKET_CUSTODY_ACK,
		SenderNxId:    "NX-RELAY-NODE-A",
		RecipientNxId: "NX-ALICE",
		Timestamp:     1729483500000,
		CustodyAck: &CustodyAckPayload{
			OriginalPacketId: "env_dtn_deadbeef",
			CustodianNxId:    "NX-RELAY-NODE-A",
			Status:           CustodyStatus_CUSTODY_ACCEPTED,
			Timestamp:        1729483500000,
			Signature:        sig,
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

	if decoded.Type != PacketType_PACKET_CUSTODY_ACK {
		t.Errorf("Type mismatch: got %v, want %v", decoded.Type, PacketType_PACKET_CUSTODY_ACK)
	}
	if decoded.CustodyAck == nil {
		t.Fatal("CustodyAck payload is nil")
	}
	if decoded.CustodyAck.OriginalPacketId != "env_dtn_deadbeef" {
		t.Errorf("OriginalPacketId mismatch: got %s", decoded.CustodyAck.OriginalPacketId)
	}
	if decoded.CustodyAck.CustodianNxId != "NX-RELAY-NODE-A" {
		t.Errorf("CustodianNxId mismatch: got %s", decoded.CustodyAck.CustodianNxId)
	}
	if decoded.CustodyAck.Status != CustodyStatus_CUSTODY_ACCEPTED {
		t.Errorf("Status mismatch: got %v", decoded.CustodyAck.Status)
	}
	if decoded.CustodyAck.Timestamp != 1729483500000 {
		t.Errorf("Timestamp mismatch: got %d", decoded.CustodyAck.Timestamp)
	}
	if !bytes.Equal(decoded.CustodyAck.Signature, sig) {
		t.Errorf("Signature mismatch")
	}
}
