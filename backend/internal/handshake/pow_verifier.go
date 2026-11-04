package handshake

import (
	"crypto/sha256"
	"fmt"
	"time"
)

// VerifyProofOfWork verifies that the provided nonce yields 16 leading zero bits
// across SHA-256(targetNxId : timestamp : nonce) and enforces a 10-minute freshness window.
func VerifyProofOfWork(targetNxId string, timestamp int64, nonce int64) bool {
	now := time.Now().UnixMilli()

	// Enforce 10-minute expiry window (and reject timestamps more than 1 min in the future)
	if now-timestamp > 10*60*1000 || timestamp > now+60*1000 {
		return false
	}

	payload := fmt.Sprintf("%s:%d:%d", targetNxId, timestamp, nonce)
	hash := sha256.Sum256([]byte(payload))

	// Verify first 2 bytes are 0x00, 0x00 (16 zero bits)
	return hash[0] == 0x00 && hash[1] == 0x00
}
