package api

import (
	"encoding/base64"
	"encoding/json"
	"log"
	"net/http"
	"time"

	"nexa/wire/internal/connection"
	"nexa/wire/internal/dtn"
)

type DtnDumpRequest struct {
	Envelopes []string `json:"envelopes"` // Array of Base64-encoded raw DTN envelope bytes
}

type DtnDumpResponse struct {
	Status        string `json:"status"`
	ReceivedCount int    `json:"receivedCount"`
	Timestamp     int64  `json:"timestamp"`
}

type DtnHandler struct {
	hub *connection.Hub
}

func NewDtnHandler(hub *connection.Hub) *DtnHandler {
	return &DtnHandler{
		hub: hub,
	}
}

// HandleOpportunisticDump processes batched DTN envelopes uploaded by intermediate mule nodes.
func (h *DtnHandler) HandleOpportunisticDump(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method Not Allowed", http.StatusMethodNotAllowed)
		return
	}

	// Request-size limit: max 10MB to prevent memory exhaustion DoS
	r.Body = http.MaxBytesReader(w, r.Body, 10*1024*1024)

	var req DtnDumpRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		log.Printf("[DTN Bridge] Failed to parse JSON body: %v", err)
		http.Error(w, "Invalid JSON payload or payload too large", http.StatusBadRequest)
		return
	}

	// Batch limit: max 100 envelopes per dump
	if len(req.Envelopes) > 100 {
		http.Error(w, "Batch limit exceeded (maximum 100 envelopes per dump)", http.StatusBadRequest)
		return
	}

	processedCount := 0
	for _, b64 := range req.Envelopes {
		rawBytes, err := base64.StdEncoding.DecodeString(b64)
		if err != nil {
			log.Printf("[DTN Bridge] Failed to decode base64 envelope: %v", err)
			continue
		}

		// Parse and validate DTN envelope (enforces Ed25519 signature & payload hash for NXDT v1)
		env, err := dtn.ParseEnvelope(rawBytes, true)
		if err != nil {
			log.Printf("[DTN Bridge] Dropped invalid/unauthenticated DTN envelope: %v", err)
			continue
		}

		nowSec := time.Now().Unix()
		if env.ExpiresAt <= nowSec {
			log.Printf("[DTN Bridge] Dropped expired envelope [token=%s, expiry=%d]", env.BlindTokenHex()[:min(len(env.BlindTokenHex()), 8)], env.ExpiresAt)
			continue
		}

		if env.HopLimit == 0 {
			log.Printf("[DTN Bridge] Dropped envelope [token=%s]: Hop limit reached zero", env.BlindTokenHex()[:min(len(env.BlindTokenHex()), 8)])
			continue
		}

		// Decrement hop limit for forward relay custody
		forwardedEnv, err := env.WithDecrementedHop(nowSec)
		forwardBytes := rawBytes
		if err == nil && forwardedEnv != nil {
			forwardBytes = forwardedEnv.Serialize()
		}

		// Route via Blind-Postman Hub:
		// If recipient is connected -> Deliver directly via WebSocket
		// If recipient is offline   -> Buffer in ephemeral Redis (24h TTL) & trigger FCM wake-up
		h.hub.RouteDtnEnvelope(env.BlindTokenHex(), forwardBytes)
		processedCount++
	}

	log.Printf("[DTN Bridge] Processed opportunistic dump: %d valid envelopes forwarded", processedCount)

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	json.NewEncoder(w).Encode(DtnDumpResponse{
		Status:        "ACCEPTED",
		ReceivedCount: processedCount,
		Timestamp:     time.Now().UnixMilli(),
	})
}
