package auth

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"
)

var (
	ErrMissingWebhookSecret    = errors.New("webhook authentication failed: GATEWAY_WEBHOOK_SECRET is not configured on server")
	ErrMissingWebhookSignature = errors.New("webhook authentication failed: missing gateway signature header")
	ErrInvalidWebhookSignature = errors.New("webhook authentication failed: cryptographic HMAC signature mismatch (potential forgery attempt)")
	ErrExpiredWebhookTimestamp = errors.New("webhook authentication failed: request timestamp is expired or outside tolerance window")
)

// VerifyGatewaySignature validates the incoming telecom gateway webhook request.
//
// Security guarantees:
// 1. Fail-closed: Rejects immediately if GATEWAY_WEBHOOK_SECRET is unset or empty.
// 2. Constant-time comparison: Uses hmac.Equal to protect against timing attacks.
// 3. Raw payload integrity: Verifies the exact raw byte slice before any JSON or form parsing.
// 4. Timestamp & replay defense: Rejects requests older than 5 minutes or in the future by > 60s if timestamp header present.
// 5. Header compatibility: Supports X-Gateway-Signature, X-Signature, and X-Hub-Signature-256.
func VerifyGatewaySignature(r *http.Request, rawBody []byte) error {
	secret := strings.TrimSpace(os.Getenv("GATEWAY_WEBHOOK_SECRET"))
	if secret == "" {
		return ErrMissingWebhookSecret
	}

	// 1. Locate signature header
	sigHeader := r.Header.Get("X-Gateway-Signature")
	if sigHeader == "" {
		sigHeader = r.Header.Get("X-Signature")
	}
	if sigHeader == "" {
		sigHeader = r.Header.Get("X-Hub-Signature-256")
	}
	if strings.TrimSpace(sigHeader) == "" {
		return ErrMissingWebhookSignature
	}

	// Normalize signature (strip optional "sha256=" prefix)
	sigHex := strings.TrimSpace(strings.TrimPrefix(sigHeader, "sha256="))

	// 2. Check timestamp for replay protection if supplied
	tsHeader := r.Header.Get("X-Gateway-Timestamp")
	if tsHeader == "" {
		tsHeader = r.Header.Get("X-Timestamp")
	}
	if tsHeader != "" {
		tsVal, err := strconv.ParseInt(strings.TrimSpace(tsHeader), 10, 64)
		if err == nil {
			var tsTime time.Time
			if tsVal > 1e11 {
				// Milliseconds
				tsTime = time.UnixMilli(tsVal)
			} else {
				// Seconds
				tsTime = time.Unix(tsVal, 0)
			}

			now := time.Now()
			// Reject if older than 5 minutes or skewed in the future by > 60 seconds
			if now.Sub(tsTime) > 5*time.Minute || tsTime.Sub(now) > 60*time.Second {
				return fmt.Errorf("%w: received ts=%s, current=%s", ErrExpiredWebhookTimestamp, tsTime.Format(time.RFC3339), now.Format(time.RFC3339))
			}
		}
	}

	// 3. Compute expected HMAC-SHA256 over raw body
	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write(rawBody)
	expectedSigHex := hex.EncodeToString(mac.Sum(nil))

	// 4. Constant-time verification
	if !hmac.Equal([]byte(strings.ToLower(sigHex)), []byte(strings.ToLower(expectedSigHex))) {
		return ErrInvalidWebhookSignature
	}

	return nil
}
