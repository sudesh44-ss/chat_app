# RENDER PRODUCTION CONFIGURATION AUDIT: NEXA RELAY BACKEND

**Target System:** NEXA Blind-Postman Relay Server & Auth Engine  
**Live Endpoint:** `https://nexa-relay.onrender.com` / `wss://nexa-relay.onrender.com`  
**Deployment Infrastructure:** Render Cloud Web Service (Linux Alpine Container)  
**Audit Mode:** STRICT READ-ONLY INSPECTION  
**Date:** November 2026  

---

## 1. Executive Summary

A comprehensive, read-only architectural inspection of the NEXA Go backend deployed on Render (`https://nexa-relay.onrender.com`) was conducted. The audit specifically investigated three critical startup warning logs and the complete backend configuration lifecycle:

1. **Redis Connection Failure:**
   `[Queue] Redis connection failed at localhost:6379 (dial tcp [::1]:6379: connect: connection refused). Falling back to in-memory RAM queue.`
2. **Missing Production SMS Provider:**
   `[SmsProvider] CRITICAL: Running in production with NO SMS provider configured! Mock fallback is DISABLED.`
3. **FCM Test Mode / Incomplete Service:**
   `[FCM] Initialized in local/test mode (FCM_PROJECT_ID not set; logging wake-ups without external HTTP calls).`

The audit revealed that **the backend incorrectly signals health (`/health` returns `HTTP 200 UP`) while critical state persistence, cryptographic verification webhooks, and push notification services are either completely absent, falling back to volatile process RAM, or misconfigured.**

---

## 2. Startup Warnings: Detailed Root Cause Analysis

### A. Redis Connection Failure (`localhost:6379`)

#### Exact Log Observed
```
[Queue] Redis connection failed at localhost:6379 (dial tcp [::1]:6379: connect: connection refused). Falling back to in-memory RAM queue.
```

#### Source Location
- **Configuration Loading:** `backend/cmd/server/main.go:41-45`
- **Buffer Initialization:** `backend/internal/queue/redis_buffer.go:33-61`

#### Root Cause Analysis
1. In `backend/cmd/server/main.go:41-45`:
   ```go
   redisAddr := os.Getenv("REDIS_ADDR")
   if redisAddr == "" {
       redisAddr = "localhost:6379"
   }
   redisPassword := os.Getenv("REDIS_PASSWORD")
   ```
2. On Render (as well as managed Redis providers like Upstash, Redis Cloud, and Heroku), Redis is provisioned as a managed instance with a connection URI supplied in the standard environment variable `REDIS_URL` (e.g., `rediss://default:secret@red-xxxx.render.com:6379`).
3. The Go backend **never inspects `REDIS_URL`**.
4. Furthermore, `queue.NewRedisBuffer(redisAddr, redisPassword)` constructs `&redis.Options{Addr: redisAddr, Password: password, DB: 0}`. It has no URL parsing logic (`redis.ParseURL`), cannot parse TLS `rediss://` schemes, and fails to configure TLS certificates.
5. In the isolated Render container, there is no local Redis daemon running at `localhost:6379`. The connection is immediately refused.

#### Current Fallback Behavior
- When `client.Ping(ctx)` fails, `NewRedisBuffer` logs the warning and returns a `RedisBuffer` struct with `client = nil`.
- The buffer falls back to `fallback: make(map[string][][]byte)` (an in-memory Go map protected by a mutex).
- Similarly, `authHandler.SetChallengeStore(auth.NewChallengeStore(buffer.GetClient()))` receives `nil`, causing `NewChallengeStore` to fall back to `NewMemoryChallengeStore()`.

#### Production Impact
- **Data Loss on Restart:** Whenever Render spins down the service (15-min inactivity on Free tier) or deploys a new container, **all queued offline messages and all active authentication challenges are permanently deleted**.
- **Multi-Instance Incompatibility:** If scaled horizontally across multiple Render instances, messages buffered on Instance A cannot be popped by a client connected to Instance B.
- **Race Conditions:** Memory maps lack Redis atomic `GETDEL` / Lua script capabilities, allowing potential replay races under concurrent load.

---

### B. Missing Production SMS Provider

#### Exact Log Observed
```
[SmsProvider] CRITICAL: Running in production with NO SMS provider configured! Mock fallback is DISABLED.
```

#### Source Location
- **Provider Resolver:** `backend/internal/auth/sms_provider.go:226-263`
- **Invocation:** `backend/cmd/server/main.go:69-70`

#### Root Cause Analysis
1. In `backend/internal/auth/sms_provider.go:227-263`:
   ```go
   func GetDefaultSmsProvider() SmsProvider {
       env := strings.ToLower(os.Getenv("APP_ENV"))
       if env == "" {
           env = strings.ToLower(os.Getenv("ENV"))
       }
       msg91Key := strings.TrimSpace(os.Getenv("MSG91_AUTH_KEY"))
       msg91Template := strings.TrimSpace(os.Getenv("MSG91_TEMPLATE_ID"))
       if msg91Key != "" && msg91Template != "" { ... return NewMsg91Provider(...) }
       
       twilioSid := strings.TrimSpace(os.Getenv("TWILIO_ACCOUNT_SID"))
       ...
       if env == "production" {
           return &UnavailableSmsProvider{ reason: "missing MSG91 or TWILIO credentials..." }
       }
       return NewMockSmsProvider()
   }
   ```
2. When `APP_ENV=production` is set in Render without MSG91 or Twilio credentials, `GetDefaultSmsProvider()` intentionally refuses to use `MockSmsProvider` to prevent dev secrets leaking in production logs.
3. It returns `&UnavailableSmsProvider{}`.
4. Any call to `POST /api/v1/auth/otp/request` fails with `503 Service Unavailable`: `cannot dispatch OTP (missing MSG91 or TWILIO credentials)`.

#### Architectural Disconnect (Legacy Outbound OTP vs Client-Initiated Reverse SMS)
- NEXA has transitioned to **Client-Initiated Reverse SMS** (`ReverseSmsService`), where the Android device dispatches an SMS beacon to an inbound gateway.
- However, `main.go` still instantiates `otpService := auth.NewOtpService(smsProvider)` on startup, triggering this log.
- **More Critically for Reverse SMS:** In `reverse_sms.go:142`, `INBOUND_SMS_RECEIVER_NUMBER` is read from the environment. If unset, it defaults to **`+919800000000`** (a non-functional dummy placeholder).
- The inbound webhook endpoint (`/api/v1/webhooks/sms-inbound` / `/api/v1/auth/inbound-sms`) requires `GATEWAY_WEBHOOK_SECRET` (`webhook_auth.go:32`). When unset, it returns `401 Unauthorized`.
- Because neither `INBOUND_SMS_RECEIVER_NUMBER` nor `GATEWAY_WEBHOOK_SECRET` is configured in Render, **both legacy OTP and Reverse SMS are non-functional in production.**

---

### C. FCM Test Mode / Incomplete Service

#### Exact Log Observed
```
[FCM] Initialized in local/test mode (FCM_PROJECT_ID not set; logging wake-ups without external HTTP calls)
```

#### Source Location
- **Service Initialization:** `backend/internal/fcm/fcm_service.go:28-43`
- **Token Dispatch:** `backend/internal/fcm/fcm_service.go:46-98`

#### Root Cause Analysis
1. In `backend/internal/fcm/fcm_service.go:29-36`:
   ```go
   projectId := os.Getenv("FCM_PROJECT_ID")
   isMock := projectId == ""
   ```
2. On Render, `FCM_PROJECT_ID` is not configured. The service runs with `isMock = true`, merely logging fake wake-ups to stdout without contacting Google FCM servers.
3. **Critical Implementation Flaw Discovered:** In `fcm_service.go:82-84`:
   ```go
   if token := s.getAccessToken(); token != "" {
       req.Header.Set("Authorization", "Bearer "+token)
   }
   ```
   In the entire codebase, **`s.accessToken` is NEVER set**. There is no Google Cloud Service Account credential loader (`google.FindDefaultCredentials`, OAuth2 token source, or JWT exchange).
4. **Impact:** Even if a user provides `FCM_PROJECT_ID` in Render, `SendWakeUp` will issue unauthenticated HTTP requests to `https://fcm.googleapis.com/v1/projects/{projectId}/messages:send` and receive `401 Unauthorized` from Google!

---

## 3. PostgreSQL Database Audit

### The Reality: Zero Database Connectivity
- The user requirements questioned whether the running Go backend actually connects to PostgreSQL.
- **Evidence:** An exhaustive search of the `backend` codebase confirms that **there is ZERO PostgreSQL or SQL database connection code**.
- No `database/sql`, `github.com/jackc/pgx`, `gorm.io`, or `github.com/lib/pq` is imported or declared in `backend/go.mod`.
- While SQL migration scripts exist in `database/migrations/` (16 `.sql` files), they are orphaned artifacts from a prior Supabase iteration and are **never executed by the Go backend**.

### Current Persistence Mechanism: `data/identity_store.json`
- In `backend/internal/identity/store.go`, user profiles, devices, and sessions are serialized to a local JSON file (`data/identity_store.json`).
- Render Web Services deploy inside ephemeral Docker containers without persistent disk storage (unless a paid persistent disk is mounted at a custom path).
- **Catastrophic Failure Mode:** Whenever the Render container restarts (e.g., after a code push, crash, or 15-minute free sleep cycle), `data/identity_store.json` is wiped clean. **All registered users, device public keys, and active authentication sessions are lost.**

---

## 4. Production Readiness Diagnostic Matrix

| Component | Target Requirement | Current State | Root Cause | Production Risk |
| :--- | :--- | :--- | :--- | :--- |
| **Persistence (Accounts/Sessions)** | PostgreSQL / Persistent Store | Local JSON file (`data/identity_store.json`) | No DB client in Go backend | **CRITICAL:** Total data wipe on Render restart |
| **Queue / Offline Messages** | Ephemeral Redis (24h TTL) | In-Memory RAM Map | `REDIS_URL` not parsed; dials `localhost:6379` | **HIGH:** Offline packets wiped on container restart |
| **Auth Challenges** | Redis atomic single-use | In-Memory RAM Map | `REDIS_URL` not parsed | **HIGH:** Multi-instance replay vulnerabilty |
| **Reverse SMS Receiver** | Telecom Inbound Virtual Number | Dummy `+919800000000` | `INBOUND_SMS_RECEIVER_NUMBER` missing | **BLOCKER:** Carrier beacon sent to invalid number |
| **Reverse SMS Webhook** | Authenticated HMAC Webhook | Rejecting all calls (401) | `GATEWAY_WEBHOOK_SECRET` missing | **BLOCKER:** Webhook verification fails closed |
| **Legacy Outbound OTP** | MSG91 / Twilio Gateway | `UnavailableSmsProvider` | Missing API credentials | **BLOCKER:** Outbound OTP fails if fallback requested |
| **Google Sign-In Auth** | Audience & RS256 JWKS validation | Fail-closed (401) if unset | `GOOGLE_CLIENT_ID` missing | **BLOCKER:** Google login rejected |
| **FCM Background Wake-Up** | Firebase HTTP v1 API | Mock logger; missing OAuth2 | `FCM_PROJECT_ID` missing + no token exchange | **MEDIUM:** Devices in sleep do not receive wake-up |
| **Health Check (`/health`)** | Deep dependency health probe | Static hardcoded `UP` | Health handler doesn't probe Redis/DB | **FALSE SENSE OF HEALTH:** Shows UP while broken |

---

## 5. Summary of Required Render Environment Variables

To bring the deployed backend into an operational state, the following environment variables must be populated in Render Dashboard:

```env
# Application Environment
APP_ENV=production
PORT=10000

# Google Authentication
GOOGLE_CLIENT_ID=356056510017-grmokt7u1sch6hdk9dbah85k2jqe32v2.apps.googleusercontent.com

# Redis Connection (Required for Ephemeral Queue & Challenges)
REDIS_URL=rediss://default:<password>@<host>.render.com:6379

# Client-Initiated Reverse SMS Gateway
INBOUND_SMS_RECEIVER_NUMBER=+91XXXXXXXXXX
GATEWAY_WEBHOOK_SECRET=<high-entropy-64-character-hex-secret>

# Persistent Identity Store Path (Mount Render Disk at /var/data)
IDENTITY_STORE_PATH=/var/data/identity_store.json
```
