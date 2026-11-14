# NEXA — Environment Variable Inventory Audit

**Audit Scope:** Comprehensive Repository-Wide Inventory of All Environment Variables, System Properties, Build Properties, and Secrets  
**Repository:** `H:\chat_app`  
**Date:** November 10, 2026  
**Auditor Mode:** Strict Read-Only Source Code Inspection  

---

## 1. Inventory Overview

This audit verified every environment variable referenced in Go backend source code, Android Gradle build scripts, Docker configurations, and cloud deployment specifications. All identified variables have been organized, documented with safe placeholder templates, and captured in the newly created root-level template:  
`H:\chat_app\.env.example`.

---

## 2. Complete Environment Variables Mapping Matrix

| Variable Name | Consuming Service / Component | Source Code Reference | Required in Production | Sensitive | Current Configuration Status |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **`APP_ENV`** | Go Server / Auth / SMS / RateLimit | `cmd/server/main.go:48`<br>`internal/auth/trusted_phone.go:22` | **Yes** | No | Active in code; dictates production fail-closed security guards. |
| **`ENV`** | Fallback for `APP_ENV` | `cmd/server/main.go:50`<br>`internal/queue/redis_buffer.go:85` | No | No | Supported as legacy alias for `APP_ENV`. |
| **`PORT`** | Go HTTP & WebSocket Server | `cmd/server/main.go:43`<br>`cmd/handshake/main.go:12` | **Yes** (Render) | No | Active in code; defaults to `8080` if unset. Injected by Render. |
| **`DATABASE_URL`** | PostgreSQL Persistence Pool | `cmd/server/main.go:87`<br>`internal/database/db.go:15` | **Yes** | **Yes** | Active in code; required for accounts, devices, prekeys, connections. |
| **`IDENTITY_STORE_PATH`** | Local JSON Identity Store | `cmd/server/main.go:110, 129`<br>`internal/identity/store.go` | No (Dev only) | No | Active in code; development fallback. In production, PG is required. |
| **`REDIS_URL`** | Redis Ephemeral Buffer & Queue | `internal/queue/redis_buffer.go:82` | **Yes** | **Yes** | Active in code; parses TLS `rediss://` scheme for Upstash/Redis. |
| **`REDIS_ADDR`** | Legacy Redis host:port | `cmd/server/main.go:54`<br>`internal/queue/redis_buffer.go:88` | No | No | Active in code; legacy fallback if `REDIS_URL` is empty. |
| **`REDIS_PASSWORD`** | Legacy Redis password | `cmd/server/main.go:58`<br>`internal/queue/redis_buffer.go:88` | No | **Yes** | Active in code; legacy fallback if `REDIS_URL` is empty. |
| **`UPSTASH_REDIS_REST_URL`**| Upstash REST Interface | `H:\chat_app\.env:1` | No | No | Configured in `.env`; used for external cloud maintenance. |
| **`UPSTASH_REDIS_REST_TOKEN`**| Upstash REST Token | `H:\chat_app\.env:2` | No | **Yes** | Configured in `.env`; used for external cloud maintenance. |
| **`GOOGLE_CLIENT_ID`** | Google OAuth2 ID Token Verifier | `internal/auth/google_verifier.go:48` | **Yes** | No | Active in code; verified against Android `R.string.google_web_client_id`. |
| **`INBOUND_SMS_RECEIVER_NUMBER`**| Reverse SMS Gateway Target | `internal/auth/reverse_sms.go:161` | **Yes** | No | Active in code; defaults to dummy `+919800000000` if unset. |
| **`GATEWAY_WEBHOOK_SECRET`** | Inbound SMS Webhook HMAC Secret | `internal/auth/webhook_auth.go:33` | **Yes** | **Yes** | Active in code; verifies HMAC-SHA256 telecom gateway callbacks. |
| **`NEXA_TRUSTED_PHONE_NUMBERS`**| Trusted Phone Auto-Verify Allowlist| `internal/auth/trusted_phone.go:33` | No | **Yes** | Active in code; fail-closed allowlist for QA auto-verification. |
| **`FCM_PROJECT_ID`** | Background Push Wake-Up | `internal/fcm/fcm_service.go:29` | **Yes** | No | Active in code; if unset, runs in local mock logging mode. |
| **`FIREBASE_SERVICE_ACCOUNT_JSON`**| Google Cloud Service Account | Planned / Documented in `.env.example` | Future | **Yes** | **Planned / Future:** Required when FCM v1 OAuth token exchange is built. |
| **`OTP_SERVER_PEPPER`** | Legacy Outbound OTP Hashing | `internal/auth/otp_service.go:93` | Optional | **Yes** | Active in code; pepper for legacy SHA-256 OTP hash storage. |
| **`DEBUG_SHOW_OTP`** | Dev Plaintext OTP Console Log | `internal/auth/sms_provider.go:207` | No (Dev only) | **Yes** | Active in code; strictly ignored when `APP_ENV=production`. |
| **`MSG91_AUTH_KEY`** | MSG91 Outbound Indian SMS OTP | `internal/auth/sms_provider.go:234` | Optional | **Yes** | Active in code; Indian domestic outbound SMS fallback. |
| **`MSG91_TEMPLATE_ID`** | MSG91 DLT Approved Template ID | `internal/auth/sms_provider.go:235` | Optional | No | Active in code; required if `MSG91_AUTH_KEY` is provided. |
| **`TWILIO_ACCOUNT_SID`** | Twilio Global Outbound SMS OTP | `internal/auth/sms_provider.go:242` | Optional | **Yes** | Active in code; global outbound SMS fallback. |
| **`TWILIO_AUTH_TOKEN`** | Twilio Auth Token | `internal/auth/sms_provider.go:243` | Optional | **Yes** | Active in code; required if `TWILIO_ACCOUNT_SID` is provided. |
| **`TWILIO_FROM_NUMBER`** | Twilio Outbound Phone Number | `internal/auth/sms_provider.go:244` | Optional | No | Active in code; sender phone number for Twilio. |
| **`TWILIO_PHONE_NUMBER`** | Alias for `TWILIO_FROM_NUMBER` | `internal/auth/sms_provider.go:246` | Optional | No | Active in code; alternative alias. |
| **`SUPABASE_JWT_SECRET`** | Legacy Supabase JWT Verifier | `internal/auth/supabase_verifier.go:34`| Optional | **Yes** | Active in code; used for `/api/v1/auth/supabase/verify`. |
| **`SUPABASE_URL`** | Supabase Project Host URL | `internal/auth/supabase_verifier.go:35`| Optional | No | Active in code; used for `/api/v1/auth/supabase/verify`. |
| **`RELEASE_STORE_PASSWORD`**| Android Release Keystore Password | `app/build.gradle.kts:39` | **Yes** (Release) | **Yes** | Active in build script; reads from local.properties or env. |
| **`RELEASE_KEY_ALIAS`** | Android Release Key Alias | `app/build.gradle.kts:40` | **Yes** (Release) | No | Active in build script; defaults to `nexa_key`. |
| **`RELEASE_KEY_PASSWORD`** | Android Release Key Password | `app/build.gradle.kts:41` | **Yes** (Release) | **Yes** | Active in build script; reads from local.properties or env. |

---

## 3. Configuration Analysis & Consistency Verification

1. **Alignment with `.env.example`:**
   - Every single variable listed above is fully documented in `H:\chat_app\.env.example` with detailed comments, required/optional status, consuming component, and safe placeholders.
2. **Client vs. Backend Secret Separation:**
   - **Zero Secret Exposure on Client:** No database passwords, webhook secrets, Twilio tokens, or backend peppers are exposed in Android `BuildConfig`, `strings.xml`, or client source code.
   - The Android client only holds the public `GOOGLE_WEB_CLIENT_ID` (which is public by design in OAuth 2.0).
3. **Fail-Closed Production Guarantees:**
   - When `APP_ENV=production`:
     - Missing `DATABASE_URL` causes backend startup to halt immediately (`log.Fatalf`).
     - Degraded Redis buffer mode is rejected (`log.Fatalf`).
     - Mock SMS provider is strictly disallowed and throws an alert.
     - Malformed `NEXA_TRUSTED_PHONE_NUMBERS` clears the trusted registry to an empty set.
