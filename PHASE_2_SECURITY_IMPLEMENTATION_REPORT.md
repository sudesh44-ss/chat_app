# NEXA — Phase 2 Security Remediation: Implementation Report

**Date:** November 6, 2026  
**Status:** Completed & Verified  
**Scope:** Device-Bound Cryptographic Authentication, WebSocket Identity Key Enforcement, Radio Callbacks, and Transactional Persistence Safeguards.

---

## 1. Executive Summary

In Phase 1, NEXA remediated the root causes of Play Protect installation blocks and Keystore key leakage by removing `RECEIVE_SMS`, enforcing HMAC-SHA256 on inbound webhooks, hardening Google OAuth token verification, and generating asymmetric **EC P-256 keypairs directly inside the Android Keystore (StrongBox / TEE)**.

In **Phase 2**, we completed the end-to-end cryptographic challenge-response authentication lifecycle across the Android client and Go backend, unified WebSocket identity verification on `/ws`, resolved SMS carrier delivery visibility, and hardened local storage persistence.

---

## 2. Implemented Architecture & Security Controls

```
+--------------------------------------------------------------------------------------------------+
|                                     NEXA PHASE 2 ARCHITECTURE                                    |
+--------------------------------------------------------------------------------------------------+
                                                                                                    
 [ Android Client ]                                                   [ Go Backend ]                
+-----------------------+                                            +-----------------------------+
| NexaHardwareSecurity  |                                            | ChallengeStore              |
| - EC P-256 in Keystore|                                            | - Redis (Atomic GETDEL/Lua) |
| - Non-exportable priv |                                            | - In-Memory Fallback        |
| - SHA256withECDSA     |                                            | - Strict 60s TTL            |
+-----------+-----------+                                            +--------------+--------------+
            |                                                                       |               
            | 1. POST /api/v1/auth/login-challenge (nx_id, device_id)               |               
            +---------------------------------------------------------------------->|               
            |                                                                       |               
            | 2. Server creates 32-byte crypto nonce & stores with 60s TTL          |               
            |<----------------------------------------------------------------------+               
            |                                                                                       
            | 3. Client signs challenge inside StrongBox / TEE enclave                              
            |    (Signature never enters RAM, private key is non-exportable)                         
            |                                                                                       
            | 4. POST /api/v1/auth/login-verify (challenge, signature, nx_id, dev_id)               
            +---------------------------------------------------------------------->|               
            |                                                                       |               
            | 5. Server atomically consumes challenge (anti-replay single use),     |               
            |    verifies ASN.1 ECDSA signature against registered P-256 public key,|               
            |    issues 30-day session token.                                       |               
            |<----------------------------------------------------------------------+               
            |                                                                                       
            | 6. Connect /ws?nx_id=...                                                              
            |    PACKET_AUTH_CHALLENGE -> PACKET_AUTH_RESPONSE                                      
            +---------------------------------------------------------------------->|               
            |    Server validates client public key matches registered public key   |               
            |    in identityStore before validating signature (blocks spoofing)     |               
+-----------+-----------+                                            +--------------+--------------+
```

---

## 3. Detailed Component Implementations

### Area 1: Cryptographic Challenge-Response Login (`POST /api/v1/auth/login-challenge` & `login-verify`)

1. **Backend Challenge Store (`backend/internal/auth/challenge_store.go`):**
   - Implemented `ChallengeStore` interface with `RedisChallengeStore` and `MemoryChallengeStore`.
   - Redis store uses an atomic Lua script executing `GET` followed by `DEL` on `auth:challenge:<nonce>`, eliminating race conditions and replay attacks under distributed load.
   - In-memory store provides thread-safe `sync.Mutex` protection and lazy expiration eviction for standalone/development environments.
   - Enforces a strict 60-second TTL on all authentication challenges.

2. **Backend Authentication Handlers (`backend/internal/auth/handler.go`):**
   - **`POST /api/v1/auth/login-challenge`:** Validates that the requested `nx_id` and `device_id` exist, belong to each other, and that the device is not revoked before issuing a cryptographically secure 32-byte challenge nonce.
   - **`POST /api/v1/auth/login-verify`:**
     - Atomically consumes the challenge (single-use guarantee).
     - Confirms device is active and not revoked.
     - Retrieves registered public key (`device.PublicKeyHex` or `account.PublicKeyHex`).
     - Parses X.509 PKIX DER public key (`x509.ParsePKIXPublicKey`) and verifies ASN.1 DER signature (`ecdsa.VerifyASN1`) over SHA-256 digest of challenge bytes.
     - Supports fallback to raw uncompressed EC points (65 bytes) and legacy Ed25519 (32 bytes).
     - On successful verification, issues an authenticated 30-day session token and updates `LastActiveAt`.
   - **Eliminated Zero-Proof Instant Login:** In `handleGoogleAuth`, replaced the unauthenticated direct session issuance (`isSameDevice == true`) with a challenge-response requirement (`REQUIRE_DEVICE_CHALLENGE`).

3. **Android Client Challenge Signing & Verification (`AuthApiService.kt`, `AuthRepositoryImpl.kt`):**
   - Added `requestLoginChallenge` and `verifyLoginChallenge` endpoints in `AuthApiService`.
   - In `AuthRepositoryImpl.loginWithGoogle`, when the backend returns `REQUIRE_DEVICE_CHALLENGE`, the app signs the challenge using `NexaHardwareSecurity.signChallenge(challengeBytes)` within the Keystore, submits it to `login-verify`, and saves the authenticated session token.
   - Registration request now attaches `device_public_key_hex` (EC P-256 DER hex) while preserving `public_key_hex` (Ed25519) for E2EE messaging identity.

---

### Area 2: Unified WebSocket Identity Key Enforcement (`/ws`)

1. **Vulnerability Identified in Original Implementation:**
   - In `backend/internal/connection/client.go:VerifyAuthResponse()`, the server previously verified `resp.Signature` against `resp.PublicKey` supplied in the client's packet without cross-referencing `identityStore`. Any client could present an ephemeral keypair and authenticate as any user.

2. **Remediation (`backend/internal/connection/client.go`):**
   - In `VerifyAuthResponse`, when `c.Hub.identityStore` is present, the server retrieves the account by `c.NxId`.
   - Decodes the registered public key (`expectedPubBytes`).
   - Rejects the packet if `!bytes.Equal(resp.PublicKey, expectedPubBytes)`.
   - Verifies the signature strictly against the server's registered public key.
   - Immediately consumes and clears `c.ChallengeNonce` (anti-replay single use).

---

### Area 3: Reverse SMS Radio Sent/Delivered Feedback (`VerificationRadarViewModel.kt`)

1. **Problem in Original Implementation:**
   - In `VerificationRadarViewModel.kt:210`, `smsManager.sendTextMessage(inbound, null, payload, null, null)` passed `null` for both `sentIntent` and `deliveryIntent`.
   - If the cellular radio rejected transmission (e.g., zero carrier balance, airplane mode, or no cell service), the app transitioned to `LISTENING_RADAR` and hung until timeout without giving user feedback.

2. **Remediation:**
   - Configured `sentPendingIntent` (`ACTION_SMS_SENT`) and `deliveryPendingIntent` (`ACTION_SMS_DELIVERED`) with `FLAG_IMMUTABLE`.
   - Registered a dynamic `BroadcastReceiver` using `ContextCompat.registerReceiver(..., ContextCompat.RECEIVER_NOT_EXPORTED)`.
   - Classified radio error codes with actionable user error messages:
     - `Activity.RESULT_OK`: Positive radio dispatch confirmed -> transitions to `LISTENING_RADAR`.
     - `SmsManager.RESULT_ERROR_NO_SERVICE`: "No mobile cellular network service available on selected SIM. Check carrier reception or send via default SMS app."
     - `SmsManager.RESULT_ERROR_RADIO_OFF`: "Cellular radio is turned off or Airplane mode is active. Disable Airplane mode to verify."
     - `SmsManager.RESULT_ERROR_LIMIT_EXCEEDED`: "Carrier rate limit reached on this SIM card. Please send manually via default SMS app."
     - Other carrier failures: "Carrier radio failed to dispatch SMS (Error code: X). Ensure your SIM has active outgoing SMS balance or send via default SMS app."
   - Handled proper unregistration in `onCleared()` to prevent context leaks.

---

### Area 4: Transactional Persistence Safeguards (`store.go`)

1. **Automatic Backup & Recovery:**
   - In `backend/internal/identity/store.go:load()`, when loading `identity_store.json`, a verified `.bak` copy is automatically generated (`os.WriteFile(bakPath, data, 0600)`).
   - If `identity_store.json` becomes corrupt (e.g. abrupt power failure), `load()` automatically detects JSON corruption and restores state from `identity_store.json.bak`.
   - Atomic file saves continue to use randomized temporary files (`.tmp.<nano>`) followed by `os.Rename`.

---

## 4. Modified & Added Files

| File | Type | Changes |
|---|---|---|
| `backend/internal/auth/challenge_store.go` | New | Redis atomic single-use challenge store with in-memory fallback. |
| `backend/internal/auth/challenge_login_test.go` | New | Unit tests for P-256 challenge-response, anti-replay, and revocation. |
| `backend/internal/auth/handler.go` | Modified | Added `login-challenge`, `login-verify`, `DevicePublicKeyHex` binding, and challenge requirement in Google auth. |
| `backend/internal/connection/client.go` | Modified | Enforced registered identity key verification in WebSocket auth handshake. |
| `backend/internal/connection/auth_test.go` | Modified | Added identityStore validation and key spoofing tests. |
| `backend/internal/identity/store.go` | Modified | Added automatic `.bak` creation and corruption fallback recovery. |
| `backend/internal/queue/redis_buffer.go` | Modified | Exposed `GetClient()` for unified Redis connection reuse. |
| `backend/cmd/server/main.go` | Modified | Wired `challengeStore` into `authHandler`. |
| `app/src/main/java/com/nexa/app/data/remote/dto/AuthDto.kt` | Modified | Added challenge DTOs and Google auth challenge parameters. |
| `app/src/main/java/com/nexa/app/data/remote/api/AuthApiService.kt` | Modified | Added challenge endpoints and attached device public key. |
| `app/src/main/java/com/nexa/app/domain/repository/AuthRepository.kt` | Modified | Added `requestAndVerifyLoginChallenge`. |
| `app/src/main/java/com/nexa/app/data/repository/AuthRepositoryImpl.kt` | Modified | Implemented Keystore hardware signing on login challenge. |
| `app/src/main/java/com/nexa/app/presentation/welcome/WelcomeViewModel.kt` | Modified | Added handling for `REQUIRE_DEVICE_CHALLENGE`. |
| `app/src/main/java/com/nexa/app/presentation/radar/VerificationRadarViewModel.kt` | Modified | Added `SMS_SENT`/`SMS_DELIVERED` callbacks with radio error handling. |
| `app/src/test/java/com/nexa/app/Phase2ChallengeResponseUnitTests.kt` | New | Unit tests for DTOs, P-256 signing, error classification, and anti-replay. |
