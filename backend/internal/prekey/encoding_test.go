package prekey

import (
	"crypto/ed25519"
	"crypto/rand"
	"encoding/hex"
	"testing"
	"time"
)

func TestCanonicalIdentityBindingEncodingAndVerification(t *testing.T) {
	pub, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatalf("failed to generate ed25519 key: %v", err)
	}

	dhPub := make([]byte, 32)
	_, _ = rand.Read(dhPub)

	nxId := "NX-ALICE123"
	deviceId := "dev_alice_01"
	ts := time.Now().UnixMilli()

	// Encode canonical TLV bytes
	canonicalBytes, err := EncodeIdentityBinding(nxId, deviceId, pub, dhPub, ts)
	if err != nil {
		t.Fatalf("failed to encode canonical identity binding: %v", err)
	}

	// Verify magic prefix
	if string(canonicalBytes[:4]) != MagicBindIdentity {
		t.Errorf("expected magic %s, got %s", MagicBindIdentity, string(canonicalBytes[:4]))
	}

	// Sign
	sig := ed25519.Sign(priv, canonicalBytes)

	// Verify valid signature
	valid, err := VerifyIdentityBindingSignature(
		hex.EncodeToString(pub),
		hex.EncodeToString(dhPub),
		hex.EncodeToString(sig),
		nxId, deviceId, ts,
	)
	if err != nil || !valid {
		t.Fatalf("expected valid signature verification, got err=%v", err)
	}

	// Verify tampering with timestamp fails
	tamperedValid, _ := VerifyIdentityBindingSignature(
		hex.EncodeToString(pub),
		hex.EncodeToString(dhPub),
		hex.EncodeToString(sig),
		nxId, deviceId, ts+1,
	)
	if tamperedValid {
		t.Errorf("expected tampered timestamp to fail signature check")
	}

	// Verify tampering with DH pubkey fails
	tamperedDh := make([]byte, 32)
	copy(tamperedDh, dhPub)
	tamperedDh[0] ^= 0xFF
	tamperedValid, _ = VerifyIdentityBindingSignature(
		hex.EncodeToString(pub),
		hex.EncodeToString(tamperedDh),
		hex.EncodeToString(sig),
		nxId, deviceId, ts,
	)
	if tamperedValid {
		t.Errorf("expected tampered DH public key to fail signature check")
	}
}

func TestCanonicalSignedPrekeyEncodingAndVerification(t *testing.T) {
	pub, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatalf("failed to generate ed25519 key: %v", err)
	}

	spkPub := make([]byte, 32)
	_, _ = rand.Read(spkPub)

	nxId := "NX-BOB45678"
	deviceId := "dev_bob_01"
	spkId := uint32(1)
	ts := time.Now().UnixMilli()

	canonicalBytes, err := EncodeSignedPrekey(spkId, nxId, deviceId, spkPub, ts)
	if err != nil {
		t.Fatalf("failed to encode canonical signed prekey: %v", err)
	}

	if string(canonicalBytes[:4]) != MagicSignSPK {
		t.Errorf("expected magic %s, got %s", MagicSignSPK, string(canonicalBytes[:4]))
	}

	sig := ed25519.Sign(priv, canonicalBytes)

	valid, err := VerifySignedPrekeySignature(
		hex.EncodeToString(pub),
		hex.EncodeToString(spkPub),
		hex.EncodeToString(sig),
		spkId, nxId, deviceId, ts,
	)
	if err != nil || !valid {
		t.Fatalf("expected valid signature verification, got err=%v", err)
	}

	// Tampered SPK ID should fail
	tamperedValid, _ := VerifySignedPrekeySignature(
		hex.EncodeToString(pub),
		hex.EncodeToString(spkPub),
		hex.EncodeToString(sig),
		spkId+1, nxId, deviceId, ts,
	)
	if tamperedValid {
		t.Errorf("expected tampered SPK ID to fail signature check")
	}
}
