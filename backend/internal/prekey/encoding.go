package prekey

import (
	"bytes"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"fmt"
	"strings"
)

func newUUID(prefix string) string {
	var b [16]byte
	_, _ = rand.Read(b[:])
	b[6] = (b[6] & 0x0f) | 0x40
	b[8] = (b[8] & 0x3f) | 0x80
	return fmt.Sprintf("%s%08x-%04x-%04x-%04x-%012x", prefix, b[0:4], b[4:6], b[6:8], b[8:10], b[10:16])
}

const (
	MagicBindIdentity = "NXBI" // 0x4E, 0x58, 0x42, 0x49
	MagicSignSPK      = "NXSP" // 0x4E, 0x58, 0x53, 0x50

	ProtocolVersion1 = 0x01
	TagBindIdentity  = 0x01
	TagSignSPK       = 0x02
)

// EncodeIdentityBinding produces the canonical length-prefixed (TLV) binary attestation
// for binding an X25519 identity DH key to an Ed25519 signing key.
func EncodeIdentityBinding(nxId, deviceId string, signingPub, dhPub []byte, timestamp int64) ([]byte, error) {
	if len(signingPub) != ed25519.PublicKeySize {
		return nil, fmt.Errorf("invalid signing public key length: %d (expected 32)", len(signingPub))
	}
	if len(dhPub) != 32 {
		return nil, fmt.Errorf("invalid DH public key length: %d (expected 32)", len(dhPub))
	}

	nxIdBytes := []byte(strings.TrimSpace(nxId))
	devIdBytes := []byte(strings.TrimSpace(deviceId))

	if len(nxIdBytes) > 65535 || len(devIdBytes) > 65535 {
		return nil, errors.New("identifier length exceeds uint16 max")
	}

	buf := new(bytes.Buffer)
	buf.WriteString(MagicBindIdentity)                  // 4 bytes
	buf.WriteByte(ProtocolVersion1)                     // 1 byte
	buf.WriteByte(TagBindIdentity)                      // 1 byte
	_ = binary.Write(buf, binary.BigEndian, timestamp)  // 8 bytes (uint64)
	_ = binary.Write(buf, binary.BigEndian, uint16(len(nxIdBytes)))
	buf.Write(nxIdBytes)
	_ = binary.Write(buf, binary.BigEndian, uint16(len(devIdBytes)))
	buf.Write(devIdBytes)
	buf.Write(signingPub) // 32 bytes
	buf.Write(dhPub)      // 32 bytes

	return buf.Bytes(), nil
}

// VerifyIdentityBindingSignature validates the Ed25519 signature over the canonical binding format,
// falling back to legacy delimited format if necessary for backward compatibility.
func VerifyIdentityBindingSignature(signingPubHex, dhPubHex, signatureHex, nxId, deviceId string, timestamp int64) (bool, error) {
	sigBytes, err := hex.DecodeString(strings.TrimSpace(signatureHex))
	if err != nil || len(sigBytes) != ed25519.SignatureSize {
		return false, fmt.Errorf("invalid Ed25519 signature hex or length: %w", err)
	}

	signPubBytes, err := hex.DecodeString(strings.TrimSpace(signingPubHex))
	if err != nil || len(signPubBytes) != ed25519.PublicKeySize {
		return false, fmt.Errorf("invalid signing public key hex or length: %w", err)
	}

	dhPubBytes, err := hex.DecodeString(strings.TrimSpace(dhPubHex))
	if err != nil || len(dhPubBytes) != 32 {
		return false, fmt.Errorf("invalid DH public key hex or length: %w", err)
	}

	// 1. Primary check: Canonical Length-Prefixed Binary TLV Encoding
	canonicalBytes, err := EncodeIdentityBinding(nxId, deviceId, signPubBytes, dhPubBytes, timestamp)
	if err == nil && ed25519.Verify(signPubBytes, canonicalBytes, sigBytes) {
		return true, nil
	}

	// 2. Backward compatibility fallback: Colon-delimited format
	delimitedBytes := []byte(fmt.Sprintf("NEXA_BIND_IK_V1:%s:%s:%s:%d", nxId, deviceId, strings.ToLower(dhPubHex), timestamp))
	if ed25519.Verify(signPubBytes, delimitedBytes, sigBytes) {
		return true, nil
	}

	// 3. Fallback: Raw public key bytes
	if ed25519.Verify(signPubBytes, dhPubBytes, sigBytes) {
		return true, nil
	}

	return false, ErrInvalidSignature
}

// EncodeSignedPrekey produces the canonical length-prefixed (TLV) binary attestation
// for certifying an X25519 Signed Prekey ($SPK$).
func EncodeSignedPrekey(spkId uint32, nxId, deviceId string, spkPub []byte, timestamp int64) ([]byte, error) {
	if len(spkPub) != 32 {
		return nil, fmt.Errorf("invalid SPK public key length: %d (expected 32)", len(spkPub))
	}

	nxIdBytes := []byte(strings.TrimSpace(nxId))
	devIdBytes := []byte(strings.TrimSpace(deviceId))

	if len(nxIdBytes) > 65535 || len(devIdBytes) > 65535 {
		return nil, errors.New("identifier length exceeds uint16 max")
	}

	buf := new(bytes.Buffer)
	buf.WriteString(MagicSignSPK)                       // 4 bytes
	buf.WriteByte(ProtocolVersion1)                     // 1 byte
	buf.WriteByte(TagSignSPK)                           // 1 byte
	_ = binary.Write(buf, binary.BigEndian, timestamp)  // 8 bytes (uint64)
	_ = binary.Write(buf, binary.BigEndian, spkId)      // 4 bytes (uint32)
	_ = binary.Write(buf, binary.BigEndian, uint16(len(nxIdBytes)))
	buf.Write(nxIdBytes)
	_ = binary.Write(buf, binary.BigEndian, uint16(len(devIdBytes)))
	buf.Write(devIdBytes)
	buf.Write(spkPub) // 32 bytes

	return buf.Bytes(), nil
}

// VerifySignedPrekeySignature validates the Ed25519 signature over the canonical SPK format,
// with backward compatibility fallback.
func VerifySignedPrekeySignature(signingPubHex, spkPubHex, signatureHex string, spkId uint32, nxId, deviceId string, timestamp int64) (bool, error) {
	sigBytes, err := hex.DecodeString(strings.TrimSpace(signatureHex))
	if err != nil || len(sigBytes) != ed25519.SignatureSize {
		return false, fmt.Errorf("invalid Ed25519 signature hex or length: %w", err)
	}

	signPubBytes, err := hex.DecodeString(strings.TrimSpace(signingPubHex))
	if err != nil || len(signPubBytes) != ed25519.PublicKeySize {
		return false, fmt.Errorf("invalid signing public key hex or length: %w", err)
	}

	spkPubBytes, err := hex.DecodeString(strings.TrimSpace(spkPubHex))
	if err != nil || len(spkPubBytes) != 32 {
		return false, fmt.Errorf("invalid SPK public key hex or length: %w", err)
	}

	// 1. Primary check: Canonical Length-Prefixed Binary TLV Encoding
	canonicalBytes, err := EncodeSignedPrekey(spkId, nxId, deviceId, spkPubBytes, timestamp)
	if err == nil && ed25519.Verify(signPubBytes, canonicalBytes, sigBytes) {
		return true, nil
	}

	// 2. Backward compatibility fallback: Colon-delimited format
	delimitedBytes := []byte(fmt.Sprintf("NEXA_SPK_SIG_V1:%s:%s:%d:%s:%d", nxId, deviceId, spkId, strings.ToLower(spkPubHex), timestamp))
	if ed25519.Verify(signPubBytes, delimitedBytes, sigBytes) {
		return true, nil
	}

	// 3. Fallback: Raw public key bytes
	if ed25519.Verify(signPubBytes, spkPubBytes, sigBytes) {
		return true, nil
	}

	return false, ErrInvalidSignature
}
