# NEXA — Discovered Bugs, Defects & Risk Register

**Audit Date:** November 4, 2026  
**Audited Location:** `H:\chat_app`  
**Classification Standard:** Critical (Blocker for multi-device operation), High (Security or reliability failure), Medium (Degraded functionality), Low (Code hygiene / warning).

---

## 1. Discovered Vulnerability & Defect Register

### Bug B-01 [CRITICAL]: Inter-Device Decryption Failure in `ChatRepositoryImpl`
- **Location:** `app/src/main/java/com/nexa/app/data/repository/ChatRepositoryImpl.kt` (lines 142 & 183)
- **Defect Description:** When sending messages over `INTERNET` or `DATA_SMS`, `ChatRepositoryImpl` invokes `keystoreManager.encryptWithMasterKey(rawPlainBytes)` instead of `doubleRatchetEngine.ratchetEncrypt(recipientNxId, rawPlainBytes)`.
- **Impact:** `encryptWithMasterKey()` encrypts data with the sender's local `AndroidKeyStore` master key. The recipient device possesses its own unique, unexportable hardware key, so attempting to decrypt with `keystoreManager.decryptWithMasterKey()` will throw an `AEADBadTagException` and display `"[Encrypted Message]"` on the receiving phone.
- **Root Cause:** Incomplete substitution during the transition from the prototype Keystore manager to the production `DoubleRatchetEngine`.
- **Proposed Fix:** Inject `DoubleRatchetEngine` into `ChatRepositoryImpl` and replace calls with `doubleRatchetEngine.ratchetEncrypt(recipientNxId, rawPlainBytes)`.

---

### Bug B-02 [HIGH]: Unauthenticated WebSocket Session Registration (Spoofing)
- **Location:** `backend/internal/connection/hub.go` & `backend/cmd/server/main.go` (line 61)
- **Defect Description:** The Go WebSocket relay registers client sessions purely based on the query parameter `?nx_id={nx_id}` without requiring an Ed25519 signature challenge or cryptographic proof of private key possession.
- **Impact:** An attacker on the same network or internet can connect using a target's `nx_id`, stealing the target's active session and receiving queued offline messages from Redis. (The attacker cannot read message contents due to AEAD encryption, but intercepts metadata).
- **Proposed Fix:** The server should send a 32-byte cryptographic challenge upon WebSocket connection, requiring the client to respond with an Ed25519 signature of the challenge before session registration is granted.

---

### Bug B-03 [HIGH]: Client-Side Simulated Auth & Random NX-ID Generation
- **Location:** `app/src/main/java/com/nexa/app/data/remote/api/AuthApiService.kt` (`AuthApiServiceImpl.kt`)
- **Defect Description:** `AuthApiServiceImpl` is currently an in-memory simulation that accepts any 6-digit numeric OTP and generates NX-IDs locally via `SecureRandom` (`NX-XXXX-XXX`).
- **Impact:** Two separate devices registering simultaneously may generate the same NX-ID (Birthday Paradox collision). Furthermore, anyone can register without owning the specified phone number.
- **Proposed Fix:** Wire registration through the Go backend `nexa-handshake` service with server-side collision checks and derive the NX-ID from the Ed25519 public key hash.

---

### Bug B-04 [HIGH]: Absence of Firebase Cloud Messaging (FCM) Integration
- **Location:** `backend/internal/connection/hub.go` (line 206) & `app/src/main/AndroidManifest.xml`
- **Defect Description:** When a recipient is offline, the Go backend logs `[FCM] Dispatching background wake-up ping` without sending an actual FCM payload. The Android client contains no `FirebaseMessagingService` or `google-services.json`.
- **Impact:** When the app process is terminated or killed by Android battery optimization, background messages will not wake the device until the user manually opens the app or a WorkManager task executes.
- **Proposed Fix:** Add Firebase Android SDK, register a `NexaFirebaseMessagingService`, and configure FCM HTTP v1 API credentials in the Go relay server.

---

### Bug B-05 [MEDIUM]: Missing Explicit Room Migrations
- **Location:** `app/src/main/java/com/nexa/app/data/local/NexaDatabase.kt` (lines 47–48)
- **Defect Description:** `NexaDatabase` is configured at `version = 10` with `exportSchema = false`. No sequential `Migration(1, 2)`, `Migration(2, 3)`... `Migration(9, 10)` classes are defined.
- **Impact:** Upgrading existing installed devices across database versions will cause an `IllegalStateException` or necessitate destructive database purging (`fallbackToDestructiveMigration()`), resulting in user chat loss.
- **Proposed Fix:** Set `exportSchema = true` and provide explicit Room `Migration` definitions for all version increments.

---

### Bug B-06 [MEDIUM]: Google OAuth Web Client ID Unconfigured
- **Location:** `app/src/main/java/com/nexa/app/presentation/welcome/WelcomeViewModel.kt` (lines 80–88)
- **Defect Description:** If the Google Web Client ID is not provided or fails Play Services verification, `WelcomeViewModel` falls back silently to `"simulated_oauth_" + UUID.randomUUID()`.
- **Impact:** Production users without Google Play Services or on unconfigured builds receive simulated OAuth tokens rather than real Google accounts.
- **Proposed Fix:** Externalize `webClientId` into `BuildConfig` or `local.properties` and validate token integrity against a Google OAuth token verification endpoint.

---

### Bug B-07 [LOW]: Deprecated Android NsdManager Host Resolution
- **Location:** `app/src/main/java/com/nexa/app/core/webrtc/LanDiscoveryBridge.kt` (line 84)
- **Defect Description:** Accesses `serviceInfo.host` and `resolveService(p0, p1)`, which are deprecated in Android 14+ (API 34) in favor of `registerServiceInfoCallback`.
- **Impact:** Compiler warnings and potential future compatibility issues on Android 16+.
- **Proposed Fix:** Migrate to `NsdManager.registerServiceInfoCallback` for API 34+.

---

## 2. Risk Matrix Summary

```
+---------------+---------------------+---------------------+
| SEVERITY      | COUNT DISCOVERED    | PRIMARY SUBSYSTEM   |
+---------------+---------------------+---------------------+
| CRITICAL      | 1                   | Cryptography / Chat |
| HIGH          | 3                   | Auth & Signaling    |
| MEDIUM        | 2                   | DB & OAuth Config   |
| LOW           | 1                   | API Deprecation     |
+---------------+---------------------+---------------------+
```
