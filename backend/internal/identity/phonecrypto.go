package identity

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"strings"
)

// Phone numbers are never indexed or compared in plaintext. A deterministic
// HMAC is stored for uniqueness checks and lookups, while a randomised
// AES-256-GCM ciphertext is stored so the owner-facing value can be recovered
// without keeping the plaintext in the database.
var (
	ErrPhoneCryptoUnconfigured = errors.New("phone encryption keys are not configured")
	ErrPhoneCipherInvalid      = errors.New("invalid encrypted phone value")
)

const (
	phoneHMACDomain = "nexa:phone:hmac:v1:"
	phoneEncAAD     = "nexa:phone:enc:v1"
	phoneKeyLength  = 32
)

// PhoneCrypto holds the two independent keys used for phone protection.
// The HMAC key and the encryption key must never be the same value.
type PhoneCrypto struct {
	hmacKey []byte
	encKey  []byte
}

func envIsProduction() bool {
	env := strings.ToLower(strings.TrimSpace(os.Getenv("APP_ENV")))
	if env == "" {
		env = strings.ToLower(strings.TrimSpace(os.Getenv("ENV")))
	}
	return env == "production"
}

// decodePhoneKey accepts a 32-byte key as raw text, hex, or base64.
func decodePhoneKey(raw string, name string) ([]byte, error) {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return nil, fmt.Errorf("%w: %s is empty", ErrPhoneCryptoUnconfigured, name)
	}

	if len(raw) == phoneKeyLength*2 {
		if decoded, err := hex.DecodeString(raw); err == nil && len(decoded) == phoneKeyLength {
			return decoded, nil
		}
	}
	if decoded, err := base64.StdEncoding.DecodeString(raw); err == nil && len(decoded) == phoneKeyLength {
		return decoded, nil
	}
	if len(raw) == phoneKeyLength {
		return []byte(raw), nil
	}

	return nil, fmt.Errorf("%w: %s must be %d bytes encoded as raw, hex, or base64", ErrPhoneCryptoUnconfigured, name, phoneKeyLength)
}

// LoadPhoneCryptoFromEnv resolves the phone protection keys. In production a
// missing key is a hard failure: there is no shipped fallback secret. Outside
// production a clearly-labelled deterministic development key is derived so the
// local test suite can run without provisioning secrets.
func LoadPhoneCryptoFromEnv() (*PhoneCrypto, error) {
	hmacRaw := os.Getenv("PHONE_HMAC_KEY")
	encRaw := os.Getenv("PHONE_ENC_KEY")

	if hmacRaw == "" || encRaw == "" {
		if envIsProduction() {
			missing := "PHONE_HMAC_KEY"
			if hmacRaw != "" {
				missing = "PHONE_ENC_KEY"
			}
			return nil, fmt.Errorf("%w: %s is required when APP_ENV=production", ErrPhoneCryptoUnconfigured, missing)
		}

		devHMAC := sha256.Sum256([]byte("nexa_dev_only_phone_hmac_key_not_for_production"))
		devEnc := sha256.Sum256([]byte("nexa_dev_only_phone_enc_key_not_for_production"))
		return &PhoneCrypto{hmacKey: devHMAC[:], encKey: devEnc[:]}, nil
	}

	hmacKey, err := decodePhoneKey(hmacRaw, "PHONE_HMAC_KEY")
	if err != nil {
		return nil, err
	}
	encKey, err := decodePhoneKey(encRaw, "PHONE_ENC_KEY")
	if err != nil {
		return nil, err
	}
	if hmac.Equal(hmacKey, encKey) {
		return nil, fmt.Errorf("%w: PHONE_HMAC_KEY and PHONE_ENC_KEY must be different", ErrPhoneCryptoUnconfigured)
	}

	return &PhoneCrypto{hmacKey: hmacKey, encKey: encKey}, nil
}

// ValidatePhoneCryptoConfig can be called during startup to fail fast when
// production secrets are missing.
func ValidatePhoneCryptoConfig() error {
	_, err := LoadPhoneCryptoFromEnv()
	return err
}

func getPhoneCrypto() (*PhoneCrypto, error) {
	return LoadPhoneCryptoFromEnv()
}

// HMAC returns the deterministic lookup hash for a canonical phone number.
func (p *PhoneCrypto) HMAC(phone string) string {
	mac := hmac.New(sha256.New, p.hmacKey)
	mac.Write([]byte(phoneHMACDomain))
	mac.Write([]byte(phone))
	return hex.EncodeToString(mac.Sum(nil))
}

// Encrypt returns base64(nonce || AES-256-GCM ciphertext) for a phone number.
func (p *PhoneCrypto) Encrypt(phone string) (string, error) {
	block, err := aes.NewCipher(p.encKey)
	if err != nil {
		return "", err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return "", err
	}

	nonce := make([]byte, gcm.NonceSize())
	if _, err := rand.Read(nonce); err != nil {
		return "", err
	}

	sealed := gcm.Seal(nil, nonce, []byte(phone), []byte(phoneEncAAD))
	payload := append(nonce, sealed...)
	return base64.StdEncoding.EncodeToString(payload), nil
}

// Decrypt reverses Encrypt. It returns a typed error for malformed values.
func (p *PhoneCrypto) Decrypt(encoded string) (string, error) {
	if encoded == "" {
		return "", ErrPhoneCipherInvalid
	}

	payload, err := base64.StdEncoding.DecodeString(encoded)
	if err != nil {
		return "", fmt.Errorf("%w: %v", ErrPhoneCipherInvalid, err)
	}

	block, err := aes.NewCipher(p.encKey)
	if err != nil {
		return "", err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return "", err
	}
	if len(payload) < gcm.NonceSize() {
		return "", ErrPhoneCipherInvalid
	}

	nonce := payload[:gcm.NonceSize()]
	ciphertext := payload[gcm.NonceSize():]
	plaintext, err := gcm.Open(nil, nonce, ciphertext, []byte(phoneEncAAD))
	if err != nil {
		return "", fmt.Errorf("%w: %v", ErrPhoneCipherInvalid, err)
	}
	return string(plaintext), nil
}

// PhoneHMAC is the package-level helper used by persistence adapters.
func PhoneHMAC(phone string) (string, error) {
	if phone == "" {
		return "", nil
	}
	p, err := getPhoneCrypto()
	if err != nil {
		return "", err
	}
	return p.HMAC(phone), nil
}

// EncryptPhone encrypts a phone number for storage.
func EncryptPhone(phone string) (string, error) {
	if phone == "" {
		return "", nil
	}
	p, err := getPhoneCrypto()
	if err != nil {
		return "", err
	}
	return p.Encrypt(phone)
}

// DecryptPhone decrypts a stored phone number.
func DecryptPhone(encoded string) (string, error) {
	p, err := getPhoneCrypto()
	if err != nil {
		return "", err
	}
	return p.Decrypt(encoded)
}
