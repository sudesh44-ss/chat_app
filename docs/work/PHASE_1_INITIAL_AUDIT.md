# NEXA — Phase 1: Core Security & Messaging Initial Audit Report

**Date:** 2026-11-04  
**Audit Target:** NEXA Android Client & Go Ephemeral Relay Backend  
**Scope:** Core Security, Cryptography (Double Ratchet & E2EE), WebSocket Authentication, Prekey Management, Thread Safety, Multi-Transport Consistency

---

## 1. Executive Summary & Audit Scope

A deep architectural and cryptographic audit of the NEXA messaging system was conducted across the Android codebase (`H:\chat_app\app`) and Go backend (`H:\chat_app\backend`). The system is architected as a **privacy-first, Local-First, Zero-Disk messaging platform** combining Signal-style Double Ratchet end-to-end encryption, Ed25519 identity authentication, and multi-transport delivery (Internet WebSocket, Port 8901 binary SMS, and BLE DTN mesh).

The audit revealed critical architectural and cryptographic vulnerabilities that prevent genuine end-to-end encryption between separate physical devices and permit unauthenticated socket connections at the backend.

---

## 2. Existing Implementation Status

| Component | Location | Documented Capability | Actual Implementation Status |
| :--- | :--- | :--- | :--- |
| **Double Ratchet Engine** | `core/crypto/DoubleRatchetEngine.kt` | X25519 DH Ratchet + HKDF-SHA256 KDF Chains | **Implemented but isolated**: Not wired to `ChatRepositoryImpl`. Flawed `getOrCreateSession` generates random public keys when uninitiated. |
| **Encrypted Wire Envelope** | `core/crypto/EncryptedEnvelope.kt` | 49-byte fixed overhead binary envelope | **Implemented**: Serializes `[Version(1B)][EphemeralPK(32B)][AuthTag(16B)][Ciphertext(NB)]`. |
| **Chat Repository** | `data/repository/ChatRepositoryImpl.kt` | Outbox pattern, WebSocket, Data SMS, DTN routing | **CRITICAL SECURITY FLAW**: Encrypts and decrypts outgoing and incoming messages using device-local `keystoreManager.encryptWithMasterKey()`. Cross-device decryption fails. |
| **WebSocket Relay** | `backend/internal/connection/`, `backend/cmd/server/main.go` | RAM-only ephemeral blind routing | **CRITICAL AUTH FLAW**: Accepts raw `?nx_id=...` parameter without cryptographic proof-of-identity. Any socket can claim any identity. |
| **Wire Protocol** | `core/proto/NexaWirePacket.kt`, `backend/internal/wire/wire.go` | Zero-dependency Protobuf wire encoder/decoder | **Clean match**: Identical varint and length-delimited encoding. Missing explicit auth challenge/response packet types. |
| **Prekey Management** | `backend/internal/handshake/handler.go` | X3DH Prekey storage and retrieval | **SECURITY VULNERABILITY**: Hardcoded fallback bundle `0123456789abcdef...` returned on cache miss. |
| **Digital Signatures** | `core/crypto/Ed25519Signer.kt` | BouncyCastle Ed25519 signing & verification | **Fully functional**: 32-byte seed signing and 64-byte signature verification. |
| **Hardware Keystore** | `core/keystore/HardwareKeystoreVault.kt` | AES-256-GCM hardware-backed vault | **Functional**: Wraps local keys at rest in TEE/StrongBox. |

---

## 3. Broken Integration Paths & Critical Vulnerabilities

### Vulnerability 1 (P0): Broken End-to-End Encryption (Local KeyStore Master Key Reliance)
- **File:** `app/src/main/java/com/nexa/app/data/repository/ChatRepositoryImpl.kt:142, 183, 228, 358, 444`
- **Root Cause:**
  When dispatching messages via `TransportType.INTERNET` or `TransportType.DATA_SMS`, `ChatRepositoryImpl` executes:
  ```kotlin
  val (iv, ciphertext) = keystoreManager.encryptWithMasterKey(rawPlainBytes)
  ```
  And upon receiving incoming packets:
  ```kotlin
  val decryptedBytes = keystoreManager.decryptWithMasterKey(payload.nonce, payload.ciphertext)
  ```
  `keystoreManager.encryptWithMasterKey` encrypts using the Android KeyStore AES-256-GCM key stored in the **local device's hardware enclave**. When Alice sends this packet to Bob over WebSocket or Data SMS, Bob's phone attempts to decrypt it using Bob's KeyStore key, resulting in `AEADBadTagException` and fallback to `"[Encrypted Payload]"`.
- **Required Fix:**
  Replace local KeyStore calls with `DoubleRatchetEngine.ratchetEncrypt(recipientNxId, plaintext)` and `DoubleRatchetEngine.ratchetDecrypt(senderNxId, envelope)`.

### Vulnerability 2 (P0): Insecure WebSocket Authentication (Identity Impersonation)
- **File:** `backend/cmd/server/main.go:60-83`, `backend/internal/connection/hub.go:34-43`, `app/src/main/java/com/nexa/app/core/network/WebSocketManager.kt:51-75`
- **Root Cause:**
  The server upgrades any HTTP connection to WebSocket at `/ws?nx_id=<NX-ID>` and immediately registers it into `Hub.sessions[nxId]`.
  - An attacker connecting with `ws://relay:8080/ws?nx_id=target_user` hijacks all pending and active incoming messages intended for `target_user`.
  - The client in `WebSocketManager.kt` transitions immediately to `ConnectionState.Connected` on HTTP 101 Switching Protocols without presenting an Ed25519 signature.
- **Required Fix:**
  Implement a cryptographically enforced challenge-response handshake:
  1. Upon connection, the server generates a cryptographically secure 32-byte challenge nonce with a 60-second TTL and sends `PACKET_AUTH_CHALLENGE`.
  2. The Android client signs `SHA256(challengeNonce + ":" + nxId + ":" + timestamp)` with its Ed25519 private identity key and transmits `PACKET_AUTH_RESPONSE`.
  3. The server verifies the signature against the registered public key. Only authenticated sessions are registered in the routing hub. Unauthenticated packets trigger socket termination (Close Code 4001: Unauthorized).

### Vulnerability 3 (P0): Broken Double Ratchet Session Establishment
- **File:** `app/src/main/java/com/nexa/app/core/crypto/DoubleRatchetEngine.kt:24-53`
- **Root Cause:**
  `getOrCreateSession(contactNxId, remoteDhPublicKey)` had a fatal fallback:
  ```kotlin
  val remotePub = remoteDhPublicKey ?: X25519.publicFromPrivate(X25519.generatePrivateKey())
  val initialDhSecret = X25519.computeSharedSecret(localPriv, remotePub)
  ```
  If Alice encrypts a message to Bob without a pre-existing session, Alice generates a random `remotePub` for Bob. Bob does not hold the private key for that ephemeral public key. Bob's device cannot compute the matching shared secret.
- **Required Fix:**
  Establish sessions using verified contact public keys or bilateral initialization (`initBilateralSession`), and ensure `DoubleRatchetEngine` supports symmetric initiator/responder ratchet initialization.

### Vulnerability 4 (P1): Hardcoded Fallback Prekey Bundle
- **File:** `backend/internal/handshake/handler.go:61-69`
- **Root Cause:**
  When a requested NX-ID is not present in `MemoryStore.prekeys`, `handleUsers` returns a hardcoded mock key bundle with key `0123456789abcdef...`. If an attacker requests prekeys for unregistered users, they receive known static keys, completely undermining forward secrecy and authenticity.
- **Required Fix:**
  Remove mock fallback bundle; return HTTP 404 Not Found if the prekey bundle is not registered.

### Vulnerability 5 (P1): Thread Safety & Race Conditions in Double Ratchet
- **File:** `app/src/main/java/com/nexa/app/core/crypto/DoubleRatchetEngine.kt`
- **Root Cause:**
  `ratchetEncrypt` and `ratchetDecrypt` perform read-modify-write operations on Room database entities without thread synchronization. Under rapid inbound/outbound messaging or concurrent outbox flushes, race conditions can cause:
  - Sequence number desynchronization.
  - Skipped key loss.
  - Broken ratchet chain progression.
- **Required Fix:**
  Introduce a Mutex map (keyed by `contactNxId`) in `DoubleRatchetEngine` to ensure atomic state transitions per contact session.

---

## 4. Files Requiring Modification

| Layer | File Path | Scope of Modification |
| :--- | :--- | :--- |
| **Android Crypto** | `app/.../core/crypto/DoubleRatchetEngine.kt` | Session Mutex locking, atomic ratchet stepping, robust session init. |
| **Android Crypto** | `app/.../core/crypto/EncryptedEnvelope.kt` | Serialization helpers and wire conversion. |
| **Android Proto** | `app/.../core/proto/NexaWirePacket.kt` | Add `PACKET_AUTH_CHALLENGE`, `PACKET_AUTH_RESPONSE`, `PACKET_AUTH_SUCCESS`, `PACKET_AUTH_FAILURE`. |
| **Android Network** | `app/.../core/network/WebSocketManager.kt` | Challenge-response authentication state machine with Ed25519 signing. |
| **Android Data** | `app/.../data/repository/ChatRepositoryImpl.kt` | Inject `DoubleRatchetEngine`, replace master key encryption with Double Ratchet across Internet and SMS, handle envelopes. |
| **Android App** | `app/.../NexaApplication.kt` | Inject `doubleRatchetEngine` into `ChatRepositoryImpl`, ensure identity key persistence. |
| **Android Security** | `app/.../core/security/SecureStorage.kt` | Persist Ed25519 identity keypair encrypted under hardware Master Key. |
| **Backend Wire** | `backend/internal/wire/wire.go` | Add Auth packet types and payload structures matching Android wire format. |
| **Backend Server** | `backend/cmd/server/main.go` | Integrate authentication challenge verification before session routing. |
| **Backend Conn** | `backend/internal/connection/client.go` | Auth challenge timeout (60s), message gating on authenticated status. |
| **Backend Conn** | `backend/internal/connection/hub.go` | Authenticated session registration, route gating. |
| **Backend Handshake**| `backend/internal/handshake/handler.go` | Remove hardcoded fallback prekey bundle (HTTP 404 enforcement). |

---

## 5. Implementation Priority Order

1. **P0 — Wire & Packet Protocol Alignment**: Add authentication packet types and payload structs in `NexaWirePacket.kt` (Android) and `wire.go` (Backend).
2. **P0 — Double Ratchet Engine Hardening**: Add per-contact concurrency Mutex, atomic state persistence, and symmetric session establishment in `DoubleRatchetEngine.kt`.
3. **P0 — E2EE Integration in `ChatRepositoryImpl.kt`**: Replace local KeyStore encryption with Double Ratchet encryption/decryption across both `INTERNET` and `DATA_SMS`. Update `NexaApplication.kt`.
4. **P0 — Go Backend WebSocket Authentication**: Add Ed25519 challenge-response handshake with 60-second single-use token verification and connection termination for unauthenticated clients.
5. **P0 — Android `WebSocketManager.kt` Handshake**: Implement challenge listener, Ed25519 signing, and delayed `ConnectionState.Connected` emission until authentication ACK.
6. **P1 — Backend Prekey Hardening**: Remove hardcoded bundle in `handler.go`, return 404 for unregistered prekeys.
7. **P1 — Automated Verification & Testing**: Multi-device E2EE tests (Alice & Bob), out-of-order tolerance tests, tamper tests, and backend auth test suite.
