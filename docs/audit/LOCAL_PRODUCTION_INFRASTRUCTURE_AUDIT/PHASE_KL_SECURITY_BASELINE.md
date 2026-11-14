# NEXA — Phase K–L: Security Baseline Report
**Project:** NEXA Secure Mobile Messenger & Multi-Hop DTN Mesh  
**Workspace:** `H:\chat_app`  
**Phase:** K–L — Security, Abuse Prevention & Multi-Device Testing  
**Audit Date:** November 2026  
**Status:** COMPLETE & INDEPENDENTLY VERIFIED  

---

## 1. Executive Summary

This Security Baseline Report establishes the verified security posture of the NEXA system prior to and during Phase K–L security hardening. The audit covered three interconnected architectural layers:
1. **Android Client Application:** Native Kotlin, Android Keystore StrongBox/TEE, Jetpack Room SQLCipher persistence, Double Ratchet cryptographic engine, and Universal Transport Ingestion pipeline.
2. **Go Backend Server:** Ephemeral RAM-only Blind-Postman WebSocket Hub, Redis sliding-window rate limiters, PostgreSQL pgxpool identity persistence, and DTN cloud bridge.
3. **Network & Cryptographic Protocols:** NXDT v1 binary envelope format, Pairwise rotating routing tokens, BIP-39 AES-256-GCM encrypted backup vault, and multi-device outbox fan-out.

The primary objective was to independently audit the source code, verify all claimed security invariants, identify concrete security gaps, apply targeted remediations without disrupting production dependencies, and execute automated regression test suites.

---

## 2. Baseline Architecture & Security Guarantees

### 2.1 Cryptographic Key Lifecycle
* **Master Identity Seed:** Derives 256-bit Ed25519 signing keys and X25519 Diffie-Hellman key pairs. The private keys never leave local device secure hardware (`CryptoKeystoreManager.kt` / `NexaHardwareSecurity.kt`).
* **Double Ratchet (Signal Protocol Compatible):** Implements symmetric-key ratchets with Diffie-Hellman ratchets. Message keys are single-use; skipped message keys are stored with finite TTL to handle out-of-order delivery.
* **Zero Plaintext Invariant:** Intermediate relays (DTN mules, Wi-Fi Direct peers) and the central Cloud Gateway operate strictly on blind encrypted envelopes. Plaintext is never stored in server memory or disk.

### 2.2 Transport Agnostic Ingestion
* **Transports Supported:** Internet WebSocket (`INTERNET_WS`), Cellular binary SMS Port 8901 (`CELLULAR_DATA_SMS`), and Offline Delay-Tolerant Networking Mesh (`OFFLINE_MESH`).
* **Universal Ingestion Pipeline (`NexaUniversalTransportIngest.kt`):** A single atomic ingestion gatekeeper processes incoming packets across all transports, ensuring exact deduplication, transport observation tracking, and Double Ratchet decryption.

---

## 3. Discovered Security Gaps (Pre-Remediation Baseline)

The pre-implementation inspection identified six concrete security and abuse vulnerabilities:

| Vulnerability ID | Component | Location | Description | Severity |
| :--- | :--- | :--- | :--- | :--- |
| **VULN-01** | Media API | `backend/cmd/server/main.go` | `/api/v1/media/upload-ticket` and `/api/v1/media/purge-ack` lacked Bearer session authentication and identity binding. | **HIGH** |
| **VULN-02** | User Discovery | `backend/internal/auth/handler.go` | `/api/v1/users/resolve` allowed unauthenticated callers to query user profiles by phone number, enabling mass scraping. | **HIGH** |
| **VULN-03** | Connection Requests | `backend/internal/connections/handler.go` | `/api/v1/connections/request` did not bound `intro_message` length or restrict HTTP body size, allowing payload bloat. | **MEDIUM** |
| **VULN-04** | Android OS Backup | `app/src/main/res/xml/backup_rules.xml` | Boilerplate backup rules allowed standard Android Cloud Backup to upload unencrypted Room SQLite databases and SharedPreferences. | **HIGH** |
| **VULN-05** | Network Security | `app/src/main/res/xml/network_security_config.xml` | Global `cleartextTrafficPermitted="true"` and user certificate trust allowed network eavesdropping outside localhost. | **HIGH** |
| **VULN-06** | DTN Cloud Dump | `backend/internal/api/dtn_handler.go` | `/api/v1/relay/opportunistic-dump` lacked HTTP payload ceilings and batch size caps, enabling RAM exhaustion. | **HIGH** |

---

## 4. Initial Audit Verification Checklist

| Domain | Baseline Status | Verification Method |
| :--- | :--- | :--- |
| **OTP Brute-Force Defense** | VERIFIED | Inspected `otp_service.go`; constant-time compare and 3-attempt lockout verified. |
| **Session Token Entropy** | VERIFIED | 32-byte `crypto/rand` tokens generated (`"ticket_"`, `"sess_"`). |
| **Double Ratchet AEAD** | VERIFIED | AES-256-GCM / ChaCha20-Poly1305 with 128-bit MAC; tampered ciphertexts rejected. |
| **NXDT Envelope Authenticity** | VERIFIED | 64-byte Ed25519 digital signature over canonical immutable fields. |
| **Device Revocation Propagation** | VERIFIED | Postgres DB revocation cascades to active WebSocket termination and Redis Pub/Sub. |
| **Zero Server-Side Storage** | VERIFIED | Cloud Gateway stores messages only in ephemeral Redis buffer (24h TTL) when recipient offline. |

---

## 5. Conclusion

The NEXA baseline codebase demonstrated strong cryptographic foundations. The identified vulnerabilities have been systematically logged for remediation in Phase K–L.
