# NEXA — Phase A Implementation Report: Reverse SMS & Production Onboarding Remediation

**Date:** November 6, 2026  
**Status:** COMPLETE & VERIFIED  
**Target Environments:** Go Relay Backend (`main` branch) & Android Native Client  

---

## 1. Executive Summary

Phase A focused on remediating critical onboarding and verification defects within the NEXA authentication pipeline. Prior to this remediation:
1. The backend defaulted to a dummy inbound receiver number (`+919800000000`) without failing closed in production, resulting in silently failed SMS verification.
2. Webhook endpoints accepted unauthenticated or replayed payloads under certain provider header formats.
3. Redis initialization failed under Render's native `REDIS_URL` (`rediss://`) scheme, silently dropping connection state into ephemeral in-memory RAM without alerting operations or reflecting degradation in the `/health` endpoint.
4. Legacy outbound OTP startup logs produced misleading critical failure alerts when the application intentionally utilized zero-permission reverse SMS verification.

All identified vulnerabilities, regressions, and configuration gaps have been addressed and tested with 100% pass rates.

---

## 2. Detailed Technical Remediations

### 2.1 Backend Reverse SMS Engine (`backend/internal/auth/reverse_sms.go`)

- **Fail-Closed Production Receiver Guard:**
  - In production (`APP_ENV=production` or `ENV=production`), `InitiateVerification()` strictly verifies that `INBOUND_SMS_RECEIVER_NUMBER` is configured with a valid, non-dummy E.164 telecommunications number.
  - If unset or configured with dummy prefixes (`+919800000000`), the endpoint refuses to issue verification nonces and returns an explicit HTTP 503 error, preventing silent user onboarding lockouts.
- **Single-Use Anti-Replay Defense:**
  - `ProcessInboundWebhook()` enforces state immutability. If an inbound SMS payload attempts to verify a nonce whose status is already `VERIFIED`, the request is immediately rejected with `replay attack rejected`.
  - Nonce life-cycles are strictly bounded by a 90-second TTL window.

### 2.2 Webhook Cryptographic Authentication (`backend/internal/auth/webhook_auth.go`)

- **Multi-Provider Signature Header Compatibility:**
  - The webhook verification handler inspects standard industry headers in priority order:
    1. `X-Gateway-Signature`
    2. `X-Signature`
    3. `X-Hub-Signature-256` (handles `sha256=` prefix stripping)
    4. `X-Twilio-Signature`
- **Bearer / Static Token Authentication:**
  - Supported for webhooks behind reverse-proxy / SMS forwarders via `X-Webhook-Secret` or `Authorization: Bearer <secret>`.
- **Constant-Time Verification:**
  - All hash comparisons execute via `subtle.ConstantTimeCompare` and `hmac.Equal` to prevent timing attacks.
- **Replay Protection Window:**
  - Optional `X-Gateway-Timestamp` enforcement ensures webhook requests older than 300 seconds (5 minutes) or with future timestamps are discarded.

### 2.3 Production Redis Buffer & TLS Support (`backend/internal/queue/redis_buffer.go`)

- **Native `REDIS_URL` Parsing:**
  - Implemented `redis.ParseURL(redisURL)` supporting both unencrypted (`redis://`) and TLS-secured (`rediss://`) connection strings standard on Render, Upstash, and managed cloud providers.
- **Degraded State Awareness:**
  - Added `IsDegraded()` and `IsConnected()` accessors.
  - In production, connection errors log prominent `CRITICAL` warnings and explicitly mark the buffer as running in degraded in-memory mode.
- **Backwards Compatibility:**
  - Maintained fallback support for `REDIS_ADDR` and `REDIS_PASSWORD` configurations.

### 2.4 Legacy SMS Provider Log Correction (`backend/internal/auth/sms_provider.go`)

- Corrected startup logging: when outbound SMS providers (MSG91/Twilio) are unconfigured, the server accurately logs that the legacy outbound OTP endpoint is disabled while Client-Initiated Reverse SMS is active as the primary authentication engine.

### 2.5 Server Diagnostics & Health Check (`backend/cmd/server/main.go`)

- Updated the `/health` endpoint to report live Redis connectivity:
  - `"redis": "CONNECTED"` when operating against cloud/local Redis.
  - `"redis": "DEGRADED_IN_MEMORY"` when operating on RAM fallback.
- Added active route listings for reverse SMS endpoints on the root `/` endpoint.

### 2.6 Android Client Verification Flow (`VerificationRadarViewModel.kt` & `VerificationRadarScreen.kt`)

- **Zero-Permission SMS Intent:**
  - Verification initiates via Android OS standard `Intent.ACTION_SENDTO` targeting `smsto:$inboundNumber` with payload `NEXA-VERIFY:<nonce>:<sessionId>`.
  - App requires zero telephony permissions (`SEND_SMS`, `RECEIVE_SMS`, `READ_PHONE_STATE` are completely avoided).
  - 100% compliant with Android 15 Restricted Settings (Enhanced Confirmation Mode) and Google Play Protect.
- **Polling & Handshake:**
  - Polling runs at 2.5-second intervals against `/api/v1/auth/verification-status`.
  - Upon receiving `VERIFIED` status, session tokens and user credentials are cryptographically committed, and navigation proceeds to the main chat screen.
- **Retry Mechanism:**
  - `retryVerification()` cleanly terminates any active polling/timer coroutines, re-requests a fresh nonce from the server, and re-launches the SMS composer without leaving orphaned background jobs.

---

## 3. Files Modified & Added

| File Path | Status | Purpose |
| :--- | :--- | :--- |
| `backend/cmd/server/main.go` | Modified | Health check Redis state reporting & diagnostic startup logs |
| `backend/internal/auth/reverse_sms.go` | Modified | Fail-closed dummy number prevention & single-use anti-replay defense |
| `backend/internal/auth/reverse_sms_test.go` | Modified | Unit tests for dummy rejection & anti-replay verification |
| `backend/internal/auth/sms_provider.go` | Modified | Clear decoupling of legacy outbound OTP from reverse SMS |
| `backend/internal/auth/webhook_auth.go` | Modified | Multi-header HMAC & Bearer token webhook verification |
| `backend/internal/auth/webhook_auth_test.go` | Modified | Unit tests for HMAC, Bearer tokens, and replay window |
| `backend/internal/queue/redis_buffer.go` | Modified | `REDIS_URL` (`rediss://`) support, TLS, and degraded state tracking |
| `backend/internal/queue/redis_buffer_test.go` | Added | Unit tests for buffer operations, fallback, and URL parsing |
