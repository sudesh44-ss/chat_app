# PRODUCTION READINESS REPORT: NEXA SECURE MESSENGER

**Audit Scope:** End-to-End Onboarding, Authentication, Cryptographic Device Binding, Reverse SMS, Redis, and Chat List Readiness  
**Target:** Android 15 Mobile Client & Render Go Backend (`https://nexa-relay.onrender.com`)  
**Verdict:** **NOT PRODUCTION READY (CRITICAL BLOCKERS DETECTED)**  
**Audit Mode:** STRICT READ-ONLY INSPECTION  
**Date:** November 2026  

---

## 1. Executive Summary & Production Readiness Verdict

NEXA cannot currently be certified as production-ready. While the frontend screens are cleanly constructed in Jetpack Compose, the cryptographic algorithms are mathematically sound, and zero dangerous telephony permissions are requested on Android, **the complete end-to-end user onboarding flow terminates prematurely during Phone Number Verification, and the backend suffers from catastrophic ephemeral state wipes on Render.**

### Core Scorecard

| Domain | Readiness Grade | Production Status | Primary Blocker |
| :--- | :---: | :---: | :--- |
| **Google Sign-In** | **B+** | Config Dependent | Requires `GOOGLE_CLIENT_ID` in Render |
| **Reverse SMS Verification** | **F** | **BLOCKED** | Dummy inbound number `+919800000000` + missing webhook secret |
| **Device Cryptographic Binding** | **B** | Functional for Login | WebSocket public key mismatch |
| **State Persistence & Database** | **F** | **CRITICAL RISK** | No PostgreSQL; JSON file wiped on Render restart |
| **Queue & Ephemeral Messaging** | **D** | Degraded / RAM Only | Dialing `localhost:6379`; `REDIS_URL` ignored |
| **Push Notifications (FCM)** | **F** | Broken / Non-Functional | Missing service account OAuth2 token exchange |
| **Chat List Screen** | **B+** | Ready on Device | Blocked from access by onboarding timeout |
| **WebSocket Relay** | **C** | Degraded | Ed25519 public key mismatch causes disconnection |

---

## 2. Answers to Direct Audit Questions

### 1. Is Google login fully functional?
- **Frontend:** Yes. `WelcomeViewModel` successfully launches Credential Manager using `context.getString(R.string.google_web_client_id)`.
- **Backend:** Yes, provided `GOOGLE_CLIENT_ID` is set in Render. The backend uses `GoogleVerifier` to validate RS256 signatures against Google JWKS, verifies audience, and enforces expiry.
- **Missing Link:** If `GOOGLE_CLIENT_ID` is unset on Render, it fails closed with `401 Unauthorized`.

### 2. Is Google token verification secure?
- **Yes.** `google_verifier.go` enforces strict RS256 verification against Google's public certificates (`https://www.googleapis.com/oauth2/v3/certs`), rejects symmetric tokens, validates audience against `GOOGLE_CLIENT_ID`, and validates expiry.

### 3. Why does the phone verification flow fail or remain unverified?
- **Root Cause Chain:**
  1. `INBOUND_SMS_RECEIVER_NUMBER` is missing in Render, defaulting to `+919800000000`.
  2. Android dispatches the beacon SMS to `+919800000000`, which is not routed to an active telecom receiver.
  3. No webhook reaches `https://nexa-relay.onrender.com/api/v1/auth/inbound-sms`.
  4. Even if called, `GATEWAY_WEBHOOK_SECRET` is unset on Render, so the webhook handler rejects incoming requests with `401 Unauthorized`.
  5. The client polls for 90 seconds, receives `PENDING`, and finally displays **"Verification Window Expired"**.

### 4. Can the backend actually send the verification SMS?
- Under **Method 1 (Client-Initiated Reverse SMS)**, the backend **does NOT send** outbound SMS. The client sends the SMS to the inbound gateway.
- For legacy outbound OTP (`OtpService`), the backend **CANNOT send** SMS in production because `UnavailableSmsProvider` is active (`[SmsProvider] CRITICAL: Running in production with NO SMS provider configured!`).

### 5. Can the inbound webhook receive and process verification messages?
- **No.** `VerifyGatewaySignature` fails closed immediately if `GATEWAY_WEBHOOK_SECRET` is unset on Render.

### 6. Is PostgreSQL connected and persistent?
- **NO.** The Go backend contains **zero PostgreSQL code**. SQL migrations in `database/migrations/` are orphaned.
- All accounts and sessions reside in `data/identity_store.json`, which is stored on Render's ephemeral disk. **Every restart or redeployment deletes all users.**

### 7. Is Redis configured correctly?
- **NO.** The backend dials `localhost:6379` because it reads `REDIS_ADDR` instead of `REDIS_URL`. It falls back to volatile RAM maps.

### 8. Is FCM operational?
- **NO.** `FCM_PROJECT_ID` is missing on Render. Furthermore, `fcm_service.go` has no code to generate OAuth2 bearer tokens from a Google Service Account key, meaning real calls would fail with 401.

### 9. Is device authentication implemented correctly?
- **Partial.** Login challenge-response with EC P-256 Android Keystore keys works via `POST /api/v1/auth/login-verify`.
- **However, WebSocket authentication fails:** `CryptoKeystoreManager` generates a public key using SHA-256 clamping, while `WebSocketManager` signs with `nexa_handshake_keys` (Ed25519). When the WebSocket connects, the backend compares the public keys and disconnects the client.

### 10. Can the application retrieve the authenticated user's profile?
- **Yes.** Once verified, `userDao` stores the profile locally in Room database (`UserEntity`), and `authRepository.getActiveProfile()` emits the domain model.

### 11. Can the application retrieve the Chat List?
- **Yes.** The Chat List (`MainChatDashboardScreen`) is populated entirely from local Room database (`ChatDao`). For a new user, it displays a valid empty state card.

### 12. What exactly prevents the user from reaching the Chat List?
- The user cannot complete **Step 3 (Reverse SMS Verification)**. Because the gateway does not confirm the beacon within 90 seconds, the UI transitions to `EXPIRED` with a retry button, blocking progression to `ProfileBadgeScreen` and `MainChatDashboardScreen`.

---

## 3. Prioritized Remediation Roadmap

### Phase P0: Critical Blockers (Required to Complete Onboarding)

1. **Fix Inbound Reverse SMS Gateway Configuration:**
   - In Render Dashboard, configure `INBOUND_SMS_RECEIVER_NUMBER` with the actual GSM gateway number.
   - Configure `GATEWAY_WEBHOOK_SECRET` in Render.
   - Point the telecom gateway (Twilio / MSG91 / GSM Gateway) to webhook URL: `https://nexa-relay.onrender.com/api/v1/auth/inbound-sms` with `X-Gateway-Signature` HMAC-SHA256 header.

2. **Add Render Persistent Volume for Identity Store:**
   - Attach a Render Persistent Disk mounted at `/var/data`.
   - Set environment variable `IDENTITY_STORE_PATH=/var/data/identity_store.json`.
   - *(Long-term)*: Migrate identity store to PostgreSQL using the existing `database/migrations/`.

3. **Fix Redis Connection Loader:**
   - In `backend/cmd/server/main.go` and `internal/queue/redis_buffer.go`, parse `REDIS_URL` using `redis.ParseURL(redisURL)` with TLS support (`rediss://`).
   - Pass Render Redis URL into `REDIS_URL`.

4. **Synchronize WebSocket Handshake Public Key:**
   - Unify the cryptographic identity key: ensure the public key registered during account onboarding matches the Ed25519 public key used by `WebSocketManager.setIdentitySigner`.

---

### Phase P1: Chat List & Messaging Polish

1. **Verify WebSocket Live Handshake:**
   - Ensure the client receives `PACKET_AUTH_CHALLENGE`, signs it with matching Ed25519 key, and transitions `RelayStatusPill` to **"Relay Connected"** (green dot).

2. **FCM Background Wake-Up Integration:**
   - Add Google Cloud Service Account credential loader to `fcm_service.go` to generate OAuth2 bearer tokens.
   - Set `FCM_PROJECT_ID` and `FIREBASE_SERVICE_ACCOUNT_JSON` in Render.

---

### Phase P2: Production Hardening

1. **PostgreSQL Repository Migration:**
   - Implement real PostgreSQL repositories for `UserAccount`, `Device`, and `Session` in Go to eliminate reliance on JSON flat files.
2. **Deep Health Check:**
   - Update `/health` to verify Redis ping and database reachability rather than returning hardcoded `UP`.
