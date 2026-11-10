package dtn

import (
	"crypto/ed25519"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"fmt"
	"time"
)

// Magic identifier bytes for NXDT protocol (0x4E, 0x58, 0x44, 0x54).
var MagicBytes = [4]byte{0x4E, 0x58, 0x44, 0x54}

const (
	MagicString = "NXDT"

	ProtocolVersion1      uint8 = 1
	ProtocolVersionLegacy uint8 = 0

	TypeStandardData uint8 = 1
	TypeAckPacket    uint8 = 2
	TypeControlPacket uint8 = 3

	SuiteEd25519Sha256 uint8 = 1

	NxdtHeaderSize           = 200
	CanonicalSigningDataSize = 124
	LegacyHeaderSize         = 45

	MaxHopLimit          uint8 = 15
	DefaultHopLimit      uint8 = 7
	DefaultTtlSeconds    int64 = 48 * 3600
	MaxTtlSeconds        int64 = 7 * 86400
	MaxFutureSkewSeconds int64 = 300 // 5 minutes

	MaxPacketSize  = 64 * 1024 // 64 KB
	MaxPayloadSize = MaxPacketSize - NxdtHeaderSize
)

var (
	ErrPacketTooShort          = errors.New("packet is too short to contain minimum header")
	ErrPacketTooLarge          = errors.New("packet exceeds maximum allowed size (64 KB)")
	ErrUnsupportedVersion      = errors.New("unsupported protocol version")
	ErrUnsupportedSuite        = errors.New("unsupported cryptographic algorithm suite")
	ErrHopLimitExceeded        = errors.New("hop count or limit exceeded maximum bounds")
	ErrHopLimitZero            = errors.New("envelope hop limit reached zero")
	ErrPayloadSizeMismatch     = errors.New("payload length does not match header length")
	ErrCiphertextHashMismatch  = errors.New("ciphertext SHA-256 digest mismatch")
	ErrInvalidSignature        = errors.New("cryptographic Ed25519 signature verification failed")
	ErrExpiredEnvelope         = errors.New("envelope TTL has expired")
	ErrTimestampInFuture       = errors.New("envelope creation timestamp is too far in the future")
	ErrInvalidTtl              = errors.New("envelope TTL is invalid or exceeds maximum permitted duration")
)

// Envelope represents an NXDT store-carry-forward envelope.
type Envelope struct {
	ProtocolVersion uint8
	EnvelopeType    uint8
	AlgorithmSuite  uint8
	Flags           uint8

	// Mutable Relay Metadata (12 bytes)
	HopCount       uint8
	HopLimit       uint8
	RelayTimestamp int64
	RelayFlags     uint16

	// Immutable Authenticated Header (116 bytes)
	MessageUUID      [16]byte
	SenderPublicKey  [32]byte
	BlindToken       [16]byte
	CreatedAt        int64
	ExpiresAt        int64
	CiphertextLength uint32
	CiphertextSha256 [32]byte

	// Sender Signature (64 bytes)
	Signature [64]byte

	// Payload
	Payload []byte
}

// NewAuthenticatedEnvelope creates and signs a new NXDT v1 envelope.
func NewAuthenticatedEnvelope(
	blindToken [16]byte,
	payload []byte,
	senderPrivateKey ed25519.PrivateKey,
	envelopeType uint8,
	hopLimit uint8,
	ttlSeconds int64,
	customUUID *[16]byte,
) (*Envelope, error) {
	if len(payload) > MaxPayloadSize {
		return nil, fmt.Errorf("payload size %d exceeds max %d: %w", len(payload), MaxPayloadSize, ErrPacketTooLarge)
	}
	if len(senderPrivateKey) != ed25519.PrivateKeySize {
		return nil, fmt.Errorf("sender private key size must be %d bytes", ed25519.PrivateKeySize)
	}
	if hopLimit == 0 || hopLimit > MaxHopLimit {
		return nil, ErrHopLimitExceeded
	}
	if ttlSeconds <= 0 || ttlSeconds > MaxTtlSeconds {
		return nil, ErrInvalidTtl
	}

	pubKey := senderPrivateKey.Public().(ed25519.PublicKey)
	var pubKeyBytes [32]byte
	copy(pubKeyBytes[:], pubKey)

	var uuid [16]byte
	if customUUID != nil {
		uuid = *customUUID
	} else {
		if _, err := rand.Read(uuid[:]); err != nil {
			return nil, fmt.Errorf("failed to generate message UUID: %w", err)
		}
	}

	nowSec := time.Now().Unix()
	expiresAt := nowSec + ttlSeconds

	hash := sha256.Sum256(payload)

	env := &Envelope{
		ProtocolVersion:  ProtocolVersion1,
		EnvelopeType:     envelopeType,
		AlgorithmSuite:   SuiteEd25519Sha256,
		Flags:            0,
		HopCount:         0,
		HopLimit:         hopLimit,
		RelayTimestamp:   0,
		RelayFlags:       0,
		MessageUUID:      uuid,
		SenderPublicKey:  pubKeyBytes,
		BlindToken:       blindToken,
		CreatedAt:        nowSec,
		ExpiresAt:        expiresAt,
		CiphertextLength: uint32(len(payload)),
		CiphertextSha256: hash,
		Payload:          payload,
	}

	canonical := env.GetCanonicalSigningData()
	sig := ed25519.Sign(senderPrivateKey, canonical)
	copy(env.Signature[:], sig)

	return env, nil
}

// ParseEnvelope parses a binary envelope.
// Supports both modern NXDT v1 and legacy v0 unsigned envelopes.
func ParseEnvelope(raw []byte, verifySig bool) (*Envelope, error) {
	if len(raw) < LegacyHeaderSize {
		return nil, ErrPacketTooShort
	}
	if len(raw) > MaxPacketSize {
		return nil, ErrPacketTooLarge
	}

	// Check magic "NXDT" (0x4E, 0x58, 0x44, 0x54)
	isNxdt := len(raw) >= 4 &&
		raw[0] == MagicBytes[0] &&
		raw[1] == MagicBytes[1] &&
		raw[2] == MagicBytes[2] &&
		raw[3] == MagicBytes[3]

	if isNxdt {
		if len(raw) < NxdtHeaderSize {
			return nil, ErrPacketTooShort
		}

		version := raw[4]
		if version != ProtocolVersion1 {
			return nil, fmt.Errorf("%w: version %d", ErrUnsupportedVersion, version)
		}
		envType := raw[5]
		suite := raw[6]
		if suite != SuiteEd25519Sha256 {
			return nil, fmt.Errorf("%w: suite %d", ErrUnsupportedSuite, suite)
		}
		flags := raw[7]

		hopCount := raw[8]
		hopLimit := raw[9]
		if hopLimit > MaxHopLimit || hopCount > MaxHopLimit {
			return nil, ErrHopLimitExceeded
		}

		relayTimestamp := int64(binary.BigEndian.Uint64(raw[10:18]))
		relayFlags := binary.BigEndian.Uint16(raw[18:20])

		var messageUUID [16]byte
		copy(messageUUID[:], raw[20:36])

		var senderPubKey [32]byte
		copy(senderPubKey[:], raw[36:68])

		var blindToken [16]byte
		copy(blindToken[:], raw[68:84])

		createdAt := int64(binary.BigEndian.Uint64(raw[84:92]))
		expiresAt := int64(binary.BigEndian.Uint64(raw[92:100]))
		cipherLen := binary.BigEndian.Uint32(raw[100:104])

		if cipherLen > uint32(MaxPayloadSize) {
			return nil, ErrPacketTooLarge
		}

		expectedTotal := NxdtHeaderSize + int(cipherLen)
		if len(raw) != expectedTotal {
			return nil, ErrPayloadSizeMismatch
		}

		var cipherHash [32]byte
		copy(cipherHash[:], raw[104:136])

		var signature [64]byte
		copy(signature[:], raw[136:200])

		payload := raw[200:]
		actualHash := sha256.Sum256(payload)
		if subtle.ConstantTimeCompare(cipherHash[:], actualHash[:]) != 1 {
			return nil, ErrCiphertextHashMismatch
		}

		if expiresAt < createdAt {
			return nil, ErrInvalidTtl
		}

		env := &Envelope{
			ProtocolVersion:  version,
			EnvelopeType:     envType,
			AlgorithmSuite:   suite,
			Flags:            flags,
			HopCount:         hopCount,
			HopLimit:         hopLimit,
			RelayTimestamp:   relayTimestamp,
			RelayFlags:       relayFlags,
			MessageUUID:      messageUUID,
			SenderPublicKey:  senderPubKey,
			BlindToken:       blindToken,
			CreatedAt:        createdAt,
			ExpiresAt:        expiresAt,
			CiphertextLength: cipherLen,
			CiphertextSha256: cipherHash,
			Signature:        signature,
			Payload:          payload,
		}

		if verifySig {
			if err := env.VerifySignature(); err != nil {
				return nil, err
			}
		}

		return env, nil
	}

	// Legacy unsigned envelope fallback (45 bytes header)
	var envUUID [16]byte
	copy(envUUID[:], raw[0:16])

	var blindToken [16]byte
	copy(blindToken[:], raw[16:32])

	hopLimit := raw[32]
	expiresAt := int64(binary.BigEndian.Uint64(raw[33:41]))
	payloadLen := binary.BigEndian.Uint32(raw[41:45])

	if len(raw) != LegacyHeaderSize+int(payloadLen) {
		return nil, ErrPayloadSizeMismatch
	}

	return &Envelope{
		ProtocolVersion:  ProtocolVersionLegacy,
		EnvelopeType:     TypeStandardData,
		AlgorithmSuite:   0,
		Flags:            0,
		HopCount:         0,
		HopLimit:         hopLimit,
		RelayTimestamp:   0,
		RelayFlags:       0,
		MessageUUID:      envUUID,
		SenderPublicKey:  [32]byte{},
		BlindToken:       blindToken,
		CreatedAt:        0,
		ExpiresAt:        expiresAt,
		CiphertextLength: payloadLen,
		CiphertextSha256: [32]byte{},
		Signature:        [64]byte{},
		Payload:          raw[45:],
	}, nil
}

// GetCanonicalSigningData constructs the exact 124-byte buffer signed by Ed25519.
func (e *Envelope) GetCanonicalSigningData() []byte {
	buf := make([]byte, CanonicalSigningDataSize)
	copy(buf[0:4], MagicBytes[:])
	buf[4] = e.ProtocolVersion
	buf[5] = e.EnvelopeType
	buf[6] = e.AlgorithmSuite
	buf[7] = e.Flags
	copy(buf[8:24], e.MessageUUID[:])
	copy(buf[24:56], e.SenderPublicKey[:])
	copy(buf[56:72], e.BlindToken[:])
	binary.BigEndian.PutUint64(buf[72:80], uint64(e.CreatedAt))
	binary.BigEndian.PutUint64(buf[80:88], uint64(e.ExpiresAt))
	binary.BigEndian.PutUint32(buf[88:92], e.CiphertextLength)
	copy(buf[92:124], e.CiphertextSha256[:])
	return buf
}

// VerifySignature validates that the Ed25519 signature matches the canonical immutable header.
func (e *Envelope) VerifySignature() error {
	if e.ProtocolVersion != ProtocolVersion1 {
		return ErrUnsupportedVersion
	}

	actualHash := sha256.Sum256(e.Payload)
	if subtle.ConstantTimeCompare(e.CiphertextSha256[:], actualHash[:]) != 1 {
		return ErrCiphertextHashMismatch
	}

	canonical := e.GetCanonicalSigningData()
	if !ed25519.Verify(e.SenderPublicKey[:], canonical, e.Signature[:]) {
		return ErrInvalidSignature
	}

	return nil
}

// Serialize converts the envelope into its canonical binary wire format.
func (e *Envelope) Serialize() []byte {
	if e.ProtocolVersion == ProtocolVersion1 {
		total := NxdtHeaderSize + len(e.Payload)
		buf := make([]byte, total)
		copy(buf[0:4], MagicBytes[:])
		buf[4] = e.ProtocolVersion
		buf[5] = e.EnvelopeType
		buf[6] = e.AlgorithmSuite
		buf[7] = e.Flags

		buf[8] = e.HopCount
		buf[9] = e.HopLimit
		binary.BigEndian.PutUint64(buf[10:18], uint64(e.RelayTimestamp))
		binary.BigEndian.PutUint16(buf[18:20], e.RelayFlags)

		copy(buf[20:36], e.MessageUUID[:])
		copy(buf[36:68], e.SenderPublicKey[:])
		copy(buf[68:84], e.BlindToken[:])
		binary.BigEndian.PutUint64(buf[84:92], uint64(e.CreatedAt))
		binary.BigEndian.PutUint64(buf[92:100], uint64(e.ExpiresAt))
		binary.BigEndian.PutUint32(buf[100:104], e.CiphertextLength)
		copy(buf[104:136], e.CiphertextSha256[:])

		copy(buf[136:200], e.Signature[:])
		copy(buf[200:], e.Payload)
		return buf
	}

	// Legacy serialization (45 + N bytes)
	total := LegacyHeaderSize + len(e.Payload)
	buf := make([]byte, total)
	copy(buf[0:16], e.MessageUUID[:])
	copy(buf[16:32], e.BlindToken[:])
	buf[32] = e.HopLimit
	binary.BigEndian.PutUint64(buf[33:41], uint64(e.ExpiresAt))
	binary.BigEndian.PutUint32(buf[41:45], e.CiphertextLength)
	copy(buf[45:], e.Payload)
	return buf
}

// WithDecrementedHop returns a copy with decremented hop limit, incremented hop count,
// and updated relay timestamp without altering the immutable header or signature.
func (e *Envelope) WithDecrementedHop(nowSec int64) (*Envelope, error) {
	if e.HopLimit == 0 {
		return nil, ErrHopLimitZero
	}
	newHopCount := e.HopCount + 1
	if newHopCount > MaxHopLimit {
		return nil, ErrHopLimitExceeded
	}

	clone := *e
	clone.HopCount = newHopCount
	clone.HopLimit = e.HopLimit - 1
	clone.RelayTimestamp = nowSec
	return &clone, nil
}

func (e *Envelope) BlindTokenHex() string {
	return hex.EncodeToString(e.BlindToken[:])
}

func (e *Envelope) MessageUUIDHex() string {
	return hex.EncodeToString(e.MessageUUID[:])
}

func (e *Envelope) IsLegacy() bool {
	return e.ProtocolVersion == ProtocolVersionLegacy
}
