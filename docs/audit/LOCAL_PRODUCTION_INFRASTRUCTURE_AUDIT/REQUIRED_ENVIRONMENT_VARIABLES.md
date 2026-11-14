# NEXA — Required Production Environment Variables Specification

**Target:** Render Cloud Deployment (`https://nexa-relay.onrender.com`)  
**Audit Status:** STRICT READ-ONLY INSPECTION  
**Security Standard:** Strict Secret Masking & Zero Plaintext Credentials  

---

## 1. Complete Environment Variables Matrix

| Variable Name | Required By | Current Deployment Status | Expected Format / Example | Production Impact if Missing / Invalid |
| :--- | :--- | :--- | :--- | :--- |
| `APP_ENV` | Server, SMS Provider, Auth | Set (`production`) | `production` or `development` | Dictates strict security checks. In `production`, mock providers and unverified fallbacks are disabled. |
| `PORT` | Go HTTP Server (`main.go`) | Injected by Render | Numeric port string (e.g. `10000`) | Server defaults to `8080`. Render routes traffic through `$PORT`; failure to bind halts container. |
| `GOOGLE_CLIENT_ID` | Google OAuth2 Verifier | Configured in Android, verify in Render | `*.apps.googleusercontent.com` | **P0 BLOCKER:** If missing or mismatched with Android `strings.xml`, `POST /api/v1/auth/google` fails with `401 Unauthorized` (`GOOGLE_CLIENT_ID is not configured on server`). |
| `REDIS_URL` | Ephemeral Queue & Challenge Store | **MISSING in Go Code** (Go code expects `REDIS_ADDR`) | `rediss://default:<pwd>@<host>:6379` | **P0 BLOCKER:** Backend attempts to connect to `localhost:6379`, fails, and falls back to volatile RAM. All offline messages and challenges lost on restart. |
| `REDIS_ADDR` | Legacy Redis loader | Currently unset / defaulting to `localhost:6379` | `host:port` (e.g., `red-xxx:6379`) | Causes `dial tcp [::1]:6379: connect: connection refused`. |
| `REDIS_PASSWORD` | Legacy Redis loader | Unset | Secret string | Required if Redis requires authentication and `REDIS_ADDR` is used without `REDIS_URL`. |
| `INBOUND_SMS_RECEIVER_NUMBER` | Reverse SMS Verification | **MISSING** (Defaults to dummy `+919800000000`) | E.164 phone string (e.g., `+9198XXXXXXXX`) | **P0 BLOCKER:** Android opens SMS composer targeting `+919800000000`. User sends SMS into black hole; verification times out after 90s. |
| `GATEWAY_WEBHOOK_SECRET` | Inbound SMS Webhook HMAC Auth | **MISSING** | 64-char Hex string | **P0 BLOCKER:** Inbound webhook fails closed with `401 Unauthorized` (`GATEWAY_WEBHOOK_SECRET is not configured on server`). |
| `IDENTITY_STORE_PATH` | Local JSON Identity Store | Unset (Defaults to `data/identity_store.json`) | Absolute file path on persistent volume (e.g., `/var/data/identity.json`) | **P0 BLOCKER:** Defaults to ephemeral container path. Every Render deployment or restart permanently deletes all user accounts and sessions. |
| `FCM_PROJECT_ID` | Background Push Wake-Up | **MISSING** | Firebase Project ID (e.g., `nexa-chat-prod`) | FCM operates in test/mock mode. Inactive/background Android devices do not receive wake-up pings when new messages arrive. |
| `FIREBASE_SERVICE_ACCOUNT_JSON` | Firebase Admin SDK | **MISSING in Code & Env** | JSON service account key contents | **ARCHITECTURAL BUG:** Even if `FCM_PROJECT_ID` is set, `fcm_service.go` has no code to generate OAuth2 bearer tokens, resulting in 401 errors from Google FCM API. |
| `OTP_SERVER_PEPPER` | Legacy Outbound OTP | Recommended | 64-char Hex salt pepper | Used by legacy `OtpService` to hash verification codes. |
| `MSG91_AUTH_KEY` | Legacy Outbound OTP (India) | Optional / Unset | Secret API key string | If outbound OTP fallback is requested, required for MSG91 carrier dispatch. |
| `MSG91_TEMPLATE_ID` | Legacy Outbound OTP (India) | Optional / Unset | DLT approved template ID | Required alongside `MSG91_AUTH_KEY`. |
| `TWILIO_ACCOUNT_SID` | Legacy Outbound OTP (Global) | Optional / Unset | Twilio SID (`AC...`) | Required for Twilio fallback. |
| `TWILIO_AUTH_TOKEN` | Legacy Outbound OTP (Global) | Optional / Unset | Secret string | Required alongside `TWILIO_ACCOUNT_SID`. |
| `TWILIO_FROM_NUMBER` | Legacy Outbound OTP (Global) | Optional / Unset | E.164 phone number | Sender number for Twilio dispatch. |

---

## 2. Configuration Format Verification

### A. Google OAuth Verification
```env
GOOGLE_CLIENT_ID=356056510017-grmokt7u1sch6hdk9dbah85k2jqe32v2.apps.googleusercontent.com
```
- **Validation Rule:** Must strictly match Android's `R.string.google_web_client_id` in `app/src/main/res/values/strings.xml`.
- **Backend File:** `backend/internal/auth/google_verifier.go:48`
- **Rejection Message:** `google token verification failed: audience mismatch` or `missing GOOGLE_CLIENT_ID`.

### B. Redis URL (TLS Support Required)
```env
REDIS_URL=rediss://default:AbCdEf123456@red-xxxxx-singapore.render.com:6379
```
- **Validation Rule:** Must support `rediss://` scheme with TLS verification enabled.
- **Backend File:** `backend/internal/queue/redis_buffer.go:33` (Requires update to use `redis.ParseURL`).

### C. Reverse SMS Inbound Telecom Receiver
```env
INBOUND_SMS_RECEIVER_NUMBER=+919876543210
GATEWAY_WEBHOOK_SECRET=7f92a1b8c3d4e5f678901234567890abcdef1234567890abcdef1234567890ab
```
- **Validation Rule:**
  - `INBOUND_SMS_RECEIVER_NUMBER`: E.164 format with country code prefix (e.g. `+91...`).
  - `GATEWAY_WEBHOOK_SECRET`: Shared secret configured in the telecom gateway webhook header generator (`X-Gateway-Signature: sha256=<hmac>`).

### D. Identity Store Persistent Mount
```env
IDENTITY_STORE_PATH=/var/data/identity_store.json
```
- **Validation Rule:** Points to a mount path backed by a Render Persistent Disk (`/var/data`), preventing data wipe upon container restart.

---

## 3. Degradation vs. Hard Startup Failure Rules

1. **`GOOGLE_CLIENT_ID` Missing:** Hard failure on authentication. Service can start, but `/api/v1/auth/google` immediately fails closed with 401.
2. **`REDIS_URL` Unreachable:** Service currently degrades to in-memory queue. In production, this causes silent data loss of offline messages upon container sleep. **Recommendation:** Hard fail on startup in `production` mode if Redis is unreachable.
3. **`INBOUND_SMS_RECEIVER_NUMBER` Missing:** Silently defaults to `+919800000000`. **Recommendation:** Hard fail on startup in `production` mode to prevent users sending SMS to non-existent numbers.
4. **`GATEWAY_WEBHOOK_SECRET` Missing:** Webhook fails closed (401). **Recommendation:** Hard fail on startup if Reverse SMS is active.
5. **`FCM_PROJECT_ID` Missing:** Degrades gracefully; notifications logged to stdout. Initial chat access is NOT blocked.
