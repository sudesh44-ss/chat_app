# NEXA — Phase E Protocol Design: End-to-End Encrypted Session Initialization & Wire Routing

**Date:** 2026-11-07  
**Branch:** `main`  
**Phase:** Phase E — STEP 2 (Revised Protocol Design)  
**Status:** REVISED & COMPREHENSIVELY CORRECTED (Awaiting Execution Gate Approval)

---

## 1. Protocol Architecture & Standards Compliance

The NEXA End-to-End Encryption (E2EE) protocol implements an authenticated **Extended Triple Diffie-Hellman (X3DH)** initial key agreement followed by a continuous **Double Ratchet (X25519 + ChaCha20-Poly1305 + HKDF-SHA256)** state machine.

This protocol adheres strictly to peer-reviewed, industry-standard cryptographic primitives:
- **X3DH Key Agreement:** Conforms to the Signal X3DH standard on Curve25519 (RFC 7748).
- **Double Ratchet:** Conforms to the Signal Double Ratchet specification with asymmetric KDF chains and out-of-order skipped-key resolution.
- **Symmetric AEAD:** ChaCha20-Poly1305 (RFC 8439) with explicit 12-byte nonces, 16-byte Poly1305 authentication tags, and full header binding via Associated Data ($AD$).
- **Identity & Signatures:** RFC 8032 Ed25519 for account identity proofs, device registration certificates, WebSocket challenge authentication, and prekey binding attestations.
- **Key Derivation:** RFC 5869 HKDF using HMAC-SHA256 with domain-separated salt and info parameters.

```
       [ Initiator: Alice ]                                    [ Recipient: Bob ]
                │                                                       │
                ├─────── 1. POST /api/v1/prekeys/claim ────────────────►│ (via Go Backend)
                │        (Idempotent OPK Reservation)                   │
                │◄────── Returns: IK_B, SPK_B, Sig_B, OPK_B_opt ────────┤
                │                                                       │
                │ 2. X3DH Secret Computation                           │
                │    Verify Ed25519 Sig_B on SPK_B                     │
                │    DH1 = X25519(sk_ik_a, pk_spk_b)                    │
                │    DH2 = X25519(sk_ek_a, pk_ik_b)                     │
                │    DH3 = X25519(sk_ek_a, pk_spk_b)                    │
                │    DH4 = X25519(sk_ek_a, pk_opk_b) [if present]      │
                │    SK = HKDF-Extract-Expand(DH1||DH2||DH3[||DH4])     │
                │                                                       │
                │ 3. Asymmetric Ratchet Initialization                  │
                │    RK = SK, fresh DHR_A                               │
                │    (RK, CK_s) = KDF_RK(RK, X25519(sk_dhr_a, pk_spk_b))│
                │                                                       │
                │ 4. Send Initial Message (Wire Packet)                 │
                ├─────── NexaWirePacket ───────────────────────────────►│
                │        Payload: EncryptedMessagePayload               │
                │          Header: DHR_A, PN=0, N=0, EK_A, OPK_id       │
                │          AEAD_Nonce: 12-byte (NEXA || N)              │
                │          AD: Encoded Header                           │
                │          Ciphertext + 16-byte Poly1305 Tag            │
                │                                                       │ 5. Bob receives packet
                │                                                       │    Computes identical SK
                │                                                       │    Initializes CK_r
                │                                                       │    Verifies Tag & Decrypts
                │                                                       │    Purges sk_opk (PFS)
                │                                                       │
                │◄────── 6. Continuous Bidirectional Ratchet Steps ─────┤
```

---

## 2. Cryptographic Key Roles & Separation Matrix

Under no circumstances are keys derived or converted between Ed25519 and X25519. Edwards and Montgomery representations remain completely independent keypairs.

| Key Identifier | Algorithm | Lifetime | Storage Location | Protocol Function |
|---|---|---|---|---|
| **$IK_{\text{account}}$** | Ed25519 (RFC 8032) | Account lifetime | Encrypted in KeyStore | Primary account trust anchor; signs device registration certificates. |
| **$IK_{\text{device\_sign}}$** | Ed25519 (RFC 8032) | Device lifetime | Encrypted in KeyStore | Signs WebSocket challenges and prekey binding attestations. |
| **$IK_{\text{device\_dh}}$** | X25519 (RFC 7748) | Device lifetime | Encrypted in KeyStore | Permanent Diffie-Hellman key for mutual identity binding ($DH_1, DH_2$). |
| **$SPK$** | X25519 (RFC 7748) | 7 to 14 days | Private: SQLCipher; Public: Server DB | Medium-term prekey protecting offline handshakes ($DH_1, DH_3$). |
| **$OPK$** | X25519 (RFC 7748) | Ephemeral single-use | Private: SQLCipher; Public: Server DB | Single-use ephemeral key ($DH_4$) providing Perfect Forward Secrecy. |
| **$EK$** | X25519 (RFC 7748) | Single handshake | Ephemeral RAM only | Session initiator key ($DH_2, DH_3, DH_4$). Wiped immediately. |
| **$DHR$** | X25519 (RFC 7748) | Per ratchet step | Private: SQLCipher; Public: Wire Header | Continuous asymmetric ratchet providing Post-Compromise Security. |
| **$RK$** | 256-bit symmetric | Evolving | SQLCipher Room DB | Master root key updated on each DH ratchet turnaround. |
| **$CK_s, CK_r$** | 256-bit symmetric | Monotonic chain | SQLCipher Room DB | KDF chain keys advancing sequentially per message. |
| **$MK$** | 256-bit symmetric | Single message | Transient RAM only | ChaCha20-Poly1305 message key. Erased immediately. |

---

## 3. Public-Key Distribution & Idempotent OPK Reservation

### 3.1 Prekey Publication (`POST /api/v1/users/prekey`)
- **Authentication:** Mandatory HTTP `Authorization: Bearer <session_token>`.
- **Request Body:**
  ```json
  {
    "nx_id": "NX-BOB45678",
    "device_id": "dev_bob_01",
    "identity_key": "5b8f... (64-hex X25519 DH public key)",
    "identity_key_sig": "a1b2... (128-hex Ed25519 signature of IK_dh)",
    "signing_key": "9c1a... (64-hex Ed25519 signing public key)",
    "signed_prekey": "2e4a... (64-hex X25519 signed prekey)",
    "signed_prekey_id": 1,
    "signed_prekey_sig": "d5e3... (128-hex Ed25519 signature of SPK)",
    "one_time_prekeys": [
      { "id": 1, "key": "8a1b... (64-hex X25519)" },
      { "id": 2, "key": "3f9c... (64-hex X25519)" }
    ]
  }
  ```
- **Backend Validation Pipeline (Fail-Closed):**
  1. Validate Bearer token: Ensure `caller.NxID == req.nx_id` and caller device matches.
  2. Verify authoritative Ed25519 key against `public.identity_keys` / `public.profiles`.
  3. Validate $IK_{\text{dh}}$ binding signature using Canonical TLV Binary Encoding:
     $$\text{ed25519.Verify}(pk_{\text{sign}}, \text{EncodeTLV}(\text{"NXBI"}, \text{version}=1, \text{ts}, \text{nx\_id}, \text{device\_id}, pk_{\text{sign}}, pk_{\text{dh}}), sig_{ik}) == \text{true}$$
  4. Validate $SPK$ signature using Canonical TLV Binary Encoding:
     $$\text{ed25519.Verify}(pk_{\text{sign}}, \text{EncodeTLV}(\text{"NXSP"}, \text{version}=1, \text{ts}, spk\_id, \text{nx\_id}, \text{device\_id}, pk_{spk}), sig_{spk}) == \text{true}$$
  5. Upsert active $SPK$ and batch insert $OPK$ records into `public.prekeys`.

### 3.2 Idempotent Two-Phase OPK Claim (`POST /api/v1/prekeys/claim`)
To eliminate the fatal flaw of mutating `GET` requests, OPKs are reserved and consumed via an idempotent two-phase claim protocol.

- **Authentication:** Mandatory HTTP `Authorization: Bearer <session_token>`.
- **Request Body:**
  ```json
  {
    "recipient_nx_id": "NX-BOB45678",
    "claim_id": "urn:uuid:f47ac10b-58cc-4372-a567-0e02b2c3d479",
    "initiator_device_id": "dev_alice_01"
  }
  ```
- **Backend Atomic State Machine:**
  1. **Idempotency Check:** Query if `claim_id` already holds an active reservation for this caller. If found, return the identical reserved bundle (protects against network timeouts and client retries).
  2. **Row Locking & Allocation:**
     ```sql
     -- Step 1: Select available OPK using SKIP LOCKED
     SELECT id, key_id, public_key_hex FROM public.prekeys
     WHERE user_id = (SELECT id FROM public.profiles WHERE nx_id = $recipient_nx_id)
       AND key_type = 'ONE_TIME_PREKEY'
       AND is_consumed = FALSE
       AND (reserved_until IS NULL OR reserved_until < NOW())
     ORDER BY id ASC LIMIT 1
     FOR UPDATE SKIP LOCKED;

     -- Step 2: Mark reserved for 5 minutes
     UPDATE public.prekeys
     SET reserved_until = NOW() + INTERVAL '5 minutes', claim_id = $claim_id
     WHERE id = $selected_id;
     ```
  3. **Pool Depletion Handling:** If no unreserved OPKs are available, the response returns `"one_time_prekey": null`. Alice's client gracefully completes a 3-way DH handshake without $DH_4$.
  4. **Consumption & Mandatory Burn Rule:** When Alice's initial wire packet arrives, the server marks the claim and OPK as `CONSUMED`. If the reservation expires without a message arriving, the OPK is permanently marked `BURNED` and is **never** recycled or reassigned to another claim. A new claim receives a fresh OPK.

---

## 4. Exact X3DH Key Agreement Specification

### 4.1 Alice (Initiator) Derivation Steps
1. Alice fetches Bob's authenticated prekey bundle via `POST /api/v1/prekeys/claim`.
2. Alice verifies Bob's $SPK$ signature using Bob's published Ed25519 identity key. If invalid, the handshake terminates immediately.
3. Alice generates an ephemeral keypair $EK_A = (sk_{ek_a}, pk_{ek_a})$.
4. Alice computes Diffie-Hellman operations:
   $$DH_1 = \text{X25519}(sk_{ik_a}, pk_{spk_b})$$
   $$DH_2 = \text{X25519}(sk_{ek_a}, pk_{ik_b})$$
   $$DH_3 = \text{X25519}(sk_{ek_a}, pk_{spk_b})$$
   $$DH_4 = \text{X25519}(sk_{ek_a}, pk_{opk_b}) \quad (\text{if } OPK_B \text{ is present})$$
5. Alice derives the Input Keying Material ($KM$):
   $$KM = \begin{cases}
   DH_1 \parallel DH_2 \parallel DH_3 \parallel DH_4 & (\text{128 bytes, if } OPK \text{ present}) \\
   DH_1 \parallel DH_2 \parallel DH_3 & (\text{96 bytes, if } OPK \text{ absent})
   \end{cases}$$
6. Alice derives Master Shared Secret ($SK$) via Official Signal X3DH Specification:
   - Curve25519 Prefix: $F = \text{0xFF} \times 32$ (32 bytes of `0xFF`)
   - $IKM = F \parallel KM$ (160 bytes with OPK, 128 bytes without)
   - HKDF Salt: $\text{Salt} = \text{0x00} \times 32$ (32 bytes of `0x00`)
   - $\text{PRK} = \text{HMAC-SHA256}(\text{Salt}, IKM)$
   - HKDF Info: $\text{Info} = \text{"NexaX3DH"}$
   - $SK = \text{HKDF-Expand}(\text{PRK}, \text{Info}, 32)$

### 4.2 Bob (Responder) State Reconstruction
1. Bob receives the initial wire packet carrying Alice's $pk_{ik_a}$, $pk_{ek_a}$, and $OPK_{\text{id}}$.
2. Bob retrieves $sk_{opk}$ from local SQLCipher storage (or omits $DH_4$ if no OPK was claimed).
3. Bob computes identical Diffie-Hellman shared secrets:
   $$DH_1 = \text{X25519}(sk_{spk_b}, pk_{ik_a})$$
   $$DH_2 = \text{X25519}(sk_{ik_b}, pk_{ek_a})$$
   $$DH_3 = \text{X25519}(sk_{spk_b}, pk_{ek_a})$$
   $$DH_4 = \text{X25519}(sk_{opk_b}, pk_{ek_a})$$
4. Bob executes identical HKDF derivation to obtain $SK$.
5. **Immediate Forward Secrecy Purge:** Bob securely wipes and deletes $sk_{opk}$ from local SQLCipher storage immediately after deriving $SK$.

---

## 5. Double Ratchet State Transitions & AEAD Framing

### 5.1 Asymmetric Initial Ratchet States
- **Alice (Initiator):**
  - Sets Root Key $RK = SK$.
  - Generates initial DH Ratchet keypair $DHR_A = (sk_{dhr_a}, pk_{dhr_a})$.
  - Advances Root KDF:
    $$(RK, CK_s) = \text{KDF}_{RK}(RK, \text{X25519}(sk_{dhr_a}, pk_{spk_b}))$$
  - Sending Chain $CK_s$ is active. Receiving Chain $CK_r$ is uninitialized.
- **Bob (Responder):**
  - Sets Root Key $RK = SK$.
  - Receives Alice's $pk_{dhr_a}$.
  - Advances Root KDF:
    $$(RK, CK_r) = \text{KDF}_{RK}(RK, \text{X25519}(sk_{spk_b}, pk_{dhr_a}))$$
  - Receiving Chain $CK_r$ is active. Sending Chain $CK_s$ is uninitialized until Bob transmits a reply.

### 5.2 Wire Envelope & Header Structure
The binary packet framing conforms to the revised `nexa_wire.proto`:

```protobuf
message DoubleRatchetHeader {
  bytes ratchet_public_key = 1;     // 32 bytes: X25519 DHR public key
  uint32 previous_chain_length = 2; // PN: Message count in previous sending chain
  uint32 message_index = 3;         // N: Message index in current sending chain
  string session_id = 4;            // Canonical UUID of pairwise session
  int64 one_time_prekey_id = 5;     // Optional: OPK ID for initial message
  bytes ephemeral_public_key = 6;   // Optional: 32-byte EK for initial message
}

message EncryptedMessagePayload {
  int64 conversation_seq = 1 [deprecated = true];
  bytes ciphertext = 2;
  bytes nonce = 3 [deprecated = true]; // Legacy 32-byte key shim
  bytes auth_tag = 4;                  // 16-byte Poly1305 MAC tag
  DoubleRatchetHeader header = 5;      // Full Signal Double Ratchet header
  bytes aead_nonce = 6;                // Exactly 12-byte ChaCha20 nonce
}
```

### 5.3 AEAD Encryption & Nonce Derivation
1. Monotonic Chain Step:
   $$(CK_{s}^{\text{next}}, MK) = \text{KDF}_{CK}(CK_s)$$
2. AEAD Nonce Derivation (Strictly 12 bytes):
   $$\text{AEAD\_Nonce}_{12} = \text{0x4E, 0x45, 0x58, 0x41} \parallel \text{uint64\_be}(header.message\_index)$$
3. Associated Data ($AD$) Construction:
   $$\text{AD} = \text{"NEXA\_HDR\_V1:"} \parallel header.session\_id \parallel \text{":"} \parallel header.ratchet\_public\_key \parallel \text{":"} \parallel \text{uint32\_be}(PN) \parallel \text{":"} \parallel \text{uint32\_be}(N)$$
4. Ciphertext & Poly1305 Tag Generation:
   $$(Ciphertext, AuthTag) = \text{ChaCha20-Poly1305-Encrypt}(MK, \text{AEAD\_Nonce}_{12}, Plaintext, AD)$$
5. Memory Wipe: $MK$ is overwritten with zeroes immediately.

---

## 6. Offline Ciphertext Durability & Ack-Before-Deletion

### 6.1 Server-Side Durability Protocol
To ensure zero message loss while maintaining zero-knowledge server privacy:
1. **Redis Pending Buffer:** When the recipient is offline, the backend buffers the raw `NexaWirePacket` in Redis:
   - Primary Key: `pending:<recipientNxId>`
   - Retention: Ephemeral 24-hour TTL (`DefaultTTL`).
   - Staging Structure: `unacked:<recipientNxId>:<deviceId>`
2. **Reconnection Streaming:** Upon WebSocket reconnection, the server streams buffered packets to the client.
3. **Ack-Before-Deletion:** The server **never** deletes a buffered packet upon popping. A packet is only removed from Redis after the client transmits an authenticated `PACKET_ACK` (`ACK_DEVICE_DELIVERED`) containing the matching `packet_id`.
4. **Disconnection Recovery:** If the client disconnects before ACKs are received, unacknowledged packets remain staged and are automatically re-streamed upon subsequent reconnect.

### 6.2 Client-Side Deduplication & Transaction Safety
1. **Deduplication Index:** The client maintains a `received_packets_table` in SQLCipher Room DB. If a re-delivered packet arrives, the client immediately returns `ACK_DEVICE_DELIVERED` and suppresses duplicate ratchet advancement or UI rendering.
2. **Atomic State Transactions:** In [`DoubleRatchetEngine.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/crypto/DoubleRatchetEngine.kt), all database modifications (ratchet state update, message plaintext commit, skipped-key updates) occur inside a single atomic database transaction (`@Transaction`). If any failure occurs during decryption or storage, the ratchet state is rolled back cleanly.

---

## 7. Migration & Backward Compatibility Summary

1. **Dual-Population Serialization:** During Phase 1, Android clients populate both the new `header` + `aead_nonce` fields and legacy fields (`conversation_seq`, `nonce`), enabling seamless interoperation across updated and legacy builds.
2. **Backend Zero-Knowledge Plane:** The Go backend relays `NexaWirePacket` blindly based on envelope metadata (`packet_id`, `recipient_nx_id`), requiring zero modifications to intermediate routing pipelines.
3. **Database Schema:** PostgreSQL tables `public.identity_keys` and `public.prekeys` are fully leveraged with the addition of idempotent reservation fields (`claim_id`, `reserved_until`).
