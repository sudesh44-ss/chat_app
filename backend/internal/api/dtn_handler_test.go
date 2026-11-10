package api

import (
	"bytes"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"nexa/wire/internal/connection"
	"nexa/wire/internal/dtn"
)

func TestDtnHandler_OpportunisticDump_NxdtAndLegacy(t *testing.T) {
	hub := connection.NewHub(nil)
	handler := NewDtnHandler(hub)

	// 1. Generate valid NXDT v1 envelope
	_, priv, _ := ed25519.GenerateKey(rand.Reader)
	var blindToken [16]byte
	copy(blindToken[:], []byte("valid_blind_tok1"))

	nxdtEnv, err := dtn.NewAuthenticatedEnvelope(blindToken, []byte("Hello via NXDT mule"), priv, dtn.TypeStandardData, 7, 3600, nil)
	if err != nil {
		t.Fatalf("failed to create NXDT envelope: %v", err)
	}
	nxdtBase64 := base64.StdEncoding.EncodeToString(nxdtEnv.Serialize())

	// 2. Generate tampered NXDT envelope (should be dropped)
	tamperedBytes := nxdtEnv.Serialize()
	tamperedBytes[len(tamperedBytes)-1] ^= 0xFF
	tamperedBase64 := base64.StdEncoding.EncodeToString(tamperedBytes)

	// 3. Construct HTTP dump request
	reqBody := DtnDumpRequest{
		Envelopes: []string{nxdtBase64, tamperedBase64},
	}
	bodyBytes, _ := json.Marshal(reqBody)

	req := httptest.NewRequest(http.MethodPost, "/api/v1/relay/opportunistic-dump", bytes.NewReader(bodyBytes))
	rec := httptest.NewRecorder()

	handler.HandleOpportunisticDump(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected HTTP 200, got %d", rec.Code)
	}

	var resp DtnDumpResponse
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatalf("failed to parse response JSON: %v", err)
	}

	if resp.Status != "ACCEPTED" {
		t.Errorf("expected status ACCEPTED, got %s", resp.Status)
	}
	// Exactly 1 valid envelope should have been forwarded (the tampered one dropped)
	if resp.ReceivedCount != 1 {
		t.Errorf("expected ReceivedCount 1, got %d", resp.ReceivedCount)
	}
}

func TestDtnHandler_PairwiseRotatingTokenRouting(t *testing.T) {
	hub := connection.NewHub(nil)
	handler := NewDtnHandler(hub)

	// 1. Derive pairwise rotating token between Alice and Bob
	pairwiseSecret := make([]byte, 32)
	copy(pairwiseSecret, []byte("shared_secret_between_alice_bob_"))
	kRoute, err := dtn.DeriveRoutingKey(pairwiseSecret, "NX-ALICE", "NX-BOB")
	if err != nil {
		t.Fatalf("failed to derive routing key: %v", err)
	}
	epoch := dtn.CurrentEpoch(1728000000)
	routingToken, err := dtn.GenerateRoutingToken(kRoute, epoch)
	if err != nil {
		t.Fatalf("failed to generate routing token: %v", err)
	}
	routingTokenHex, err := dtn.GenerateRoutingTokenHex(kRoute, epoch)
	if err != nil {
		t.Fatalf("failed to generate hex token: %v", err)
	}

	// 2. Mock Bob's active WebSocket session and register active tokens
	bobSession := connection.NewClientSession(hub, nil, "NX-BOB", "device-bob-1")
	bobSession.Authenticated = true
	hub.RegisterRoutingTokens([]string{routingTokenHex}, bobSession)

	// 3. Alice creates authenticated envelope addressed to Bob's pairwise token
	_, alicePriv, _ := ed25519.GenerateKey(rand.Reader)
	env, err := dtn.NewAuthenticatedEnvelope(routingToken, []byte("Secret pairwise message to Bob"), alicePriv, dtn.TypeStandardData, 7, 3600, nil)
	if err != nil {
		t.Fatalf("failed to create authenticated envelope: %v", err)
	}
	envBase64 := base64.StdEncoding.EncodeToString(env.Serialize())

	// 4. Dump envelope via Opportunistic Dump API
	reqBody := DtnDumpRequest{
		Envelopes: []string{envBase64},
	}
	bodyBytes, _ := json.Marshal(reqBody)
	req := httptest.NewRequest(http.MethodPost, "/api/v1/relay/opportunistic-dump", bytes.NewReader(bodyBytes))
	rec := httptest.NewRecorder()

	handler.HandleOpportunisticDump(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected HTTP 200, got %d", rec.Code)
	}

	// 5. Verify Bob received the envelope via his bound pairwise token
	select {
	case receivedBytes := <-bobSession.Send:
		receivedEnv, parseErr := dtn.ParseEnvelope(receivedBytes, true)
		if parseErr != nil {
			t.Fatalf("failed to parse forwarded envelope: %v", parseErr)
		}
		if receivedEnv.BlindTokenHex() != routingTokenHex {
			t.Fatalf("blind token mismatch: got %s, want %s", receivedEnv.BlindTokenHex(), routingTokenHex)
		}
		t.Logf("Successfully routed pairwise envelope to Bob's session: token=%s", routingTokenHex)
	default:
		t.Fatalf("expected envelope in Bob's session Send channel, but channel was empty")
	}
}

func TestDtnHandler_PayloadCeilingAndBatchLimit(t *testing.T) {
	hub := connection.NewHub(nil)
	handler := NewDtnHandler(hub)

	// 1. Test batch size limit (>100 envelopes)
	oversizedBatch := make([]string, 101)
	for i := range oversizedBatch {
		oversizedBatch[i] = "dummy_base64_envelope"
	}
	reqBody := DtnDumpRequest{
		Envelopes: oversizedBatch,
	}
	bodyBytes, _ := json.Marshal(reqBody)
	req := httptest.NewRequest(http.MethodPost, "/api/v1/relay/opportunistic-dump", bytes.NewReader(bodyBytes))
	rec := httptest.NewRecorder()

	handler.HandleOpportunisticDump(rec, req)

	if rec.Code != http.StatusBadRequest {
		t.Fatalf("expected HTTP 400 for batch > 100, got %d", rec.Code)
	}

	// 2. Test payload ceiling (>10MB)
	// Create an oversized body reader (>10MB)
	hugeBody := bytes.Repeat([]byte("A"), 11*1024*1024)
	hugeReq := httptest.NewRequest(http.MethodPost, "/api/v1/relay/opportunistic-dump", bytes.NewReader(hugeBody))
	hugeRec := httptest.NewRecorder()

	handler.HandleOpportunisticDump(hugeRec, hugeReq)

	if hugeRec.Code != http.StatusBadRequest {
		t.Fatalf("expected HTTP 400 for payload > 10MB, got %d", hugeRec.Code)
	}
}
