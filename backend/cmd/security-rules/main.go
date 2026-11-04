package main

import (
	"encoding/json"
	"log"
	"net/http"
	"os"

	"nexa/wire/internal/rules"
)

type VersionResponse struct {
	Version      string `json:"version"`
	BloomEntries int    `json:"bloom_entries"`
	Timestamp    int64  `json:"timestamp"`
}

func main() {
	port := os.Getenv("PORT")
	if port == "" {
		port = "8086"
	}

	store := rules.NewRulesStore()
	mux := http.NewServeMux()

	// Metadata endpoint: returns current signature version & bloom count
	mux.HandleFunc("/api/v1/security/rules/version", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet {
			http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
			return
		}

		ver, entries, updatedAt := store.GetVersion()
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_ = json.NewEncoder(w).Encode(VersionResponse{
			Version:      ver,
			BloomEntries: entries,
			Timestamp:    updatedAt.Unix(),
		})
	})

	// Bundle distribution endpoint with HTTP ETag differential caching
	mux.HandleFunc("/api/v1/security/rules/bundle", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet {
			http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
			return
		}

		clientEtag := r.Header.Get("If-None-Match")
		currentEtag := store.GetEtag()

		if clientEtag != "" && clientEtag == currentEtag {
			w.WriteHeader(http.StatusNotModified)
			return
		}

		bundleData, etag, ver := store.GetBundle()
		w.Header().Set("Content-Type", "application/octet-stream")
		w.Header().Set("ETag", etag)
		w.Header().Set("X-Rules-Version", ver)
		w.Header().Set("Cache-Control", "public, max-age=86400")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write(bundleData)
	})

	// Health check endpoint
	mux.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("OK"))
	})

	log.Printf("[NEXA SECURITY-RULES] Starting Differential OTA Rule Distribution Service on :%s", port)
	if err := http.ListenAndServe(":"+port, mux); err != nil {
		log.Fatalf("Security Rules Server failed: %v", err)
	}
}
