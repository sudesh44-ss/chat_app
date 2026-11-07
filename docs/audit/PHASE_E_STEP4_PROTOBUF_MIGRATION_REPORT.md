# NEXA Phase E Step 4 — Protobuf Migration & Wire Framing Report

**Schema File:** `proto/nexa_wire.proto`  
**Go Codec:** `backend/internal/wire/wire.go`  
**Kotlin Codec:** `app/src/main/java/com/nexa/app/core/proto/NexaWirePacket.kt`  
**Test Verification:** `go test -v ./internal/wire/...` (PASS), `PhaseEStep4SecurityTests.test17` (PASS)

---

## 1. Schema Upgrades

To support Signal Double Ratchet message framing and eliminate the insecure practice of overloading ratchet public keys into the AEAD nonce, `proto/nexa_wire.proto` was upgraded as follows:

```protobuf
message DoubleRatchetHeader {
    bytes ratchet_public_key = 1;      // 32-byte current X25519 ratchet public key
    uint32 previous_chain_length = 2;  // PN: length of previous sending chain
    uint32 message_index = 3;          // N: sequence number in current sending chain
    string session_id = 4;             // Unique E2EE session UUID
    uint32 one_time_prekey_id = 5;     // Optional: ID of consumed OPK
    bytes ephemeral_public_key = 6;    // Optional: Alice's EKA for initial handshake message
}

message EncryptedMessagePayload {
    bytes sender_ratchet_key = 1 [deprecated = true]; // Legacy shim
    bytes ciphertext = 2;
    bytes nonce = 3 [deprecated = true];              // Legacy shim
    bytes auth_tag = 4;
    DoubleRatchetHeader header = 5;                   // Standard Double Ratchet header
    bytes aead_nonce = 6;                             // Strictly 12-byte AEAD nonce
}
```

---

## 2. Backward-Compatibility & Deprecation Strategy

| Field | Status | Purpose / Rule |
|---|---|---|
| `EncryptedMessagePayload.header` (field 5) | **Primary** | Contains the full Signal Double Ratchet message metadata. |
| `EncryptedMessagePayload.aead_nonce` (field 6) | **Primary** | Strictly 12 bytes (`"NEXA"` $\parallel$ `uint64_be(N)`). |
| `EncryptedMessagePayload.sender_ratchet_key` (field 1) | *Deprecated* | Populated with `header.ratchet_public_key` when encoding to ensure legacy clients can still parse peer public keys. |
| `EncryptedMessagePayload.nonce` (field 3) | *Deprecated* | Populated with `aead_nonce` when encoding. If a legacy message arrives without field 6, field 3 is used as fallback. |

---

## 3. Go Backend Wire Codec (`backend/internal/wire/wire.go`)

- Added Go structs:
  ```go
  type DoubleRatchetHeader struct {
      RatchetPublicKey    []byte
      PreviousChainLength uint32
      MessageIndex        uint32
      SessionID           string
      OneTimePrekeyID     uint32
      EphemeralPublicKey  []byte
  }
  ```
- Implemented robust submessage parsing (`decodeDoubleRatchetHeader`) with length-delimited boundary checking.
- Updated `EncryptedMessagePayload.MarshalProtobuf()` to serialize field 5 (header) and field 6 (aead_nonce) alongside deprecated shims.
- Regression verified across all Go wire unit tests.

---

## 4. Android Kotlin Wire Codec (`NexaWirePacket.kt`)

- Added data class `DoubleRatchetHeader`:
  ```kotlin
  data class DoubleRatchetHeader(
      val ratchetPublicKey: ByteArray,
      val previousChainLength: Int = 0,
      val messageIndex: Int = 0,
      val sessionId: String = "",
      val oneTimePrekeyId: Long = 0,
      val ephemeralPublicKey: ByteArray = ByteArray(0)
  )
  ```
- Integrated directly with `EncryptedEnvelope`:
  - `envelope.toProtobufPayload()` builds `EncryptedMessagePayload` with `header` and `aead_nonce`.
  - `EncryptedEnvelope.fromProtobufPayload(payload)` transparently handles header extraction with fallback to legacy fields.
