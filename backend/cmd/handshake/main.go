package main

import (
	"log"
	"net/http"
	"os"

	"nexa/wire/internal/handshake"
)

func main() {
	port := os.Getenv("PORT")
	if port == "" {
		port = "8080"
	}

	store := handshake.NewMemoryStore()
	handler := handshake.NewHandshakeHandler(store)

	mux := http.NewServeMux()
	handler.RegisterRoutes(mux)

	// Health check endpoint
	mux.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		w.Write([]byte("OK"))
	})

	log.Printf("[NEXA HANDSHAKE] Starting Zero-Knowledge Dispatch Server on :%s", port)
	if err := http.ListenAndServe(":"+port, mux); err != nil {
		log.Fatalf("Server failed: %v", err)
	}
}
