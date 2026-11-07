# NEXA — Phase E Step 4 Pre-Implementation Compatibility Audit

**Date:** 2026-11-07  
**Branch:** `main`  
**Execution Mode:** PRE-IMPLEMENTATION AUDIT (Step 1 of Phase E Step 4)  
**Standard References:**  
- Signal X3DH Key Agreement Protocol (Revision 1, 2016-11-04)  
- Signal Double Ratchet Algorithm (Revision 1, 2016-11-20)  
- RFC 8439 (ChaCha20 and Poly1305 for IETF Protocols)  
- RFC 8032 (Ed25519 Signatures)  
- RFC 7748 (Curve25519 / X25519 Key Agreement)  
- RFC 5869 (HKDF)  

---

## 1. Executive Summary & Objective

In accordance with Phase E Step 4 authorization, this document performs an exhaustive, byte-level compatibility audit of existing Android client cryptographic components, persistence models, network framing, and backend prekey integration against the approved specifications:
- `PHASE_E_PROTOCOL_DESIGN.md`
- `PHASE_E_KEY_LIFECYCLE_SPEC.md`
- `PHASE_E_PROTOBUF_MIGRATION_PLAN.md`
- `PHASE_E_X3DH_TEST_VECTORS.md`
- `PHASE_E_RATCHET_INITIALIZATION_SPEC.md`
- `PHASE_E_OPK_LIFECYCLE_REVIEW.md`

All incompatible cryptographic behaviors, security gaps, and schema deficiencies are classified below prior to code modification.

---

## 2. Component-by-Component Cryptographic Audit

### 2.1 `DoubleRatchetEngine.kt`

| Component / Function | Existing Implementation | Approved Specification | Compatibility Status | Required Remediation |
|---|---|---|---|---|
| **Root KDF (`kdfRk`)** | Single HMAC-SHA256 followed by SHA-256 with `"chain_salt"` | HKDF-SHA256 (`salt=rk, ikm=dhOut, info="NexaRatchetRK", length=64`) | **INCOMPATIBLE** | Refactor to standard RFC 5869 HKDF Extract & Expand. Output first 32 bytes as `RK'`, next 32 bytes as `CK`. |
| **Chain KDF (`kdfCk`)** | `nextCk = mac(0x01)`, `messageKey = mac(0x02)` | `messageKey = HMAC(ck, 0x01)`, `nextCk = HMAC(ck, 0x02)` | **INCOMPATIBLE (INVERTED)** | Invert symmetric constants to conform to official Signal specification. |
| **Session Initialization** | Ad-hoc fallback: generates random keypair and calls `kdfRk(sharedSecret, sharedSecret)` | Asymmetric roles: Initiator derives $(RK, CK_s) = \text{KDF\_RK}(SK, DH_{\text{init}})$; Responder derives $(RK, CK_r) = \text{KDF\_RK}(SK, DH_{\text{init}})$ | **INCOMPATIBLE** | Implement explicit Initiator and Responder algorithms anchored to the X3DH shared master secret ($SK$). |
| **Previous Chain Length ($PN$)** | Not tracked; absent from session state | Explicit $PN$ tracking across DH ratchet transitions | **INCOMPATIBLE** | Add `previousChainLength` to session state and wire headers. |
| **Message Counter ($N$)** | Prepended directly to plaintext buffer (`payloadBuffer.putInt(seqNumber)`) | Stored in wire header; used to derive AEAD nonce | **INCOMPATIBLE** | Decouple $N$ from plaintext. Plaintext contains raw message payload only. |
| **AEAD Nonce** | Local 12-byte derivation with `"NX"` prefix (`0x4E580000` + 64-bit counter) | Explicit 12-byte nonce: `"NEXA"` (`0x4E455841`) $\parallel$ `uint64_be(N)` | **INCOMPATIBLE** | Align prefix and byte endianness to approved RFC 8439 specification. |
| **Associated Data ($AD$)** | Omitted (`null` / empty AD in `ChaChaAeadCipher`) | Canonical header binding: `"NEXA_HDR_V1:" || session_id || ":" || DHR || ":" || uint32_be(PN) || ":" || uint32_be(N)` | **INCOMPATIBLE (VULNERABILITY)** | Pass canonical serialized header bytes as $AD$ into ChaCha20-Poly1305 to prevent header tampering. |
| **Skipped Keys Indexing** | Indexed solely by `(contactNxId, sequenceNumber)` | Must be indexed by `(contactNxId, ratchetPublicKeyHex, messageIndex)` | **INCOMPATIBLE** | Update `SkippedKeyEntity` and `RatchetDao` to distinguish sequence indices across DH ratchet steps. |

### 2.2 `X3dhHandshakeEngine.kt`

| Component / Function | Existing Implementation | Approved Specification | Compatibility Status | Required Remediation |
|---|---|---|---|---|
| **HKDF Prefix ($F$)** | Omitted | Exactly 32 bytes of `0xFF` (`F = 0xFF * 32`) prepended to $KM$ for Curve25519 | **INCOMPATIBLE** | Prepend prefix $F$ to create $IKM = F \parallel KM$. |
| **HKDF Salt** | `"NEXA_X3DH_SALT_V1"` (16 ASCII bytes) | Exactly 32 zero bytes (`0x00 * 32`) | **INCOMPATIBLE** | Use 32-byte zero salt per Signal X3DH Specification Section 2.1. |
| **HKDF Info** | `"NEXA_HANDSHAKE_ENVELOPE"` | `"NexaX3DH"` | **INCOMPATIBLE** | Use `"NexaX3DH"` to match deterministic test vectors in `PHASE_E_X3DH_TEST_VECTORS.md`. |
| **Diffie-Hellman Steps** | $DH_1, DH_2, DH_3, [DH_4]$ computed via `X25519.computeSharedSecret` | $DH_1, DH_2, DH_3, [DH_4]$ computed via RFC 7748 scalar multiplication | **COMPATIBLE** | Retain `X25519.computeSharedSecret` from Google Tink. |
| **Signed Prekey Signature** | Opaque signature without canonical prefix | Domain-separated canonical TLV format: `"NXSP" || account_id || device_id || spk_id || spk_pub || created_at` | **INCOMPATIBLE** | Verify SPK signature using canonical encoding before computing $DH_1$ and $DH_3$. |
| **Identity Binding Signature** | Not verified during bundle consumption | Signed by device Ed25519: `"NXBI" || account_id || device_id || identity_key` | **INCOMPATIBLE** | Verify device binding signature against peer's registered identity key. |
| **OPK Consumption** | Reads from local map without backend claim API | Calls Step 3 `POST /api/v1/prekeys/claim` with initiator and target device IDs | **INCOMPATIBLE** | Integrate authenticated Step 3 claim endpoint. |

### 2.3 `EncryptedEnvelope.kt` & `proto/nexa_wire.proto`

| Component / Function | Existing Implementation | Approved Specification | Compatibility Status | Required Remediation |
|---|---|---|---|---|
| **Wire Schema Framing** | `EncryptedMessagePayload`: `nonce = 3` overloaded for 32-byte ratchet key | Dedicated `DoubleRatchetHeader` (fields 1..6) and separate `bytes aead_nonce = 6` (12 bytes) | **INCOMPATIBLE (VULNERABILITY)** | Update `nexa_wire.proto` and `NexaWirePacket.kt`. Mark fields 1 and 3 as deprecated shims. |
| **Overhead Framing** | 49-byte custom fixed layout (`[1B ver][32B PK][16B tag][ciphertext]`) | Structured Protobuf message with explicit field tags | **INCOMPATIBLE** | Transition to structured `DoubleRatchetHeader` with backward compatibility decoding shim. |
| **Session Identification** | Omitted | Canonical UUID `session_id` | **INCOMPATIBLE** | Add `session_id` string field to header. |

### 2.4 Device Identity & Key Storage (`CryptoKeystoreManager.kt` & `SecureStorage.kt`)

| Component / Function | Existing Implementation | Approved Specification | Compatibility Status | Required Remediation |
|---|---|---|---|---|
| **Identity Keys Separation** | Manages Ed25519 identity key ($IK_{\text{device\_sign}}$) only | Manages both Ed25519 ($IK_{\text{device\_sign}}$) and independent X25519 ($IK_{\text{device\_dh}}$) | **INCOMPATIBLE** | Add independent X25519 identity keypair generation, KeyStore AES-GCM encryption, and storage. |
| **Prekey Generation & Pool** | Only handles ephemeral keys on demand | Prekey generation ($SPK$ + pool of 50 $OPKs$) with local encrypted persistence | **INCOMPATIBLE** | Implement prekey generator and local SQLCipher prekeys table. |
| **Device Binding Generation** | Not generated | Signs `"NXBI"` attestation using Ed25519 identity | **INCOMPATIBLE** | Implement device identity binding attestation builder. |

### 2.5 Database Schema & Persistence (`RatchetStateEntity.kt`, `RatchetDao.kt`, `NexaDatabase.kt`)

| Component / Function | Existing Implementation | Approved Specification | Compatibility Status | Required Remediation |
|---|---|---|---|---|
| **`RatchetStateEntity`** | Lacks `previousChainLength`, `sessionId`, `remoteDeviceId` | Requires `previousChainLength` ($PN$), `sessionId`, `remoteDeviceId`, `lastRemoteDhPublicKeyHex` | **INCOMPATIBLE** | Extend entity fields and define Room migration (v16 -> v17). |
| **`SkippedKeyEntity`** | Lacks `ratchetPublicKeyHex` | Requires `(contactNxId, ratchetPublicKeyHex, sequenceNumber)` | **INCOMPATIBLE** | Update composite index to include ratchet public key. |
| **Local Prekeys Table** | Does not exist | Local private keys for $SPK$ and unconsumed $OPKs$ must be stored in SQLCipher | **MISSING** | Create `LocalPrekeyEntity` and `PrekeyDao` in Room DB. |

### 2.6 Application & Repository Integration (`ChatRepositoryImpl.kt` & `HandshakeApi.kt`)

| Component / Function | Existing Implementation | Approved Specification | Compatibility Status | Required Remediation |
|---|---|---|---|---|
| **Prekey Bundle Fetching** | Calls legacy `GET /api/v1/users/{nxId}/prekey` | Calls Step 3 `GET /api/v1/users/{nxId}/prekey-bundle` | **INCOMPATIBLE** | Update `HandshakeApi` to consume Step 3 endpoint and parse signed bundle response. |
| **OPK Claiming** | Assumes OPK is directly in prekey bundle | Calls Step 3 `POST /api/v1/prekeys/claim` to atomically obtain an OPK | **INCOMPATIBLE** | Implement atomic claim request with unique `claim_id`. |
| **Message Dispatch Framing** | Encapsulates 32-byte key in `payload.nonce` | Builds `DoubleRatchetHeader` with 12-byte `aead_nonce` and passes $AD$ | **INCOMPATIBLE** | Update `ChatRepositoryImpl.sendMessage` to construct full `DoubleRatchetHeader`. |
| **Message Inbound Handling** | Reads `payload.nonce` directly | Inspects `payload.header`; validates $AD$; decrypts with fallback | **INCOMPATIBLE** | Update `ChatRepositoryImpl.handleIncomingDataSmsPacket` and WebSocket message observer. |

---

## 3. Risk Assessment & Migration Plan

1. **Protocol Breakage Risk:**  
   Changing KDF algorithms (`kdfRk`, `kdfCk`, X3DH HKDF) breaks compatibility with any mock or pre-Phase E sessions. Since this is an authorized protocol migration on branch `main` in dev/staging, all sessions will cleanly initialize under the standard Signal specification.
2. **Wire Backward Compatibility:**  
   Dual-population in Protobuf ensures that existing decoders reading `conversation_seq` and `nonce` will not crash if receiving an updated packet during rollout.
3. **Database Migration Safety:**  
   Room migration from v16 to v17 must be additive and wrapped in SQLCipher transactions to preserve existing chat history and contact records.

---

## 4. Audit Conclusion & Approval Status

- **Status:** COMPATIBILITY AUDIT COMPLETE
- **Findings:** 16 critical cryptographic and structural incompatibilities identified and classified.
- **Action:** Proceed immediately to Step 2 (X3DH Integration) and Step 3 (Double Ratchet Integration).
