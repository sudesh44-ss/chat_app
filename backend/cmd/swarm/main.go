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
	"nexa/wire/internal/swarm"
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
	log.Println("  NEXA Swarm & Seeder Coordinator (Zero-Disk)     ")
	log.Println("  On-Demand Selective Distribution Hub            ")
	log.Println("==================================================")

	hub := swarm.NewSwarmHub()

	http.HandleFunc("/ws/swarm", func(w http.ResponseWriter, r *http.Request) {
		peerId := r.URL.Query().Get("peer_id")
		if peerId == "" {
			http.Error(w, "Missing peer_id query parameter", http.StatusBadRequest)
			return
		}

		conn, err := upgrader.Upgrade(w, r, nil)
		if err != nil {
			log.Printf("[Swarm Server] WebSocket upgrade failed: %v", err)
			return
		}

		session := swarm.NewSwarmSession(hub, conn, peerId)
		hub.Register(session)

		go session.WritePump()
		go session.ReadPump()
	})

	http.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]interface{}{
			"status":    "UP",
			"subsystem": "Swarm-Coordinator",
			"storage":   "Zero-Disk-Ephemeral-RAM",
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
		log.Printf("[Swarm Server] Listening on ws://0.0.0.0%s/ws/swarm", addr)
		if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("[Swarm Server] Listen error: %v", err)
		}
	}()

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit

	log.Println("[Swarm Server] Shutting down swarm coordinator...")
}
