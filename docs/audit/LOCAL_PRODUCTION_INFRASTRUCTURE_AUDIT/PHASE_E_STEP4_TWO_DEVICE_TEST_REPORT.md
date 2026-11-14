# NEXA Phase E Step 4 — Two-Device End-to-End Integration Report

**Test:** `PhaseEStep4SecurityTests.test19_twoDeviceIntegrationE2E`  
**Execution Environment:** Isolated In-Memory SQLCipher State & Network Mocking  
**Result:** PASSED

---

## 1. Test Architecture & Lifecycle Overview

The Two-Device E2E integration test simulates a complete real-world communication lifecycle between two independent devices:
- **Device A (Alice):** Initiator device running independent Keystore, SecureStorage, and RatchetDao.
- **Device B (Bob):** Responder device running independent Keystore, SecureStorage, and RatchetDao.
- **Step 3 Prekey Backend:** Simulated API server handling prekey publishing, bundle fetching, and atomic OPK claims.

```
Device A (Alice)                                Backend                              Device B (Bob)
       |                                           |                                        |
       |                                           |<-- Publish Identity, SPK, OPKs --------|
       |                                           |                                        |
       |--- Request Prekey Bundle ---------------->|                                        |
       |<-- Return IKB, SPKB, OPK Count -----------|                                        |
       |                                           |                                        |
       |--- Atomic Claim OPK --------------------->| (Atomically marks OPK claimed)         |
       |<-- Return OPKB ---------------------------|                                        |
       |                                           |                                        |
       | (Compute X3DH Master Secret SK)           |                                        |
       | (Initialize Alice Double Ratchet)         |                                        |
       |                                           |                                        |
       |--- Initial Wire Packet (EKA, DHR_A) ----->|======== Packet Relayed ===============>|
       |                                           |                                        |
       |                                           |         (Compute X3DH Master Secret SK)|
       |                                           |           (Initialize Bob Double Ratchet)|
       |                                           |            (Decrypt Alice Initial Message)|
       |                                           |                                        |
       |<== Return Bob Reply 1 (DHR_B) ============|<--- Bob Reply Wire Packet -------------|
       |                                           |                                        |
       | (Alice executes DH Ratchet Turn)          |                                        |
       | (Alice Decrypts Bob Reply 1)              |                                        |
       |                                           |                                        |
       |=== Send Packet 0, 1, 2 ==================>| (Simulate Network Out-of-Order Delivery)
       |                                           |== Deliver Packet 2 ===================>|
       |                                           |   (Bob derives & saves MK_1 to DB)     |
       |                                           |   (Bob successfully decrypts Packet 2) |
       |                                           |== Deliver Delayed Packet 1 ===========>|
       |                                           |   (Bob retrieves MK_1 from DB)         |
       |                                           |   (Bob successfully decrypts Packet 1) |
       |                                           |   (Bob burns MK_1 from DB)             |
       |                                           |                                        |
       |=== Offline Queued Message ===============>| (Simulate Bob Offline)                 |
       |                                           | (Bob reconnects & consumes queue) ====>|
       |                                           |   (Bob successfully decrypts queue)    |
       |                                           |   (Duplicate arrival rejected)         |
```

---

## 2. Verified Test Stages & Assertions

1. **Prekey Publishing & Attestation Verification:**
   - Bob generates independent Ed25519 identity key, X25519 identity key, Signed Prekey (SPK), and One-Time Prekey (OPK).
   - Identity binding TLV and SPK TLV signatures are verified against Bob's Ed25519 identity key.
2. **Bundle Retrieval & OPK Claim:**
   - Alice retrieves Bob's prekey bundle and claims a one-time prekey through `/api/v1/prekeys/claim`.
   - The claimed OPK is bound to the initiator's account, device, and unique claim ID.
3. **X3DH Handshake Execution:**
   - Alice computes $SK = \text{X3DH}(IKA_{dh}, EKA, IKB_{dh}, SPKB, OPKB)$.
   - Alice initializes `DoubleRatchetEngine.initInitiatorSession`.
4. **Protobuf Wire Transmission:**
   - Alice encrypts `"Hello Bob from Alice via Signal X3DH + Double Ratchet!"` into `EncryptedMessagePayload` containing `DoubleRatchetHeader` with $EKA_{pub}$ and $DHA_{pub}$.
5. **Responder Reception & Ratchet Initialization:**
   - Bob receives the payload, extracts $EKA_{pub}$ and $DHA_{pub}$, derives the exact same $SK$, and initializes `DoubleRatchetEngine.initResponderSession`.
   - Decrypted message matches Alice's plaintext with zero delta.
6. **Bidirectional DH Ratchet Transitions:**
   - Bob replies to Alice: Bob's ratchet engine detects a ratchet turn, generates new local DH keypair $DHB$, computes new root key and sending chain key, and sends encrypted reply.
   - Alice receives Bob's reply, detects $DHB_{pub}$, executes DH ratchet transition, and decrypts the reply successfully.
7. **Out-of-Order Delivery Tolerance:**
   - Alice sends Packet 0, Packet 1, and Packet 2.
   - Bob receives Packet 0 (decrypted).
   - Bob receives Packet 2 before Packet 1: Bob advances receiving chain, generates skipped message key $MK_1$, stores it in `skipped_message_keys`, and decrypts Packet 2.
   - Packet 1 arrives late: Bob retrieves $MK_1$ from SQLCipher, decrypts Packet 1, and permanently deletes $MK_1$ from the database.
8. **Offline Queue & Replay Resistance:**
   - Alice encrypts a packet while Bob is offline.
   - Upon reconnecting, Bob processes the blind wire packet and decrypts it.
   - Injecting the same wire packet a second time results in immediate `null` rejection with no state regression.
