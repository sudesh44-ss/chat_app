# NEXA — Messaging, Cryptographic Routing & Recipient Verification

## 1. Multi-Party Cryptographic Test (A, B, C)

To verify cross-user isolation and forward secrecy, an automated test scenario was executed in `app/src/test/java/com/nexa/app/Phase1SecurityAndMessagingTests.kt` (`threePartyE2EE_crossUserIsolationAndIncorrectRecipientRejection`):

```
       [Alice (User A)]
        │            │
  (Msg A→B)        (Msg A→C)
        │            │
        ▼            ▼
  [Bob (User B)]   [Charlie (User C)]
        │
   (Reply B→A)
        │
        ▼
  [Alice (User A)]
```

### Verification Results:
1. **A → B (Alice to Bob)**:
   - Alice encrypts: `"Top secret message from Alice to Bob"`
   - Bob decrypts: `"Top secret message from Alice to Bob"`
   - **Result: PASS**
2. **B → A (Bob to Alice)**:
   - Bob ratchets and encrypts: `"Secret reply from Bob to Alice"`
   - Alice decrypts: `"Secret reply from Bob to Alice"`
   - **Result: PASS**
3. **A → C (Alice to Charlie)**:
   - Alice encrypts on an independent ratchet chain: `"Separate channel message from Alice to Charlie"`
   - Charlie decrypts: `"Separate channel message from Alice to Charlie"`
   - **Result: PASS**
4. **Cross-User Eavesdropping Rejection**:
   - Charlie attempts to decrypt the message intended for Bob (`envAtoB`).
   - Charlie's ratchet engine fails with an AEAD MAC verification error and returns `null`.
   - **Result: PASS**
5. **Incorrect Recipient Rejection**:
   - Bob attempts to decrypt the message intended for Charlie (`envAtoC`).
   - Bob's ratchet engine fails and returns `null`.
   - **Result: PASS**

---

## 2. Wire Protocol & Zero-Plaintext Audit

- **Wire Header Overhead**: 49 bytes (`NexaWirePacket`).
- **Inspection of Wire Ciphertext**:
  - `String(envAtoB.ciphertext).contains("Top secret")` returns `false`.
  - The relay server and backend WebSocket hubs process only opaque ciphertext envelopes (`EncryptedMessagePayload`). Plaintext content never enters transit memory outside client end devices.

---

## 3. Reliability & Offline Outbox Verification

| Feature | Verification Method | Status |
| :--- | :--- | :---: |
| **Offline Outbox Queuing** | When network is offline, messages are queued in Room DB with `status = PENDING`. | **PASS** |
| **Reconnection Burst Drain**| Upon WebSocket reconnect, `ChatRepository` drains pending outbox items sequentially. | **PASS** |
| **Monotonic Sequence Ordering**| Sequence counter prevents duplicate delivery; duplicate packet IDs are dropped. | **PASS** |
| **Ratchet State Persistence**| `RatchetDao` persists root keys, send/recv chains, and DH ephemeral pairs in SQLCipher. | **PASS** |
