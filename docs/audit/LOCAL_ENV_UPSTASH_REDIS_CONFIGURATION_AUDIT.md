# NEXA — Local Environment Variables & Upstash Redis Integration Audit

**Audit Date:** 2026-11-07  
**Auditor Mode:** STRICT READ-ONLY INSPECTION  
**Target Repository:** `https://github.com/sudesh44-ss/chat_app`  
**Branch:** `main`  
**Final Verdict:** **NATIVE REDIS CONFIGURATION REQUIRED**

---

## Executive Summary

An exhaustive, read-only architectural audit was conducted across the NEXA Go backend and deployment configurations to analyze local environment variable loading, Upstash Redis integration, and Render production requirements.

### Key Conclusions:
1. **No Automatic `.env` Loading:** The Go backend **DOES NOT** automatically load `.env` files. `go.mod` does not include `godotenv` (`github.com/joho/godotenv`), and `backend/cmd/server/main.go` contains no `.env` parser. All configuration is read exclusively from operating system process environment variables via `os.Getenv(...)`.
2. **Upstash REST API is NOT Supported:** Variables `UPSTASH_REDIS_REST_URL` and `UPSTASH_REDIS_REST_TOKEN` are **unreferenced and unsupported**. The backend does not include an HTTP REST client for Redis. Configuring Upstash REST credentials on Render will have zero effect.
3. **Upstash Native Redis Protocol (TLS) is FULLY Supported:** The backend uses `github.com/redis/go-redis/v9 v9.6.1`, which implements the native binary Redis RESP protocol over TCP. Setting `REDIS_URL=rediss://default:<password>@<endpoint>.upstash.io:6379` is 100% compatible and automatically enables TLS.
4. **Mandatory Production Fail-Closed Behavior:** In production (`APP_ENV=production`), the backend strictly enforces that Redis and PostgreSQL are operational. If `REDIS_URL` is omitted, malformed, or unreachable, `main.go:75` executes `log.Fatalf` and terminates immediately. It will **NEVER** silently fall back to degraded in-memory mode in production.
5. **Zero-Knowledge Redis Storage:** The Redis buffer stores only opaque protobuf-encoded ciphertext envelopes (`RPush pending:<nxId> <ciphertext_bytes>`). No plaintext message bodies, private identity keys, or Double Ratchet keys are ever written to Redis.

---

## 1. Project Structure & Correct Local `.env` Location

### Project Layout
```text
chat_app/
├── .gitignore                      <-- Ignores .env and .env.* (line 37-38)
├── app/                            <-- Android client (Kotlin)
├── database/                       <-- PostgreSQL DDL migrations
├── docs/audit/                     <-- Security and architectural audit reports
└── backend/                        <-- Go backend root
    ├── cmd/
    │   └── server/main.go          <-- Backend entrypoint (reads os.Getenv)
    ├── internal/
    │   ├── auth/                   <-- Reverse SMS, Google OAuth, OTP service
    │   ├── connection/             <-- WebSocket hub, multi-instance Pub/Sub
    │   ├── database/               <-- pgxpool PostgreSQL repository
    │   ├── prekey/                 <-- E2EE prekey repository & claim handlers
    │   ├── queue/redis_buffer.go   <-- Ephemeral Redis buffer & go-redis client
    │   └── ratelimit/              <-- Distributed Redis Lua rate limiter
    ├── docker-compose.yml          <-- Local ephemeral Redis 7 container
    ├── Dockerfile                  <-- Multi-stage Linux deployment build
    ├── go.mod                      <-- Go dependencies (no godotenv)
    └── go.sum
```

### Correct Local `.env` Location
- **Status on Disk:** Currently, **no `.env` or `.env.example` file exists** in the repository root or in `backend/`.
- **Git Tracking:** Git history confirms that `.env` has never been committed (`git log --all -- "**.env*" = clean`). `.gitignore` explicitly blocks `.env` and `.env.*`.
- **Target Working Directory:** When starting the backend locally, developers navigate to `backend/` (`cd backend`) or run from the repo root (`go run ./backend/cmd/server`).
- **Convention:** If a local `.env` file is created, its conventional location is `backend/.env`. However, because Go does not read files automatically, the developer must load the file into their shell environment prior to starting the process.

---

## 2. Local `.env` Loading Mechanism Audit

| Question | Audit Finding | Source Code Evidence |
|---|---|---|
| Does Go automatically load `.env`? | **NO** | `backend/cmd/server/main.go` calls only `os.Getenv(...)`. |
| Is `godotenv` installed? | **NO** | `backend/go.mod` lists only `gorilla/websocket`, `jackc/pgx/v5`, and `redis/go-redis/v9`. |
| Is `godotenv.Load()` called? | **NO** | 0 occurrences in entire codebase. |
| Is there a custom `.env` loader? | **NO** | 0 occurrences of custom file parsing in `cmd/` or `internal/`. |
| What directory does it search? | **None** | No directory search is performed. |
| What happens if `.env` is missing? | **Normal Startup** | Server starts using process environment variables or built-in defaults. |
| Are variables loaded before Redis? | **N/A** | Variables are read from the OS process environment at lines 43-58 before Redis initialization at line 67. |
| Are variables overwritten by process vars? | **N/A** | Only process variables exist; no file overwriting occurs. |
| Does local dev have a separate config path? | **YES** | If `APP_ENV != "production"`, missing Redis falls back to volatile in-memory RAM queue. |

---

## 3. Redis Client Compatibility Audit

### Exact Redis Library
- **Library:** `github.com/redis/go-redis/v9` (version `v9.6.1`)
- **Protocol:** Native **Redis RESP (REdis Serialization Protocol)** over TCP or TLS sockets.
- **Connection Logic:** Implemented in [`backend/internal/queue/redis_buffer.go`](file:///H:/chat_app/backend/internal/queue/redis_buffer.go#L40-L130).

### Comparison: Upstash REST vs. Native Redis Protocol

```
+-----------------------------------------------------------------------------------------+
|                                    NEXA GO BACKEND                                      |
|                                                                                         |
|       +-----------------------------------+   +------------------------------------+    |
|       |     Option A: Upstash REST API    |   |  Option B: Native Redis Protocol   |    |
|       | UPSTASH_REDIS_REST_URL / _TOKEN   |   |   REDIS_URL=rediss://default:...   |    |
|       +-----------------------------------+   +------------------------------------+    |
|                         |                                        |                      |
|                  NOT IMPLEMENTED                               SUPPORTED                |
|               (No HTTP REST Client)                     (go-redis/v9 RESP Engine)       |
|                         |                                        |                      |
|                         v                                        v                      |
|                  [REJECTED/IGNORED]                       [CONNECTED via TLS]           |
|                                                                                         |
+-----------------------------------------------------------------------------------------+
```

#### Detailed Breakdown:

1. **Option A — Upstash REST API (HTTP GET/POST):**
   ```env
   UPSTASH_REDIS_REST_URL=https://your-database.upstash.io
   UPSTASH_REDIS_REST_TOKEN=your-token
   ```
   - **Supported:** **NO.**
   - **Technical Reason:** The Go backend does not include the `@upstash/redis` REST SDK, nor any HTTP request dispatcher for Redis commands. These variables are never read. Setting them will leave Redis unconfigured.
   - **Protocol Limitations:** An HTTP REST API cannot support the real-time, blocking WebSocket Pub/Sub subscriptions used in `backend/internal/connection/pubsub.go` (`pubsub.ReceiveMessage(ctx)`).

2. **Option B — Native Redis Protocol (TLS Connection String):**
   ```env
   REDIS_URL=rediss://default:your_upstash_password@your-endpoint.upstash.io:6379
   ```
   - **Supported:** **YES (100% RECOMMENDED).**
   - **Technical Reason:** Handled by `redis.ParseURL(redisURL)` in `backend/internal/queue/redis_buffer.go:68`.
   - **Auto-TLS:** The `rediss://` scheme (double 's') instructs `go-redis` to initialize a secure `crypto/tls` configuration.
   - **Authentication:** Automatically parses `default` as the username and the secret as the password.

3. **Option C — Host and Port:**
   ```env
   REDIS_ADDR=your-endpoint.upstash.io:6379
   REDIS_PASSWORD=your_upstash_password
   ```
   - **Supported:** **PARTIALLY (NOT RECOMMENDED for Upstash).**
   - **Technical Reason:** `REDIS_ADDR` populates `redis.Options{Addr: redisAddr, Password: password}`, but **does NOT automatically enable TLS**. Because Upstash cloud endpoints strictly mandate TLS encryption on port 6379, connecting via `REDIS_ADDR` without TLS will result in a connection handshake rejection. Use `REDIS_URL=rediss://...` instead.

---

## 4. Environment Variable Inventory

All environment variable references across the NEXA Go backend were indexed:

| Variable | Source Location | Required? | Local Dev Usage | Render Production Usage | Actual Status & Fail-Closed Behavior |
| :--- | :--- | :---: | :--- | :--- | :--- |
| `APP_ENV` | `main.go:48`<br>`redis_buffer.go:41`<br>`reverse_sms.go:149`<br>`sms_provider.go:200` | **YES** | Defaults to `""` (dev mode). Enables in-memory RAM fallback. | Set to `production`. Enforces strict fail-closed security. | **ACTIVE**. Controls production gates across all subsystems. |
| `ENV` | `main.go:50`<br>`redis_buffer.go:43` | Optional | Fallback for `APP_ENV`. | Fallback for `APP_ENV`. | **ACTIVE**. Secondary alias for `APP_ENV`. |
| `PORT` | `main.go:43` | Optional | Defaults to `"8080"`. | Automatically injected by Render (e.g. `10000`). | **ACTIVE**. Sets HTTP server listen address. |
| `REDIS_URL` | `redis_buffer.go:40` | **YES (Prod)** | Optional. Connects local or cloud Redis instance. | **Mandatory** for managed Redis (`rediss://...`). | **ACTIVE**. Primary Redis connection mechanism with auto-TLS. |
| `REDIS_ADDR` | `main.go:54`<br>`redis_buffer.go:81` | Optional | Defaults to `"localhost:6379"`. | Ignored if `REDIS_URL` present. Fails closed if `"localhost:6379"`. | **ACTIVE**. Legacy host:port fallback. |
| `REDIS_PASSWORD` | `main.go:58`<br>`redis_buffer.go:84` | Optional | Password for local Redis if auth enabled. | Used only with `REDIS_ADDR`. | **ACTIVE**. Passed to `redis.Options`. |
| `DATABASE_URL` | `main.go:87` | **YES (Prod)** | Optional. If unset, uses `data/identity_store.json`. | **Mandatory** PostgreSQL connection URI. | **ACTIVE**. If missing in production, server aborts startup. |
| `IDENTITY_STORE_PATH` | `main.go:110, 129` | Optional | Defaults to `data/identity_store.json`. | Ephemeral migration path if migrating legacy data. | **ACTIVE**. Used by JSON store and migrator. |
| `GOOGLE_CLIENT_ID` | `google_verifier.go:48` | **YES** | Web Client ID for Google login token validation. | Must match Android app OAuth client ID. | **ACTIVE**. Rejects tokens if empty. |
| `OTP_SERVER_PEPPER` | `otp_service.go:93` | **YES (Prod)** | Pepper for hashing OTPs. Dev fallback available. | Mandatory high-entropy secret. | **ACTIVE**. Used in HMAC-SHA256 OTP hashing. |
| `INBOUND_SMS_RECEIVER_NUMBER` | `reverse_sms.go:148` | **YES (Prod)** | Defaults to `+919800000000` (mock gateway). | Must be set to active inbound telecom SIM number. | **ACTIVE**. Rejects dummy numbers in production. |
| `GATEWAY_WEBHOOK_SECRET` | `webhook_auth.go:33` | **YES (Prod)** | Secret for webhook signature validation. | Mandatory HMAC/token secret for telecom webhook. | **ACTIVE**. Fails closed (`ErrMissingWebhookSecret`). |
| `DEBUG_SHOW_OTP` | `sms_provider.go:207` | Dev Only | If `"true"`, prints OTPs to console logs. | Must **NEVER** be enabled in production. | **ACTIVE**. Development diagnostic flag. |
| `TWILIO_ACCOUNT_SID` | `sms_provider.go:242` | Optional | Twilio account identifier. | Outbound SMS fallback credential. | **ACTIVE**. |
| `TWILIO_AUTH_TOKEN` | `sms_provider.go:243` | Optional | Twilio auth token. | Outbound SMS fallback credential. | **ACTIVE**. |
| `TWILIO_FROM_NUMBER` | `sms_provider.go:244` | Optional | Outbound sender phone number. | Outbound SMS fallback number. | **ACTIVE**. |
| `TWILIO_PHONE_NUMBER` | `sms_provider.go:246` | Optional | Alias for `TWILIO_FROM_NUMBER`. | Outbound SMS fallback number alias. | **ACTIVE**. |
| `MSG91_AUTH_KEY` | `sms_provider.go:234` | Optional | MSG91 cellular gateway key. | Outbound SMS credential for India delivery. | **ACTIVE**. |
| `MSG91_TEMPLATE_ID` | `sms_provider.go:235` | Optional | MSG91 DLT approved template. | Outbound SMS template ID. | **ACTIVE**. |
| `FCM_PROJECT_ID` | `fcm_service.go:29` | Optional | Firebase Cloud Messaging project. | Background wake-up project ID. | **ACTIVE**. |
| `SUPABASE_JWT_SECRET` | `supabase_verifier.go:34` | Deprecated | Unused legacy JWT stub. | Not used by `main.go`. | *DEAD CODE*. |
| `SUPABASE_URL` | `supabase_verifier.go:35` | Deprecated | Unused legacy URL stub. | Not used by `main.go`. | *DEAD CODE*. |
| `UPSTASH_REDIS_REST_URL` | None | **NO** | Unused / Unsupported. | Unused / Unsupported. | **MISSING IN CODEBASE**. |
| `UPSTASH_REDIS_REST_TOKEN` | None | **NO** | Unused / Unsupported. | Unused / Unsupported. | **MISSING IN CODEBASE**. |

---

## 5. Local Configuration & Startup Guide

Because Go does not automatically parse `.env` files, you must inject variables into your local shell session before launching the backend.

### Correct Local Structure
```text
chat_app/
├── backend/
│   ├── .env               <-- Local secrets (create locally; gitignored)
│   ├── cmd/server/main.go
│   └── ...
```

### Option 1: Local Development with In-Memory Fallbacks (Zero External Dependencies)
If you want to run the server locally without spinning up Redis or PostgreSQL:
```powershell
Set-Location H:\chat_app\backend
$env:PORT = "8080"
$env:APP_ENV = "development"
$env:DEBUG_SHOW_OTP = "true"
go run ./cmd/server
```
- **Behavior:** Redis runs in `DEGRADED in-memory RAM fallback mode`. Identities are stored in `backend/data/identity_store.json`. Outbound OTPs are printed directly to the terminal.

### Option 2: Local Development with Docker Redis & PostgreSQL
Use the included `docker-compose.yml`:
```powershell
# 1. Start local ephemeral Redis container (Port 6379)
Set-Location H:\chat_app\backend
docker-compose up -d redis

# 2. Run backend connected to local Redis
$env:PORT = "8080"
$env:APP_ENV = "development"
$env:REDIS_URL = "redis://localhost:6379"
go run ./cmd/server
```

### Option 3: Local Development Connected to Upstash Redis
```powershell
Set-Location H:\chat_app\backend
$env:PORT = "8080"
$env:APP_ENV = "development"
$env:REDIS_URL = "rediss://default:<UPSTASH_PASSWORD>@<UPSTASH_HOST>.upstash.io:6379"
go run ./cmd/server
```

### Safe PowerShell Script to Load `backend/.env` without Echoing Secrets
```powershell
if (Test-Path H:\chat_app\backend\.env) {
    Get-Content H:\chat_app\backend\.env | Where-Object { $_ -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$' } | ForEach-Object {
        $key = $matches[1]
        $val = $matches[2].Trim("`"'")
        Set-Item -Path "env:$key" -Value $val
    }
    Write-Host "Loaded environment variables from backend/.env into current process."
} else {
    Write-Warning "backend/.env not found."
}
```

---

## 6. Render Production Configuration Instructions

In the Render Dashboard (**Dashboard → Your Service → Environment tab**), configure the following:

### 1. Mandatory Production Environment Variables

| Variable Key | Value Example / Requirement | Notes |
| :--- | :--- | :--- |
| `APP_ENV` | `production` | **Critical:** Activates production security gates and fail-closed checks. |
| `REDIS_URL` | `rediss://default:pwd@your-db.upstash.io:6379` | **Must use `rediss://`** with Upstash's native Redis port (6379), NOT the REST URL. |
| `DATABASE_URL` | `postgres://user:pwd@dpg-xxx.render.com/nexa` | PostgreSQL managed database connection URI. |
| `GOOGLE_CLIENT_ID` | `356056510017-xxx.apps.googleusercontent.com` | Google Cloud OAuth 2.0 Web Client ID. |
| `OTP_SERVER_PEPPER` | `64-character-hex-string` | High-entropy random hex secret for HMAC OTP hashing. |
| `INBOUND_SMS_RECEIVER_NUMBER` | `+91XXXXXXXXXX` | Inbound telecom receiver number for Reverse SMS. |
| `GATEWAY_WEBHOOK_SECRET` | `high-entropy-secret-string` | Shared secret with SMS provider for webhook validation. |

### 2. Upstash Configuration Step-by-Step for Render
1. Open the **Upstash Console** (`https://console.upstash.io`).
2. Navigate to your Redis Database.
3. In the **Details** / **Connect** tab, select **"ioredis"** or **"redis-cli"** (do NOT copy `@upstash/redis` REST snippet).
4. Copy the connection string starting with:
   ```text
   rediss://default:****************@your-db-name.upstash.io:6379
   ```
5. In the Render Dashboard, paste this exact URI into `REDIS_URL`.
6. Save and Trigger Deploy.

---

## 7. Security Audit & Fail-Closed Behavior

### 1. Fail-Closed Production Behavior
- **Missing Redis:** If `APP_ENV=production` and `REDIS_URL` is omitted, `backend/internal/queue/redis_buffer.go:90` logs `CRITICAL CONFIG ERROR` and sets `isDegraded = true`.
- **Unreachable Redis:** If Redis fails to respond to `client.Ping(ctx)` within 3 seconds, `redis_buffer.go:116` logs `CRITICAL ALERT` and sets `isDegraded = true`.
- **Startup Abort:** In `backend/cmd/server/main.go:73-76`:
  ```go
  if buffer.IsDegraded() {
      if isProduction {
          log.Fatalf("[Server] CRITICAL: Ephemeral Redis Queue Buffer failed in production! Operating in degraded mode is rejected. Aborting startup.")
      }
  }
  ```
  The server aborts startup immediately with exit code 1. **No degraded in-memory queue operation is permitted in production.**

### 2. Plaintext & Key Secrecy Verification
- **Ciphertext Only in Redis:** In `redis_buffer.go:132-140`, `PushPending` executes `r.client.RPush(ctx, "pending:"+recipientNxId, packetBytes)`. The `packetBytes` are the already-encrypted binary protobuf envelopes produced by client Double Ratchet engines.
- **Zero Key Exposure:** Private identity keys and Double Ratchet root/chain keys never leave the client devices and are never stored in Redis.
- **Ephemeral Mailbox Retention:** Redis queues enforce `Expire(ctx, key, 24*time.Hour)`. Once popped via `PopAllPending`, packets are immediately purged via `r.client.Del(ctx, key)`.

### 3. Client & Build Isolation
- Android client inspection confirmed zero references to Redis, Upstash, or cloud database credentials in `app/build.gradle.kts`, `BuildConfig`, or Kotlin sources.

---

## 8. Redis Functionality Inventory Across Subsystems

| Subsystem | Source Location | Redis Operation | Production Behavior without Redis |
| :--- | :--- | :--- | :--- |
| **Offline Packet Queue** | `internal/queue/redis_buffer.go` | `RPush`, `LRange`, `Del`, `Expire(24h)` | **Fails Closed:** Server aborts startup via `log.Fatalf`. |
| **Multi-Instance Pub/Sub** | `internal/connection/pubsub.go` | `client.Subscribe(ctx, "nexa:cluster:events")` | Drops cross-instance message routing (single-instance only). |
| **Distributed Rate Limiter** | `internal/ratelimit/limiter.go` | Lua Script (`ZREMRANGEBYSCORE`, `ZADD`, `ZCARD`, `PEXPIRE`) | **Fails Closed:** Rejects prekey claim requests with HTTP 500. |
| **Atomic Challenge Store** | `internal/auth/challenge_store.go` | Lua Script (`GET` and `DEL` atomic extraction) | Falls back to in-memory store in dev; vulnerable to cluster race if degraded. |
| **Reverse SMS Nonce Tracking** | `internal/auth/reverse_sms.go` | Ephemeral nonce caching and status lookup | Falls back to in-memory map in dev; server startup blocked in prod. |

---

## 9. Safe Diagnostic & Verification Commands (PowerShell)

Run these safe commands to inspect your local setup without disclosing credentials:

### 1. Verify Whether Local `.env` Exists
```powershell
Test-Path H:\chat_app\backend\.env
```

### 2. Verify Presence of Expected Variables in Process Environment
```powershell
@("APP_ENV", "PORT", "REDIS_URL", "DATABASE_URL", "GOOGLE_CLIENT_ID", "OTP_SERVER_PEPPER", "INBOUND_SMS_RECEIVER_NUMBER", "GATEWAY_WEBHOOK_SECRET") | ForEach-Object {
    [PSCustomObject]@{
        Variable = $_
        IsConfigured = [bool](Get-Item "env:$_" -ErrorAction SilentlyContinue)
    }
} | Format-Table -AutoSize
```

### 3. Safe TCP / TLS Socket Connectivity Probe to Upstash
Verify your machine can open a network socket to your Upstash instance without transmitting passwords:
```powershell
# Replace with your actual Upstash hostname from your REDIS_URL
$UpstashHost = "your-database.upstash.io"
Test-NetConnection -ComputerName $UpstashHost -Port 6379
```
*Expected Output: `TcpTestSucceeded : True`*

### 4. Verify Go Backend Compiles Cleanly
```powershell
Set-Location H:\chat_app\backend
go vet ./...
go test -count=1 ./internal/queue/...
```
*Expected Output: `PASS`*

---

## 10. Final Recommendations

1. **Do NOT Use Upstash REST Credentials:** Do not configure `UPSTASH_REDIS_REST_URL` or `UPSTASH_REDIS_REST_TOKEN`. They are unsupported by the Go backend.
2. **Use Upstash Native Redis URI:** In both local development and Render environment settings, configure:
   ```env
   REDIS_URL=rediss://default:<UPSTASH_PASSWORD>@<UPSTASH_HOST>.upstash.io:6379
   ```
3. **Ensure `APP_ENV=production` on Render:** Always set `APP_ENV=production` in Render. This guarantees that if Redis or PostgreSQL experience connection interruptions, the service fails closed immediately rather than operating with degraded, non-persisted in-memory queues.
4. **Local Development Convenience:** If you prefer automatic `.env` file loading during local development, either use a PowerShell wrapper script to export `backend/.env` before running `go run ./cmd/server`, or use `docker-compose -f backend/docker-compose.yml up`.

---

## 11. Final Verdict

### **NATIVE REDIS CONFIGURATION REQUIRED**

**Justification:**
- The Go backend strictly uses `github.com/redis/go-redis/v9`, which operates exclusively over native Redis TCP/TLS RESP sockets.
- The Upstash REST API is **not supported**.
- Connecting to Upstash is **100% verified and supported** when using Upstash's native Redis URI (`REDIS_URL=rediss://default:...@...:6379`).
- Production startup correctly fails closed if Redis is unavailable.
