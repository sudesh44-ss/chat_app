# NEXA — Render Production Environment Variables Audit

**Target:** Render Web Service Deployment (`nexa-relay`)  
**Audit Date:** 2026-11-10  
**Scope:** Entire Go Backend (`backend/`), `.env.example`, `.env`, and Deployment Architecture  
**Audit Standard:** Strict Source-Code Evidence Only (No Assumed Variables)  
**Security Standard:** Strict Secret Masking & Zero Plaintext Credentials  

---

## 1. Executive Summary

This audit independently inspects every environment variable utilized by the NEXA Go backend. Every finding is verified against actual calls to `os.Getenv()` and `os.LookupEnv()` in the Go source code.

### Inventory Statistics:
- **Total Unique Environment Variables Evaluated:** 22 active in Go code + 5 documented-only/Android variables.
- **Mandatory for Production Boot (P0):** 4 variables (`APP_ENV`, `PORT`, `DATABASE_URL`, `REDIS_URL` or `REDIS_ADDR`).
- **Mandatory for Core Public Onboarding (P0):** 3 variables (`GOOGLE_CLIENT_ID`, `INBOUND_SMS_RECEIVER_NUMBER`, `GATEWAY_WEBHOOK_SECRET`).
- **Feature-Dependent / Optional (P1/P2):** 9 variables (`NEXA_TRUSTED_PHONE_NUMBERS`, `FCM_PROJECT_ID`, `MSG91_AUTH_KEY`, `MSG91_TEMPLATE_ID`, `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN`, `TWILIO_FROM_NUMBER`, `SUPABASE_JWT_SECRET`, `SUPABASE_URL`).
- **Development-Only / Deprecated:** 6 variables (`IDENTITY_STORE_PATH`, `DEBUG_SHOW_OTP`, `OTP_SERVER_PEPPER`, `ENV`, `TWILIO_PHONE_NUMBER`, `REDIS_PASSWORD`).
- **Documented in Templates but Unused in Go Code:** 4 variables (`FIREBASE_SERVICE_ACCOUNT_JSON`, `UPSTASH_REDIS_REST_URL`, `UPSTASH_REDIS_REST_TOKEN`, `client_secret`).

---

## 2. Complete Environment Variables Master Table

| Variable Name | Required | Purpose | Used by (Source File) | Production Value Format | If Missing / Invalid Impact | Where to Obtain |
|---|---|---|---|---|---|---|
| `APP_ENV` | **MANDATORY** | Controls runtime security mode (`production`, `development`, `test`). In `production`, disables mock SMS, rejects in-memory queue fallback, and enforces fail-closed checks. | `cmd/server/main.go:48`<br>`internal/auth/handler.go:453`<br>`internal/auth/reverse_sms.go:162`<br>`internal/auth/sms_provider.go:228`<br>`internal/queue/redis_buffer.go:83` | `production` | Falls back to `ENV`. If both unset, defaults to dev mode (SECURITY RISK: degrades fail-closed guards). | Render Dashboard Environment Settings |
| `PORT` | **MANDATORY** | TCP port for HTTP and WebSocket listeners. | `cmd/server/main.go:43`<br>`cmd/signaling/main.go:26`<br>`cmd/relay/main.go:26` | Numeric port string (e.g. `10000`) | Defaults to `8080`. On Render, container health check fails if server does not bind to injected `$PORT`. | Injected automatically by Render at container launch |
| `DATABASE_URL` | **MANDATORY** | PostgreSQL connection string for connection pool (`pgxpool`) and migrations (001–006). | `cmd/server/main.go:87`<br>`internal/database/db.go:45` | `postgres://USER:PASSWORD@HOST:PORT/DB?sslmode=require` | **P0 BLOCKER:** In `production`, server **halts immediately** on startup (`log.Fatalf`). | Supabase Dashboard &rarr; Project Settings &rarr; Database &rarr; Connection String |
| `REDIS_URL` | **MANDATORY** | TLS-enabled Redis connection URL for 24h offline queue buffers, nonces, and cluster pub/sub. | `internal/queue/redis_buffer.go:82` | `rediss://default:PASSWORD@HOST:PORT` | **P0 BLOCKER:** If neither `REDIS_URL` nor remote `REDIS_ADDR` is configured, server **halts immediately** on startup (`log.Fatalf`). | Upstash Console or Render Redis Service |
| `GOOGLE_CLIENT_ID` | **MANDATORY** (for Google Auth) | Google OAuth 2.0 Web Client ID. Validates RS256 token signatures against Google JWKS and verifies `aud` claim. | `internal/auth/google_verifier.go:48` | `[NUM]-[HASH].apps.googleusercontent.com` | **P0 BLOCKER:** Server logs warning; all requests to `POST /api/v1/auth/google` fail closed with `401 Unauthorized`. | Google Cloud Console &rarr; APIs & Services &rarr; Credentials |
| `INBOUND_SMS_RECEIVER_NUMBER` | **MANDATORY** (for Reverse SMS) | Dedicated domestic Indian telecom phone number that receives incoming verification SMS (`NX-VERIFY <nonce>`). | `internal/auth/reverse_sms.go:161` | E.164 phone string starting with `+91` (e.g. `+91XXXXXXXXXX`) | **P0 BLOCKER:** In production, non-trusted onboarding calls to `/api/v1/auth/initiate-reverse-verify` fail closed with `HTTP 500`. | Telecom SMS gateway / Dedicated GSM SIM modem |
| `GATEWAY_WEBHOOK_SECRET` | **MANDATORY** (for Reverse SMS) | Shared secret key for HMAC-SHA256 signature verification on incoming carrier webhooks. | `internal/auth/webhook_auth.go:33` | 32+ byte high-entropy secret string or 64-char Hex | **P0 BLOCKER:** All inbound carrier webhooks fail closed with `401 Unauthorized` (`GATEWAY_WEBHOOK_SECRET is not configured`). | Cryptographically generated via `openssl rand -hex 32` and shared with gateway provider |
| `NEXA_TRUSTED_PHONE_NUMBERS` | **OPTIONAL** / QA | Comma-separated list of pre-authorized phone numbers that bypass cellular Reverse SMS for testing. | `internal/auth/trusted_phone.go:33, 123` | Comma-separated E.164 numbers (e.g. `+91XXXXXXXXXX,+91YYYYYYYYYY`) | Defaults to empty allowlist. In production, any malformed number clears the allowlist completely. | Internal test devices / Developer mobile numbers |
| `FCM_PROJECT_ID` | **FEATURE-DEPENDENT** | Google Cloud Firebase Project ID for silent background wake-up pings. | `internal/fcm/fcm_service.go:29` | Firebase project identifier (e.g. `nexa-chat-prod`) | Operates in mock log-only mode. Inactive background devices will not receive wake-up pings when offline. | Firebase Console &rarr; Project Settings &rarr; General &rarr; Project ID |
| `MSG91_AUTH_KEY` | **FEATURE-DEPENDENT** | Authentication key for MSG91 outbound SMS API (India DLT OTP). | `internal/auth/sms_provider.go:234` | Alphanumeric API key string | If Twilio is also unset, outbound OTP endpoint is disabled with `UnavailableSmsProvider`. (Reverse SMS active as primary). | MSG91 Dashboard &rarr; Authkey |
| `MSG91_TEMPLATE_ID` | **FEATURE-DEPENDENT** | DLT approved template ID for OTP message dispatch. | `internal/auth/sms_provider.go:235` | Template ID string | Required alongside `MSG91_AUTH_KEY`. If missing, MSG91 is not initialized. | MSG91 Dashboard / TRAI DLT Portal |
| `TWILIO_ACCOUNT_SID` | **FEATURE-DEPENDENT** | Twilio Account SID for international outbound SMS OTP fallback. | `internal/auth/sms_provider.go:242` | `ACxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx` | Outbound Twilio fallback is disabled. | Twilio Console Dashboard |
| `TWILIO_AUTH_TOKEN` | **FEATURE-DEPENDENT** | Twilio API Auth Token. | `internal/auth/sms_provider.go:243` | 32-character secret string | Required alongside `TWILIO_ACCOUNT_SID`. | Twilio Console Dashboard |
| `TWILIO_FROM_NUMBER` | **FEATURE-DEPENDENT** | Twilio verified outbound sender phone number. | `internal/auth/sms_provider.go:244` | E.164 phone string (e.g. `+12025550199`) | Required alongside `TWILIO_ACCOUNT_SID`. | Twilio Console &rarr; Phone Numbers |
| `TWILIO_PHONE_NUMBER` | **CONDITIONAL** | Alias fallback for `TWILIO_FROM_NUMBER`. | `internal/auth/sms_provider.go:246` | E.164 phone string | Used only if `TWILIO_FROM_NUMBER` is unset. | Twilio Console &rarr; Phone Numbers |
| `SUPABASE_JWT_SECRET` | **OPTIONAL** | Secret key for verifying legacy Supabase Auth JWTs on `/api/v1/auth/supabase/verify`. | `internal/auth/supabase_verifier.go:34` | High-entropy secret string | Supabase token verifier skips HMAC signature verification. | Supabase Dashboard &rarr; Project Settings &rarr; API &rarr; JWT Secret |
| `SUPABASE_URL` | **OPTIONAL** | Supabase project API endpoint URL. | `internal/auth/supabase_verifier.go:35` | `https://[PROJECT_ID].supabase.co` | Claims validation does not verify issuer project domain. | Supabase Dashboard &rarr; Project Settings &rarr; API &rarr; Project URL |
| `OTP_SERVER_PEPPER` | **OPTIONAL** | Cryptographic salt pepper for legacy OTP code hashing in `OtpService`. | `internal/auth/otp_service.go:93` | 64-character hex salt string | OTP service hashes codes without additional pepper. | Generated via `openssl rand -hex 32` |
| `DEBUG_SHOW_OTP` | **DEVELOPMENT ONLY** | Logs OTPs in plaintext to server console in dev mode. | `internal/auth/sms_provider.go:207` | `true` or `false` | Strictly ignored in `production`. Must be omitted or `false`. | Development only |
| `IDENTITY_STORE_PATH` | **DEVELOPMENT ONLY** | File path to legacy JSON identity store (`data/identity_store.json`). | `cmd/server/main.go:110, 129` | File path | In production, running on JSON store without PostgreSQL **halts server startup**. | Obsolete in production |
| `REDIS_ADDR` | **CONDITIONAL** | Legacy host:port address for Redis. | `cmd/server/main.go:54`<br>`internal/queue/redis_buffer.go:100` | `HOST:PORT` (e.g. `localhost:6379`) | Defaults to `localhost:6379`. In production, `localhost:6379` is rejected unless `REDIS_URL` is set. | Redis hosting provider |
| `REDIS_PASSWORD` | **CONDITIONAL** | Password for Redis when using `REDIS_ADDR`. | `cmd/server/main.go:58`<br>`internal/queue/redis_buffer.go:100` | Secret string | No password auth passed to `REDIS_ADDR`. Unused if `REDIS_URL` is set. | Redis hosting provider |

---

## 3. Categorized Analysis

### 3.1 Application Environment & Server
- `APP_ENV`: Mandatory. Must be set to `production`.
- `PORT`: Mandatory. Automatically injected by Render (typically `10000`). Server listens on `0.0.0.0:$PORT`.

### 3.2 PostgreSQL Database (Supabase)
- `DATABASE_URL`: Mandatory. The server connects via `pgxpool` with production connection pool tuning (MaxConns: 25, MinConns: 5, Lifetime: 1h). Applies migrations 001–006. If missing or unreachable in production, server fails closed and aborts boot.

### 3.3 Redis (Queue, Cache, Nonces, Rate Limiting)
- `REDIS_URL`: Mandatory. Supports `rediss://` for TLS. Used for:
  - 24-hour offline message buffer queues.
  - Ephemeral authentication challenge nonces.
  - Multi-instance WebSocket Pub/Sub clustering.
  - Reverse SMS nonce state machine (`reverse_sms:nonce:*`).
  - Distributed rate limiting (IP-based and user-based).
- `REDIS_ADDR` & `REDIS_PASSWORD`: Alternative legacy variables.

### 3.4 Authentication & Identity
- `GOOGLE_CLIENT_ID`: Mandatory for Google OAuth. Validated against Android `google_web_client_id`.
- `SUPABASE_JWT_SECRET` & `SUPABASE_URL`: Optional legacy verifier for Supabase session tokens.
- `OTP_SERVER_PEPPER`: Optional salt pepper for legacy OTP codes.

### 3.5 Client-Initiated Reverse SMS & Webhook Security
- `INBOUND_SMS_RECEIVER_NUMBER`: Mandatory. In production, placeholder numbers (`+919800000000`, `+910000000000`) are rejected. Real domestic Indian number (+91) required.
- `GATEWAY_WEBHOOK_SECRET`: Mandatory. Used by `VerifyGatewaySignature` to authenticate carrier webhooks via HMAC-SHA256 and constant-time comparisons.
- `NEXA_TRUSTED_PHONE_NUMBERS`: Optional. Comma-separated E.164 phone numbers that bypass cellular SMS verification for internal developer testing.

### 3.6 Push Notifications (FCM)
- `FCM_PROJECT_ID`: Feature-dependent. Enables background silent wake-up signals (`{ "data": { "type": "WAKE_UP" } }`). Operates in mock log-only mode if omitted.

### 3.7 Legacy Outbound SMS Providers (Fallback)
- `MSG91_AUTH_KEY` & `MSG91_TEMPLATE_ID`: India DLT outbound SMS.
- `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN`, `TWILIO_FROM_NUMBER`: Global outbound SMS.
- Note: If both are unset in production, outbound OTP is safely disabled (`UnavailableSmsProvider`). Client-Initiated Reverse SMS remains active as primary.

### 3.8 Integrations Inspected & Confirmed Absent from Go Backend
- **Google Drive:** Handled 100% on Android client via Android Google SignIn & REST API. Zero backend variables.
- **WebRTC / TURN / STUN:** Pure WebSocket SDP signaling (`/ws/signaling`). No server-side TURN credentials.
- **Cloudflare R2 / S3:** `/api/v1/media/upload-ticket` outputs a static URL stub (`https://r2.nexa.network/...`). No R2/S3 secret variables exist in Go backend.
- **CORS:** WebSocket upgrader sets `CheckOrigin = func(r) bool { return true }`. No CORS origin variables exist.
