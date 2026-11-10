package dtn

import (
	"crypto/ed25519"
	"crypto/rand"
	"encoding/binary"
	"testing"
	"time"
)

func generateTestKeyPair(t *testing.T) (ed25519.PublicKey, ed25519.PrivateKey) {
	t.Helper()
	pub, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatalf("failed to generate Ed25519 test keypair: %v", err)
	}
	return pub, priv
}

func TestEnvelope_SerializationRoundTrip(t *testing.T) {
	_, priv := generateTestKeyPair(t)

	var blindToken [16]byte
	copy(blindToken[:], []byte("0123456789abcdef"))

	payload := []byte("Autonomous store-carry-forward test payload")

	env, err := NewAuthenticatedEnvelope(blindToken, payload, priv, TypeStandardData, 7, 3600, nil)
	if err != nil {
		t.Fatalf("failed to create envelope: %v", err)
	}

	serialized := env.Serialize()
	if len(serialized) != NxdtHeaderSize+len(payload) {
		t.Fatalf("expected serialized size %d, got %d", NxdtHeaderSize+len(payload), len(serialized))
	}

	parsed, err := ParseEnvelope(serialized, true)
	if err != nil {
		t.Fatalf("failed to parse envelope: %v", err)
	}

	if parsed.ProtocolVersion != ProtocolVersion1 {
		t.Errorf("expected version %d, got %d", ProtocolVersion1, parsed.ProtocolVersion)
	}
	if parsed.EnvelopeType != TypeStandardData {
		t.Errorf("expected type %d, got %d", TypeStandardData, parsed.EnvelopeType)
	}
	if parsed.HopLimit != 7 {
		t.Errorf("expected hop limit 7, got %d", parsed.HopLimit)
	}
	if parsed.HopCount != 0 {
		t.Errorf("expected hop count 0, got %d", parsed.HopCount)
	}
	if string(parsed.Payload) != string(payload) {
		t.Errorf("payload mismatch: expected %s, got %s", payload, parsed.Payload)
	}
	if parsed.BlindToken != blindToken {
		t.Errorf("blind token mismatch")
	}
}

func TestEnvelope_EmptyPayload(t *testing.T) {
	_, priv := generateTestKeyPair(t)
	var blindToken [16]byte

	env, err := NewAuthenticatedEnvelope(blindToken, []byte{}, priv, TypeAckPacket, 5, 1800, nil)
	if err != nil {
		t.Fatalf("failed to create envelope with empty payload: %v", err)
	}

	serialized := env.Serialize()
	if len(serialized) != NxdtHeaderSize {
		t.Fatalf("expected size %d, got %d", NxdtHeaderSize, len(serialized))
	}

	parsed, err := ParseEnvelope(serialized, true)
	if err != nil {
		t.Fatalf("failed to parse empty payload envelope: %v", err)
	}

	if len(parsed.Payload) != 0 {
		t.Errorf("expected empty payload, got %d bytes", len(parsed.Payload))
	}
}

func TestEnvelope_MaxPayloadSize(t *testing.T) {
	_, priv := generateTestKeyPair(t)
	var blindToken [16]byte

	maxPayload := make([]byte, MaxPayloadSize)
	env, err := NewAuthenticatedEnvelope(blindToken, maxPayload, priv, TypeStandardData, 7, 3600, nil)
	if err != nil {
		t.Fatalf("failed to create max size envelope: %v", err)
	}

	serialized := env.Serialize()
	if len(serialized) != MaxPacketSize {
		t.Fatalf("expected max packet size %d, got %d", MaxPacketSize, len(serialized))
	}

	parsed, err := ParseEnvelope(serialized, true)
	if err != nil {
		t.Fatalf("failed to parse max size envelope: %v", err)
	}
	if len(parsed.Payload) != MaxPayloadSize {
		t.Errorf("expected %d payload bytes, got %d", MaxPayloadSize, len(parsed.Payload))
	}

	// Oversized payload (> MaxPayloadSize) must be rejected
	oversized := make([]byte, MaxPayloadSize+1)
	_, err = NewAuthenticatedEnvelope(blindToken, oversized, priv, TypeStandardData, 7, 3600, nil)
	if err == nil {
		t.Fatalf("expected error for oversized payload, got nil")
	}
}

func TestEnvelope_TruncatedOrMalformedBytes(t *testing.T) {
	// Less than legacy header
	if _, err := ParseEnvelope(make([]byte, 30), true); err == nil {
		t.Errorf("expected error for packet under 45 bytes")
	}

	// Magic is "NXDT", but truncated under 200 bytes
	truncatedNxdt := make([]byte, 150)
	copy(truncatedNxdt[0:4], MagicBytes[:])
	truncatedNxdt[4] = ProtocolVersion1
	if _, err := ParseEnvelope(truncatedNxdt, true); err == nil {
		t.Errorf("expected error for truncated NXDT header")
	}

	// Header claims 100 bytes payload, but only 10 provided
	_, priv := generateTestKeyPair(t)
	var blindToken [16]byte
	env, _ := NewAuthenticatedEnvelope(blindToken, []byte("short"), priv, TypeStandardData, 7, 3600, nil)
	serialized := env.Serialize()
	// Corrupt ciphertext length to claim 100 bytes
	binary.BigEndian.PutUint32(serialized[100:104], 100)
	if _, err := ParseEnvelope(serialized, true); err == nil {
		t.Errorf("expected error for payload length mismatch")
	}
}

func TestEnvelope_InvalidMagicAndVersion(t *testing.T) {
	_, priv := generateTestKeyPair(t)
	var blindToken [16]byte
	env, _ := NewAuthenticatedEnvelope(blindToken, []byte("test"), priv, TypeStandardData, 7, 3600, nil)
	serialized := env.Serialize()

	// Unsupported protocol version
	serialized[4] = 99
	if _, err := ParseEnvelope(serialized, true); err == nil {
		t.Errorf("expected error for unsupported version 99")
	}

	// Unsupported suite
	serialized[4] = ProtocolVersion1
	serialized[6] = 42
	if _, err := ParseEnvelope(serialized, true); err == nil {
		t.Errorf("expected error for unsupported algorithm suite 42")
	}
}

func TestEnvelope_TamperedCiphertext_FailsVerification(t *testing.T) {
	_, priv := generateTestKeyPair(t)
	var blindToken [16]byte
	payload := []byte("Original secret ciphertext")
	env, _ := NewAuthenticatedEnvelope(blindToken, payload, priv, TypeStandardData, 7, 3600, nil)
	serialized := env.Serialize()

	// Tamper with a single bit in the payload (offset 200)
	serialized[205] ^= 0x01

	_, err := ParseEnvelope(serialized, true)
	if err != ErrCiphertextHashMismatch {
		t.Fatalf("expected ErrCiphertextHashMismatch, got %v", err)
	}
}

func TestEnvelope_TamperedImmutableHeader_FailsSignature(t *testing.T) {
	_, priv := generateTestKeyPair(t)
	var blindToken [16]byte
	payload := []byte("Test immutable header tamper")
	env, _ := NewAuthenticatedEnvelope(blindToken, payload, priv, TypeStandardData, 7, 3600, nil)

	// Tamper with message UUID (offset 25)
	serialized := env.Serialize()
	serialized[25] ^= 0xFF

	parsed, err := ParseEnvelope(serialized, false)
	if err != nil {
		t.Fatalf("parse without verify failed: %v", err)
	}

	if err := parsed.VerifySignature(); err != ErrInvalidSignature {
		t.Fatalf("expected ErrInvalidSignature on tampered UUID, got %v", err)
	}

	// Tamper with expiry timestamp (offset 95)
	serialized2 := env.Serialize()
	serialized2[95] ^= 0xFF
	parsed2, _ := ParseEnvelope(serialized2, false)
	if err := parsed2.VerifySignature(); err != ErrInvalidSignature {
		t.Fatalf("expected ErrInvalidSignature on tampered expiry, got %v", err)
	}

	// Tamper with sender public key (offset 40)
	serialized3 := env.Serialize()
	serialized3[40] ^= 0xFF
	parsed3, _ := ParseEnvelope(serialized3, false)
	if err := parsed3.VerifySignature(); err != ErrInvalidSignature {
		t.Fatalf("expected ErrInvalidSignature on tampered sender pubkey, got %v", err)
	}
}

func TestEnvelope_RelayHopDecrement_DoesNotInvalidateSignature(t *testing.T) {
	_, priv := generateTestKeyPair(t)
	var blindToken [16]byte
	payload := []byte("Relay hop decrement test")

	env, err := NewAuthenticatedEnvelope(blindToken, payload, priv, TypeStandardData, 7, 3600, nil)
	if err != nil {
		t.Fatalf("failed to create envelope: %v", err)
	}

	// Simulate 7 intermediate relay hops
	current := env
	for hop := 1; hop <= 7; hop++ {
		decremented, err := current.WithDecrementedHop(time.Now().Unix())
		if err != nil {
			t.Fatalf("hop %d decrement failed: %v", hop, err)
		}

		if decremented.HopCount != uint8(hop) {
			t.Errorf("hop %d: expected hop count %d, got %d", hop, hop, decremented.HopCount)
		}
		if decremented.HopLimit != uint8(7-hop) {
			t.Errorf("hop %d: expected remaining limit %d, got %d", hop, 7-hop, decremented.HopLimit)
		}

		// The Ed25519 signature over the immutable header MUST remain 100% valid!
		if err := decremented.VerifySignature(); err != nil {
			t.Fatalf("hop %d: signature invalidated after hop decrement: %v", hop, err)
		}

		// Verify serialized round-trip at this hop
		serialized := decremented.Serialize()
		parsed, err := ParseEnvelope(serialized, true)
		if err != nil {
			t.Fatalf("hop %d: failed to parse decremented serialized envelope: %v", hop, err)
		}
		if parsed.HopCount != uint8(hop) || parsed.HopLimit != uint8(7-hop) {
			t.Errorf("hop %d: parsed hop count/limit mismatch", hop)
		}

		current = decremented
	}

	// Attempting to decrement beyond limit (0 remaining) must return error
	if _, err := current.WithDecrementedHop(time.Now().Unix()); err != ErrHopLimitZero {
		t.Fatalf("expected ErrHopLimitZero, got %v", err)
	}
}

func TestEnvelope_LegacyEnvelopeCompatibility(t *testing.T) {
	// Construct a legacy 45-byte header envelope
	payload := []byte("Legacy unsigned DTN envelope")
	rawLegacy := make([]byte, LegacyHeaderSize+len(payload))

	var uuid [16]byte
	copy(uuid[:], []byte("legacy_uuid_1234"))
	copy(rawLegacy[0:16], uuid[:])

	var blindToken [16]byte
	copy(blindToken[:], []byte("legacy_token_123"))
	copy(rawLegacy[16:32], blindToken[:])

	rawLegacy[32] = 5 // hopLimit
	binary.BigEndian.PutUint64(rawLegacy[33:41], uint64(time.Now().Unix()+3600))
	binary.BigEndian.PutUint32(rawLegacy[41:45], uint32(len(payload)))
	copy(rawLegacy[45:], payload)

	env, err := ParseEnvelope(rawLegacy, true)
	if err != nil {
		t.Fatalf("failed to parse legacy envelope: %v", err)
	}

	if !env.IsLegacy() {
		t.Errorf("expected IsLegacy() == true")
	}
	if env.ProtocolVersion != ProtocolVersionLegacy {
		t.Errorf("expected ProtocolVersionLegacy, got %d", env.ProtocolVersion)
	}
	if env.HopLimit != 5 {
		t.Errorf("expected hop limit 5, got %d", env.HopLimit)
	}
	if string(env.Payload) != string(payload) {
		t.Errorf("expected payload %s, got %s", payload, env.Payload)
	}

	// Reserializing legacy envelope preserves 45 + N bytes format
	reserialized := env.Serialize()
	if len(reserialized) != len(rawLegacy) {
		t.Errorf("expected legacy size %d, got %d", len(rawLegacy), len(reserialized))
	}
}
