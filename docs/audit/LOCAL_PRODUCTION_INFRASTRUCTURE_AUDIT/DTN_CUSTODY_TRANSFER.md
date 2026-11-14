# NEXA — Multi-Hop DTN Custody Transfer Specification

**Status:** IMPLEMENTED & VERIFIED  
**Version:** 1.0  
**Scope:** Store-Carry-Forward Mesh, Custody Handover Sequence & Multi-Hop Reliability  

---

## 1. Multi-Hop Store-Carry-Forward Architecture

In disconnected, infrastructure-free environments, NEXA relies on Delay-Tolerant Networking (DTN) and physical store-carry-forward mules to transport end-to-end encrypted messages across physical distances.

Because intermediate devices may belong to strangers or untrusted network participants, the system enforces **zero-trust custody transfer**:
- Intermediate devices act as blind relays.
- Each hop explicitly accepts durable custody before relieving the upstream node.
- Sender delivery state reflects relaying progress (`RELAYED`), never premature delivery.

---

## 2. End-to-End Multi-Hop Custody Sequence

The full custody lifecycle across 4 network hops (`Sender -> Relay A -> Relay B -> Relay C -> Recipient`) executes as follows:

```
Sender                Relay A               Relay B               Relay C              Recipient
  │                      │                     │                     │                     │
  │── (1) NXDT Env ─────►│                     │                     │                     │
  │   [hop_limit=5]      │ (Persist Vault)     │                     │                     │
  │◄── (2) Custody ACK ──│                     │                     │                     │
  │   [CUSTODY_ACCEPTED] │                     │                     │                     │
  │ [Status -> RELAYED]  │                     │                     │                     │
  │                      │── (3) NXDT Env ────►│                     │                     │
  │                      │   [hop_limit=4]     │ (Persist Vault)     │                     │
  │                      │◄── (4) Custody ACK ─│                     │                     │
  │                      │   [CUSTODY_ACCEPTED]│                     │                     │
  │                      │ [CUSTODY_TRANSFER]  │                     │                     │
  │                      │                     │── (5) NXDT Env ────►│                     │
  │                      │                     │   [hop_limit=3]     │ (Persist Vault)     │
  │                      │                     │◄── (6) Custody ACK ─│                     │
  │                      │                     │   [CUSTODY_ACCEPTED]│                     │
  │                      │                     │ [CUSTODY_TRANSFER]  │                     │
  │                      │                     │                     │── (7) NXDT Env ────►│
  │                      │                     │                     │   [hop_limit=2]     │ (Match Token)
  │                      │                     │                     │                     │ (Decrypt)
  │                      │                     │                     │                     │ (Store Room)
  │                      │                     │                     │                     │
  │◄─────────────────────────── (8) Signed Delivery ACK ───────────────────────────────────│
  │                             [ACK_DEVICE_DELIVERED]
  │ [Status -> DELIVERED]
```

### Detailed Step-by-Step Breakdown

#### Step 1 & 2: Sender $\to$ Relay A
- **Action:** Sender encrypts payload via Double Ratchet, seals it inside an NXDT envelope with Bob's rotating blind token, and transmits it to Relay A over BLE / Mesh.
- **Relay A Ingestion:** Relay A verifies the Ed25519 signature, checks `hopLimit > 0` and `expiresAtEpochSec > nowSec`, decrements hop limit (`hopLimit = 4`), and writes the envelope into `dtn_envelopes`.
- **Custody Handshake:** Relay A dispatches `PACKET_CUSTODY_ACK(CUSTODY_ACCEPTED)` to Sender.
- **Sender Transition:** Sender transitions message status from `CREATED` to `RELAYED`. Single checkmark displayed in UI.

#### Step 3 & 4: Relay A $\to$ Relay B
- **Action:** Relay A encounters Relay B (e.g. via Wi-Fi Aware / Nearby Connections) and forwards the sealed envelope.
- **Relay B Ingestion:** Relay B validates envelope, decrements hop limit (`hopLimit = 3`), persists to its local `dtn_envelopes`, and emits `PACKET_CUSTODY_ACK(CUSTODY_ACCEPTED)` to Relay A.
- **Relay A Custody Transfer:** Upon receiving B's Custody ACK, Relay A updates its vault entry to `CUSTODY_TRANSFERRED` and records Relay B's node ID. Relay A is no longer required to actively advertise this envelope.

#### Step 5 & 6: Relay B $\to$ Relay C
- **Action:** Relay B carries the packet and transfers it to Relay C.
- **Relay C Ingestion:** Relay C decrements hop limit (`hopLimit = 2`), persists to vault, and sends `PACKET_CUSTODY_ACK(CUSTODY_ACCEPTED)`.
- **Relay B Custody Transfer:** Relay B updates vault entry to `CUSTODY_TRANSFERRED`.

#### Step 7: Relay C $\to$ Final Recipient (Bob)
- **Action:** Relay C comes into proximity with Bob and transmits the envelope.
- **Recipient Ingestion:**
  1. Bob's node matches the envelope's blind token against his local current or grace-period pairwise routing tokens (`isEnvelopeDestinedForMe == true`).
  2. Bob extracts the sealed ciphertext and passes it to the `DoubleRatchetEngine`.
  3. Plaintext is recovered and committed into Room `messages` table.
  4. Vault cache is cleaned up (`envelopeDao.deleteEnvelope`).

#### Step 8: Final Delivery Acknowledgment
- **Action:** Bob signs a canonical delivery digest with his Ed25519 identity key, constructing `DeliveryAckPayload(ACK_DEVICE_DELIVERED, recipientNxId = "NX-BOB", signature = ...)`.
- **Return Path:** The `PACKET_ACK` travels back to Alice (via mesh return or Cloud Gateway).
- **Sender Verification:** Alice validates the Ed25519 signature and Bob's recipient NX-ID binding.
- **Status Transition:** Alice's message status transitions from `RELAYED` to `DELIVERED`. UI updates to double checkmarks `✓✓`.

---

## 3. Durable Copy Preservation & Custody Recovery

1. **Storage Survival across Process Restarts:**
   - Intermediate custody is stored in SQLite Room (`dtn_envelopes`), which survives process death, battery exhaustion, and system restarts.
   - On startup, the relay loads all records where `custodyState == CUSTODY_ACCEPTED` and resumes forwarding.

2. **Durable Copy Retention:**
   - A custodian node keeps its copy in `CUSTODY_ACCEPTED` until downstream custody acceptance is confirmed.
   - If a downstream node disconnects before completing the Custody ACK exchange, the upstream node retains custody and will attempt transfer to another peer.

3. **Pruning & Expiration Rules:**
   - Envelopes with `custodyState == CUSTODY_TRANSFERRED` are low-priority for eviction when storage pressure occurs.
   - Expired envelopes (`expiresAtEpochSec <= currentEpochSec`) are automatically purged from the vault.
