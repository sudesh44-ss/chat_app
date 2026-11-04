package main

import (
	"encoding/hex"
	"encoding/json"
	"log"
	"net/http"
	"os"
	"strings"

	"nexa/wire/internal/sync"
)

type SyncRequest struct {
	ScannerNxID      string `json:"scanner_nx_id"`
	ScannedNxID      string `json:"scanned_nx_id"`
	ScannedSignature string `json:"scanned_signature"`
}

type SyncResponse struct {
	Status    string `json:"status"`
	Timestamp int64  `json:"timestamp"`
}

func main() {
	port := os.Getenv("PORT")
	if port == "" {
		port = "8085"
	}

	hub := sync.NewSyncHub(100)

	mux := http.NewServeMux()

	// Synchronize verified Day-0 QR handshake from mobile client
	mux.HandleFunc("/api/v1/contacts/sync-qr", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
			return
		}

		var req SyncRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			http.Error(w, "Bad request: invalid JSON payload", http.StatusBadRequest)
			return
		}

		scannerID := strings.TrimSpace(req.ScannerNxID)
		scannedID := strings.TrimSpace(req.ScannedNxID)
		sig := strings.TrimSpace(req.ScannedSignature)

		if scannerID == "" || scannedID == "" || sig == "" {
			http.Error(w, "scanner_nx_id, scanned_nx_id, and scanned_signature are required", http.StatusBadRequest)
			return
		}

		// Enforce Ed25519 signature is 64 bytes (128 hex characters)
		if len(sig) != 128 {
			http.Error(w, "scanned_signature must be a 128-char hex string (64 bytes Ed25519)", http.StatusBadRequest)
			return
		}

		if _, err := hex.DecodeString(sig); err != nil {
			http.Error(w, "scanned_signature must be valid hex", http.StatusBadRequest)
			return
		}

		event := hub.RecordHandshake(scannerID, scannedID, sig)
		log.Printf("[QR-SYNC] Verified handshake recorded: %s <-> %s", scannerID, scannedID)

		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_ = json.NewEncoder(w).Encode(SyncResponse{
			Status:    "synced",
			Timestamp: event.Timestamp,
		})
	})

	// Query recent verified QR sync events for a user
	mux.HandleFunc("/api/v1/contacts/sync-qr/events", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet {
			http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
			return
		}

		nxID := strings.TrimSpace(r.URL.Query().Get("nx_id"))
		if nxID == "" {
			http.Error(w, "nx_id query parameter required", http.StatusBadRequest)
			return
		}

		events := hub.GetEvents(nxID)
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_ = json.NewEncoder(w).Encode(events)
	})

	// Health check endpoint
	mux.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("OK"))
	})

	log.Printf("[NEXA QR-SYNC] Starting Instant Cryptographic QR Sync Service on :%s", port)
	if err := http.ListenAndServe(":"+port, mux); err != nil {
		log.Fatalf("QR Sync Server failed: %v", err)
	}
}
