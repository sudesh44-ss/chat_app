package identity

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"fmt"
	"math/big"
	"regexp"
	"strings"
)

// Allowed character set for the locked NX-XXXXXXXX format:
// Uppercase Base32 characters avoiding visually ambiguous glyphs (0, O, 1, I).
// Exactly 32 characters: 23456789ABCDEFGHJKLMNPQRSTUVWXYZ.
const NxIdCharset = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"

var nxIdRegex = regexp.MustCompile(`^NX-[A-Z0-9]{8}$`)

// GenerateRandomNxID generates a cryptographically secure random NX-ID adhering strictly
// to the locked format: NX- followed by exactly 8 random uppercase alphanumeric characters.
// Example outputs: NX-29AB731Q, NX-A7K92B4M, NX-8Z3P1R6C
func GenerateRandomNxID() (string, error) {
	const idLength = 8
	charsetLen := big.NewInt(int64(len(NxIdCharset)))
	buf := make([]byte, idLength)

	for i := 0; i < idLength; i++ {
		idx, err := rand.Int(rand.Reader, charsetLen)
		if err != nil {
			return "", fmt.Errorf("entropy source failure during NX-ID generation: %w", err)
		}
		buf[i] = NxIdCharset[idx.Int64()]
	}

	return "NX-" + string(buf), nil
}

// IsValidNxID validates whether a given NX-ID strictly conforms to the NX-XXXXXXXX specification.
func IsValidNxID(nxID string) bool {
	return nxIdRegex.MatchString(strings.TrimSpace(nxID))
}

// Legacy Crockford-style base32 charset (maintained for backward-compatible test fixtures)
const legacyBase32Charset = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

// DeriveNxID (Legacy) computes deterministic NX-ID in legacy format NX-XXXX-XXX.
// Deprecated: Production code must use GenerateRandomNxID() to comply with the locked NX-XXXXXXXX specification.
func DeriveNxID(pubKeyBytes []byte, salt int) string {
	h := sha256.New()
	h.Write(pubKeyBytes)
	if salt > 0 {
		h.Write([]byte(fmt.Sprintf("_salt_%d", salt)))
	}
	hash := h.Sum(nil)

	numVal := binary.BigEndian.Uint16(hash[0:2])
	digits := 1000 + int(numVal%9000)

	c1 := legacyBase32Charset[int(hash[2])%len(legacyBase32Charset)]
	c2 := legacyBase32Charset[int(hash[3])%len(legacyBase32Charset)]
	c3 := legacyBase32Charset[int(hash[4])%len(legacyBase32Charset)]

	return fmt.Sprintf("NX-%04d-%c%c%c", digits, c1, c2, c3)
}

// DeriveNxId is a case-insensitive alias for DeriveNxID.
func DeriveNxId(pubKeyBytes []byte, salt int) string {
	return DeriveNxID(pubKeyBytes, salt)
}

// DeriveNxIDFromHex (Legacy) computes NX-ID from 64-char hex string.
func DeriveNxIDFromHex(pubKeyHex string, salt int) (string, error) {
	pubBytes, err := hex.DecodeString(strings.TrimSpace(pubKeyHex))
	if err != nil || len(pubBytes) != 32 {
		return "", fmt.Errorf("invalid 32-byte public key hex: %s", pubKeyHex)
	}
	return DeriveNxID(pubBytes, salt), nil
}
