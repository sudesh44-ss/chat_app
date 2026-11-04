package main

import (
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/gorilla/websocket"
	"nexa/wire/internal/relay"
)

var upgrader = websocket.Upgrader{
	ReadBufferSize:  1024 * 64,
	WriteBufferSize: 1024 * 64,
	CheckOrigin: func(r *http.Request) bool {
		return true
	},
}

func main() {
	port := os.Getenv("PORT")
	if port == "" {
		port = "8080"
	}

	log.Println("==================================================")
	log.Println("  NEXA Ephemeral Zero-Knowledge Relay Backend     ")
	log.Println("  Multi-Transport E2EE Blind Ciphertext Hub       ")
	log.Println("==================================================")

	hub := relay.NewRelayHub()

	http.HandleFunc("/ws/relay", func(w http.ResponseWriter, r *http.Request) {
		token := r.URL.Query().Get("token")
		if token == "" {
			http.Error(w, "Missing token query parameter", http.StatusBadRequest)
			return
		}

		conn, err := upgrader.Upgrade(w, r, nil)
		if err != nil {
			log.Printf("[Relay Server] WebSocket upgrade failed: %v", err)
			return
		}

		session := relay.NewRelaySession(hub, conn, token)
		hub.Register(session)

		go session.WritePump()
		go session.ReadPump()
	})

	type DumpEnvelope struct {
		EnvelopeID    string `json:"envelope_id"`
		DestinationID string `json:"destination_id"`
		PayloadHex    string `json:"payload_hex"`
		CreatedAt     int64  `json:"created_at"`
	}

	http.HandleFunc("/api/v1/relay/opportunistic-dump", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
			return
		}

		var envelopes []DumpEnvelope
		if err := json.NewDecoder(r.Body).Decode(&envelopes); err != nil {
			http.Error(w, "Invalid JSON payload", http.StatusBadRequest)
			return
		}

		log.Printf("[Relay Server] Ingested %d opportunistic DTN envelopes from Data Mule", len(envelopes))

		forwarded := 0
		for _, env := range envelopes {
			if hub.DispatchToToken(env.DestinationID, []byte(env.PayloadHex)) {
				forwarded++
			}
		}

		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusCreated)
		json.NewEncoder(w).Encode(map[string]interface{}{
			"status":    "INGESTED",
			"received":  len(envelopes),
			"forwarded": forwarded,
			"timestamp": time.Now().Unix(),
		})
	})

	http.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]interface{}{
			"status":    "UP",
			"subsystem": "Zero-Knowledge-Ciphertext-Relay",
			"storage":   "Zero-Disk-Zero-Plaintext",
			"timestamp": time.Now().UnixMilli(),
		})
	})

	addr := fmt.Sprintf(":%s", port)
	server := &http.Server{
		Addr:         addr,
		ReadTimeout:  15 * time.Second,
		WriteTimeout: 15 * time.Second,
	}

	go func() {
		log.Printf("[Relay Server] Listening on ws://0.0.0.0%s/ws/relay", addr)
		if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("[Relay Server] Listen error: %v", err)
		}
	}()

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit

	log.Println("[Relay Server] Shutting down relay service...")
}
