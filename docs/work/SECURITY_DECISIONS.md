# NEXA — Architectural & Cryptographic Security Decisions

**Date:** 2026-11-04  
**Scope:** Phase 1 & Phase 2 Cryptographic Architecture, Zero-Trust Threat Model, Protocol Decisions

---

## 1. Cryptographic Primitive Selection

### 1.1. Diffie-Hellman Key Exchange: Curve25519 (X25519)
- **Selection:** `com.google.crypto.tink.subtle.X25519` (Android) and standard X25519 (RFC 7748).
- **Rationale:** Provides 128-bit security level, immune to timing side-channels, with small 32-byte public keys that easily fit in GSM SMS PDUs and compact Protobuf wire packets.
- **Role:** Ratchet key rotation and forward secrecy.

### 1.2. Symmetric Authenticated Encryption: ChaCha20-Poly1305 (IETF RFC 8439)
- **Selection:** ChaCha20 stream cipher with Poly1305 authenticator.
- **Rationale:**
  - High performance in software without dedicated hardware AES-NI instructions.
  - Immune to cache-timing attacks that afflict software AES implementations on low-cost Android chipsets.
  - Generates a 16-byte cryptographic MAC tag guaranteeing ciphertext authenticity and integrity.
- **Nonce Derivation:** 12-byte deterministic nonce derived from message sequence number and ratcheted message key (`0x4E580000` prefix + 64-bit sequence number).

### 1.3. Key Derivation Function: HKDF-SHA256
- **Selection:** HMAC-SHA256 based Key Derivation Function.
- **Chains:**
  - **Root KDF (`kdfRk`):** Derives `(nextRootKey, nextChainKey)` from root key and DH output.
  - **Chain KDF (`kdfCk`):** Derives `(nextChainKey, messageKey)` using constant inputs `0x01` and `0x02`.
- **Properties:** Guarantees backward secrecy (compromise of current message key does not reveal past message keys) and forward secrecy (past message keys cannot be derived from future keys).

### 1.4. Digital Signatures: Ed25519
- **Selection:** Pure Edwards-curve Digital Signature Algorithm over Curve25519 (RFC 8032) via BouncyCastle on Android and `crypto/ed25519` on Go backend.
- **Role:** Identity authentication, anti-impersonation, and WebSocket handshake verification.
- **Rationale:** 64-byte high-speed deterministic signatures, zero reliance on external random number generators during signing (immune to catastrophic nonce reuse bugs).

---

## 2. Universal 49-Byte Overhead Binary Envelope

The binary wire envelope is engineered with a strict 49-byte fixed overhead:
```
+---------------+------------------------+-------------------+--------------------+
|  Version (1B) |  Ephemeral PK (32B)    |  Poly1305 (16B)   |  Ciphertext (NB)   |
+---------------+------------------------+-------------------+--------------------+
```
- **GSM Data SMS Optimization:** Standard GSM SMS allows 140 bytes per PDU. With 49 bytes overhead, messages up to 91 bytes fit within a **single SMS segment**, eliminating multi-part SMS costs and out-of-order reassembly issues over cellular SMS channels.
- **Universal Transport Neutrality:** The exact same binary envelope is routed across Internet WebSocket, Port 8901 binary SMS, and BLE DTN mesh ("Encrypt Once, Route Anywhere").

---

## 3. Threat Model & Mitigation Strategies

### 3.1. Server Eavesdropping (Zero-Trust Relay)
- **Threat:** Relay server operator inspects passing packets or Redis offline buffers.
- **Mitigation:**
  - All messages are encrypted end-to-end between client devices using Double Ratchet.
  - The server only observes binary payloads containing `EncryptedEnvelope` (ephemeral public key, MAC tag, ciphertext).
  - The server holds no private keys and cannot decrypt any packet.
  - Redis buffer has a 24-hour TTL, after which undelivered packets are permanently dropped. Zero disk persistence.

### 3.2. Identity Impersonation on WebSocket Relay
- **Threat:** Malicious client connects to `ws://relay:8080/ws?nx_id=victim` to intercept incoming messages.
- **Mitigation:**
  - Ephemeral challenge-response handshake:
    1. Server generates 32-byte secure random nonce with 60-second expiry.
    2. Client must sign `nonce + ":" + nxId + ":" + timestamp` with its Ed25519 private key.
    3. Server verifies signature against client's registered public key.
    4. Sockets are only registered in the routing hub after successful cryptographic verification.
    5. Unauthenticated packets trigger immediate disconnection with code 4001.

### 3.3. Replay Attacks on WebSocket Authentication
- **Threat:** Attacker intercepts an auth response and replays it to open an unauthorized session.
- **Mitigation:**
  - Nonces are generated fresh per connection from `crypto/rand`.
  - Strict 60-second timeout.
  - **Single-use invalidation:** The challenge nonce is erased from memory immediately upon the first verification attempt. Any second attempt with the same nonce fails.
  - Connection binding: `packet.SenderNxId` must strictly equal the connection's query parameter `nx_id`.

### 3.4. Prekey Bundle Tampering & MITM
- **Threat:** Attacker queries prekeys for a victim and receives fake or fallback keys.
- **Mitigation:**
  - Removed all hardcoded fallback prekey bundles.
  - The handshake server returns HTTP 404 for any unregistered user.
  - Signed prekeys are verified against the user's permanent Ed25519 identity key.

### 3.5. Local Device Compromise & At-Rest Storage
- **Threat:** Forensic extraction of local database or device backup.
- **Mitigation:**
  - Local database is encrypted with SQLCipher using a 256-bit passphrase stored in `EncryptedSharedPreferences`.
  - Raw private keys at rest are encrypted with hardware-backed AES-256-GCM Master Keys inside Android KeyStore (TEE / StrongBox).
  - Keys in memory are zeroed (`Arrays.fill(bytes, 0)`) immediately after derivation.

### 3.6. Concurrency & Race Conditions in Ratchet State
- **Threat:** Rapid concurrent incoming or outgoing messages desynchronize sequence numbers and cause skipped key loss.
- **Mitigation:**
  - Per-contact `Mutex` locking pool in `DoubleRatchetEngine.kt`.
  - All read-modify-write operations on ratchet state entities in SQLCipher are serialized.

---

## 4. Phase 2 Security Architecture & Design Decisions

### 4.1. Deterministic Zero-PII Server-Side NX-ID Allocation
- **Decision:** Derive `NX-XXXX-XXX` deterministically from `SHA-256(Ed25519PubKey)`.
- **Security Rationale:**
  - Clients cannot claim arbitrary vanity or impersonation IDs.
  - Contains zero phone digits, email addresses, or personal information.
  - Crockford Base32 alphabet avoids ambiguity between `1`/`I`/`L` and `0`/`O`.
  - Salt-based collision resolution prevents state overwrites.

### 4.2. Salted SHA-256 OTP Storage & Brute-Force Defense
- **Decision:** Never store plaintext 6-digit OTP codes in server RAM.
- **Security Rationale:**
  - OTPs are stored strictly as `SHA-256(Salt || PlaintextCode)`.
  - Max 3 verification attempts per challenge prevents brute-force enumeration of 6-digit codes (1 in 333,333 chance of guessing).
  - 60-second cooldown and 5 OTPs/hour rate limits protect against SMS toll-fraud and carrier spam.

### 4.3. Single-Use AuthProofTicket
- **Decision:** Successful OTP or Google verification issues an ephemeral 15-minute `AuthProofTicket`.
- **Security Rationale:**
  - Registration endpoint `/api/v1/auth/register` requires a valid ticket.
  - Tickets are destroyed immediately upon registration (anti-replay).
  - Prevents automated account creation without verified carrier or OAuth proofs.

### 4.4. Privacy-Preserving Zero-Plaintext FCM Push Wake-Up
- **Decision:** FCM push notifications contain strictly generic `{ "data": { "type": "WAKE_UP" } }`.
- **Security Rationale:**
  - Google servers never see message text, conversation IDs, participant identities, or key exchanges.
  - Push signal merely wakes the app to pull pending packets directly from the relay server over TLS.
  - Zero push cost (₹0 operating cost).

### 4.5. Multi-Device Cryptographic Key Isolation
- **Decision:** Private keys are strictly hardware-bound to each individual device and never synced across devices.
- **Security Rationale:**
  - Compromise of one device (e.g. tablet) does not expose the private keys of the user's primary phone.
  - Each device maintains independent Double Ratchet sessions with contacts.
  - Sibling device outgoing sync is encrypted and routed via relay fan-out without server plaintext inspection.

### 4.6. Room Database Non-Destructive Migration Invariant
- **Decision:** Completely removed `.fallbackToDestructiveMigration()`.
- **Security Rationale:**
  - User chat history, mutual contact trust states, and Double Ratchet cryptographic states are irreplaceable.
  - All schema upgrades are executed via continuous, explicit SQL migrations (`MIGRATION_1_2` through `MIGRATION_10_11`).
