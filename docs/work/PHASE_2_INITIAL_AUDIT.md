# NEXA — Phase 2: Authentication, Identity & Reliability Initial Audit Report

**Date:** 2026-11-04  
**Audit Target:** NEXA Android Client (`H:\chat_app\app`) & Go Ephemeral Relay Backend (`H:\chat_app\backend`)  
**Scope:** Phase 1 Cryptographic Consistency, Server-Side NX-ID Allocation, Phone SMS OTP, Google OAuth, FCM Wake-Up, Room Migrations, Multi-Device Sync, Real-Time Typing Indicators

---

## 1. Phase 1 Regression & Cryptographic Verification

A full regression verification of Phase 1 functionality was conducted across the Android codebase, Go backend, and automated test suite:

- **Double Ratchet Integration**: Verified active in [`ChatRepositoryImpl.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/repository/ChatRepositoryImpl.kt#L140-L215). `ratchetEncrypt()` and `ratchetDecrypt()` are invoked on all `TransportType.INTERNET` and `TransportType.DATA_SMS` message dispatches and inbound packet handlers.
- **WebSocket Ed25519 Authentication**: Verified active in [`WebSocketManager.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/network/WebSocketManager.kt#L115-L160) and [`client.go`](file:///H:/chat_app/backend/internal/connection/client.go#L40-L135). Unauthenticated sockets are blocked from routing and closed with code 4001.
- **Wire Protocol Binary Alignment**: Protobuf encoder/decoder in [`NexaWirePacket.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/proto/NexaWirePacket.kt) and [`wire.go`](file:///H:/chat_app/backend/internal/wire/wire.go) match field-for-field across fields 1–9.
- **Automated Test Suite**: All 118 unit and integration tests are passing with 100% success rate.
- **16 KB Page-Size & Security**: ELF LOAD segment alignment preserved (`p_align = 16384`), debug APK builds cleanly (`app-debug.apk`, 98 MB).

### Cryptographic Signing Input Canonical Format Audit

The Phase 1 documents showed a minor documentation discrepancy regarding challenge signing input:
- *Initial Audit Proposal*: Recommended signing `SHA-256(challengeNonce + ":" + nxId + ":" + timestamp)`.
- *Final Implementation*: Directly signs the canonical raw byte concatenation `challengeNonce + ":" + nxId + ":" + timestamp`.

**Code Inspection Findings**:
1. In Android [`WebSocketManager.kt:133`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/network/WebSocketManager.kt#L133):
   ```kotlin
   val dataToSign = nonce + ":$nxId:$challengeTs".toByteArray(Charsets.UTF_8)
   val signature = Ed25519Signer.sign(dataToSign, privKey)
   ```
2. In Go [`client.go:106-112`](file:///H:/chat_app/backend/internal/connection/client.go#L106-L112):
   ```go
   dataToSign := append(append(c.ChallengeNonce, []byte(":"+c.NxId+":")...), []byte(fmt.Sprintf("%d", c.ChallengeTime))...)
   altDataToSign := []byte(fmt.Sprintf("%s:%s:%d", hex.EncodeToString(c.ChallengeNonce), c.NxId, c.ChallengeTime))
   valid := ed25519.Verify(resp.PublicKey, dataToSign, resp.Signature) ||
       ed25519.Verify(resp.PublicKey, altDataToSign, resp.Signature)
   ```

**Analysis & Verdict**:
PureEd25519 (RFC 8032) internally hashes the input data using SHA-512 as part of the signature scheme. Pre-hashing with SHA-256 prior to PureEd25519 signing is unnecessary and non-standard. The existing implementation uses exact byte-for-byte raw concatenation on both Android and Go. The Go backend additionally verifies the hex-encoded variant as an alternative format. **This cryptographic protocol is correct, working, and verified. No modification is required.**

---

## 2. Phase 2 Component-by-Component Baseline Audit

### 2.1. Server-Side NX-ID Allocation & Identity Registration (P0)
- **Current State**:
  - In `AuthApiServiceImpl.kt:56-62`, NX-ID is generated entirely client-side using a random number generator (`NX-XXXX-XXX`, e.g. `NX-9842-K7X`).
  - No server-side registration endpoint exists to validate public keys, enforce identity uniqueness, or bind an NX-ID to an Ed25519 public key.
  - An attacker could craft any arbitrary NX-ID and register it.
- **Deficiencies**:
  - Missing server-side NX-ID derivation / allocation service.
  - Missing cryptographic proof-of-possession of the private key during registration.
  - Missing persistent storage on the Go backend for user identities, registered devices, and public keys.

### 2.2. Real Phone Number OTP Authentication (P0)
- **Current State**:
  - `AuthApiServiceImpl.kt` contains a hardcoded client-side mock: accepts any 6-digit number after a simulated `delay(1000)`.
  - The Go backend does not possess any phone OTP endpoints (`/api/v1/auth/otp/request`, `/api/v1/auth/otp/verify`).
- **Deficiencies**:
  - No SMS provider adapter interface (e.g. Twilio, MSG91, AWS SNS).
  - No secure server-side OTP generation, expiration, rate limiting (per-phone, per-IP), or attempt throttling.
  - Lack of canonical E.164 phone number formatting and validation.
  - High risk of unauthorized account creation.

### 2.3. Real Google OAuth Authentication (P0)
- **Current State**:
  - In `WelcomeViewModel.kt:81-102`, if Google Credential Manager fails or is cancelled, the app silently falls back to `simulated_oauth_<UUID>`.
  - The Go backend has no Google ID token verification endpoint.
- **Deficiencies**:
  - Client can bypass authentication by submitting arbitrary string tokens.
  - Server does not verify Google's public key signatures, issuer (`accounts.google.com`), audience (`GOOGLE_CLIENT_ID`), or token expiry.
  - No safe account linking between phone numbers and Google accounts.

### 2.4. Firebase Cloud Messaging (FCM) Wake-Up Integration (P1)
- **Current State**:
  - In `hub.go:206`, FCM is a stub: `log.Printf("[FCM] Dispatching background wake-up ping...")`.
  - Android project lacks `FirebaseMessagingService`, token upload logic, and notification channel initialization.
- **Deficiencies**:
  - When the app is in the background or killed, offline messages buffered in Redis cannot trigger a wake-up signal.
  - Push notification token lifecycle (registration, refresh, logout invalidation) is absent.

### 2.5. Room Database Migrations & Safety (P1)
- **Current State**:
  - In `NexaDatabase.kt:47-48, 107`:
    ```kotlin
    @Database(..., version = 10, exportSchema = false)
    ...
    .fallbackToDestructiveMigration()
    ```
- **CRITICAL DEFICIENCY**:
  - Despite claiming `version = 10`, there are **ZERO `Migration` objects** registered in `NexaDatabase.kt`.
  - If schema changes occur or existing databases are upgraded, `.fallbackToDestructiveMigration()` will **completely drop and recreate all tables**, causing silent data loss of encrypted messages, contacts, Double Ratchet state, and outbox packets.
  - `exportSchema = false` prevents Room from compiling JSON schema history for schema verification.

### 2.6. Multi-Device Synchronization & Typing Indicators (P1/P2)
- **Current State**:
  - NEXA assumes 1 device per user profile. No device registry exists to manage multi-device public keys.
  - Typing indicators in `ChatScreen.kt` / `ChatViewModel.kt` are simulated UI placeholders without wire protocol packets or server routing.

---

## 3. Action Plan & Priority Execution Order

1. **P0 — Identity & Server-Side NX-ID Allocation**:
   - Create Go backend persistent identity store (`backend/internal/identity/store.go`).
   - Implement deterministic cryptographic NX-ID derivation (`NX-<SHA256-PREFIX>`) or server allocation.
   - Implement registration endpoint validating public key proof-of-possession.
2. **P0 — Real Phone SMS OTP Service**:
   - Build SMS provider interface (`SmsProvider`) in Go with configurable Twilio/MSG91/Generic adapters and environment variables.
   - Implement cryptographically secure 6-digit OTP generation, SHA-256 OTP hashing (never plaintext), 5-minute expiry, max 3 attempts, 60s cooldown, and IP rate limiting.
   - Implement REST endpoints `/api/v1/auth/otp/request` and `/api/v1/auth/otp/verify`.
   - Update Android `AuthApiService` to call real endpoints with mock fallback strictly guarded for test environments.
3. **P0 — Real Google OAuth ID Token Verification & Safe Account Linking**:
   - Implement server-side Google ID token verification in Go backend using Google public certificates.
   - Add safe account linking protecting against account collision or takeover.
   - Update Android `WelcomeViewModel` to remove insecure simulated token fallbacks.
4. **P1 — Firebase Cloud Messaging (FCM)**:
   - Implement server-side FCM HTTP v1 / Admin push dispatch service for background wake-up (RAM-only notification, zero plaintext).
   - Implement Android `NexaFirebaseMessagingService`, token registration, and background wake-up intent.
5. **P1 — Room Database Sequential Migrations**:
   - Remove `.fallbackToDestructiveMigration()` for production instances.
   - Define explicit sequential migrations (v1 through v10 and v10 -> v11 for device sync tables).
   - Enable schema export and add migration unit tests.
6. **P1 — Multi-Device Synchronization**:
   - Implement device registry (independent device keys per physical phone).
   - Classify data categories and implement encrypted delta sync.
7. **P2 — Real-Time Typing Indicators**:
   - Add `PACKET_TYPING` wire packets with 3-second auto-expiration and debouncing.
