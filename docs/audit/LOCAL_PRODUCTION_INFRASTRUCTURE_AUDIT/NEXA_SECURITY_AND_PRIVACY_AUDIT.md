# NEXA — Security & Privacy Architecture Audit

**Audit Scope:** End-to-End Cryptography, Authentication, Device Identity, E2EE Double Ratchet, Hardware Enclaves, User Data Isolation, Rate Limiting, and Privacy Invariants  
**Repository:** `H:\chat_app`  
**Date:** November 10, 2026  
**Auditor Mode:** Strict Read-Only Source Code Inspection  

---

## 1. Executive Security Evaluation

NEXA’s security architecture reflects a sophisticated, privacy-first, zero-knowledge threat model. Key cryptographic principles (such as hardware-enclave device identity, Double Ratchet forward secrecy, Zero-Disk blind postman relay, and client-side encrypted cloud vaults) are strictly enforced in code rather than treated as optional configurations.

---

## 2. Cryptographic Architecture & Key Management

```
+---------------------------------------------------------------------------------------------------------+
|                                    NEXA CRYPTOGRAPHIC HIERARCHY                                         |
+---------------------------------------------------------------------------------------------------------+
| 1. Device Hardware Identity   | EC P-256 (secp256r1) inside Android Keystore (StrongBox / TEE)          |
|                               | - Private key non-exportable; never enters RAM                          |
|                               | - Used strictly for challenge-response auth and device binding          |
+---------------------------------------------------------------------------------------------------------+
| 2. E2EE Identity Keys         | Ed25519 (Signing) + X25519 (Diffie-Hellman Key Exchange)                |
|                               | - Root identity key binding verified by counterparty                   |
+---------------------------------------------------------------------------------------------------------+
| 3. Message Encryption (E2EE)  | Signal Double Ratchet Engine + ChaCha20-Poly1305 AEAD                   |
|                               | - Per-message forward secrecy + post-compromise security                |
|                               | - Skipped key tracking with out-of-order decryption support             |
+---------------------------------------------------------------------------------------------------------+
| 4. Network Envelope Protocol  | NXDT Envelope Protocol Version 1                                        |
|                               | - Outer sender Ed25519 signature over immutable metadata                |
|                               | - Pairwise rotating routing tokens (HKDF derived)                       |
+---------------------------------------------------------------------------------------------------------+
| 5. Backup Vault Storage       | AES-256-GCM + PBKDF2-HMAC-SHA256 (100,000 iterations)                  |
|                               | - 12-word BIP-39 mnemonic recovery passphrase                           |
+---------------------------------------------------------------------------------------------------------+
```

### Critical Cryptographic Guarantees:
- **Private Keys Never Leave the Client:** Neither the Android Keystore P-256 private key nor the Ed25519/X25519 identity and ratchet private keys are ever transmitted over the network or persisted to remote databases.
- **Relay Zero-Disk Invariant:** The Go relay server operates strictly in RAM and ephemeral 24-hour Redis buffers. The server never writes message payloads to disk and cannot decrypt E2EE payloads.

---

## 3. Threat Model & Abuse Resistance Audit

| Attack Vector | Countermeasure Implemented | Code Location | Verification Status |
| :--- | :--- | :--- | :--- |
| **OAuth Token Hijacking** | Google token alone cannot log in. Requires P-256 hardware signature or Reverse SMS. | `backend/internal/auth/handler.go:910` | **`VERIFIED`** |
| **Hardware Enclave Spoofing** | Challenge signed with ECDSA ASN.1 DER; verified against Keystore public key. | `backend/internal/auth/handler.go:1375` | **`VERIFIED`** |
| **SMS Gateway Webhook Replay** | HMAC-SHA256 signature verification + 5-minute timestamp tolerance + single-use status. | `backend/internal/auth/webhook_auth.go:30` | **`VERIFIED`** |
| **IDOR Profile Tampering** | Session token strictly dictates `session.NxID`. Caller cannot supply foreign user ID. | `backend/internal/auth/handler.go:656` | **`VERIFIED`** |
| **Contact Address Book Scraping** | Zero cloud upload of contacts. Phone discovery queries only verified & discoverable users. | `backend/internal/database/repository.go:250`| **`VERIFIED`** |
| **Message Duplication / Flooding**| Exact deduplication via `packetId` and `payloadSha256` digest cache before decryption. | `app/.../dao/MessageDedupDao.kt` | **`VERIFIED`** |
| **Mesh Passive Traffic Analysis** | Pairwise rotating routing tokens (Phase C). Relays cannot correlate recipient activity. | `app/.../core/dtn/BlindTokenGenerator.kt` | **`VERIFIED`** |
| **Memory Exhaustion (DOS)** | `http.MaxBytesReader` enforced across all REST endpoints (64KB JSON, 512KB telemetry). | `backend/cmd/server/main.go:324` | **`VERIFIED`** |
| **Prekey Starvation / Depletion** | Distributed rate limiter in Redis (max 30 prekey claims/min per IP/identity). | `backend/internal/ratelimit/distributed.go` | **`VERIFIED`** |
| **Malicious Media Attachments** | Magic byte inspection, EXIF metadata stripping, and isolated cache quarantine sandbox. | `app/.../core/security/QuarantineSandbox.kt`| **`VERIFIED`** |

---

## 4. Privacy Invariants & Telemetry Audit

1. **FCM Push Notification Privacy:**
   - Source: `backend/internal/fcm/fcm_service.go`
   - Invariant: FCM payloads contain **zero** plaintext message content, zero sender metadata, and zero cryptographic tokens.
   - Payload format: Strictly a generic wake-up notification `{ "data": { "type": "WAKE_UP", "timestamp": "..." } }`.
   - The device wakes up in the background and pulls encrypted envelopes directly over WebSocket.
2. **Contact Discovery Privacy:**
   - Source: `app/src/main/java/com/nexa/app/core/contacts/ContactMatchingRepository.kt`
   - Phone numbers from the user's local address book are queried via batch lookup; the server performs in-memory matching against verified accounts and **never stores, logs, or retains** queried numbers.
3. **Anonymous Telemetry:**
   - Source: `app/src/main/java/com/nexa/app/core/telemetry/NexaTelemetryEngine.kt`
   - Telemetry batches contain aggregated latency, hop counts, and transport counts without user identifiers, conversation IDs, or IP bindings.

---

## 5. Security Vulnerabilities & Remediation Roadmap

### Vulnerability 1: Android Release Build Minification Disabled (P1)
- **Location:** `app/build.gradle.kts` (lines 45–54)
- **Finding:** `isMinifyEnabled = false` in `buildTypes.release`.
- **Risk:** Without R8/ProGuard obfuscation, decompiling the release APK exposes proprietary Kotlin class structures, security checks, and cryptographic method boundaries.
- **Remediation:** Set `isMinifyEnabled = true` in `app/build.gradle.kts` and verify R8 rules in `proguard-rules.pro`.

### Vulnerability 2: Cleartext Exception for Local IP in Network Security Config (P1)
- **Location:** `app/src/main/res/xml/network_security_config.xml` (line 18)
- **Finding:** `<domain includeSubdomains="true">10.155.150.84</domain>` is permitted cleartext HTTP traffic in the base application resources.
- **Risk:** If a release build points to that IP address, communication occurs without TLS encryption.
- **Remediation:** Move the private LAN IP domain configuration into `app/src/debug/res/xml/network_security_config.xml`, leaving production strictly cleartext-free.

### Vulnerability 3: FCM v1 Google OAuth Token Exchange Unimplemented (P2)
- **Location:** `backend/internal/fcm/fcm_service.go` (lines 111–115)
- **Finding:** `getAccessToken()` returns an empty string; no OAuth2 token exchange with Google service account exists.
- **Risk:** When `FCM_PROJECT_ID` is set in production, calls to Google FCM API return 401 Unauthorized.
- **Remediation:** Implement Google Application Default Credentials or service account JWT signing in `FcmService`.
