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
	"nexa/wire/internal/signaling"
)

var upgrader = websocket.Upgrader{
	ReadBufferSize:  1024 * 64,
	WriteBufferSize: 1024 * 64,
	CheckOrigin: func(r *http.Request) bool {
		return true // Allow all mobile client origins
	},
}

func main() {
	port := os.Getenv("PORT")
	if port == "" {
		port = "8080"
	}

	log.Println("==================================================")
	log.Println("  NEXA WebRTC P2P Signaling Hub (Zero-Disk)       ")
	log.Println("  Direct SCTP DataChannel SDP/ICE Relaying        ")
	log.Println("==================================================")

	// 1. Initialize ephemeral in-memory signaling hub (Zero Disk Storage)
	hub := signaling.NewSignalingHub()

	// 2. Register WebRTC Signaling WebSocket Endpoint
	http.HandleFunc("/ws/signaling", func(w http.ResponseWriter, r *http.Request) {
		nxId := r.URL.Query().Get("nx_id")
		if nxId == "" {
			http.Error(w, "Missing nx_id query parameter", http.StatusBadRequest)
			return
		}

		conn, err := upgrader.Upgrade(w, r, nil)
		if err != nil {
			log.Printf("[Signaling Server] WebSocket upgrade failed: %v", err)
			return
		}

		session := signaling.NewSignalingSession(hub, conn, nxId)
		hub.Register(session)

		go session.WritePump()
		go session.ReadPump()
	})

	// 3. Health & Status Check Endpoint
	http.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]interface{}{
			"status":    "UP",
			"mode":      "Direct-P2P-Signaling-Only",
			"storage":   "Zero-Disk-Zero-Cloud",
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
		log.Printf("[Signaling Server] Listening on ws://0.0.0.0%s/ws/signaling", addr)
		if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("[Signaling Server] Listen error: %v", err)
		}
	}()

	// Graceful shutdown
	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit

	log.Println("[Signaling Server] Shutting down signaling service...")
}
