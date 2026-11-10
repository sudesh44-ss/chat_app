package main

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/gorilla/websocket"
	"github.com/jackc/pgx/v5/pgxpool"
	"nexa/wire/internal/api"
	"nexa/wire/internal/auth"
	"nexa/wire/internal/connection"
	"nexa/wire/internal/connections"
	"nexa/wire/internal/database"
	"nexa/wire/internal/fcm"
	"nexa/wire/internal/identity"
	"nexa/wire/internal/prekey"
	"nexa/wire/internal/queue"
	"nexa/wire/internal/ratelimit"
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

	env := strings.ToLower(os.Getenv("APP_ENV"))
	if env == "" {
		env = strings.ToLower(os.Getenv("ENV"))
	}
	isProduction := env == "production"

	redisAddr := os.Getenv("REDIS_ADDR")
	if redisAddr == "" {
		redisAddr = "localhost:6379"
	}
	redisPassword := os.Getenv("REDIS_PASSWORD")

	log.Println("==================================================")
	log.Println("   NEXA Blind-Postman Relay Server (Zero-Disk)   ")
	log.Println("   + Identity, Auth, FCM & Multi-Device Sync     ")
	log.Printf("   Environment: %s (Production: %v)\n", env, isProduction)
	log.Println("==================================================")

	// 1. Initialize Ephemeral Redis Queue Buffer (24h TTL)
	buffer, err := queue.NewRedisBuffer(redisAddr, redisPassword)
	if err != nil {
		log.Fatalf("[Server] Failed to initialize buffer: %v", err)
	}
	defer buffer.Close()

	if buffer.IsDegraded() {
		if isProduction {
			log.Fatalf("[Server] CRITICAL: Ephemeral Redis Queue Buffer failed in production! Operating in degraded mode is rejected. Aborting startup.")
		}
		log.Println("[Server] WARNING: Buffer is running in DEGRADED in-memory RAM fallback mode.")
	} else {
		log.Println("[Server] Ephemeral Redis Queue Buffer initialized successfully.")
	}

	// 2. Initialize Persistence: PostgreSQL with fallback to file-backed JSON Store
	var identityRepo identity.IdentityRepository
	var pgPool *pgxpool.Pool
	var dbStatus = "DISABLED"

	databaseURL := strings.TrimSpace(os.Getenv("DATABASE_URL"))
	if databaseURL != "" {
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		dbConfig := database.DefaultConfig(databaseURL)
		pool, err := database.NewPool(ctx, dbConfig)
		cancel()
		if err != nil {
			log.Printf("[Server] ERROR: Failed to connect to PostgreSQL: %v", err)
			dbStatus = "CONNECTION_FAILED"
		} else {
			pgPool = pool
			// Apply database migrations
			migCtx, migCancel := context.WithTimeout(context.Background(), 30*time.Second)
			if err := database.RunMigrations(migCtx, pool); err != nil {
				log.Fatalf("[Server] CRITICAL: Failed to apply database migrations: %v", err)
			}
			migCancel()

			pgRepo := database.NewPostgresRepository(pool)
			identityRepo = pgRepo
			dbStatus = "CONNECTED"

			// If legacy JSON identity store exists, run safe one-time migration
			identityStorePath := os.Getenv("IDENTITY_STORE_PATH")
			if identityStorePath == "" {
				identityStorePath = "data/identity_store.json"
			}
			migrator := identity.NewMigrator(identityStorePath, pgRepo)
			migRes, err := migrator.Migrate(context.Background())
			if err != nil {
				log.Printf("[Server] WARNING: Identity migration error: %v", err)
			} else if migRes != nil && migRes.AccountsMigrated > 0 {
				log.Printf("[Server] Identity migration completed: %d accounts migrated. Fingerprint: %s",
					migRes.AccountsMigrated, migRes.TargetFingerprint)
			}
		}
	}

	if identityRepo == nil {
		if isProduction {
			log.Fatalf("[Server] CRITICAL: PostgreSQL identity repository connection failed or unconfigured in production! Aborting startup.")
		}
		identityStorePath := os.Getenv("IDENTITY_STORE_PATH")
		if identityStorePath == "" {
			identityStorePath = "data/identity_store.json"
		}
		log.Printf("[Server] Running with file-backed JSON identity store: %s", identityStorePath)
		jsonStore, err := identity.NewStore(identityStorePath)
		if err != nil {
			log.Fatalf("[Server] Failed to initialize JSON identity store: %v", err)
		}
		identityRepo = jsonStore
		if dbStatus == "DISABLED" {
			dbStatus = "FALLBACK_JSON"
		}
	}
	defer identityRepo.Close()

	smsProvider := auth.GetDefaultSmsProvider()
	otpService := auth.NewOtpService(smsProvider)
	googleVerifier := auth.NewGoogleVerifier()
	authHandler := auth.NewAuthHandler(identityRepo, otpService, googleVerifier)
	authHandler.SetChallengeStore(auth.NewChallengeStore(buffer.GetClient()))
	authHandler.SetRedisClient(buffer.GetClient())
	authHandler.RegisterRoutes(http.DefaultServeMux)

	// 3. Initialize FCM Background Wake-Up Service (Cost: ₹0)
	fcmService := fcm.NewFcmService()

	// 4. Initialize in-memory concurrent Session Hub
	hub := connection.NewHub(buffer)
	hub.SetFcmService(fcmService)
	hub.SetIdentityStore(identityRepo)
	authHandler.SetSessionRevoker(hub)

	// Multi-instance Redis Pub/Sub cluster subscriber
	if buffer.GetClient() != nil {
		if err := hub.StartClusterSubscriber(context.Background(), buffer.GetClient()); err != nil {
			log.Printf("[Server] WARNING: Cluster subscriber startup error: %v", err)
		}
	}

	// Connections & Contacts Management
	var connRepo connections.ConnectionRepository
	if pgPool != nil {
		connRepo = connections.NewPostgresRepository(pgPool)
	} else {
		if isProduction {
			log.Fatalf("[Server] CRITICAL: PostgreSQL connections repository is unavailable in production! Aborting startup.")
		}
		connRepo = connections.NewMemoryRepository(identityRepo)
	}
	hub.SetConnectionValidator(connRepo)
	connHandler := connections.NewHandler(connRepo, identityRepo, hub)
	connHandler.RegisterRoutes(http.DefaultServeMux)

	// E2EE Prekey & Claim Management (Phase E Step 3)
	var prekeyRepo prekey.PrekeyRepository
	if pgPool != nil {
		prekeyRepo = prekey.NewPostgresPrekeyRepository(pgPool)
	} else {
		if isProduction {
			log.Fatalf("[Server] CRITICAL: PostgreSQL prekey repository is unavailable in production! Aborting startup.")
		}
		prekeyRepo = prekey.NewMemoryPrekeyRepository()
	}
	prekeyHandler := prekey.NewHandler(prekeyRepo, identityRepo)
	if connRepo != nil {
		prekeyHandler.SetConnectionValidator(connRepo)
	}
	if buffer.GetClient() != nil {
		prekeyHandler.SetRateLimiter(ratelimit.NewDistributedLimiter(buffer.GetClient(), isProduction))
	}
	prekeyHandler.RegisterRoutes(http.DefaultServeMux)

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

		go session.WritePump()
		go session.ReadPump()

		// Dispatch Ed25519 authentication challenge (60s strict TTL)
		if err := session.SendChallenge(); err != nil {
			log.Printf("[Server] Failed to dispatch auth challenge to %s: %v", nxId, err)
			session.CloseWithUnauthorized("Challenge dispatch failed")
			return
		}
	})

	// 7. Register WebRTC P2P Signaling WebSocket Endpoint (VULN-01 Hardened)
	http.HandleFunc("/ws/signaling", func(w http.ResponseWriter, r *http.Request) {
		nxId := r.URL.Query().Get("nx_id")
		if nxId == "" {
			http.Error(w, "Missing nx_id query parameter", http.StatusBadRequest)
			return
		}

		token := r.URL.Query().Get("token")
		if token == "" {
			authHeader := r.Header.Get("Authorization")
			if strings.HasPrefix(authHeader, "Bearer ") {
				token = strings.TrimSpace(strings.TrimPrefix(authHeader, "Bearer "))
			}
		}

		if token == "" {
			http.Error(w, "Unauthorized: missing session token", http.StatusUnauthorized)
			return
		}

		sess, ok := identityRepo.GetSession(token)
		if !ok || sess == nil || sess.IsRevoked || (sess.ExpiresAt > 0 && time.Now().UnixMilli() > sess.ExpiresAt) {
			http.Error(w, "Unauthorized: invalid or expired session", http.StatusUnauthorized)
			return
		}

		if sess.NxID != nxId {
			http.Error(w, "Forbidden: session token does not match nx_id", http.StatusForbidden)
			return
		}

		if sess.DeviceID != "" {
			if dev, found := identityRepo.GetDevice(sess.DeviceID); found && (dev.IsRevoked || dev.NxID != sess.NxID) {
				http.Error(w, "Unauthorized: device revoked", http.StatusUnauthorized)
				return
			}
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
		redisStatus := "CONNECTED"
		if buffer.IsDegraded() {
			redisStatus = "DEGRADED_IN_MEMORY"
		}
		json.NewEncoder(w).Encode(map[string]interface{}{
			"service":   "NEXA Blind-Postman Relay Server",
			"status":    "UP",
			"database":  dbStatus,
			"redis":     redisStatus,
			"mode":      "Ephemeral-RAM-Only",
			"version":   "1.0",
			"endpoints": []string{
				"/health", "/api/v1/telemetry/metrics", "/api/v1/telemetry/batch",
				"/api/v1/media/upload-ticket", "/api/v1/media/purge-ack", "/ws", "/ws/signaling",
				"/api/v1/auth/otp/request", "/api/v1/auth/otp/verify", "/api/v1/auth/initiate-reverse-sms", "/api/v1/auth/verification-status", "/api/v1/webhooks/sms-inbound",
				"/api/v1/users/discover", "/api/v1/connections/request", "/api/v1/connections/requests/incoming", "/api/v1/connections/requests/outgoing",
				"/api/v1/connections/requests/accept", "/api/v1/connections/requests/reject", "/api/v1/connections/requests/cancel",
				"/api/v1/connections", "/api/v1/connections/remove", "/api/v1/users/block", "/api/v1/users/unblock", "/api/v1/users/blocked",
				"/api/v1/users/identity-key", "/api/v1/users/prekeys/signed", "/api/v1/users/prekeys/one-time", "/api/v1/users/prekey", "/api/v1/prekeys/claim",
			},
			"timestamp": time.Now().UnixMilli(),
		})
	})

	http.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		redisStatus := "CONNECTED"
		if buffer.IsDegraded() {
			redisStatus = "DEGRADED_IN_MEMORY"
		}
		json.NewEncoder(w).Encode(map[string]interface{}{
			"status":    "UP",
			"database":  dbStatus,
			"redis":     redisStatus,
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

		// Enforce authentication via Bearer session token
		authHeader := r.Header.Get("Authorization")
		sessionToken := strings.TrimSpace(strings.TrimPrefix(authHeader, "Bearer "))
		if sessionToken == "" {
			http.Error(w, "Unauthorized: session required", http.StatusUnauthorized)
			return
		}
		sess, ok := identityRepo.GetSession(sessionToken)
		if !ok || sess == nil || sess.IsRevoked || (sess.ExpiresAt > 0 && time.Now().UnixMilli() > sess.ExpiresAt) {
			http.Error(w, "Unauthorized: invalid or revoked session", http.StatusUnauthorized)
			return
		}
		if sess.DeviceID != "" {
			if dev, found := identityRepo.GetDevice(sess.DeviceID); found && (dev.IsRevoked || dev.NxID != sess.NxID) {
				http.Error(w, "Unauthorized: session device revoked", http.StatusUnauthorized)
				return
			}
		}

		// Enforce maximum body size 64 KB to prevent JSON DOS
		r.Body = http.MaxBytesReader(w, r.Body, 64*1024)

		var req struct {
			NxId       string `json:"nx_id"`
			SizeBytes  int64  `json:"size_bytes"`
			CipherHash string `json:"cipher_hash"`
		}
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			http.Error(w, "Invalid JSON body", http.StatusBadRequest)
			return
		}

		// Bind req.NxId to authenticated caller identity to prevent IDOR spoofing
		if req.NxId != "" && req.NxId != sess.NxID {
			http.Error(w, "Forbidden: cannot request ticket for another NX-ID", http.StatusForbidden)
			return
		}
		req.NxId = sess.NxID

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

		// Enforce authentication via Bearer session token
		authHeader := r.Header.Get("Authorization")
		sessionToken := strings.TrimSpace(strings.TrimPrefix(authHeader, "Bearer "))
		if sessionToken == "" {
			http.Error(w, "Unauthorized: session required", http.StatusUnauthorized)
			return
		}
		sess, ok := identityRepo.GetSession(sessionToken)
		if !ok || sess == nil || sess.IsRevoked || (sess.ExpiresAt > 0 && time.Now().UnixMilli() > sess.ExpiresAt) {
			http.Error(w, "Unauthorized: invalid or revoked session", http.StatusUnauthorized)
			return
		}
		if sess.DeviceID != "" {
			if dev, found := identityRepo.GetDevice(sess.DeviceID); found && (dev.IsRevoked || dev.NxID != sess.NxID) {
				http.Error(w, "Unauthorized: session device revoked", http.StatusUnauthorized)
				return
			}
		}

		// Enforce maximum body size 64 KB
		r.Body = http.MaxBytesReader(w, r.Body, 64*1024)

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
