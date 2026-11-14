# NEXA — Phase K–L: Comprehensive Security Audit Report
**Project:** NEXA Secure Mobile Messenger & Multi-Hop DTN Mesh  
**Workspace:** `H:\chat_app`  
**Phase:** K–L — Security, Abuse Prevention & Multi-Device Testing  
**Audit Date:** November 2026  
**Status:** ALL 11 DOMAINS VERIFIED & SECURED  

---

## 1. Scope & Audit Standards

This audit evaluated the end-to-end security posture of the NEXA application across 11 critical security domains, referencing NIST SP 800-63B (Digital Identity Guidelines), OWASP Mobile Application Security (MASVS), and Signal Protocol cryptographic standards.

---

## 2. In-Depth Evaluation Across 11 Security Domains

### Domain 1: Authorization, Authentication, and Session Controls
* **OTP Hardening (`internal/auth/otp_service.go`):**
  * 6-digit random codes generated via `crypto/rand`.
  * Hashed using server-keyed HMAC-SHA256 (`OTP_SERVER_PEPPER`) with a 16-byte cryptographically random salt.
  * Verified using constant-time comparison (`subtle.ConstantTimeCompare`).
  * Enforces a hard lockout: exceeding 3 failed attempts permanently deletes the OTP record.
  * Rate-limited to 5 requests/phone/hour and 15 requests/IP/hour, with a 60-second cooldown.
* **Session Lifecycle:**
  * Active sessions stored with explicit expiration (`ExpiresAt` in Unix milliseconds) and revocation boolean (`IsRevoked`).
  * Verified in `TestOtpBruteForceLockout`, `TestOtpExpiration`, and `TestAuthTicketOneTimeConsumption` (PASS).
* **Verdict:** **PASS (VERIFIED)**

---

### Domain 2: Device Identity Lifecycle and Revocation
* **Device Registration:** Each device generates an Ed25519 identity key and registers with the backend identity repository (`identity.Device`).
* **Active Challenge-Response:** On WebSocket connect, the server dispatches a 32-byte cryptographically random challenge with a 60-second TTL. The client signs `nonce:nxId:timestamp` with its device private key.
* **Revocation Cascade:**
  * Calling `RevokeDevice` marks `devices.is_revoked = TRUE` and invalidates associated sessions in PostgreSQL.
  * `hub.RevokeDeviceSessionLocal` actively evicts running WebSocket sessions, closes the outbound `s.Send` channel, and purges registered routing tokens.
  * Revocation events are broadcast across instances via Redis Pub/Sub (`device:revoked`).
  * Reconnection attempts by revoked devices fail challenge verification (`Auth failed: device is revoked`).
* **Verdict:** **PASS (VERIFIED)**

---

### Domain 3: End-to-End Encryption & Cryptographic Invariants
* **Double Ratchet Implementation (`DoubleRatchetEngine.kt`):**
  * Performs Diffie-Hellman ratcheting on every turn and symmetric KDF ratcheting on every message.
  * Tampering with AEAD ciphertext or authentication tag results in immediate authentication failure; corrupt messages are marked `FAILED` and never decrypted as plaintext.
* **NXDT Binary Envelope (`DtnEnvelope.kt`):**
  * Rigid 200-byte v1 layout with magic bytes `0x4E, 0x58, 0x44, 0x54` (`NXDT`).
  * Immutable header is signed with sender's Ed25519 private key.
  * Relays decrement `hopCount` without invalidating the sender signature because mutable relay metadata is excluded from canonical signing bytes.
  * Tampering with envelope header, blind token, or payload results in signature verification failure.
* **Verdict:** **PASS (VERIFIED)**

---

### Domain 4: API Endpoint Hardening, IDOR & Request Size Limits
* **Media Upload & Purge Endpoints (`backend/cmd/server/main.go`):**
  * Remediated: `/api/v1/media/upload-ticket` and `/api/v1/media/purge-ack` now require Bearer session authentication.
  * Caller NX-ID is strictly bound to `session.NxID`, preventing ticket impersonation and IDOR attacks.
  * Enforces `http.MaxBytesReader(w, r.Body, 64*1024)` to reject oversized JSON bodies.
  * Enforces a 100 MB hard ceiling on media file size before cloud ticket issuance.
* **Connection Request Endpoint (`backend/internal/connections/handler.go`):**
  * Remediated: Added `http.MaxBytesReader(w, r.Body, 64*1024)`.
  * Enforces `len(reqBody.IntroMessage) <= 256` characters, rejecting spam floods.
* **DTN Opportunistic Dump Endpoint (`backend/internal/api/dtn_handler.go`):**
  * Remediated: Enforces `http.MaxBytesReader(w, r.Body, 10*1024*1024)` (10MB maximum request ceiling).
  * Enforces a batch cap of 100 envelopes per request.
* **Verdict:** **PASS (REMEDIATED & VERIFIED)**

---

### Domain 5: Abuse Prevention, Rate Limiting & Fail-Closed Policies
* **Distributed Rate Limiting (`backend/internal/ratelimit/distributed_limiter.go`):**
  * Sliding-window rate limiting backed by atomic Redis Lua scripts (`ratelimit:conn_req`, `ratelimit:reversesms`, `ratelimit:prekey_claim`).
  * Fail-closed architecture: In production (`isProduction = true`), Redis connectivity failures result in request denial (`fail-closed`), preventing rate limit bypass under network partitions.
* **Verdict:** **PASS (VERIFIED)**

---

### Domain 6: Spam Resistance, Anti-Flooding & Exact Deduplication
* **Cross-Transport Exact Deduplication (`NexaUniversalTransportIngest.kt`):**
  * Evaluates incoming packets against in-memory `BloomDuplicateFilter` and persistent `message_dedup_index` SQLite table.
  * Atomic reservation: first arrival marks packet as `STATUS_RESERVED` and transitions to `STATUS_PROCESSED` upon persistence.
  * Subsequent arrivals across WebSocket, BLE, or Nearby Connections are recognized as duplicates; decryption and database re-insertion are suppressed while recording transport observations in `message_transports`.
  * Collision detection: if an incoming packet reuses a known `packetId` with a different sender or altered payload digest, the status is flagged as `STATUS_CONFLICT` and dropped immediately.
* **Verdict:** **PASS (VERIFIED)**

---

### Domain 7: Delivery and Custody ACK Cryptographic Integrity
* **Delivery ACK vs Custody ACK Separation:**
  * Relay nodes only issue `PACKET_CUSTODY_ACK` (`CUSTODY_ACCEPTED` / `CUSTODY_TRANSFERRED`). Intermediate custody is marked `RELAYED` and is NEVER treated as final delivery.
  * Only the final recipient device generates `PACKET_ACK` (`ACK_DEVICE_DELIVERED` / `ACK_DEVICE_READ`).
* **Cryptographic Verification (`AckDigestHelper.kt`):**
  * Both delivery and custody ACKs include digital signatures verified against sender public keys via `Ed25519Signer.verify`.
  * Anti-spoofing in `Hub.RouteDecodedPacket`: if an incoming delivery ACK claims a recipient that does not match the authenticated session, the server drops the ACK with a security alert.
* **Verdict:** **PASS (VERIFIED)**

---

### Domain 8: Multi-Device Fan-Out and Consistency
* **Hub Routing (`backend/internal/connection/hub.go`):**
  * For online recipients with multiple registered devices, `RoutePacket` fans out incoming wire packets to all active device sessions.
  * Sender self-sync: messages sent from one device are mirrored to all sibling devices of the sender (`devId != sender.DeviceId`).
  * Revoked devices are excluded immediately from subsequent message routing.
* **Verdict:** **PASS (VERIFIED)**

---

### Domain 9: Client-Side Backup & Recovery Privacy
* **BIP-39 Vault Engine (`BackupCryptoEngine.kt`):**
  * Generates standard BIP-39 mnemonic phrases.
  * Normalizes phrases and derives a 256-bit AES master key via PBKDF2-HMAC-SHA256 (65,536 iterations).
  * Authenticated streaming encryption using AES-256-GCM with a rigid 71-byte header (`NXVB` magic, salt, IV, payload SHA-256).
  * Pre-decryption checksum validation: tampering with ciphertext or authentication tag is rejected before bundle extraction.
  * Zero server-side knowledge: cloud backups are opaque encrypted blobs.
* **Verdict:** **PASS (VERIFIED)**

---

### Domain 10: Infrastructure, Redis Buffer & PostgreSQL Isolation
* **PostgreSQL pgxpool:**
  * Prepared statements and parameterized queries prevent SQL injection across all operations.
  * Device and session tables enforce strict relational integrity with foreign keys on `account_id`.
* **Redis Buffer (`internal/queue/redis_buffer.go`):**
  * Stores offline DTN envelopes and messages under hashed routing keys with an explicit 24-hour TTL.
  * Acknowledged messages are atomically removed from recipient queues.
* **Verdict:** **PASS (VERIFIED)**

---

### Domain 11: Mobile Client Hardening
* **Android OS Auto-Backup Policy (`res/xml/backup_rules.xml` & `data_extraction_rules.xml`):**
  * Remediated: Explicitly excludes `database` (`nexa_database*`) and `sharedpref` (keystores, session tokens) from Google Cloud and device-to-device transfers.
* **Network Security Configuration (`res/xml/network_security_config.xml`):**
  * Remediated: Set `base-config cleartextTrafficPermitted="false"`. Cleartext traffic is prohibited globally; user certificate trust anchors removed to mitigate MITM proxy interception.
  * Local development loopbacks (`10.0.2.2`, `127.0.0.1`, `localhost`, `10.155.150.84`) are isolated inside explicit `<domain-config>` tags.
  * Manifest enforced with `android:usesCleartextTraffic="false"`.
* **Notification Privacy (`NexaNotificationDispatcher.kt`):**
  * Supports `LockScreenMode.HIDE_CONTENT` and `LockScreenMode.DONT_SHOW`.
  * On locked screens, notification text is replaced with a generic redacted placeholder (`"NEXA: 1 new encrypted message"`), preventing lock screen leakage.
* **Verdict:** **PASS (REMEDIATED & VERIFIED)**

---

## 3. Summary Assessment

All 11 security domains were verified against live code and passing unit/integration tests. The 6 identified baseline vulnerabilities have been fully remediated and verified with automated test assertions.
