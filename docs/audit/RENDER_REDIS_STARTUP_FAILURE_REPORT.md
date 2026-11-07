# NEXA — Render Redis Startup Failure Investigation & Remediation Report

**Date:** 2026-11-07  
**Auditor Mode:** STRICT READ-ONLY INVESTIGATION  
**Target Service:** NEXA Go Relay Server (`backend/cmd/server/main.go`)  
**Deployment Target:** Render Web Service (`https://nexa-relay.onrender.com`)  
**Observed Error:**
```text
[Queue] CRITICAL CONFIG ERROR: Neither REDIS_URL nor a remote REDIS_ADDR is configured in production! Operating in DEGRADED in-memory RAM mode. State WILL NOT persist across restarts.
[Server] CRITICAL: Ephemeral Redis Queue Buffer failed in production! Operating in degraded mode is rejected. Aborting startup.
```

---

## 1. Executive Summary & Root Cause

### Root Cause
The Render deployment builds successfully because `backend/Dockerfile` compiles the Go source code with exit code 0. However, when the container boots, the process fails during startup because:

1. **`REDIS_URL` is Unset or Missing in Render:** The environment variable `REDIS_URL` was not configured in the Render Dashboard (or Upstash REST API credentials were configured instead).
2. **`REDIS_ADDR` Defaulted to Localhost:** Because `REDIS_ADDR` was also not provided in Render, `main.go:56` fell back to its default value `"localhost:6379"`.
3. **Production Fail-Closed Triggered:** In a Render web service container, no local Redis daemon exists at `localhost:6379`. The backend detects `APP_ENV=production` and `redisAddr == "localhost:6379"`, logs the critical config error, marks the buffer as degraded, and immediately executes `log.Fatalf` to prevent running in production without persistent offline message buffering.

---

## 2. Source Code Trace & Line References

### Trace Step 1: Environment Variables Read at Process Startup
**File:** [`backend/cmd/server/main.go`](file:///H:/chat_app/backend/cmd/server/main.go#L48-L58)
```go
48: env := strings.ToLower(os.Getenv("APP_ENV"))
49: if env == "" {
50:     env = strings.ToLower(os.Getenv("ENV"))
51: }
52: isProduction := env == "production" // -> Evaluates to TRUE on Render
53: 
54: redisAddr := os.Getenv("REDIS_ADDR")
55: if redisAddr == "" {
56:     redisAddr = "localhost:6379"   // -> Defaults to localhost:6379 when REDIS_ADDR is unset
57: }
58: redisPassword := os.Getenv("REDIS_PASSWORD")
```

### Trace Step 2: Buffer Initialization Calls `NewRedisBuffer`
**File:** [`backend/cmd/server/main.go:67`](file:///H:/chat_app/backend/cmd/server/main.go#L67)
```go
67: buffer, err := queue.NewRedisBuffer(redisAddr, redisPassword)
```

### Trace Step 3: `NewRedisBuffer` Reads `REDIS_URL`
**File:** [`backend/internal/queue/redis_buffer.go:39-46`](file:///H:/chat_app/backend/internal/queue/redis_buffer.go#L39-L46)
```go
39: func NewRedisBuffer(redisAddr, password string) (*RedisBuffer, error) {
40:     redisURL := strings.TrimSpace(os.Getenv("REDIS_URL")) // -> Empty string if unset on Render
41:     env := strings.ToLower(os.Getenv("APP_ENV"))
42:     if env == "" {
43:         env = strings.ToLower(os.Getenv("ENV"))
44:     }
45:     isProduction := env == "production"
46:     return NewRedisBufferWithConfig(redisURL, redisAddr, password, isProduction)
47: }
```

### Trace Step 4: The Exact Error is Emitted
**File:** [`backend/internal/queue/redis_buffer.go:88-93`](file:///H:/chat_app/backend/internal/queue/redis_buffer.go#L88-L93)
```go
67:     if redisURL != "" {
68:         // Skipped because redisURL is empty
...
88:     } else if redisAddr == "localhost:6379" {
89:         if isProduction {
90:             log.Println("[Queue] CRITICAL CONFIG ERROR: Neither REDIS_URL nor a remote REDIS_ADDR is configured in production! Operating in DEGRADED in-memory RAM mode. State WILL NOT persist across restarts.")
91:             rb.isDegraded = true
92:             return rb, nil
93:         }
```

### Trace Step 5: Startup Aborted via `log.Fatalf`
**File:** [`backend/cmd/server/main.go:73-76`](file:///H:/chat_app/backend/cmd/server/main.go#L73-L76)
```go
73: if buffer.IsDegraded() {
74:     if isProduction {
75:         log.Fatalf("[Server] CRITICAL: Ephemeral Redis Queue Buffer failed in production! Operating in degraded mode is rejected. Aborting startup.")
76:     }
77:     log.Println("[Server] WARNING: Buffer is running in DEGRADED in-memory RAM fallback mode.")
78: }
```
`log.Fatalf` prints the critical abort message and calls `os.Exit(1)`, causing the Render container to crash and restart.

---

## 3. Upstash REST vs. Native Redis Compatibility

### 1. Upstash REST API (`UPSTASH_REDIS_REST_URL` & `UPSTASH_REDIS_REST_TOKEN`)
- **Status:** **COMPLETELY UNSUPPORTED.**
- **Code Audit:** A global search across `backend/` confirmed **zero occurrences** of `UPSTASH_REDIS_REST_URL` or `UPSTASH_REDIS_REST_TOKEN`.
- **Reason:** The backend does not use HTTP REST calls for Redis. It does not import `@upstash/redis` or any HTTP REST client.
- **Architectural Blocker:** The NEXA backend requires native Redis TCP capabilities that the Upstash REST API cannot provide:
  - **Blocking WebSocket Pub/Sub:** [`backend/internal/connection/pubsub.go:94`](file:///H:/chat_app/backend/internal/connection/pubsub.go#L94) runs `pubsub.ReceiveMessage(ctx)` in an infinite streaming loop for real-time multi-instance message synchronization.
  - **Lua Scripting:** Atomic sliding-window rate limiting ([`internal/ratelimit/limiter.go:30`](file:///H:/chat_app/backend/internal/ratelimit/limiter.go#L30)) and single-use challenge consumption ([`internal/auth/challenge_store.go:120`](file:///H:/chat_app/backend/internal/auth/challenge_store.go#L120)) require `redis.NewScript`.
  - **Pipelines:** Atomic queue push and TTL renewal ([`internal/queue/redis_buffer.go:135`](file:///H:/chat_app/backend/internal/queue/redis_buffer.go#L135)) use `client.Pipeline()`.

### 2. Upstash Native Redis Protocol (TLS)
- **Status:** **FULLY SUPPORTED & OPERATIONAL.**
- **Library:** `github.com/redis/go-redis/v9 v9.6.1`.
- **How It Works:**
  When `REDIS_URL` is set to Upstash's native Redis URI:
  ```env
  REDIS_URL=rediss://default:<UPSTASH_PASSWORD>@<UPSTASH_HOST>.upstash.io:6379
  ```
  `redis.ParseURL(redisURL)` in `redis_buffer.go:68`:
  1. Detects the `rediss://` scheme (double 's') and automatically configures `opts.TLSConfig = &tls.Config{...}`.
  2. Parses the username (`default`), password, host, and port (6379).
  3. `redis.NewClient(opts)` establishes a native TLS-encrypted TCP socket connection.
  4. `client.Ping(ctx)` succeeds, setting `rb.isDegraded = false`.
  5. The server starts with 100% functionality enabled.

---

## 4. Initialization Order & Dependency Verification

An audit of [`backend/cmd/server/main.go`](file:///H:/chat_app/backend/cmd/server/main.go) confirms that Redis initializes **before** all dependent subsystems start:

```
[1. os.Getenv("APP_ENV", "REDIS_URL")] (Lines 48-58)
                |
                v
[2. queue.NewRedisBuffer()] (Lines 67-71)
    - If fail in production: ABORT IMMEDIATELY (Line 75)
                |
                v (Success)
[3. database.NewPool() & Migrations] (Lines 90-104)
                |
                v
[4. auth.NewChallengeStore(buffer.GetClient())] (Line 149)
                |
                v
[5. hub.StartClusterSubscriber(buffer.GetClient())] (Line 164)
                |
                v
[6. prekeyHandler.SetRateLimiter(buffer.GetClient())] (Line 198)
                |
                v
[7. http.ListenAndServe()] (Line 432)
```

- **Guarantees:**
  - `buffer.GetClient()` is never passed as `nil` to auth challenge stores, cluster Pub/Sub, or prekey rate limiters in production.
  - If Redis fails, no WebSockets or HTTP listeners ever open.

---

## 5. Why Fail-Closed Behavior Must NOT Be Weakened

> [!IMPORTANT]
> The error message observed on Render is **intentional, correct, and vital for security**.

If the backend were allowed to start without Redis in production:
1. **Silent Loss of Offline Messages:** Encrypted messages sent to offline recipients would be stored in a volatile in-memory map (`rb.fallback`). The moment Render restarts, deploys, or sleeps the container, all buffered offline packets would be permanently wiped out.
2. **Horizontal Scaling Breakdown:** If multiple backend instances run on Render, a user connected to Instance A would not receive messages routed through Instance B because Redis Pub/Sub cluster events would be disabled.
3. **Replay Attack Exposure:** Authentication challenge nonces would fall back to in-memory maps, losing distributed atomic single-use extraction (`GETDEL` via Redis Lua).
4. **Rate Limiting Failure:** Prekey claim endpoints would lose distributed rate limiting, leaving OPKs vulnerable to automated scraping.

---

## 6. Exact Render Environment Configuration

To resolve the startup failure, configure the following in the Render Dashboard (**Dashboard → Your Web Service → Environment tab**):

### 1. Variables to ADD or UPDATE:

| Key | Exact Value Format | Explanation |
| :--- | :--- | :--- |
| `REDIS_URL` | `rediss://default:<UPSTASH_PASSWORD>@<UPSTASH_HOST>.upstash.io:6379` | **Mandatory.** The Upstash native Redis TLS connection string. Must use `rediss://` and port 6379. |
| `APP_ENV` | `production` | Enforces production fail-closed security. |
| `DATABASE_URL` | `postgres://<USER>:<PASSWORD>@<HOST>.render.com/<DB>` | PostgreSQL connection string. |
| `GOOGLE_CLIENT_ID` | `356056510017-grmokt7u1sch6hdk9dbah85k2jqe32v2.apps.googleusercontent.com` | Google OAuth Web Client ID. |
| `OTP_SERVER_PEPPER` | `64-character-hex-string` | Pepper secret for HMAC OTP hashing. |
| `INBOUND_SMS_RECEIVER_NUMBER` | `+91XXXXXXXXXX` | Inbound telecom SIM number. |
| `GATEWAY_WEBHOOK_SECRET` | `your-webhook-secret` | Shared secret for telecom webhook verification. |

### 2. Variables to REMOVE (If Present):
- **REMOVE** `UPSTASH_REDIS_REST_URL` (Not used by Go backend).
- **REMOVE** `UPSTASH_REDIS_REST_TOKEN` (Not used by Go backend).
- **REMOVE** `REDIS_ADDR` (Do not set if using `REDIS_URL`).
- **REMOVE** `DEBUG_SHOW_OTP` (Must never be present in production).

### 3. How to Obtain the Correct Value from Upstash:
1. Log in to the [Upstash Console](https://console.upstash.io/).
2. Select your Redis Database.
3. In the **Details** / **Connect** tab, look for **"ioredis"** or **"redis-cli"**.
4. Copy the connection string. It looks like:
   ```text
   rediss://default:your_secret_password@your-database-name.upstash.io:6379
   ```
5. Paste this directly into Render as `REDIS_URL`.
6. Click **Save Changes** (Render will automatically re-deploy).

---

## 7. Exact Local `.env` Configuration & Startup

### Confirmation on Local `.env` Loading
- The Go backend **DOES NOT** automatically read or load `.env` files from disk.
- If you create a `.env` file locally, you must export its contents into your shell before running `go run ./cmd/server`.

### Sample `backend/.env` (For Local Development Only):
```env
APP_ENV=development
PORT=8080
REDIS_URL=rediss://default:<UPSTASH_PASSWORD>@<UPSTASH_HOST>.upstash.io:6379
DATABASE_URL=postgres://<USER>:<PASSWORD>@localhost:5432/nexa
GOOGLE_CLIENT_ID=356056510017-grmokt7u1sch6hdk9dbah85k2jqe32v2.apps.googleusercontent.com
OTP_SERVER_PEPPER=a8b3c9d2e1f40582736451a9b8c7d6e5f4a3b2c1d0e9f8a7b6c5d4e3f2a1b0c9
INBOUND_SMS_RECEIVER_NUMBER=+919800000000
GATEWAY_WEBHOOK_SECRET=local_dev_test_webhook_secret_min_32_chars
DEBUG_SHOW_OTP=true
```

### Safe PowerShell Local Launch Command:
```powershell
Set-Location H:\chat_app\backend

# Export variables from .env into current PowerShell process without echoing secrets
if (Test-Path .env) {
    Get-Content .env | Where-Object { $_ -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$' } | ForEach-Object {
        Set-Item -Path "env:$($matches[1])" -Value $matches[2].Trim("`"'")
    }
}

# Run the server
go run ./cmd/server
```

---

## 8. Safe Pre-Flight Verification Commands (PowerShell)

Before re-deploying to Render, run these safe diagnostic tests locally without printing secret values:

### 1. Test TCP/TLS Connectivity to Upstash
```powershell
# Verify that your network can open a connection to Upstash on port 6379:
Test-NetConnection -ComputerName "<your-db-endpoint>.upstash.io" -Port 6379
```
*Expected Result: `TcpTestSucceeded : True`*

### 2. Verify Go Backend Unit Tests Pass Cleanly
```powershell
Set-Location H:\chat_app\backend
go test -v -count=1 ./internal/queue/...
```
*Expected Result: `PASS`*

---

## 9. Next Steps (Awaiting User Approval)

1. **User Action in Render Dashboard:**
   - In Render Web Service Environment settings, add `REDIS_URL` containing the native Upstash TLS connection string (`rediss://default:...@...:6379`).
   - Remove any `UPSTASH_REDIS_REST_*` entries.
   - Trigger a manual deployment.
2. **Expected Render Startup Log upon Fix:**
   ```text
   [Queue] Connected to ephemeral Redis at <host>.upstash.io:6379 (TLS: true, TTL: 24h0m0s)
   [Server] Ephemeral Redis Queue Buffer initialized successfully.
   [Server] Listening on ws://0.0.0.0:10000/ws...
   ```
3. **No Code Modifications Required:**
   The Go source code already fully supports `REDIS_URL` with auto-TLS and fails closed correctly. No code changes or test updates are necessary for this fix.
