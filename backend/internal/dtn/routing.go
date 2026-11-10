package dtn

import (
	"crypto/hmac"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"fmt"
)

var (
	SaltPairwiseRouting      = []byte("NEXA_PAIRWISE_ROUTING_SALT_V1")
	InfoPairwiseRoutingPrefix = "NEXA_PAIRWISE_ROUTING_KEY_V1:"
	HmacEpochPrefix          = []byte("NEXA_ROUTING_EPOCH_V1_")

	ErrInvalidPairwiseSecret = errors.New("pairwise secret must be exactly 32 bytes")
	ErrInvalidRoutingKey    = errors.New("routing key must be exactly 32 bytes")
	ErrBlankNxID            = errors.New("sender and recipient NX-IDs cannot be blank")
	ErrHkdfLengthExceeded   = errors.New("requested HKDF output length exceeds maximum allowable")
)

const (
	EpochDurationSeconds = int64(86400) // 24 hours
	RoutingTokenSize     = 16           // 16 bytes (128 bits)
)

// HKDFExtract implements RFC 5869 HKDF-Extract using SHA-256.
func HKDFExtract(salt, ikm []byte) []byte {
	if len(salt) == 0 {
		salt = make([]byte, sha256.Size)
	}
	mac := hmac.New(sha256.New, salt)
	mac.Write(ikm)
	return mac.Sum(nil)
}

// HKDFExpand implements RFC 5869 HKDF-Expand using SHA-256.
func HKDFExpand(prk, info []byte, length int) ([]byte, error) {
	if length > 255*sha256.Size {
		return nil, ErrHkdfLengthExceeded
	}
	n := (length + sha256.Size - 1) / sha256.Size
	var okm []byte
	var prev []byte
	for i := 1; i <= n; i++ {
		mac := hmac.New(sha256.New, prk)
		mac.Write(prev)
		mac.Write(info)
		mac.Write([]byte{byte(i)})
		prev = mac.Sum(nil)
		okm = append(okm, prev...)
	}
	return okm[:length], nil
}

// DeriveRoutingKey derives a dedicated 32-byte directional routing key from a pairwise secret.
// K_route = HKDF-Expand(HKDF-Extract(Salt, pairwiseSecret), "NEXA_PAIRWISE_ROUTING_KEY_V1:" || senderNxId || ":" || recipientNxId, 32)
func DeriveRoutingKey(pairwiseSecret []byte, senderNxId, recipientNxId string) ([]byte, error) {
	if len(pairwiseSecret) != 32 {
		return nil, ErrInvalidPairwiseSecret
	}
	if senderNxId == "" || recipientNxId == "" {
		return nil, ErrBlankNxID
	}

	info := []byte(fmt.Sprintf("%s%s:%s", InfoPairwiseRoutingPrefix, senderNxId, recipientNxId))
	prk := HKDFExtract(SaltPairwiseRouting, pairwiseSecret)
	return HKDFExpand(prk, info, 32)
}

// GenerateRoutingToken generates a 16-byte rotating routing identifier for a given epoch.
// RoutingID = Truncate16(HMAC-SHA256(K_route, "NEXA_ROUTING_EPOCH_V1_" || BigEndianInt64(epoch)))
func GenerateRoutingToken(routingKey []byte, epoch int64) ([16]byte, error) {
	var token [16]byte
	if len(routingKey) != 32 {
		return token, ErrInvalidRoutingKey
	}

	epochBuf := make([]byte, 8)
	binary.BigEndian.PutUint64(epochBuf, uint64(epoch))
	data := append(HmacEpochPrefix, epochBuf...)

	mac := hmac.New(sha256.New, routingKey)
	mac.Write(data)
	fullMac := mac.Sum(nil)

	copy(token[:], fullMac[:RoutingTokenSize])
	return token, nil
}

// GenerateRoutingTokenHex generates the hex string representation of a routing token.
func GenerateRoutingTokenHex(routingKey []byte, epoch int64) (string, error) {
	token, err := GenerateRoutingToken(routingKey, epoch)
	if err != nil {
		return "", err
	}
	return hex.EncodeToString(token[:]), nil
}

// CurrentEpoch returns the epoch number for a unix epoch timestamp in seconds.
func CurrentEpoch(timestampSec int64) int64 {
	return timestampSec / EpochDurationSeconds
}

// GetActiveTokens returns the active routing tokens in the bounded tolerance window [epoch-2, epoch-1, epoch, epoch+1].
// This provides seamless coverage for 24h, 36h, and 48h offline DTN delivery across midnight boundaries.
func GetActiveTokens(routingKey []byte, currentEpoch int64) ([][16]byte, error) {
	epochs := []int64{currentEpoch, currentEpoch - 1, currentEpoch - 2, currentEpoch + 1}
	tokens := make([][16]byte, 0, len(epochs))
	for _, ep := range epochs {
		t, err := GenerateRoutingToken(routingKey, ep)
		if err != nil {
			return nil, err
		}
		tokens = append(tokens, t)
	}
	return tokens, nil
}

// GetActiveTokenHexes returns the hex strings of the active routing tokens in the bounded window.
func GetActiveTokenHexes(routingKey []byte, currentEpoch int64) ([]string, error) {
	tokens, err := GetActiveTokens(routingKey, currentEpoch)
	if err != nil {
		return nil, err
	}
	hexes := make([]string, len(tokens))
	for i, t := range tokens {
		hexes[i] = hex.EncodeToString(t[:])
	}
	return hexes, nil
}

// IsTokenInWindow performs constant-time verification to check if candidateToken matches
// any of the active tokens in the bounded tolerance window [epoch-2, epoch-1, epoch, epoch+1].
func IsTokenInWindow(candidateToken [16]byte, routingKey []byte, currentEpoch int64) bool {
	activeTokens, err := GetActiveTokens(routingKey, currentEpoch)
	if err != nil {
		return false
	}
	for _, active := range activeTokens {
		if subtle.ConstantTimeCompare(candidateToken[:], active[:]) == 1 {
			return true
		}
	}
	return false
}

// IsTokenInWindowForEnvelope verifies routing token validity against the envelope's creation timestamp
// with strict bounds checking (rejecting expired envelopes, excessive future skew, or envelopes exceeding MaxTtlSeconds).
func IsTokenInWindowForEnvelope(candidateToken [16]byte, routingKey []byte, createdAt, expiresAt, nowSec int64) bool {
	if len(routingKey) != 32 {
		return false
	}
	if nowSec > expiresAt {
		return false // TTL expired
	}
	if createdAt > nowSec+MaxFutureSkewSeconds {
		return false // Future timestamp skew exceeded
	}
	if nowSec-createdAt > MaxTtlSeconds || (expiresAt-createdAt) > MaxTtlSeconds {
		return false // Exceeds maximum 7-day protocol lifetime
	}

	// 1. Direct creation epoch check
	creationEpoch := CurrentEpoch(createdAt)
	expectedToken, err := GenerateRoutingToken(routingKey, creationEpoch)
	if err == nil && subtle.ConstantTimeCompare(candidateToken[:], expectedToken[:]) == 1 {
		return true
	}

	// 2. Fallback to active tolerance window at current time
	return IsTokenInWindow(candidateToken, routingKey, CurrentEpoch(nowSec))
}
