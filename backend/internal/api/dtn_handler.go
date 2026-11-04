package api

import (
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"log"
	"net/http"
	"time"

	"nexa/wire/internal/connection"
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

	var req DtnDumpRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		log.Printf("[DTN Bridge] Failed to parse JSON body: %v", err)
		http.Error(w, "Invalid JSON payload", http.StatusBadRequest)
		return
	}

	processedCount := 0
	for _, b64 := range req.Envelopes {
		rawBytes, err := base64.StdEncoding.DecodeString(b64)
		if err != nil {
			log.Printf("[DTN Bridge] Failed to decode base64 envelope: %v", err)
			continue
		}

		// Validate minimum envelope header (45 bytes):
		// [EnvId 16B][BlindToken 16B][HopLimit 1B][Expiry 8B][PayloadLen 4B]
		if len(rawBytes) < 45 {
			log.Printf("[DTN Bridge] Dropped truncated envelope of size %d bytes", len(rawBytes))
			continue
		}

		blindTokenBytes := rawBytes[16:32]
		blindTokenHex := hex.EncodeToString(blindTokenBytes)

		expiryEpochSec := int64(binary.BigEndian.Uint64(rawBytes[33:41]))
		nowSec := time.Now().Unix()
		if expiryEpochSec <= nowSec {
			log.Printf("[DTN Bridge] Dropped expired envelope [token=%s, expiry=%d]", blindTokenHex[:8], expiryEpochSec)
			continue
		}

		// Route via Blind-Postman Hub:
		// If recipient is connected -> Deliver directly via WebSocket
		// If recipient is offline   -> Buffer in ephemeral Redis (24h TTL) & trigger FCM wake-up
		h.hub.RouteDtnEnvelope(blindTokenHex, rawBytes)
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
