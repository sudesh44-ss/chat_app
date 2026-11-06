package auth

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"
)

func TestVerifyGatewaySignature(t *testing.T) {
	testSecret := "super_secret_telecom_gateway_key_99812"
	os.Setenv("GATEWAY_WEBHOOK_SECRET", testSecret)
	defer os.Unsetenv("GATEWAY_WEBHOOK_SECRET")

	rawPayload := []byte(`{"sender_number":"+919876543210","body":"NX-VERIFY NX-7K99M"}`)

	// Compute valid signature
	mac := hmac.New(sha256.New, []byte(testSecret))
	mac.Write(rawPayload)
	validSignature := hex.EncodeToString(mac.Sum(nil))

	t.Run("Valid signature succeeds", func(t *testing.T) {
		req := httptest.NewRequest(http.MethodPost, "/api/v1/webhooks/sms-inbound", strings.NewReader(string(rawPayload)))
		req.Header.Set("X-Gateway-Signature", validSignature)

		err := VerifyGatewaySignature(req, rawPayload)
		if err != nil {
			t.Fatalf("expected valid signature to succeed, got: %v", err)
		}
	})

	t.Run("Valid signature with sha256= prefix succeeds", func(t *testing.T) {
		req := httptest.NewRequest(http.MethodPost, "/api/v1/webhooks/sms-inbound", strings.NewReader(string(rawPayload)))
		req.Header.Set("X-Hub-Signature-256", "sha256="+validSignature)

		err := VerifyGatewaySignature(req, rawPayload)
		if err != nil {
			t.Fatalf("expected sha256= prefixed signature to succeed, got: %v", err)
		}
	})

	t.Run("Missing signature fails", func(t *testing.T) {
		req := httptest.NewRequest(http.MethodPost, "/api/v1/webhooks/sms-inbound", strings.NewReader(string(rawPayload)))
		err := VerifyGatewaySignature(req, rawPayload)
		if err == nil || err != ErrMissingWebhookSignature {
			t.Fatalf("expected ErrMissingWebhookSignature, got: %v", err)
		}
	})

	t.Run("Invalid/tampered signature fails", func(t *testing.T) {
		req := httptest.NewRequest(http.MethodPost, "/api/v1/webhooks/sms-inbound", strings.NewReader(string(rawPayload)))
		req.Header.Set("X-Gateway-Signature", "deadbeef00112233445566778899aabbccddeeff")

		err := VerifyGatewaySignature(req, rawPayload)
		if err == nil || err != ErrInvalidWebhookSignature {
			t.Fatalf("expected ErrInvalidWebhookSignature, got: %v", err)
		}
	})

	t.Run("Tampered body fails verification", func(t *testing.T) {
		tamperedBody := []byte(`{"sender_number":"+919876543210","body":"NX-VERIFY NX-FORGED"}`)
		req := httptest.NewRequest(http.MethodPost, "/api/v1/webhooks/sms-inbound", strings.NewReader(string(tamperedBody)))
		req.Header.Set("X-Gateway-Signature", validSignature) // signature was for rawPayload, not tamperedBody

		err := VerifyGatewaySignature(req, tamperedBody)
		if err == nil || err != ErrInvalidWebhookSignature {
			t.Fatalf("expected signature mismatch on tampered body, got: %v", err)
		}
	})

	t.Run("Missing secret fails closed", func(t *testing.T) {
		os.Unsetenv("GATEWAY_WEBHOOK_SECRET")
		req := httptest.NewRequest(http.MethodPost, "/api/v1/webhooks/sms-inbound", strings.NewReader(string(rawPayload)))
		req.Header.Set("X-Gateway-Signature", validSignature)

		err := VerifyGatewaySignature(req, rawPayload)
		if err == nil || err != ErrMissingWebhookSecret {
			t.Fatalf("expected ErrMissingWebhookSecret when secret is unset, got: %v", err)
		}
		os.Setenv("GATEWAY_WEBHOOK_SECRET", testSecret)
	})

	t.Run("Valid timestamp passes replay check", func(t *testing.T) {
		req := httptest.NewRequest(http.MethodPost, "/api/v1/webhooks/sms-inbound", strings.NewReader(string(rawPayload)))
		req.Header.Set("X-Gateway-Signature", validSignature)
		req.Header.Set("X-Gateway-Timestamp", fmt.Sprintf("%d", time.Now().Unix()))

		err := VerifyGatewaySignature(req, rawPayload)
		if err != nil {
			t.Fatalf("expected valid timestamp to succeed, got: %v", err)
		}
	})

	t.Run("Expired timestamp (> 5 min old) is rejected", func(t *testing.T) {
		req := httptest.NewRequest(http.MethodPost, "/api/v1/webhooks/sms-inbound", strings.NewReader(string(rawPayload)))
		req.Header.Set("X-Gateway-Signature", validSignature)
		expiredTs := time.Now().Add(-10 * time.Minute).Unix()
		req.Header.Set("X-Gateway-Timestamp", fmt.Sprintf("%d", expiredTs))

		err := VerifyGatewaySignature(req, rawPayload)
		if err == nil || !strings.Contains(err.Error(), "expired") {
			t.Fatalf("expected expired timestamp error, got: %v", err)
		}
	})
}
