package auth

import (
	"crypto/hmac"
	"crypto/sha1"
	"encoding/base64"
	"testing"
)

func TestValidateTwilioSignature_Valid(t *testing.T) {
	authToken := "12345secretToken"
	requestURL := "https://api.nexa.im/api/v1/auth/reverse-sms/webhook"
	params := map[string]string{
		"From": "+919876543210",
		"To":   "+919800000000",
		"Body": "NX-K9Y2M",
	}

	// Canonical Twilio calculation:
	// Sorted keys: Body, From, To
	// String: https://api.nexa.im/api/v1/auth/reverse-sms/webhookBodyNX-K9Y2MFrom+919876543210To+919800000000
	expectedData := "https://api.nexa.im/api/v1/auth/reverse-sms/webhook" +
		"Body" + "NX-K9Y2M" +
		"From" + "+919876543210" +
		"To" + "+919800000000"

	mac := hmac.New(sha1.New, []byte(authToken))
	mac.Write([]byte(expectedData))
	validSig := base64.StdEncoding.EncodeToString(mac.Sum(nil))

	if !ValidateTwilioSignature(authToken, requestURL, params, validSig) {
		t.Fatalf("Expected valid Twilio signature to be accepted")
	}
}

func TestValidateTwilioSignature_Tampered(t *testing.T) {
	authToken := "12345secretToken"
	requestURL := "https://api.nexa.im/api/v1/auth/reverse-sms/webhook"
	params := map[string]string{
		"From": "+919876543210",
		"To":   "+919800000000",
		"Body": "NX-K9Y2M",
	}

	validSig := "invalidsignature=="

	if ValidateTwilioSignature(authToken, requestURL, params, validSig) {
		t.Fatalf("Tampered signature must be rejected")
	}

	// Missing params
	delete(params, "Body")
	if ValidateTwilioSignature(authToken, requestURL, params, validSig) {
		t.Fatalf("Signature with missing parameters must be rejected")
	}

	// Empty token
	if ValidateTwilioSignature("", requestURL, params, validSig) {
		t.Fatalf("Empty auth token must be rejected")
	}
}
