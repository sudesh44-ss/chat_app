# NEXA — Phase 5: Pre-Release Security Checklist

**Document Version:** 1.0.0  
**Audit Reference:** `docs/audit/PHASE_5_COMPLETE_SECURITY_AUDIT.md`  
**Remediation Plan:** `docs/audit/PHASE_5_SECURITY_REMEDIATION_PLAN.md`  
**Target Codebase:** `H:\chat_app` (Android Mobile Application, Go Backend Relay, PostgreSQL & Redis)  
**Evaluation Standard:** Pre-Production Commercial Release Hardening Gate  
**Overall Security Status:** **BLOCKED — RELEASE GATE FAILED (Remediation Required)**

---

## 1. Executive Pre-Release Status Summary

NEXA's cryptographic core (Double Ratchet, X25519, Ed25519, SQLCipher 4, Keystore hardware backing) demonstrates high implementation fidelity. However, **the release gate is strictly BLOCKED** due to 2 Critical (P0) and 3 High (P1) vulnerabilities that expose user communication, signaling metadata, or device resources to unauthorized actors.

### Release Gate Scorecard

| Domain | Total Items | PASS | FAIL (Critical P0) | FAIL (High P1) | WARNING (P2) | NOT TESTED | Status |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **1. Android App & IPC Security** | 8 | 5 | 0 | 2 | 0 | 1 | 🔴 **BLOCKED** |
| **2. Auth & Device Identity** | 7 | 7 | 0 | 0 | 0 | 0 | 🟢 **PASS** |
| **3. Cryptography & E2EE** | 7 | 7 | 0 | 0 | 0 | 0 | 🟢 **PASS** |
| **4. Go Backend & API Security** | 6 | 5 | 1 | 0 | 0 | 0 | 🔴 **BLOCKED** |
| **5. Database & Supabase Security** | 4 | 2 | 1 | 0 | 1 | 0 | 🔴 **BLOCKED** |
| **6. Redis & Ephemeral Relay** | 4 | 4 | 0 | 0 | 0 | 0 | 🟢 **PASS** |
| **7. Backup & Cloud Security** | 5 | 4 | 0 | 0 | 1 | 0 | 🟡 **WARNING** |
| **8. P2P, WebRTC & Mesh Security** | 6 | 4 | 0 | 0 | 1 | 1 | 🟡 **WARNING** |
| **9. Secrets & Credential Safety** | 5 | 5 | 0 | 0 | 0 | 0 | 🟢 **PASS** |
| **10. Release Build Hardening** | 4 | 3 | 0 | 1 | 0 | 0 | 🔴 **BLOCKED** |
| **TOTALS** | **56** | **46** | **2** | **3** | **3** | **2** | 🔴 **BLOCKED** |

---

## 2. Detailed Pre-Release Security Checklist

### Legend
- 🟢 **PASS:** Verified in source code and automated tests; satisfies security requirements.
- 🔴 **FAIL (CRITICAL / P0):** Active security vulnerability that completely blocks public release.
- 🔴 **FAIL (HIGH / P1):** Substantial security or hardening deficiency; must be fixed before release.
- 🟡 **WARNING (MEDIUM / P2):** Degraded network resilience, push delivery, or restore safety concern.
- ⚪ **NOT TESTED:** Requires live multi-device radio hardware or physical carrier network setup.

---

### Section 1: Android Application & IPC Security

| ID | Security Item | Requirement | Status | Evidence / Source Code | Finding / Action |
| :--- | :--- | :--- | :---: | :--- | :--- |
| **APP-01** | Manifest Activity Exposure | `MainActivity` only exported component; protected intent filters. | 🟢 **PASS** | [`app/src/main/AndroidManifest.xml:28`](file:///H:/chat_app/app/src/main/AndroidManifest.xml#L28) | Single launcher activity; deep links filtered. |
| **APP-02** | Background Service Isolation | P2P transfer and sync services must be `exported="false"`. | 🟢 **PASS** | [`app/src/main/AndroidManifest.xml:68-80`](file:///H:/chat_app/app/src/main/AndroidManifest.xml#L68-L80) | `P2PTransferService` and foreground service non-exported. |
| **APP-03** | Push Wakeup Receiver Isolation | BroadcastReceiver waking app must be unexported or permission-guarded. | 🔴 **FAIL (P1)** | [`app/src/main/AndroidManifest.xml:86`](file:///H:/chat_app/app/src/main/AndroidManifest.xml#L86) | **VULN-05:** `NexaPushWakeUpReceiver` is `exported="true"` with no permission requirement. |
| **APP-04** | SMS Permission Elimination | No dangerous SMS read/write permissions declared in manifest. | 🟢 **PASS** | [`app/src/main/AndroidManifest.xml:10-25`](file:///H:/chat_app/app/src/main/AndroidManifest.xml#L10-L25) | `SEND_SMS` / `RECEIVE_SMS` omitted; Google Play compliant. |
| **APP-05** | Bluetooth Location Isolation | Nearby mesh Bluetooth permissions must omit location tracking. | 🟢 **PASS** | [`app/src/main/AndroidManifest.xml:18-21`](file:///H:/chat_app/app/src/main/AndroidManifest.xml#L18-L21) | Uses `neverForLocation` flag on Android 12+ (API 31+). |
| **APP-06** | Local Database Encryption | Room SQLite must be encrypted at rest via SQLCipher 4. | 🟢 **PASS** | [`app/src/main/java/com/nexa/app/core/database/`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/database/) | Net.zetetic SQLCipher with Android Keystore MasterKey. |
| **APP-07** | OS Backup Infiltration Defense | SQLite DB and shared prefs excluded from ADB and Cloud backups. | 🟢 **PASS** | [`app/src/main/res/xml/backup_rules.xml`](file:///H:/chat_app/app/src/main/res/xml/backup_rules.xml), `data_extraction_rules.xml` | `database` and `sharedpref` domains explicitly excluded. |
| **APP-08** | Production Cleartext Prohibition | Release builds must never permit cleartext HTTP/WS traffic. | 🔴 **FAIL (P1)** | [`app/src/main/res/xml/network_security_config.xml:18`](file:///H:/chat_app/app/src/main/res/xml/network_security_config.xml#L18) | **VULN-03:** Hardcoded LAN IP `10.155.150.84` cleartext exception active in main config. |

---

### Section 2: Authentication & Device Identity

| ID | Security Item | Requirement | Status | Evidence / Source Code | Finding / Action |
| :--- | :--- | :--- | :---: | :--- | :--- |
| **AUTH-01** | Google ID Token Verification | Cryptographic signature validation against Google public keys. | 🟢 **PASS** | [`backend/internal/auth/verifier.go:34`](file:///H:/chat_app/backend/internal/auth/verifier.go#L34) | `GoogleVerifier.Verify()` validates audience, expiry, certs. |
| **AUTH-02** | Hardware Challenge-Response | Re-authentication requires Keystore P-256 ECDSA hardware signature. | 🟢 **PASS** | [`backend/internal/auth/handler.go:210`](file:///H:/chat_app/backend/internal/auth/handler.go#L210) | Validates 60-second challenge nonce before issuing session. |
| **AUTH-03** | 2FA / OTP Brute-Force Defense | Strict lockout after 3 consecutive failed verification attempts. | 🟢 **PASS** | `backend/internal/auth/handler.go` | Verified via test `TestOTPVerification_RateLimiting`. |
| **AUTH-04** | OTP Resend Rate Limiting | 60-second cooldown between resends, maximum 5 requests per hour. | 🟢 **PASS** | `backend/internal/auth/handler.go` | Cooldown and hourly caps strictly enforced. |
| **AUTH-05** | Reverse SMS Gateway Auth | Inbound gateway webhooks authenticated with HMAC-SHA256 signature. | 🟢 **PASS** | [`backend/internal/auth/webhook_auth.go:34`](file:///H:/chat_app/backend/internal/auth/webhook_auth.go#L34) | Enforces constant-time HMAC check and 5-minute replay defense. |
| **AUTH-06** | Device Revocation Teardown | Revoking a secondary device immediately terminates active sessions. | 🟢 **PASS** | [`backend/internal/connections/handler.go:420`](file:///H:/chat_app/backend/internal/connections/handler.go#L420) | Drops Go WebSocket, invalidates DB session, clears FCM token. |
| **AUTH-07** | Session Token Entropy | Session tokens generated with cryptographically secure random bytes. | 🟢 **PASS** | `backend/internal/auth/` | 128-bit hex tokens generated via `crypto/rand`. |

---

### Section 3: Cryptography & End-to-End Encryption

| ID | Security Item | Requirement | Status | Evidence / Source Code | Finding / Action |
| :--- | :--- | :--- | :---: | :--- | :--- |
| **CRYP-01** | Pairwise E2EE Double Ratchet | Every message encrypted with advancing DH and symmetric ratchets. | 🟢 **PASS** | `DualGateSecurityUnitTests.xml` | 23/23 tests pass; ChaCha20-Poly1305 pairwise authenticated cipher. |
| **CRYP-02** | Identity Key Protection | Identity private keys stored in Android Keystore StrongBox/TEE. | 🟢 **PASS** | Android Keystore provider | Keys non-exportable; ECDSA P-256 / Ed25519 hardware bound. |
| **CRYP-03** | Forward Secrecy | Compromising current key does not decrypt historical ciphertext. | 🟢 **PASS** | Double Ratchet implementation | Chain keys ratcheted forward on every sent/received packet. |
| **CRYP-04** | Post-Compromise Security | Diffie-Hellman ratchet heals channel immediately on next exchange. | 🟢 **PASS** | Double Ratchet implementation | Ratchet step advances DH pair upon receiving new ephemeral key. |
| **CRYP-05** | AEAD Bad Tag Abort | Ciphertext tampering immediately aborts decryption with error. | 🟢 **PASS** | Poly1305 MAC verification | Throws `AEADBadTagException`; zero tampered data reaches DB. |
| **CRYP-06** | Cloud Media E2EE | Media attachments encrypted client-side prior to cloud upload. | 🟢 **PASS** | `R2FallbackUploader.kt` | Encrypted with AES-256-GCM; key sent in E2EE wire envelope. |
| **CRYP-07** | DTN Mesh Blind Tokens | Mules route messages using rotating blind hashes without metadata. | 🟢 **PASS** | `DtnMeshUnitTests.xml` | 6/6 tests pass; Blake3 rotating salts prevent traffic analysis. |

---

### Section 4: Go Backend & REST API Security

| ID | Security Item | Requirement | Status | Evidence / Source Code | Finding / Action |
| :--- | :--- | :--- | :---: | :--- | :--- |
| **API-01** | Max Request Body Caps | HTTP requests bounded by `http.MaxBytesReader` to block DOS. | 🟢 **PASS** | `backend/cmd/server/main.go` | 64 KB JSON, 512 KB telemetry, 10 MB DTN payload caps. |
| **API-02** | SQL Injection Defense | Database queries strictly parameterized using `$1`, `$2` bind params. | 🟢 **PASS** | `backend/internal/database/` | `pgx/v5` parameterized queries; zero string concatenation. |
| **API-03** | IDOR / BOLA Authorization | Operations on user data strictly validated against authenticated `nx_id`. | 🟢 **PASS** | `backend/internal/media/` | Upload tickets and profile edits bound to session `nx_id`. |
| **API-04** | Signaling WebSocket Auth | WebRTC signaling socket requires valid session token or auth challenge. | 🔴 **FAIL (P0)** | [`backend/cmd/server/main.go:234`](file:///H:/chat_app/backend/cmd/server/main.go#L234) | **VULN-01:** `/ws/signaling` accepts any query `nx_id` with zero authentication. |
| **API-05** | Messaging WebSocket Auth | Core messaging socket requires valid session token and device binding. | 🟢 **PASS** | [`backend/cmd/server/main.go:210`](file:///H:/chat_app/backend/cmd/server/main.go#L210) | Authenticates session token; drops revoked devices immediately. |
| **API-06** | CORS & Header Hardening | Strict origin checking and security headers on HTTP responses. | 🟢 **PASS** | `backend/cmd/server/main.go` | Default CORS rejects arbitrary origins; no wildcard in prod. |

---

### Section 5: Database & Supabase Security

| ID | Security Item | Requirement | Status | Evidence / Source Code | Finding / Action |
| :--- | :--- | :--- | :---: | :--- | :--- |
| **DB-01** | Row-Level Security (RLS) | All public tables must enable RLS to block unauthenticated PostgREST. | 🔴 **FAIL (P0)** | [`backend/internal/database/migrations.go:23`](file:///H:/chat_app/backend/internal/database/migrations.go#L23) | **VULN-02:** 13 public tables lack RLS; queryable via Supabase `anon` key. |
| **DB-02** | Database Connection TLS | PostgreSQL connections require SSL/TLS in transit. | 🟢 **PASS** | `backend/internal/database/db.go` | Enforces `sslmode=require` / `sslmode=verify-full`. |
| **DB-03** | Principle of Least Privilege | Database users restricted from administrative / DDL operations. | 🟡 **WARNING (P2)** | Database deployment | Go backend connects with primary role; should use restricted role. |
| **DB-04** | Sensitive Column Encryption | Phone numbers and sensitive hashes protected at rest in DB. | 🟢 **PASS** | `backend/internal/database/migrations.go` | Phone hashes stored as SHA-256; prekeys store public curves only. |

---

### Section 6: Redis & Ephemeral Relay Security

| ID | Security Item | Requirement | Status | Evidence / Source Code | Finding / Action |
| :--- | :--- | :--- | :---: | :--- | :--- |
| **RED-01** | Zero-Disk Message Storage | Redis retains only encrypted in-flight envelopes; no message plaintext. | 🟢 **PASS** | `backend/internal/relay/` | Envelopes are opaque binary wire packets (`.enc`). |
| **RED-02** | TTL Expiration | In-flight message buffers expire strictly after 24 hours. | 🟢 **PASS** | `backend/internal/relay/redis_queue.go` | `EXPIRE 86400` set on all `pending:<nxId>` keys. |
| **RED-03** | Lease Acknowledgment Safety | Messages leased for 30s; re-queued if client disconnects unacked. | 🟢 **PASS** | `backend/internal/relay/redis_queue.go` | Lease timeout prevents packet loss on unexpected network drop. |
| **RED-04** | Fail-Closed Production Guard | Backend refuses startup if Redis is unreachable in production mode. | 🟢 **PASS** | `backend/cmd/server/main.go:88` | Halts initialization when `APP_ENV=production` and Redis fails. |

---

### Section 7: Backup, Restore & Cloud Storage Security

| ID | Security Item | Requirement | Status | Evidence / Source Code | Finding / Action |
| :--- | :--- | :--- | :---: | :--- | :--- |
| **BAK-01** | Client-Side Backup Encryption | Backups encrypted with AES-256-GCM derived from 12-word recovery phrase. | 🟢 **PASS** | `DriveBackupRepositoryImpl.kt` | PBKDF2-HMAC-SHA256 (65,536 rounds); keys never reach cloud. |
| **BAK-02** | Google Drive Isolation | Vault stored in Google Drive `appDataFolder`; inaccessible to other apps. | 🟢 **PASS** | Google Drive REST API integration | Hidden app data scope prevents accidental user or app tampering. |
| **BAK-03** | Split-Brain Conflict Detection | Remote epoch checked before overwrite to prevent cloud rollback. | 🟢 **PASS** | `DriveBackupRepositoryImpl.kt` | Epoch comparison rejects backups older than remote timestamp. |
| **BAK-04** | Atomic Restore & Rollback Safety | Failed restore must restore original local database without data loss. | 🟡 **WARNING (P2)** | `DriveBackupRepositoryImpl.kt:294` | Destructive delete-before-copy; requires staging swap to prevent corruption. |
| **BAK-05** | Integrity Verification Pre-Restore | Encrypted blob checksum validated prior to database replacement. | 🟢 **PASS** | `DriveBackupRepositoryImpl.kt` | SHA-256 integrity hash validated before cipher stream begins. |

---

### Section 8: P2P, WebRTC & Nearby Mesh Security

| ID | Security Item | Requirement | Status | Evidence / Source Code | Finding / Action |
| :--- | :--- | :--- | :---: | :--- | :--- |
| **NET-01** | Inbound File Quarantine | Received P2P files held in sandbox until magic bytes validated. | 🟢 **PASS** | `P2PQuarantineManager.kt` | Downloaded chunks assembled in `cache/quarantine/`; YARA verified. |
| **NET-02** | Executable File Block | Inbound payloads matching `.apk`, `.dex`, `.so`, `.sh` blocked. | 🟢 **PASS** | `FileSecurityAndSanitizationUnitTests.xml` | MIME type spoofing detected via header inspection; rejected. |
| **NET-03** | Path Traversal Protection | File names sanitized to prevent directory traversal (`../`). | 🟢 **PASS** | `P2PQuarantineManager.kt` | Strips path separators and normalizes names to random UUIDs. |
| **NET-04** | STUN/TURN NAT Traversal | WebRTC configured with TURN relay to succeed on Symmetric NAT. | 🟡 **WARNING (P2)** | `WebRtcClient.kt:95` | **VULN-06:** Configures only Google public STUN; fails on carrier NATs. |
| **NET-05** | Push Notification Service Token | FCM push dispatch must authenticate via Google OAuth2 Service Account. | 🟡 **WARNING (P2)** | `backend/internal/fcm/fcm_service.go:111` | **VULN-07:** `getAccessToken()` returns empty string; FCM calls fail with 401. |
| **NET-06** | Nearby Mesh Fleet Testing | Physical 3-device hop testing under variable RF conditions. | ⚪ **NOT TESTED** | Physical hardware lab | Automated tests pass (14/14); physical RF range unverified. |

---

### Section 9: Secrets & Credential Management

| ID | Security Item | Requirement | Status | Evidence / Source Code | Finding / Action |
| :--- | :--- | :--- | :---: | :--- | :--- |
| **SEC-01** | Git Secrets Exclusion | Environment files (`.env`), private keystores excluded from Git. | 🟢 **PASS** | `.gitignore` | `.env`, `*.jks`, `*.keystore` strictly ignored in Git. |
| **SEC-02** | No Hardcoded API Keys | No private cloud keys or database passwords committed in source. | 🟢 **PASS** | Source code grep | All secrets loaded via `os.Getenv` / environment variables. |
| **SEC-03** | Production Key Segregation | Test certificates and mock API tokens excluded from release builds. | 🟢 **PASS** | `app/build.gradle.kts` | Mock credentials isolated in `src/test/` and `src/debug/`. |
| **SEC-04** | Logcat Privacy Redaction | Log output strips phone numbers, tokens, OTPs, and private keys. | 🟢 **PASS** | `sanitizeUrlForLog()` & Log checks | All sensitive query params masked; OTPs masked as `+91****3210`. |
| **SEC-05** | Android Keystore Hardware Backing | Cryptographic keys non-exportable outside Android TEE/StrongBox. | 🟢 **PASS** | `MasterKey.Builder` | Hardware-backed keystore configured with fallback to TEE. |

---

### Section 10: Release Build Hardening

| ID | Security Item | Requirement | Status | Evidence / Source Code | Finding / Action |
| :--- | :--- | :--- | :---: | :--- | :--- |
| **BLD-01** | R8 Minification & Obfuscation | Release APK bytecode shrunk and obfuscated using ProGuard/R8. | 🔴 **FAIL (P1)** | [`app/build.gradle.kts:47`](file:///H:/chat_app/app/build.gradle.kts#L47) | **VULN-04:** `isMinifyEnabled = false`; classes and methods exposed in plaintext. |
| **BLD-02** | Resource Shrinking | Unused XML, layouts, and assets stripped from release package. | 🔴 **FAIL (P1)** | [`app/build.gradle.kts:48`](file:///H:/chat_app/app/build.gradle.kts#L48) | `isShrinkResources = false`; tied to `isMinifyEnabled`. |
| **BLD-03** | Debuggable Flag Disabled | Release APK compiled with `android:debuggable="false"`. | 🟢 **PASS** | Android build system | Standard Gradle release variant enforces `debuggable false`. |
| **BLD-04** | Static Code Analysis Cleanliness | Go backend and Android codebase clean of linter/vet errors. | 🟢 **PASS** | `go vet ./...` | 0 errors, 0 warnings across all Go backend packages. |

---

## 3. Final Pre-Release Release Gate Determination

```
================================================================================
                    NEXA PRE-RELEASE SECURITY RELEASE GATE
================================================================================
  TOTAL CHECKLIST ITEMS EVALUATED:  56
  PASSED CONTROLS:                  46 (82.1%)
  FAILED CRITICAL CONTROLS (P0):     2 (VULN-01, VULN-02)
  FAILED HIGH CONTROLS (P1):         3 (VULN-03, VULN-04, VULN-05)
  WARNINGS / RESILIENCE GAPS (P2):   3 (VULN-06, VULN-07, BAK-04)
  NOT TESTED (PHYSICAL TESTBED):     2 (Physical Mesh Hop, Real Carrier SMS)

  FINAL RELEASE DECISION:           🔴 BLOCKED (CANNOT RELEASE TO PRODUCTION)
================================================================================
```

### Mandatory Action Items Before Commercial Launch:
1. **Apply Remediation Patch 2.1 (VULN-01):** Enforce session token validation on `/ws/signaling` in `backend/cmd/server/main.go`.
2. **Apply Remediation Patch 2.2 (VULN-02):** Execute Migration 006 enabling Row-Level Security (`ENABLE ROW LEVEL SECURITY`) with fail-closed PostgREST policies across all 13 PostgreSQL tables.
3. **Apply Remediation Patch 2.3 (VULN-03):** Remove LAN IP `10.155.150.84` cleartext exception from `app/src/main/res/xml/network_security_config.xml`.
4. **Apply Remediation Patch 2.4 (VULN-04):** Set `isMinifyEnabled = true` and `isShrinkResources = true` in `app/build.gradle.kts` with verified ProGuard rules for Room, SQLCipher, BouncyCastle, and WebRTC.
5. **Apply Remediation Patch 2.5 (VULN-05):** Set `android:exported="false"` on `NexaPushWakeUpReceiver` in `app/src/main/AndroidManifest.xml`.
6. **Re-run Automated Regression Test Suites:** Ensure 100% pass rate on backend and Android crypto tests after patches are applied.
