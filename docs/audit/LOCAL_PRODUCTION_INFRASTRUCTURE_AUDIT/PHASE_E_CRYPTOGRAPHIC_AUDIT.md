# NEXA — Phase E Cryptographic & Architecture Audit (Revised)

**Date:** 2026-11-07  
**Branch:** `main`  
**Phase:** Phase E — STEP 1 & PROTOCOL RECTIFICATION GATE  
**Audit Status:** COMPLETE & VERIFIED (Read-Only Design Phase)

---

## 1. Executive Summary

Phase E addresses End-to-End Encrypted (E2EE) session initialization and wire message routing across the NEXA Android client and Go backend. Following the Phase E Protocol Design Correction Gate, this audit provides a comprehensive, verified assessment of:

1. Existing cryptographic primitives and algorithm implementations.
2. Curve segregation and identity key hierarchies (Ed25519 vs. X25519).
3. The Signal-compliant Double Ratchet and Extended Triple Diffie-Hellman (X3DH) architecture.
4. Prekey lifecycle, distribution, and idempotent allocation.
5. Zero-knowledge routing boundaries and offline ciphertext durability.

The audit confirms that the core cryptographic building blocks are present and robust. With the protocol corrections established in [PHASE_E_PROTOCOL_CORRECTIONS.md](file:///H:/chat_app/docs/audit/PHASE_E_PROTOCOL_CORRECTIONS.md), [PHASE_E_KEY_LIFECYCLE_SPEC.md](file:///H:/chat_app/docs/audit/PHASE_E_KEY_LIFECYCLE_SPEC.md), [PHASE_E_PROTOBUF_MIGRATION_PLAN.md](file:///H:/chat_app/docs/audit/PHASE_E_PROTOBUF_MIGRATION_PLAN.md), and [PHASE_E_PROTOCOL_DESIGN.md](file:///H:/chat_app/docs/audit/PHASE_E_PROTOCOL_DESIGN.md), the system design fully satisfies all cryptographic, architectural, and security mandates.

---

## 2. Cryptographic Primitives & Provider Matrix

### 2.1 Android Client (`app/src/main/java/com/nexa/app/core/crypto/`)

| Primitive | RFC / Standard | Implementation Provider | Purpose in Protocol | Audit Findings & Verifications |
|---|---|---|---|---|
| **Ed25519** | RFC 8032 | BouncyCastle (`Ed25519PrivateKeyParameters`) | Identity signatures, device certificates, prekey binding attestations, and WebSocket challenge-response. | Standard Edwards curve point multiplication verified in `Ed25519Signer.kt`. Fully segregated from X25519. |
| **X25519** | RFC 7748 | Google Tink (`subtle.X25519`) | Diffie-Hellman key exchange for X3DH handshakes and Double Ratchet turns. | High-performance scalar multiplication on Montgomery curve $y^2 = x^3 + 486662x^2 + x$. Pure random generation; no cross-curve conversion. |
| **ChaCha20-Poly1305** | RFC 8439 | JCE / BouncyCastle (`ChaChaAeadCipher.kt`) | Symmetric AEAD payload encryption for wire messages and handshake envelopes. | Strictly enforces 12-byte nonces, 16-byte Poly1305 MAC tags, and Associated Data ($AD$) binding. |
| **HKDF-SHA256** | RFC 5869 | `javax.crypto.Mac` (HmacSHA256) | Key derivation for X3DH master secrets and Double Ratchet Root/Chain keys. | Standard extract-and-expand with domain-separated salt and info parameters. |
| **AES-256-GCM** | NIST SP 800-38D | AndroidKeyStore (`CryptoKeystoreManager.kt`) | Hardware-backed Master Key envelope protecting raw private key seeds at rest. | StrongBox/TEE hardware-backed key protection. Wipes decrypted keys from RAM in `finally` blocks. |
| **SQLCipher** | AES-256-CBC | Zetetic SQLCipher (`SupportOpenHelperFactory`) | Full database encryption for Room database (`nexa_encrypted_vault.db`). | Encrypts chats, messages, ratchet session states, and unconsumed prekey private keys. |

### 2.2 Go Backend (`backend/internal/`)

| Primitive | RFC / Standard | Go Standard Library Package | Purpose in Protocol | Audit Findings & Verifications |
|---|---|---|---|---|
| **Ed25519** | RFC 8032 | `crypto/ed25519` | Authoritative verification of client WebSocket challenges (`VerifyAuthResponse`), registration proofs, and prekey binding signatures. | Constant-time verification against authoritative PostgreSQL store (`public.identity_keys`). Client-supplied arbitrary keys rejected. |
| **HMAC-SHA256** | RFC 2104 | `crypto/hmac`, `crypto/sha256` | Carrier and Reverse SMS webhook authentication (`VerifyGatewaySignature`). | Constant-time validation (`subtle.ConstantTimeCompare`). |
| **Protobuf v3** | Google Protobuf | `google.golang.org/protobuf` | Binary wire serialization (`NexaWirePacket`). | Schema-enforced framing. Zero parsing of encrypted payloads; routing is strictly blind. |

---

## 3. Cryptographic Key Lifecycle Audit

```
+---------------------------------------------------------------------------------------+
|                               KEY LIFECYCLE AUDIT                                     |
+---------------------------------------------------------------------------------------+
|  1. IK_account (Ed25519)     --> Root identity. Signs device certificates.            |
|  2. IK_device_sign (Ed25519) --> Device signing identity. Signs SPKs and auth proofs. |
|  3. IK_device_dh (X25519)    --> Device DH identity. Authenticated via Ed25519 sig.   |
|  4. SPK (X25519)             --> Medium-term prekey (14d). Signed by IK_device_sign.  |
|  5. OPK (X25519)             --> Ephemeral prekeys (pool). Consumed atomically.       |
|  6. DHR (X25519)             --> Dynamic ratchet turns. Public sent in wire header.   |
+---------------------------------------------------------------------------------------+
```

### 3.1 Curve Segregation & Cryptographic Binding
- **Finding:** Converting keys between Ed25519 and X25519 via birational equivalence poses risks of subgroup attacks and sign ambiguities.
- **Audit Verification:** The updated protocol establishes absolute algorithmic separation. Ed25519 and X25519 keys are generated independently. The X25519 identity key ($IK_{\text{device\_dh}}$) is authenticated via an explicit digital signature from $IK_{\text{device\_sign}}$ over a domain-separated string (`"NEXA_BIND_IK_V1:"`).

### 3.2 Medium-Term Signed Prekey ($SPK$) Lifecycle
- **Finding:** Prekeys uploaded to the backend must be verified to prevent malicious key-injection (MITM) attacks.
- **Audit Verification:** Senders sign $SPK$ with their device Ed25519 key. The Go backend verifies the signature against the authoritative `public.identity_keys` store before committing the prekey to `public.prekeys`. Rotation occurs every 14 days, with a 48-hour local retention window for in-flight packets.

### 3.3 One-Time Prekey ($OPK$) Allocation & Mutating GET Rectification
- **Finding:** A `GET` endpoint that marks OPKs as consumed violates HTTP idempotency and causes premature pool exhaustion from retries or network drops.
- **Audit Verification:** The revised design replaces mutating `GET` with an idempotent `POST /api/v1/prekeys/claim` protocol using client-supplied `claim_id` UUIDs and a 5-minute reservation timer (`reserved_until`). Depleted pools safely fall back to 3-way DH.

---

## 4. Message Framing & AEAD Audit

### 4.1 Nonce Separation & Header Definition
- **Finding:** Previous protobuf definition overloaded `bytes nonce` with a 32-byte X25519 ratchet public key, violating ChaCha20-Poly1305's 12-byte nonce constraint.
- **Audit Verification:**
  - Standard `DoubleRatchetHeader` is defined with fields: `ratchet_public_key` (32 bytes), `previous_chain_length` ($PN$), `message_index` ($N$), `session_id`, `one_time_prekey_id`, and `ephemeral_public_key`.
  - The AEAD nonce is strictly 12 bytes (`"NEXA"` $\parallel$ `uint64(message_index)`).
  - Uniqueness is mathematically guaranteed by the single-use message key derived per ratchet step.
  - The entire header is authenticated as Associated Data ($AD$), preventing tampering or truncation.

---

## 5. Offline Durability & Zero-Knowledge Routing Audit

### 5.1 Ack-Before-Deletion Protocol
- **Finding:** Popping pending messages from Redis upon WebSocket connection risks permanent message loss if the socket drops during streaming.
- **Audit Verification:**
  - Messages in Redis are staged (`pending:<nxId>` and `unacked:<nxId>:<deviceId>`).
  - Packets are deleted from Redis **only** after receiving an authenticated `PACKET_ACK` (`ACK_DEVICE_DELIVERED`) from the client.
  - Unacknowledged messages are safely re-streamed upon reconnect.

### 5.2 Client Transaction Safety
- **Finding:** Decrypting a message without atomically persisting the updated ratchet state can cause ratchet desynchronization.
- **Audit Verification:** In `DoubleRatchetEngine.kt`, ratchet state progression, message storage, and skipped-key pruning are wrapped inside a single atomic SQLCipher transaction (`@Transaction`). If any step fails, the ratchet state is rolled back cleanly.

---

## 6. Audit Conclusion & Gate Recommendation

All five mandatory correction gate issues have been resolved with formal, mathematically sound specifications:
1. **Identity Key Separation:** Verified independent key generation and signed binding attestations.
2. **Correct Message Header & AEAD:** Verified explicit `DoubleRatchetHeader`, 12-byte AEAD nonce, and Associated Data binding.
3. **Exact X3DH Specification:** Verified standard Diffie-Hellman ordering ($DH_1..DH_4$), canonical HKDF salt/info strings, and asymmetric initial ratchet states.
4. **Idempotent OPK Lifecycle:** Verified `POST /api/v1/prekeys/claim` state machine with 5-minute reservations and replenishment watermarks.
5. **Offline Ciphertext Durability:** Verified Redis ack-before-deletion protocol and atomic SQLCipher transactions.

**Recommendation:** The revised protocol design is cryptographically sound, backward-compatible, and ready for execution authorization. Implementation of Step 3 should commence upon user approval.
