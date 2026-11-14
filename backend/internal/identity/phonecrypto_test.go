package identity

import (
	"encoding/base64"
	"encoding/hex"
	"errors"
	"os"
	"strings"
	"testing"
)

const (
	testHMACKey = "0123456789abcdef0123456789abcdef" // 32 bytes
	testEncKey  = "fedcba9876543210fedcba9876543210" // 32 bytes
)

func withPhoneCryptoEnv(t *testing.T) {
	t.Helper()
	prevHMAC, hadHMAC := os.LookupEnv("PHONE_HMAC_KEY")
	prevEnc, hadEnc := os.LookupEnv("PHONE_ENC_KEY")
	prevEnv, hadEnv := os.LookupEnv("APP_ENV")
	prevENV, hadENV := os.LookupEnv("ENV")

	t.Cleanup(func() {
		restoreEnv("PHONE_HMAC_KEY", prevHMAC, hadHMAC)
		restoreEnv("PHONE_ENC_KEY", prevEnc, hadEnc)
		restoreEnv("APP_ENV", prevEnv, hadEnv)
		restoreEnv("ENV", prevENV, hadENV)
	})
}

func restoreEnv(key, value string, existed bool) {
	if existed {
		_ = os.Setenv(key, value)
	} else {
		_ = os.Unsetenv(key)
	}
}

func TestPhoneCrypto_RoundTripAndDeterministicLookup(t *testing.T) {
	withPhoneCryptoEnv(t)
	_ = os.Setenv("PHONE_HMAC_KEY", testHMACKey)
	_ = os.Setenv("PHONE_ENC_KEY", testEncKey)
	_ = os.Unsetenv("APP_ENV")
	_ = os.Unsetenv("ENV")

	phone := "+919876543210"

	hmac1, err := PhoneHMAC(phone)
	if err != nil {
		t.Fatalf("PhoneHMAC failed: %v", err)
	}
	hmac2, err := PhoneHMAC(phone)
	if err != nil {
		t.Fatalf("PhoneHMAC failed: %v", err)
	}
	if hmac1 != hmac2 {
		t.Fatalf("HMAC must be deterministic: %s != %s", hmac1, hmac2)
	}
	if len(hmac1) != 64 {
		t.Fatalf("expected 64 hex chars of SHA-256, got %d", len(hmac1))
	}
	if strings.Contains(hmac1, "9876543210") {
		t.Fatal("HMAC leaks plaintext phone")
	}

	enc1, err := EncryptPhone(phone)
	if err != nil {
		t.Fatalf("EncryptPhone failed: %v", err)
	}
	enc2, err := EncryptPhone(phone)
	if err != nil {
		t.Fatalf("EncryptPhone failed: %v", err)
	}
	if enc1 == enc2 {
		t.Fatal("encryption must use a random nonce, so ciphertexts must differ")
	}
	if strings.Contains(enc1, "9876543210") {
		t.Fatal("ciphertext leaks plaintext phone")
	}
	if _, err := base64.StdEncoding.DecodeString(enc1); err != nil {
		t.Fatalf("ciphertext is not valid base64: %v", err)
	}

	dec, err := DecryptPhone(enc1)
	if err != nil {
		t.Fatalf("DecryptPhone failed: %v", err)
	}
	if dec != phone {
		t.Fatalf("round-trip mismatch: got %q want %q", dec, phone)
	}
}

func TestPhoneCrypto_DifferentPhonesProduceDifferentHMACs(t *testing.T) {
	withPhoneCryptoEnv(t)
	_ = os.Setenv("PHONE_HMAC_KEY", testHMACKey)
	_ = os.Setenv("PHONE_ENC_KEY", testEncKey)

	a, err := PhoneHMAC("+919876543210")
	if err != nil {
		t.Fatal(err)
	}
	b, err := PhoneHMAC("+919876543211")
	if err != nil {
		t.Fatal(err)
	}
	if a == b {
		t.Fatal("distinct phone numbers must not collide")
	}
}

func TestPhoneCrypto_ProductionWithoutKeysFailsClosed(t *testing.T) {
	withPhoneCryptoEnv(t)
	_ = os.Unsetenv("PHONE_HMAC_KEY")
	_ = os.Unsetenv("PHONE_ENC_KEY")
	_ = os.Setenv("APP_ENV", "production")

	if err := ValidatePhoneCryptoConfig(); !errors.Is(err, ErrPhoneCryptoUnconfigured) {
		t.Fatalf("expected ErrPhoneCryptoUnconfigured in production, got %v", err)
	}
	if _, err := PhoneHMAC("+919876543210"); !errors.Is(err, ErrPhoneCryptoUnconfigured) {
		t.Fatalf("expected lookup to fail closed, got %v", err)
	}
}

func TestPhoneCrypto_SameKeyRejected(t *testing.T) {
	withPhoneCryptoEnv(t)
	_ = os.Setenv("PHONE_HMAC_KEY", testHMACKey)
	_ = os.Setenv("PHONE_ENC_KEY", testHMACKey)
	_ = os.Unsetenv("APP_ENV")
	_ = os.Unsetenv("ENV")

	if err := ValidatePhoneCryptoConfig(); !errors.Is(err, ErrPhoneCryptoUnconfigured) {
		t.Fatalf("expected identical HMAC/enc keys to be rejected, got %v", err)
	}
}

func TestPhoneCrypto_HexAndBase64KeysAccepted(t *testing.T) {
	withPhoneCryptoEnv(t)
	raw := make([]byte, 32)
	for i := range raw {
		raw[i] = byte(i + 1)
	}
	_ = os.Setenv("PHONE_HMAC_KEY", hex.EncodeToString(raw))
	encRaw := make([]byte, 32)
	for i := range encRaw {
		encRaw[i] = byte(255 - i)
	}
	_ = os.Setenv("PHONE_ENC_KEY", base64.StdEncoding.EncodeToString(encRaw))
	_ = os.Unsetenv("APP_ENV")
	_ = os.Unsetenv("ENV")

	if err := ValidatePhoneCryptoConfig(); err != nil {
		t.Fatalf("expected hex/base64 keys to be accepted, got %v", err)
	}
}

func TestPhoneCrypto_DecryptRejectsTampering(t *testing.T) {
	withPhoneCryptoEnv(t)
	_ = os.Setenv("PHONE_HMAC_KEY", testHMACKey)
	_ = os.Setenv("PHONE_ENC_KEY", testEncKey)

	enc, err := EncryptPhone("+919876543210")
	if err != nil {
		t.Fatal(err)
	}
	payload, err := base64.StdEncoding.DecodeString(enc)
	if err != nil {
		t.Fatal(err)
	}
	payload[len(payload)-1] ^= 0xFF
	tampered := base64.StdEncoding.EncodeToString(payload)

	if _, err := DecryptPhone(tampered); !errors.Is(err, ErrPhoneCipherInvalid) {
		t.Fatalf("expected ErrPhoneCipherInvalid for tampered ciphertext, got %v", err)
	}
}
