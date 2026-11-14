# NEXA — Phase E Protobuf Wire Migration Plan & AEAD Framing

**Date:** 2026-11-07  
**Branch:** `main`  
**Status:** SPECIFICATION COMPLETE (Read-Only Design Phase)

---

## 1. Context & Architectural Motivation

In the initial implementation of `proto/nexa_wire.proto`, message payloads were defined as follows:

```protobuf
message EncryptedMessagePayload {
  int64 conversation_seq = 1;
  bytes ciphertext = 2;
  bytes nonce = 3;       // 32-byte Ephemeral Public Key in Double Ratchet
  bytes auth_tag = 4;    // 16-byte Poly1305 MAC tag
}
```

### 1.1 The Critical Design Flaws
1. **Conflation of Cryptographic Primitives:** The 32-byte X25519 ratchet public key was stored in a field called `nonce`. ChaCha20-Poly1305 (RFC 8439) strictly specifies a **96-bit (12-byte)** nonce. Storing a 32-byte key in an AEAD nonce field violates cryptographic standards and causes confusion between network framing and cipher inputs.
2. **Missing Double Ratchet Header Fields:** A standard Signal Double Ratchet header requires:
   - Ratchet Public Key ($DHR$, 32 bytes)
   - Previous Chain Length ($PN$, `uint32`)
   - Message Index ($N$, `uint32`)
   The legacy schema lacked $PN$, relying solely on an ambiguous `conversation_seq`, which breaks out-of-order skipped-key resolution when multiple ratchet steps occur between messages.
3. **Missing Associated Data ($AD$) Protection:** The header metadata was not cryptographically bound to the ciphertext, making the message vulnerable to header-tampering or session-misbinding if an attacker manipulated the wire wrapper.

---

## 2. Updated Protobuf Schema Specification

The revised schema establishes an explicit `DoubleRatchetHeader` while preserving full backward compatibility with existing wire deserializers.

```protobuf
syntax = "proto3";

package nexa.wire;
option go_package = "nexa/wire";
option java_package = "com.nexa.app.core.proto";
option java_multiple_files = true;

// Dedicated Double Ratchet header conforming to Signal specifications
message DoubleRatchetHeader {
  bytes ratchet_public_key = 1;     // Exactly 32 bytes: sender's current X25519 ratchet public key (DHR)
  uint32 previous_chain_length = 2; // PN: Number of messages in the previous sending chain
  uint32 message_index = 3;         // N: Message index in the current sending chain
  string session_id = 4;            // Canonical UUID identifying the pairwise conversation session
  int64 one_time_prekey_id = 5;     // Optional: OPK ID if this message initiates a new X3DH session
  bytes ephemeral_public_key = 6;   // Optional: 32-byte ephemeral public key (EK) for initial X3DH message
}

// Updated message envelope with explicit AEAD parameter separation
message EncryptedMessagePayload {
  int64 conversation_seq = 1 [deprecated = true]; // Legacy sequence counter (for backward compatibility)
  bytes ciphertext = 2;                           // Encrypted message payload
  bytes nonce = 3 [deprecated = true];            // Legacy 32-byte ratchet key shim (for older decoders)
  bytes auth_tag = 4;                             // Exactly 16 bytes: Poly1305 authentication tag
  DoubleRatchetHeader header = 5;                 // Full Signal-compliant Double Ratchet header
  bytes aead_nonce = 6;                           // Exactly 12 bytes: ChaCha20-Poly1305 AEAD nonce
}
```

---

## 3. AEAD Nonce Construction & Uniqueness Guarantee

### 3.1 Nonce Specification
- **Length:** Exactly 12 bytes (96 bits), adhering strictly to RFC 8439 Section 2.5.
- **Format:**
  $$\text{AEAD\_Nonce} = \text{0x4E, 0x45, 0x58, 0x41} \parallel \text{uint64\_be}(message\_index)$$
  - Bytes 0..3: ASCII magic prefix `"NEXA"` (`0x4E455841`).
  - Bytes 4..11: Big-Endian 64-bit integer representing the message index ($N$) within the current symmetric chain.

### 3.2 Mathematical Proof of Nonce Uniqueness
ChaCha20-Poly1305 security collapses if the same $(Key, Nonce)$ pair is ever used to encrypt two distinct plaintexts.
In the NEXA protocol, uniqueness is guaranteed by two independent cryptographic layers:
1. **Per-Message Symmetric Key ($MK$) Progression:**
   The Double Ratchet advances its symmetric chain key on every single message:
   $$(CK_{i+1}, MK_i) = \text{KDF}_{CK}(CK_i)$$
   Each $MK_i$ is used to encrypt **exactly one message** and is immediately wiped from memory. Because the encryption key itself is strictly single-use, key-nonce collision across messages is cryptographically impossible.
2. **Deterministic Sequence Counter ($N$):**
   Even within the scope of a single chain, $N$ monotonically increments ($0, 1, 2, \dots$), ensuring that even if a theoretical key collision occurred, the derived 12-byte nonce differs.

---

## 4. Associated Data ($AD$) Cryptographic Binding

To prevent truncation, reordering, or header-substitution attacks, the entire Double Ratchet header is authenticated as Associated Data ($AD$) during ChaCha20-Poly1305 encryption.

### 4.1 Canonical Associated Data Layout
The Associated Data byte array is deterministically formatted:

$$\text{AD} = \text{"NEXA\_HDR\_V1:"} \parallel \text{session\_id} \parallel \text{":"} \parallel DHR^{\text{pub}} \parallel \text{":"} \parallel \text{uint32\_be}(PN) \parallel \text{":"} \parallel \text{uint32\_be}(N)$$

- **Domain Prefix:** `"NEXA_HDR_V1:"` (ASCII).
- **Session Identifier:** Canonical string UUID (e.g., `urn:uuid:123e4567-e89b-12d3-a456-426614174000`).
- **Ratchet Public Key:** 32 raw bytes of $DHR^{\text{pub}}$.
- **Chain Counters:** 4 bytes big-endian each for $PN$ and $N$.

### 4.2 Encryption & Decryption Pipeline
- **Encryption:**
  $$(Ciphertext, AuthTag) = \text{ChaCha20-Poly1305-Encrypt}(MK, \text{AEAD\_Nonce}_{12}, Plaintext, AD)$$
- **Decryption:**
  $$Plaintext = \text{ChaCha20-Poly1305-Decrypt}(MK, \text{AEAD\_Nonce}_{12}, Ciphertext, AuthTag, AD)$$
  If any byte of the header (session ID, ratchet key, or sequence counter) is altered in transit, the Poly1305 authenticator tag fails verification and decryption returns `null`.

---

## 5. Backward Compatibility & Migration Strategy

To transition existing clients and the backend seamlessly without service interruption, a two-phase rollout is defined:

```
+---------------------------------------------------------------------------------------+
|                                MIGRATION PHASES                                       |
+---------------------------------------------------------------------------------------+
|  Phase 1: Dual-Population (Current Release)                                           |
|  - Sender populates BOTH legacy fields and new DoubleRatchetHeader fields.            |
|  - Modern receiver reads 'header' and 'aead_nonce'.                                   |
|  - Legacy receiver continues reading 'conversation_seq' and 'nonce'.                  |
|                                                                                       |
|  Phase 2: Strict Header Validation (Future Major Release)                             |
|  - Sender omits legacy shim fields.                                                   |
|  - Receiver rejects packets lacking 'header' (fail-closed).                           |
+---------------------------------------------------------------------------------------+
```

### 5.1 Dual-Population Mapping (Sender Side)
When constructing an `EncryptedMessagePayload`:
```kotlin
val payload = EncryptedMessagePayload.newBuilder()
    // New Signal-compliant fields:
    .setHeader(DoubleRatchetHeader.newBuilder()
        .setRatchetPublicKey(ByteString.copyFrom(localDhPublicKey))
        .setPreviousChainLength(previousChainLength)
        .setMessageIndex(messageIndex)
        .setSessionId(sessionId)
        .build())
    .setAeadNonce(ByteString.copyFrom(aeadNonce12Bytes))
    .setCiphertext(ByteString.copyFrom(ciphertext))
    .setAuthTag(ByteString.copyFrom(authTag16Bytes))
    // Legacy compatibility shims:
    .setConversationSeq(messageIndex.toLong())
    .setNonce(ByteString.copyFrom(localDhPublicKey)) // 32-byte shim
    .build()
```

### 5.2 Receiver Resolution Logic
When unpacking an inbound `EncryptedMessagePayload`:
```kotlin
val (ratchetKey, pn, n, aeadNonce) = if (payload.hasHeader()) {
    val h = payload.header
    Tuple4(
        h.ratchetPublicKey.toByteArray(),
        h.previousChainLength,
        h.messageIndex,
        payload.aeadNonce.toByteArray()
    )
} else {
    // Fallback for legacy incoming packets
    Tuple4(
        payload.nonce.toByteArray(), // 32-byte key from legacy nonce field
        0,
        payload.conversationSeq.toInt(),
        deriveLegacyNonce(payload.conversationSeq.toInt())
    )
}
```

### 5.3 Go Backend Zero-Knowledge Invariant
The Go backend treats `EncryptedMessagePayload` as an opaque binary blob inside `NexaWirePacket`. The backend inspects only:
- `packet.PacketId`
- `packet.SenderNxId`
- `packet.RecipientNxId`
- `packet.Timestamp`

Because the Go server does not inspect or validate the inner message payload, this Protobuf migration requires zero breaking schema changes on the Go routing plane.
