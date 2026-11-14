# NEXA — Render Production Environment: Missing, Conflicting & Unused Variables Analysis

**Target:** Render Production Environment & Configuration Cleanliness  
**Date:** 2026-11-10  
**Scope:** Source Code Audit vs `.env.example`, `.env`, and Render Cloud  
**Audit Standard:** Strict Source-Level Evidence  

---

## 1. Executive Summary

This report evaluates configuration anomalies, discrepancies between documentation templates and active Go code, conflicting environment variable names, development fallbacks, and currently missing production settings on Render.

---

## 2. Configuration Anomaly Categories

### 2.1 Duplicate Variables (Identified in Local `.env`)
Inspection of `H:\chat_app\.env` identified several redundant entries:
1. `UPSTASH_REDIS_REST_URL`: Defined twice in `.env`.
2. `UPSTASH_REDIS_REST_TOKEN`: Defined twice in `.env`.
3. `GOOGLE_CLIENT_ID`: Defined twice in `.env`.

**Remediation:** Remove duplicate lines from `.env` to prevent confusion during local development.

---

### 2.2 Unused Variables (Present in `.env.example` or `.env` but Absent from Go Code)
The following variables are documented in templates or present in configuration files, but **zero occurrences exist in the Go backend source code**:

| Variable Name | Location Found | Source Code Inspection Result | Recommendation |
|---|---|---|---|
| `FIREBASE_SERVICE_ACCOUNT_JSON` | `.env.example:171` | **0 occurrences in Go code.** `internal/fcm/fcm_service.go` does not load or parse service account keys; it dispatches HTTP requests using `s.accessToken` (which currently lacks OAuth2 token exchange logic). | Mark as planned/future in documentation. Do not mandate on Render until token exchange is authored. |
| `UPSTASH_REDIS_REST_URL` | `.env.example:95`<br>`.env` | **0 occurrences in Go code.** Backend connects to Redis strictly via TCP/TLS using `go-redis/v9` over `REDIS_URL`. It does not use the Upstash HTTP REST API. | Safe to omit from Render Web Service. |
| `UPSTASH_REDIS_REST_TOKEN` | `.env.example:101`<br>`.env` | **0 occurrences in Go code.** Same as above. | Safe to omit from Render Web Service. |
| `client_secret` | `.env` | **0 occurrences in Go code.** Lowercase variable unused by Google verifier (Google token verifier only checks RS256 signatures against public JWKS). | Remove from `.env`. Do not configure on Render. |
| `RELEASE_STORE_PASSWORD`<br>`RELEASE_KEY_ALIAS`<br>`RELEASE_KEY_PASSWORD` | `.env.example:259-272` | **0 occurrences in Go backend.** These are Android Gradle build properties used for signing release APKs/AABs. | Do not configure on Render Web Service. |

---

### 2.3 Variables with Conflicting Names or Redundant Aliases
The Go codebase supports several alias pairs where one variable overrides or falls back to another:

| Canonical Variable | Alias / Fallback Variable | Source Code Location | Behavior & Priority | Recommendation |
|---|---|---|---|---|
| `APP_ENV` | `ENV` | `cmd/server/main.go:48-51`<br>`internal/auth/reverse_sms.go:162-165` | `APP_ENV` is checked first. If empty, falls back to `ENV`. | Configure `APP_ENV=production` explicitly on Render. Omit `ENV`. |
| `REDIS_URL` | `REDIS_ADDR` + `REDIS_PASSWORD` | `cmd/server/main.go:54-58`<br>`internal/queue/redis_buffer.go:82-132` | If `REDIS_URL` is set, it takes complete precedence and parses via `redis.ParseURL`. `REDIS_ADDR` and `REDIS_PASSWORD` are ignored. | Configure single `REDIS_URL` with `rediss://` scheme on Render. Omit `REDIS_ADDR` and `REDIS_PASSWORD`. |
| `TWILIO_FROM_NUMBER` | `TWILIO_PHONE_NUMBER` | `internal/auth/sms_provider.go:244-247` | `TWILIO_FROM_NUMBER` is checked first. If empty, falls back to `TWILIO_PHONE_NUMBER`. | Standardize on `TWILIO_FROM_NUMBER` if Twilio outbound OTP is used. |

---

### 2.4 Variables Required Only in Production (Hard Startup Halts)
In development, several subsystems gracefully degrade to local in-memory fallbacks. In `production` (`APP_ENV=production`), these fallbacks are strictly rejected with fatal process termination:

1. **`DATABASE_URL`:**
   - *In Development:* Falls back to local file `data/identity_store.json`.
   - *In Production:* If `DATABASE_URL` is missing or fails to connect, server **terminates immediately** (`log.Fatalf`).
2. **`REDIS_URL` / `REDIS_ADDR`:**
   - *In Development:* Falls back to volatile in-memory queue and challenge store.
   - *In Production:* If Redis is unavailable or unconfigured, server **terminates immediately** (`log.Fatalf`).
3. **`INBOUND_SMS_RECEIVER_NUMBER`:**
   - *In Development:* Defaults to dummy placeholder `+919800000000`.
   - *In Production:* Rejects placeholders (`+919800000000`, `+910000000000`) and fails closed on non-trusted onboarding.

---

### 2.5 Insecure Development Fallbacks (Must Be Neutralized in Production)

| Variable | Insecure Development Behavior | Production Hardening Status |
|---|---|---|
| `DEBUG_SHOW_OTP` | When set to `"true"`, logs plaintext OTPs to stdout. | In `sms_provider.go:207`, only active if `APP_ENV != "production"`. Must remain unset or `false` on Render. |
| `INBOUND_SMS_RECEIVER_NUMBER` | Defaults to dummy `+919800000000`. | In `reverse_sms.go:306`, dummy number is rejected in production. Real +91 E.164 number required. |
| `IDENTITY_STORE_PATH` | Reads unencrypted local JSON file. | In `cmd/server/main.go:127`, running without PostgreSQL is rejected with `log.Fatalf` in production. |
| `MockSmsProvider` | Dispatches simulated OTPs without cellular SMS. | In `sms_provider.go:254`, mock provider is strictly forbidden in production; initializes `UnavailableSmsProvider` instead. |

---

### 2.6 Git Secrets Commitment Audit
- **Inspection of `.gitignore`:** Confirmed lines 39–41 ignore `.env`, `.env.*`, and exempt `.env.example`.
- **Git History Audit (`git log --all -p -- .env`):** Executed clean audit; confirmed `.env` has **never** been committed into Git repository history.
- **Source Code Hardcoded Secrets:** Audited all `backend/` files; no private keys, passwords, or live API keys are hardcoded in source.

---

## 3. Currently Missing Production Configuration on Render

Based on live incident reports and audit findings, the following configuration gaps currently exist on the Render Web Service:

| Missing Variable | Root Cause Impact | Production Fix |
|---|---|---|
| `INBOUND_SMS_RECEIVER_NUMBER` | Causes **Incident B** (`[ReverseSMS] CRITICAL: INBOUND_SMS_RECEIVER_NUMBER is not configured in production!`). Non-trusted verification fails closed with HTTP 500. | Enter real Indian domestic GSM SIM or virtual receiver number in Render Environment (`+91XXXXXXXXXX`). |
| `GATEWAY_WEBHOOK_SECRET` | Inbound carrier webhooks fail closed with `HTTP 401 Unauthorized` (`GATEWAY_WEBHOOK_SECRET is not configured on server`). | Generate 32+ byte hex key (`openssl rand -hex 32`) and enter in Render Environment and gateway provider console. |
| `APP_ENV` | If missing or defaulting to empty, server runs in development mode, weakening production security boundaries. | Set `APP_ENV=production` explicitly in Render Environment. |
| `NEXA_TRUSTED_PHONE_NUMBERS` | If unset, all developer and QA devices must execute real cellular Reverse SMS. | Enter developer test phone numbers (`+91XXXXXXXXXX,+91YYYYYYYYYY`) to allow internal testing bypass. |

---

## 4. Fail-Closed Behavior Verification Summary

| Subsystem | Missing Configuration | Observed Behavior | Security Status |
|---|---|---|---|
| **PostgreSQL** | Missing `DATABASE_URL` | Immediate fatal startup crash (`log.Fatalf`) | **SECURE (Fail-Closed)** |
| **Redis** | Missing `REDIS_URL` | Immediate fatal startup crash (`log.Fatalf`) | **SECURE (Fail-Closed)** |
| **Google Auth** | Missing `GOOGLE_CLIENT_ID` | Boots with warning; `/api/v1/auth/google` returns `401 Unauthorized` | **SECURE (Fail-Closed)** |
| **Reverse SMS** | Missing `INBOUND_SMS_RECEIVER_NUMBER` | Boots with warning; `/api/v1/auth/initiate-reverse-verify` returns `500` | **SECURE (Fail-Closed)** |
| **Webhook Auth** | Missing `GATEWAY_WEBHOOK_SECRET` | Inbound webhooks return `401 Unauthorized` | **SECURE (Fail-Closed)** |
| **Trusted Allowlist** | Malformed entry in `NEXA_TRUSTED_PHONE_NUMBERS` | Registry clears entire allowlist to empty | **SECURE (Fail-Closed)** |
| **Outbound SMS** | Missing MSG91 & Twilio | Replaces mock provider with `UnavailableSmsProvider`; rejects OTP | **SECURE (Fail-Closed)** |
