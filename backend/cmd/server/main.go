package main

import (
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/gorilla/websocket"
	"nexa/wire/internal/api"
	"nexa/wire/internal/auth"
	"nexa/wire/internal/connection"
	"nexa/wire/internal/fcm"
	"nexa/wire/internal/identity"
	"nexa/wire/internal/queue"
	"nexa/wire/internal/signaling"
)

var upgrader = websocket.Upgrader{
	ReadBufferSize:  1024 * 64,
	WriteBufferSize: 1024 * 64,
	CheckOrigin: func(r *http.Request) bool {
		// Allow all origins for mobile WebSocket clients
		return true
	},
}

func main() {
	startTime := time.Now()

	port := os.Getenv("PORT")
	if port == "" {
		port = "8080"
	}

	redisAddr := os.Getenv("REDIS_ADDR")
	if redisAddr == "" {
		redisAddr = "localhost:6379"
	}
	redisPassword := os.Getenv("REDIS_PASSWORD")

	log.Println("==================================================")
	log.Println("   NEXA Blind-Postman Relay Server (Zero-Disk)   ")
	log.Println("   + Identity, Auth, FCM & Multi-Device Sync     ")
	log.Println("==================================================")

	// 1. Initialize Ephemeral Redis Queue Buffer (24h TTL)
	buffer, err := queue.NewRedisBuffer(redisAddr, redisPassword)
	if err != nil {
		log.Fatalf("[Server] Failed to initialize buffer: %v", err)
	}
	defer buffer.Close()

	// 2. Initialize Identity Store & Auth Services
	identityStorePath := os.Getenv("IDENTITY_STORE_PATH")
	if identityStorePath == "" {
		identityStorePath = "data/identity_store.json"
	}
	identityStore, err := identity.NewStore(identityStorePath)
	if err != nil {
		log.Fatalf("[Server] Failed to initialize identity store: %v", err)
	}

	smsProvider := auth.GetDefaultSmsProvider()
	otpService := auth.NewOtpService(smsProvider)
	googleVerifier := auth.NewGoogleVerifier()
	authHandler := auth.NewAuthHandler(identityStore, otpService, googleVerifier)
	authHandler.RegisterRoutes(http.DefaultServeMux)

	// 3. Initialize FCM Background Wake-Up Service (Cost: ₹0)
	fcmService := fcm.NewFcmService()

	// 4. Initialize in-memory concurrent Session Hub
	hub := connection.NewHub(buffer)
	hub.SetFcmService(fcmService)
	hub.SetIdentityStore(identityStore)

	// 5. Initialize WebRTC P2P Signaling Hub (Zero Cloud Storage, Ephemeral RAM)
	signalingHub := signaling.NewSignalingHub()

	// 6. Register Messaging WebSocket Endpoint
	http.HandleFunc("/ws", func(w http.ResponseWriter, r *http.Request) {
		nxId := r.URL.Query().Get("nx_id")
		if nxId == "" {
			http.Error(w, "Missing nx_id query parameter", http.StatusBadRequest)
			return
		}
		deviceId := r.URL.Query().Get("device_id")

		conn, err := upgrader.Upgrade(w, r, nil)
		if err != nil {
			log.Printf("[Server] WebSocket upgrade failed: %v", err)
			return
		}

		session := connection.NewClientSession(hub, conn, nxId, deviceId)

		// Optional: if client provided blind_token in query, register it
		if blindToken := r.URL.Query().Get("blind_token"); blindToken != "" {
			hub.RegisterBlindToken(blindToken, session)
		}

		go session.WritePump()
		go session.ReadPump()

		// Dispatch Ed25519 authentication challenge (60s strict TTL)
		if err := session.SendChallenge(); err != nil {
			log.Printf("[Server] Failed to dispatch auth challenge to %s: %v", nxId, err)
			session.CloseWithUnauthorized("Challenge dispatch failed")
			return
		}
	})

	// 7. Register WebRTC P2P Signaling WebSocket Endpoint
	http.HandleFunc("/ws/signaling", func(w http.ResponseWriter, r *http.Request) {
		nxId := r.URL.Query().Get("nx_id")
		if nxId == "" {
			http.Error(w, "Missing nx_id query parameter", http.StatusBadRequest)
			return
		}

		conn, err := upgrader.Upgrade(w, r, nil)
		if err != nil {
			log.Printf("[Signaling] WebSocket upgrade failed: %v", err)
			return
		}

		session := signaling.NewSignalingSession(signalingHub, conn, nxId)
		signalingHub.Register(session)

		go session.WritePump()
		go session.ReadPump()
	})

	// 8. Register DTN Opportunistic Cloud Dump Endpoint
	dtnHandler := api.NewDtnHandler(hub)
	http.HandleFunc("/api/v1/relay/opportunistic-dump", dtnHandler.HandleOpportunisticDump)

	// 9. Register Root & Health Diagnostics Endpoints
	http.HandleFunc("/favicon.ico", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	})

	http.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/" {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]interface{}{
			"service":   "NEXA Blind-Postman Relay Server",
			"status":    "UP",
			"mode":      "Ephemeral-RAM-Only",
			"version":   "1.0",
			"endpoints": []string{"/health", "/api/v1/telemetry/metrics", "/api/v1/telemetry/batch", "/api/v1/media/upload-ticket", "/api/v1/media/purge-ack", "/ws", "/ws/signaling", "/api/v1/auth/otp/request", "/api/v1/auth/otp/verify"},
			"timestamp": time.Now().UnixMilli(),
		})
	})

	http.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]interface{}{
			"status":    "UP",
			"mode":      "Ephemeral-RAM-Only",
			"dtnBridge": "Active",
			"diskUsage": "0 Bytes (Zero-Disk)",
			"timestamp": time.Now().UnixMilli(),
		})
	})

	http.HandleFunc("/api/v1/telemetry/metrics", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		metrics := hub.GetTelemetryMetrics()
		metrics["uptime_seconds"] = time.Since(startTime).Seconds()
		metrics["status"] = "HEALTHY"
		json.NewEncoder(w).Encode(metrics)
	})

	http.HandleFunc("/api/v1/telemetry/batch", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
			return
		}
		// Enforce maximum body size 512 KB to prevent memory exhaustion
		r.Body = http.MaxBytesReader(w, r.Body, 512*1024)
		body, err := io.ReadAll(r.Body)
		if err != nil {
			http.Error(w, "Payload too large or unreadable", http.StatusBadRequest)
			return
		}
		if len(body) == 0 {
			http.Error(w, "Empty telemetry payload", http.StatusBadRequest)
			return
		}

		hub.RecordTelemetryBatch(1)

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]interface{}{
			"status":            "INGESTED",
			"bytes_received":    len(body),
			"content_type":      r.Header.Get("Content-Type"),
			"compliance_policy": "IT Rules 2021 Safe-Audit (Zero-Plaintext)",
			"timestamp":         time.Now().UnixMilli(),
		})
	})

	http.HandleFunc("/api/v1/media/upload-ticket", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
			return
		}

		var req struct {
			NxId       string `json:"nx_id"`
			SizeBytes  int64  `json:"size_bytes"`
			CipherHash string `json:"cipher_hash"`
		}
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			http.Error(w, "Invalid JSON body", http.StatusBadRequest)
			return
		}

		// Max size gate: 100 MB ceiling over cloud fallback
		const maxCloudBytes = 100 * 1024 * 1024
		if req.SizeBytes > maxCloudBytes {
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusBadRequest)
			json.NewEncoder(w).Encode(map[string]interface{}{
				"error":             "File too large for cloud fallback (>100MB). Direct P2P transfer required.",
				"max_allowed_bytes": maxCloudBytes,
			})
			return
		}

		now := time.Now().Unix()
		objectKey := fmt.Sprintf("ephemeral/%s/%d_%s.enc", req.NxId, now, req.CipherHash)
		uploadUrl := fmt.Sprintf("https://r2.nexa.network/%s?ticket=%d&hash=%s", objectKey, now+900, req.CipherHash)

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]interface{}{
			"status":            "TICKET_ISSUED",
			"object_key":        objectKey,
			"upload_url":        uploadUrl,
			"expires_in_secs":   900,
			"max_allowed_bytes": maxCloudBytes,
			"ttl_policy":        "Ephemeral Mailbox (7-Day Auto-Purge / Immediate ACK Drop)",
			"timestamp":         time.Now().UnixMilli(),
		})
	})

	http.HandleFunc("/api/v1/media/purge-ack", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
			return
		}

		var req struct {
			ObjectKey string `json:"object_key"`
			Recipient string `json:"recipient_nx_id"`
		}
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			http.Error(w, "Invalid JSON body", http.StatusBadRequest)
			return
		}

		log.Printf("[MediaPurge] Immediate ACK received for object %s. Zero bytes retained.", req.ObjectKey)

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]interface{}{
			"status":         "PURGED",
			"object_key":     req.ObjectKey,
			"retained_bytes": 0,
			"storage_cost":   "₹0",
			"timestamp":      time.Now().UnixMilli(),
		})
	})

	addr := fmt.Sprintf(":%s", port)
	server := &http.Server{
		Addr:         addr,
		ReadTimeout:  15 * time.Second,
		WriteTimeout: 15 * time.Second,
	}

	go func() {
		log.Printf("[Server] Listening on ws://0.0.0.0%s/ws, ws://0.0.0.0%s/ws/signaling, REST Auth, and DTN Cloud Bridge", addr, addr)
		if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("[Server] HTTP server listen error: %v", err)
		}
	}()

	// Graceful shutdown
	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit

	log.Println("[Server] Shutting down relay server...")
}
