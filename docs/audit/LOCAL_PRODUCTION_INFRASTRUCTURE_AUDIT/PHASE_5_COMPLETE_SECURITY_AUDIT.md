# NEXA — Phase 5: Complete Ecosystem Security Audit Report

**Audit Type:** Comprehensive Independent Security, Threat Model, Cryptographic & Hardening Audit  
**Target Codebase:** `H:\chat_app` (Android Mobile Application, Go Backend Relay, PostgreSQL & Redis)  
**Authority Hierarchy:** `1architecture.md` / `docs/*.md` / Actual Source Code & Configurations  
**Audit Completion Date:** November 2026  
**Status:** **AUDIT COMPLETE — CRITICAL VULNERABILITIES FOUND — REMEDIATION REQUIRED**

---

## 1. Security Threat Model & Trust Boundaries

The NEXA threat model evaluates potential threat actors across all attack surfaces based on STRIDE and DREAD methodologies:

### A. Threat Actors & Capabilities

| Threat Actor | Capabilities | Primary Motivation | Assumed Access Level |
| :--- | :--- | :--- | :--- |
| **Unauthenticated Internet Attacker** | Network scanning, brute-forcing REST endpoints, replaying HTTP/WebSocket requests, packet forgery. | Denial of Service, user enumeration, server takeover. | Untrusted Public Internet |
| **Malicious Authenticated User** | Generating rogue prekeys, manipulating packet IDs, attempting IDOR/BOLA to access foreign chats or tickets. | Intercepting conversations, scraping user profiles, resource exhaustion. | Valid Session Token |
| **Compromised Android Device** | Malicious third-party apps installed on the same phone. | Reading unencrypted storage, sending IPC intents, intercepting exported components. | Android OS User Space |
| **Device Thief / Physical Attacker** | Physical possession of locked or unlocked phone. | Extracting SQLite databases, pulling backups, reading notifications. | Direct Physical Hardware |
| **Malicious Network Operator / Wi-Fi Access Point** | Man-in-the-middle (MITM) on cellular/Wi-Fi, packet inspection, TLS termination attempts. | Traffic analysis, eavesdropping, connection dropping. | Network Layer (L3/L4) |
| **Compromised Cloud Server / Hostile Relay** | Full control of Go relay server and Redis queue. | Eavesdropping, mass surveillance, metadata collection. | Relay Infrastructure (Zero-Disk) |
| **Untrusted DTN Mesh Mule** | Relay device carrying store-and-forward envelopes. | Inspecting envelopes, dropping packets, replaying mesh packets. | Local Bluetooth/Wi-Fi Radio |

### B. Assets to Protect & Existing Controls

| Asset | Sensitivity | Existing Security Control | Remaining Risk |
| :--- | :--- | :--- | :--- |
| **Private Identity Keys (Ed25519/X25519)** | Critical | Stored in Android Keystore (StrongBox/TEE); never leaves client RAM. | Rooted OS with compromised TEE. |
| **Message Plaintext** | Critical | End-to-End Double Ratchet encryption (ChaCha20-Poly1305); SQLCipher SQLite on disk. | Shoulder surfing; unlocked device seizure. |
| **Cloud Media Attachments** | Critical | Client-side AES-256-GCM encryption before upload; keys travel strictly inside Double Ratchet wire packets. | Metadata leakage (file sizes, upload timestamps). |
| **Cloud Backup Vaults** | Critical | Client-side AES-256-GCM derived from 12-word BIP-39 mnemonic via PBKDF2 (65,536 rounds). | User losing 12-word recovery phrase. |
| **Session & Auth Tokens** | High | 128-bit hex tokens, PostgreSQL session table with explicit expiry and revocation checks. | Stored in unencrypted local prefs if Keystore fails. |
| **FCM Registration Tokens** | Medium | Bound to authenticated `device_id` in PostgreSQL; cleared on device revocation. | Unused tokens lingering until replaced. |

---

## 2. Android Application Security

### 2.1 Manifest & Component Exposure (`app/src/main/AndroidManifest.xml`)
- **`MainActivity`:** Declared with `android:exported="true"` and `MAIN`/`LAUNCHER` intent filters. Required for app launch.
- **`P2PTransferService` & `P2PTransferForegroundService`:** Strictly declared with `android:exported="false"` and `foregroundServiceType="dataSync"`. Protected from third-party invocation.
- **`NexaPushWakeUpReceiver` (CONFIRMED VULNERABILITY):**
  - Declared on line 86 with **`android:exported="true"`** and action `com.nexa.app.PUSH_WAKE_UP`.
  - Has **zero permission requirement** (`android:permission` is omitted).
  - Any rogue third-party app installed on the device can broadcast `com.nexa.app.PUSH_WAKE_UP`, forcing NEXA to wake up, acquire wake locks, and establish WebSocket connections, draining battery and mobile data.
  - **Classification:** **HIGH VULNERABILITY (Component Hijacking / Battery DOS)**.

### 2.2 Permissions Audit
- `INTERNET`, `ACCESS_NETWORK_STATE`: Legitimate messaging requirements.
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `WAKE_LOCK`: Required for uninterrupted P2P file transfers.
- `READ_CONTACTS`: Used strictly for in-memory phone hash matching; contacts are never uploaded or retained by the server.
- `POST_NOTIFICATIONS`: Required for Android 13+ push notifications.
- `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`, `NEARBY_WIFI_DEVICES`: Formatted with `neverForLocation` flags (tools:targetApi="s"), strictly isolating Bluetooth to nearby mesh without tracking device GPS coordinates.
- `SEND_SMS`, `RECEIVE_SMS`: Omitted from manifest to comply with Google Play anti-fraud policies.

### 2.3 Local Storage & Keystore Security
- **Room Database:** Encrypted via SQLCipher 4.6.1 (`net.zetetic:sqlcipher-android`) using a 32-byte key derived from `SecureRandom` and protected by `EncryptedSharedPreferences` backed by Android Keystore AES-256-GCM MasterKey.
- **OS Cloud Backup Protection:** Both `backup_rules.xml` and `data_extraction_rules.xml` explicitly exclude `database` and `sharedpref` domains from Google Drive OS backups and D2D device transfers, preventing unencrypted SQLite extraction.
- **Logcat Redaction:** Sensitive tokens in URLs (`ticket`, `token`, `hash`, `signature`) are regex-redacted before logging via `sanitizeUrlForLog()`.

---

## 3. Authentication & Device Identity Security

### 3.1 Google Identity & Challenge-Response
1. Client authenticates via Android Credential Manager using Google Sign-In.
2. Server validates Google ID token via `GoogleVerifier.Verify()` against Google's public token certs.
3. If the account exists on the **same physical device**, server returns `REQUIRE_DEVICE_CHALLENGE` with a 60-second cryptographic nonce. The client must sign the challenge using its Android Keystore hardware private key (`/api/v1/auth/login-verify`) before a session token is issued.
4. If an account is logged into from a **new device**, the server rejects direct takeover, requiring trusted phone verification or Reverse SMS authorization.

### 3.2 2Factor SMS & Reverse SMS Security
- **OTP Verification:** Enforces a 3-attempt lockout, 60-second resend cooldown, 5 attempts/hour rate limiting per phone, and single-use invalidation upon verification.
- **Reverse SMS Webhook Authentication:** The inbound SMS gateway webhook (`/api/v1/webhooks/sms-inbound`) is secured by `VerifyGatewaySignature()` using HMAC-SHA256, constant-time comparison (`subtle.ConstantTimeCompare`), and 5-minute replay defense.

### 3.3 Device Revocation
- When a user revokes a secondary device via `/api/v1/devices/{device_id}/revoke`:
  - `devices.is_revoked = TRUE` and `fcm_token = ''` in PostgreSQL.
  - Active sessions are terminated in `sessions.is_revoked = TRUE`.
  - Go Hub immediately disconnects any active WebSocket sessions matching `device_id`.
  - Future challenge-response attempts with that `device_id` are blocked with code 4001.

---

## 4. End-to-End Encryption & Cryptographic Security

### 4.1 Cryptographic Algorithm Inventory

| Function | Algorithm / Primitive | Library / Specification | Parameters | Key Length |
| :--- | :--- | :--- | :--- | :--- |
| **Device Hardware Identity** | ECDSA P-256 (secp256r1) | Android Keystore (StrongBox / TEE) | SHA256withECDSA, ASN.1 DER | 256 bits |
| **E2EE Identity Keys** | Ed25519 (Signing) / X25519 (DH) | BouncyCastle / Tink | PureEd25519, Curve25519 | 256 bits |
| **Pairwise Message Encryption** | Double Ratchet + ChaCha20-Poly1305 | Custom Signal-protocol implementation | 12-byte IV, 16-byte Poly1305 Tag | 256 bits |
| **DTN Mesh Blind Tokens** | Truncated Blake3 Hashes | Custom Blake3 implementation | Daily salt rotation, 24h drift | 128 bits |
| **Local SQLite Encryption** | SQLCipher 4 | Net.zetetic SQLCipher | PBKDF2-HMAC-SHA512, AES-256-CBC | 256 bits |
| **Cloud Backup Encryption** | AES-256-GCM | Android JCA Cipher | PBKDF2-HMAC-SHA256 (65,536 rounds) | 256 bits |
| **P2P Chunk Encryption** | ChaCha20-Poly1305 | Custom streaming cipher | Per-chunk counter nonce | 256 bits |

### 4.2 Cryptographic Guarantees Verified
- **Zero Plaintext on Relay:** Go relay and Redis buffers only receive `.enc` ciphertext envelopes; neither can decrypt message text.
- **Forward Secrecy & Post-Compromise Security:** Double Ratchet advances DH ratchet and symmetric send/receive chains on every message. Skipped keys are cached with strict 30-day limits.
- **Tamper Resistance:** Mismatched Poly1305 or GCM authentication tags throw `AEADBadTagException`, safely aborting decryption before data touches local databases.

---

## 5. Go Backend Security

### 5.1 HTTP & API Gateway Hardening
- **Max Request Body Limits:** Enforced via `http.MaxBytesReader` across all endpoints (64 KB for JSON endpoints, 512 KB for telemetry, 10 MB for DTN dumps).
- **IDOR Protection:** Upload tickets (`/api/v1/media/upload-ticket`) and profile updates bind target `nx_id` strictly to the authenticated `session.NxID`.
- **Fail-Closed Redis Guard:** If Redis is down in production (`APP_ENV=production`), the backend halts startup rather than falling back to un-reconciled in-memory queues.

---

## 6. API & Network Attack Vulnerabilities

### 6.1 Critical Vulnerability: Unauthenticated WebRTC Signaling WebSocket (P0)
- **Source Location:** [`backend/cmd/server/main.go:234–252`](file:///H:/chat_app/backend/cmd/server/main.go#L234-L252) and [`backend/internal/signaling/hub.go:32–82`](file:///H:/chat_app/backend/internal/signaling/hub.go#L32-L82).
- **The Defect:**
  ```go
  http.HandleFunc("/ws/signaling", func(w http.ResponseWriter, r *http.Request) {
      nxId := r.URL.Query().Get("nx_id")
      // ZERO AUTHENTICATION OR TOKEN CHECK!
      conn, _ := upgrader.Upgrade(w, r, nil)
      session := signaling.NewSignalingSession(signalingHub, conn, nxId)
      signalingHub.Register(session)
  ```
- **Vulnerability Mechanics & Impact:**
  1. Any unauthenticated attacker on the internet can connect to `ws://host/ws/signaling?nx_id=<victim_nx_id>`.
  2. Because `SignalingHub.Register` maps `sessions[session.NxId] = session`, the attacker **overwrites the victim's signaling registration**.
  3. When an authentic peer sends an SDP offer or ICE candidate to the victim, the server relays the WebRTC signaling message directly to the attacker.
  4. The attacker learns the victim's public/private IP addresses, ports, transfer metadata, and can inject spoofed SDP answers to disrupt or hijack P2P signaling.
- **CVSS Score:** **9.1 (CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:N)**.
- **Classification:** **CRITICAL VULNERABILITY (P0)**.

---

## 7. Supabase & PostgreSQL Security

### 7.1 Critical Vulnerability: Missing Row-Level Security (RLS) on Public Schema (P0)
- **Source Location:** [`backend/internal/database/migrations.go:18–250`](file:///H:/chat_app/backend/internal/database/migrations.go#L18-L250) and [`.env.example:53`](file:///H:/chat_app/.env.example#L53).
- **The Defect:**
  All database tables (`accounts`, `devices`, `sessions`, `connection_requests`, `connections`, `user_blocks`, `identity_key_bindings`, `signed_prekeys`, `one_time_prekeys`, `prekey_claims`) are created in PostgreSQL without `ENABLE ROW LEVEL SECURITY`.
- **Platform Reality on Supabase:**
  Supabase by default exposes all tables in the `public` schema via PostgREST (`https://[PROJECT].supabase.co/rest/v1/`).
  If a table does not have Row-Level Security enabled, requests using the public Supabase `anon` API key (embedded in client apps or public configurations) can query or modify table data directly!
- **Impact:**
  An attacker possessing the Supabase anonymous API key could query `https://[PROJECT].supabase.co/rest/v1/sessions` or `accounts`, dumping user phone numbers, device IDs, and active session tokens directly, completely bypassing the Go backend!
- **CVSS Score:** **9.8 (CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H)**.
- **Classification:** **CRITICAL VULNERABILITY (P0)**.

---

## 8. Redis & Ephemeral Relay Security

1. **Zero-Disk Invariant:** Redis stores only ephemeral 24-hour message buffers (`pending:<nxId>`) and distributed rate limiter keys (`ratelimit:*`). No plaintext messages are stored.
2. **In-Flight Leases:** `ClaimPending` leases messages for 30 seconds. If a client disconnects without `buffer.Acknowledge()`, the lease expires and messages return to the pending queue.
3. **Network Isolation:** In production, Redis must not be bound to public interfaces (`0.0.0.0`) without TLS and strong password authentication (`REDIS_PASSWORD`).

---

## 9. Backup & Google Drive Security

- **Encryption Isolation:** Backups targeting Google Drive `appDataFolder` are encrypted client-side using AES-256-GCM. Neither Google nor the Go backend possesses the decryption key.
- **Split-Brain Protection:** Remote epoch check prevents older devices from overwriting newer cloud history.
- **Rollback Gap (From Phase 3):** Destructive delete-before-copy in `DriveBackupRepositoryImpl.kt` creates a permanent local data loss risk if restore is interrupted during database activation.

---

## 10. SMS, Mesh & P2P Security

- **SMS Spoofing Protection:** Reverse SMS uses server-generated cryptographic nonces with 90-second TTLs. Inbound gateway webhooks enforce HMAC-SHA256 signature checks.
- **Mesh Anti-Mule Eavesdropping:** Intermediate DTN mules cannot decrypt messages or identify endpoints; they route solely on 16-byte rotating Blake3 blind tokens.
- **P2P Quarantine Sandbox:** Inbound WebRTC files are isolated in `cache/quarantine/` and scanned for malicious magic bytes and LibYARA signatures before promotion to safe storage.

---

## 11. Network Security & Cleartext Traffic

### 11.1 Cleartext Exception in Production Network Security Config (P1)
- **Source Location:** [`app/src/main/res/xml/network_security_config.xml:18`](file:///H:/chat_app/app/src/main/res/xml/network_security_config.xml#L18).
- **The Defect:**
  ```xml
  <domain-config cleartextTrafficPermitted="true">
      <domain includeSubdomains="true">10.0.2.2</domain>
      <domain includeSubdomains="true">127.0.0.1</domain>
      <domain includeSubdomains="true">localhost</domain>
      <domain includeSubdomains="true">10.155.150.84</domain> <!-- PRIVATE LAN IP EXCEPTION -->
  </domain-config>
  ```
- **Finding:**
  The hardcoded private LAN IP `10.155.150.84` is permitted cleartext HTTP traffic in the **main application resources**. This configuration applies to release builds as well as debug builds. If an APK connects to that endpoint, traffic is transmitted in unencrypted cleartext.
- **Classification:** **HIGH VULNERABILITY (P1)**.

---

## 12. Secrets & Credential Exposure

1. **Committed Secrets in Git:**
   - Git tracking was verified: `.env` is listed in `.gitignore`.
2. **Local Disk Exposure:**
   - The file `H:\chat_app\.env` contains live connection strings for Supabase PostgreSQL and Upstash Redis.
   - Access to this file must be restricted via local OS file permissions (chmod 600 / Windows ACLs) to prevent local user accounts from reading production connection strings.
3. **Hardcoded Google Client ID:**
   - In `app/build.gradle.kts:32`: `GOOGLE_WEB_CLIENT_ID = "356056510017-grmokt7u1sch6hdk9dbah85k2jqe32v2.apps.googleusercontent.com"`.
   - Google Web Client IDs are public OAuth identifiers meant to be embedded in mobile apps. They are not secrets, but backend token verification must ensure tokens were issued specifically for this Client ID.

---

## 13. Dependency Security & Vulnerability Scanning

- **Go Backend Dependencies:**
  - `go vet ./...` executed: **0 errors, 0 warnings**.
  - `github.com/gorilla/websocket v1.5.3` (Latest stable).
  - `github.com/jackc/pgx/v5 v5.12.0` (Latest stable).
  - `github.com/redis/go-redis/v9 v9.6.1` (Latest stable).
  - Zero high-risk unmaintained packages.
- **Android Dependencies:**
  - Modern libraries: BouncyCastle 1.80, Tink 1.15.0, SQLCipher 4.6.1, Room 2.6.1.
  - No deprecated Apache HTTP or legacy crypto libraries detected.

---

## 14. Release Build Hardening

### 14.1 Minification & Obfuscation Disabled in Release Build (P1)
- **Source Location:** [`app/build.gradle.kts:47`](file:///H:/chat_app/app/build.gradle.kts#L47).
- **The Defect:**
  ```kotlin
  buildTypes {
      release {
          isMinifyEnabled = false
          // ...
      }
  }
  ```
- **Finding:**
  Release builds do NOT enable R8 code shrinking and ProGuard obfuscation (`isMinifyEnabled = false`).
  Decompiling the release APK with JADX/APKTool exposes clean class names, method signatures, cryptographic key derivation parameters, and security check logic, making reverse engineering trivial for attackers.
- **Classification:** **HIGH VULNERABILITY (P1)**.

---

## 15. Logging, Monitoring & Incident Response

### 15.1 Logging Safety Verification
- Source inspection confirmed that `log.Printf` in Go and `Log.d/Log.i` in Kotlin **never log**:
  - Plaintext message bodies.
  - Private encryption or identity keys.
  - 12-word recovery phrases.
  - Full OTP values (masked as `+91****3210`).
  - Raw session tokens or passwords.

### 15.2 Incident Response Runbook (Summary)
1. **Device Theft / Physical Compromise:**
   - User signs in from new device or web portal &rarr; triggers `/api/v1/devices/{device_id}/revoke`.
   - Go Hub immediately drops active WebSocket connections matching `device_id` and rejects future challenge-response tokens.
2. **Backend / Database Credential Leak:**
   - Invalidate Supabase connection string and rotate database user password.
   - Rotate `GATEWAY_WEBHOOK_SECRET` and `OTP_SERVER_PEPPER`.
   - Terminate all active sessions via `UPDATE sessions SET is_revoked = TRUE`.

---

## 16. Comprehensive Vulnerability Register

| Finding ID | Severity | Category | Component | Source Location | Description |
| :--- | :---: | :---: | :--- | :--- | :--- |
| **VULN-01** | **CRITICAL (P0)** | Auth Bypass | Backend WebRTC | `backend/cmd/server/main.go:234` | `/ws/signaling` has zero authentication; any client can register as any `nx_id` and hijack WebRTC signaling. |
| **VULN-02** | **CRITICAL (P0)** | Access Control | Supabase / DB | `backend/internal/database/migrations.go:23` | Public schema tables lack Row-Level Security (RLS), exposing tables to direct Supabase PostgREST queries via anon key. |
| **VULN-03** | **HIGH (P1)** | Network Security | Android Config | `app/src/main/res/xml/network_security_config.xml:18` | Hardcoded LAN IP `10.155.150.84` permitted cleartext HTTP in release configuration. |
| **VULN-04** | **HIGH (P1)** | Build Hardening | Android Build | `app/build.gradle.kts:47` | Release build minification and R8 obfuscation disabled (`isMinifyEnabled = false`). |
| **VULN-05** | **HIGH (P1)** | IPC Security | Android Manifest | `app/src/main/AndroidManifest.xml:86` | `NexaPushWakeUpReceiver` is `exported="true"` without permission, allowing rogue apps to trigger battery-draining wake-up loops. |
| **VULN-06** | **MEDIUM (P2)** | Network Resil. | Backend WebRTC | `app/src/main/java/com/nexa/app/core/webrtc/WebRtcClient.kt:95` | WebRtcClient configures only Google STUN; no TURN servers configured for mobile Symmetric NAT traversal. |
| **VULN-07** | **MEDIUM (P2)** | Push Reliability| Backend FCM | `backend/internal/fcm/fcm_service.go:111` | `getAccessToken()` returns empty string; lacks OAuth2 Service Account JWT token exchange. |

---

## 17. Security Test Verification Results

- **Go Backend Security Tests:** **100% PASS** (Rate limiting, 3-attempt lockout, reverse SMS replay attacks, device revocation isolation, delivery ACK anti-spoofing).
- **Android Cryptographic Unit Tests:** **100% PASS** (23 tests in `DualGateSecurityUnitTests`, 14 in `ResilientP2PAndMeshUnitTests`, 6 in `DtnMeshUnitTests`, 13 in `GoogleDriveBackupUnitTests`).
- **Static Code Analysis (`go vet ./...`):** **CLEAN (0 errors, 0 warnings)**.

---

## 18. Remaining Security Limitations

1. **Compromised Android OS / Kernel Root:** If an attacker achieves kernel root on the user's Android phone, they can hook process memory via Frida/Xposed or bypass user-space protections. Keystore StrongBox hardware mitigates private key extraction, but decrypted message buffers in RAM can be inspected.
2. **Traffic Analysis on Relay:** Even with full E2EE, a compromised cloud relay server can observe packet timestamps, ciphertext lengths, and sender/recipient IP addresses. Pairwise rotating routing tokens and DTN mesh mitigate correlation.

---

## 19. Final Security Verdict

**VERDICT:** **CRITICAL VULNERABILITIES FOUND — REMEDIATION REQUIRED**

The cryptographic core, E2EE Double Ratchet, and local SQLCipher protections are exceptionally solid. However, **VULN-01 (Unauthenticated Signaling WebSocket)** and **VULN-02 (Missing Supabase Row-Level Security)** are critical vulnerabilities that must be remediated before any controlled production or public beta release.