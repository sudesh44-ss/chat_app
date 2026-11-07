# NEXA Phase E Step 4 — Implementation Report: Android X3DH & Double Ratchet Integration

**Status:** Completed  
**Branch:** `main`  
**Target Environment:** Development / Staging Only  
**Audit Scope:** Android Client Cryptographic Stack, Room DB v17, Protobuf Wire Serialization, Keystore/SecureStorage Key Isolation, Prekey API Integration.

---

## 1. Executive Summary

In Phase E Step 4, the Android client cryptographic architecture has been fully upgraded to conform with the official Signal Extended Triple Diffie-Hellman (X3DH) and Double Ratchet protocol specifications, backed by the Step 3 PostgreSQL Prekey APIs.

All legacy ad-hoc implementations (including non-standard HKDF expansions, inverted chain-key derivation constants, 32-byte AEAD nonce overloading, unauthenticated plaintext framing, and non-canonical TLV signatures) were audited and systematically remediated.

All 262 Android unit and integration tests (including the 18 new cryptographic security tests in `PhaseEStep4SecurityTests.kt`) pass with zero errors. The Android application cleanly compiles and packages (`assembleDebug` PASS).

---

## 2. Component Implementation Details

### 2.1 Standard X3DH Handshake Engine (`X3dhHandshakeEngine.kt`)
- **Key Separation:** Enforces strict distinction between Ed25519 signing identity keys ($IK_{sign}$ / $IKA_{sign}$) and X25519 Diffie-Hellman identity keys ($IK_{dh}$ / $IKA_{dh}$).
- **Canonical Attestation Format:**
  - **Identity Binding:** Generates and verifies canonical TLV binary structures using tag `"NXBI"`, timestamp, and account/device IDs signed by the account's Ed25519 private key.
  - **Signed Prekey (SPK):** Generates and verifies canonical TLV binary structures using tag `"NXSP"`, SPK key ID, and timestamp signed by the account's Ed25519 private key.
- **Signal-Compliant Key Agreement:**
  - Standard pre-computation buffer: $F = 0xFF^{32}$ (32 bytes of $0xFF$).
  - Shared secrets:
    - $DH_1 = \text{X25519}(IKA_{dh}, SPKB)$
    - $DH_2 = \text{X25519}(EKA, IKB_{dh})$
    - $DH_3 = \text{X25519}(EKA, SPKB)$
    - $DH_4 = \text{X25519}(EKA, OPKB)$ (omitted when OPK is exhausted).
  - Key Material: $KM = DH_1 \parallel DH_2 \parallel DH_3 [\parallel DH_4]$.
  - Input Keying Material: $IKM = F \parallel KM$.
  - Salt: $32$ zero bytes ($0x00^{32}$).
  - Info: `"NexaX3DH"`.
  - Master Secret: $SK = \text{HKDF-Extract-and-Expand}(\text{salt}=0x00^{32}, \text{ikm}=IKM, \text{info}=\text{"NexaX3DH"}, L=32)$.
- **RFC 5869 Multi-Block HKDF:**
  - Implemented compliant `hkdfExpand` supporting arbitrary output lengths $L \le 255 \times 32$ bytes via iterated HMAC-SHA256 blocks $T(1) \dots T(N)$.

---

### 2.2 Signal-Compliant Double Ratchet Engine (`DoubleRatchetEngine.kt`)
- **Session Initialization:**
  - Initiator ($A$): Generates DH ratchet keypair $DHA$, calculates $DH_{init} = \text{X25519}(DHA_{priv}, SPKB)$, derives initial root key and sender chain key via $KDF_{RK}(SK, DH_{init})$.
  - Responder ($B$): Upon receiving $A$'s initial message with header $DHA_{pub}$, calculates $DH_{init} = \text{X25519}(SPKB_{priv}, DHA_{pub})$, derives initial root key and receiver chain key via $KDF_{RK}(SK, DH_{init})$.
- **Root Key Derivation ($KDF_{RK}$):**
  - $PRK = \text{HMAC-SHA256}(\text{salt}=RK, \text{ikm}=DH_{out})$
  - Output: $\text{HKDF-Expand}(PRK, \text{"NexaRatchetRK"}, 64)$
  - Returns: $newRK = \text{Output}[0..31]$, $newCK = \text{Output}[32..63]$.
- **Symmetric Chain Key Derivation ($KDF_{CK}$):**
  - Message Key: $MK = \text{HMAC-SHA256}(CK, [0x01])$
  - Next Chain Key: $nextCK = \text{HMAC-SHA256}(CK, [0x02])$
- **12-Byte AEAD Nonce Construction:**
  - Nonce = `"NEXA"` (4 bytes, `0x4E455841`) $\parallel$ `uint64_be(messageIndex)` (8 bytes).
  - Eliminates the previous insecure design of using 32-byte ratchet keys as AEAD nonces.
- **Canonical Associated Data ($AD$):**
  - $AD = \text{"NEXA\_HDR\_V1:"} \parallel \text{sessionId} \parallel \text{":"} \parallel DHR \parallel \text{":"} \parallel \text{uint32\_be}(PN) \parallel \text{":"} \parallel \text{uint32\_be}(N)$
  - Binds ciphertext cryptographically to the session identifier, current ratchet public key, previous chain length ($PN$), and sequence index ($N$).
- **State Transition Guard:**
  - Strict atomic protection: if AEAD authentication fails, the ratchet engine discards the packet immediately and DOES NOT advance sender or receiver sequence numbers or chain keys.
- **Out-of-Order Recovery & Skipped Keys:**
  - Automatically computes and caches intermediate skipped message keys up to `maxLookahead = 2000`.
  - Persists skipped keys in SQLCipher with 14-day TTL.

---

### 2.3 AEAD Cipher Engine (`ChaChaAeadCipher.kt`)
- Direct integration with BouncyCastle's `ChaCha20Poly1305` engine.
- Supports arbitrary length Associated Data processed via `cipher.processAADBytes(...)`.
- Decryption failures or tag mismatches safely throw and return `null`, discarding corrupted data without leaking secret material.

---

### 2.4 Envelope & Wire Serialization (`EncryptedEnvelope.kt`, `NexaWirePacket.kt`, `nexa_wire.proto`)
- Protobuf schema updated with `DoubleRatchetHeader`:
  - Field 1: `ratchet_public_key` (bytes, 32)
  - Field 2: `previous_chain_length` (uint32)
  - Field 3: `message_index` (uint32)
  - Field 4: `session_id` (string)
  - Field 5: `one_time_prekey_id` (uint32)
  - Field 6: `ephemeral_public_key` (bytes)
- `EncryptedMessagePayload` upgraded:
  - Field 2: `ciphertext` (bytes)
  - Field 4: `auth_tag` (bytes, 16)
  - Field 5: `header` (`DoubleRatchetHeader`)
  - Field 6: `aead_nonce` (bytes, strictly 12)
  - Legacy fields 1 (`sender_ratchet_key`) and 3 (`nonce`) retained as deprecated migration shims.
- Kotlin wire serializer and Go backend wire codec updated with bidirectional compatibility.

---

### 2.5 Room Database Migration (`NexaDatabase.kt`, `RatchetDao.kt`)
- Database bumped from version 16 to 17.
- `MIGRATION_16_17` executed:
  - Added `previousChainLength`, `sessionId`, `remoteDeviceId` to `ratchet_sessions`.
  - Added `ratchetPublicKeyHex`, `messageIndex` to `skipped_message_keys`.
- Added multi-index DAO lookup methods for atomic retrieval and deletion of out-of-order keys.

---

### 2.6 Key Storage Isolation (`CryptoKeystoreManager.kt`, `SecureStorage.kt`)
- `CryptoKeystoreManager` now manages independent X25519 identity keypairs alongside Ed25519 signing identity keypairs.
- DH identity private keys are persisted securely in EncryptedSharedPreferences / SQLCipher (`KEY_DH_IDENTITY_PRIVATE_KEY_HEX`).
- Private keys are never exported across network boundaries or exposed in plaintext.

---

### 2.7 API Client Integration (`HandshakeApi.kt`, `ChatRepositoryImpl.kt`)
- `HandshakeApi` now communicates with Step 3 backend endpoints:
  - `GET /api/v1/users/{nxId}/prekey-bundle` (fetches authenticated identity key, SPK, and available OPK count).
  - `POST /api/v1/prekeys/claim` (claims an individual OPK atomically bound to client session).
- `ChatRepositoryImpl` encodes outgoing packets directly into `EncryptedMessagePayload` with `DoubleRatchetHeader` and unpacks incoming payloads with Associated Data validation.
