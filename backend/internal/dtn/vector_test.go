package dtn

import (
	"crypto/ed25519"
	"crypto/sha256"
	"encoding/hex"
	"testing"
)

func TestGenerateAndVerifyCrossPlatformVector(t *testing.T) {
	// RFC 8032 Test Vector 1 seed
	seedHex := "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"
	seedBytes, _ := hex.DecodeString(seedHex)
	privKey := ed25519.NewKeyFromSeed(seedBytes)
	pubKey := privKey.Public().(ed25519.PublicKey)
	expectedPubKeyHex := "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"
	if hex.EncodeToString(pubKey) != expectedPubKeyHex {
		t.Fatalf("expected RFC 8032 pubkey %s, got %s", expectedPubKeyHex, hex.EncodeToString(pubKey))
	}

	var uuid [16]byte
	uuidBytes, _ := hex.DecodeString("0102030405060708090a0b0c0d0e0f10")
	copy(uuid[:], uuidBytes)

	var blindToken [16]byte
	blindBytes, _ := hex.DecodeString("1112131415161718191a1b1c1d1e1f20")
	copy(blindToken[:], blindBytes)

	payload := []byte("Hello NXDT Cross-Platform Verified Vector")
	hash := sha256.Sum256(payload)

	var pubKeyBytes [32]byte
	copy(pubKeyBytes[:], pubKey)

	env := &Envelope{
		ProtocolVersion:  ProtocolVersion1,
		EnvelopeType:     TypeStandardData,
		AlgorithmSuite:   SuiteEd25519Sha256,
		Flags:            0,
		HopCount:         0,
		HopLimit:         7,
		RelayTimestamp:   0,
		RelayFlags:       0,
		MessageUUID:      uuid,
		SenderPublicKey:  pubKeyBytes,
		BlindToken:       blindToken,
		CreatedAt:        1700000000,
		ExpiresAt:        1700172800,
		CiphertextLength: uint32(len(payload)),
		CiphertextSha256: hash,
		Payload:          payload,
	}

	canonical := env.GetCanonicalSigningData()
	sig := ed25519.Sign(privKey, canonical)
	copy(env.Signature[:], sig)

	serialized := env.Serialize()
	t.Logf("\n--- Cross-Platform Test Vector ---")
	t.Logf("SeedHex: %s", seedHex)
	t.Logf("PubKeyHex: %s", expectedPubKeyHex)
	t.Logf("CiphertextSha256Hex: %s", hex.EncodeToString(hash[:]))
	t.Logf("CanonicalSigningDataHex: %s", hex.EncodeToString(canonical))
	t.Logf("SignatureHex: %s", hex.EncodeToString(sig))
	t.Logf("SerializedVectorHex: %s", hex.EncodeToString(serialized))

	// Verify in Go
	parsed, err := ParseEnvelope(serialized, true)
	if err != nil {
		t.Fatalf("ParseEnvelope failed: %v", err)
	}
	if err := parsed.VerifySignature(); err != nil {
		t.Fatalf("VerifySignature failed: %v", err)
	}
}
