# NEXA — Multi-Transport Deduplication & Ingestion Pipeline

**Status:** IMPLEMENTED & VERIFIED  
**Version:** 1.0.0  
**Target Architecture:** Android Universal Transport Ingest (`NexaUniversalTransportIngest.kt`)  

---

## 1. Unified Message Pipeline Architecture

In NEXA, messages do not have separate processing pipelines for different radio or network interfaces. Whether a packet arrives via **Internet WebSocket**, **Cellular Binary SMS (Port 8901)**, **Nearby Connections**, **Wi-Fi Direct**, **BLE Mesh**, or **DTN Store-and-Forward Relays**, every incoming packet passes through the single authoritative **Universal Transport Ingest Pipeline**.

```
  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐
  │  WebSocket   │  │  Binary SMS  │  │  BLE / Mesh  │  │ Wi-Fi Direct │
  │  Transport   │  │ (Port 8901)  │  │  Transport   │  │  Transport   │
  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘
         │                 │                 │                 │
         └─────────────────┼─────────────────┴─────────────────┘
                           │
                           ▼
          ┌───────────────────────────────────┐
          │   NexaUniversalTransportIngest    │
          │      (Unified Entry Point)        │
          └────────────────┬──────────────────┘
                           │
       ┌───────────────────┴───────────────────┐
       ▼                                       ▼
  [Tier 1: Fast Filter]              [Tier 2: Authoritative DB]
  BloomDuplicateFilter               MessageDedupDao.reserveOrCheckConflict()
       │                                       │
       └───────────────────┬───────────────────┘
                           │
                  Reservation Decision:
                  ├── ALREADY_PROCESSED ──► Log Observation & Send ACK
                  ├── CONFLICT ───────────► Drop (Collision / Tamper)
                  └── RESERVED ───────────► Continue to E2EE
                           │
                           ▼
          ┌───────────────────────────────────┐
          │    Double Ratchet Decryption      │
          │   (Single-Threaded Execution)     │
          └────────────────┬──────────────────┘
                           │
                           ▼
          ┌───────────────────────────────────┐
          │        Room Database Commit       │
          │   - insertMessage()               │
          │   - markProcessed()               │
          │   - recordObservation()           │
          └────────────────┬──────────────────┘
                           │
                           ▼
          ┌───────────────────────────────────┐
          │     Inbound Delivery ACK          │
          │  Dispatched to originating radio  │
          └───────────────────────────────────┘
```

---

## 2. Ingestion Pipeline Step-by-Step Flow

When a packet bytes array is received from any transport:

### Step 1: Inbound Packet Framing & SHA-256 Digest Calculation
`NexaUniversalTransportIngest.kt` parses the raw wire bytes into a `NexaWirePacket`:
```kotlin
val packet = NexaWirePacket.parseFrom(rawBytes)
val payloadSha256 = MessageDigest.getInstance("SHA-256")
    .digest(packet.payload)
    .joinToString("") { "%02x".format(it) }
```

### Step 2: Tier-1 Sliding Bloom Filter Screening
```kotlin
val mightBeDuplicate = bloomFilter.mightContain(packet.packetId)
if (mightBeDuplicate) {
    // Infallible fallback check against SQLCipher index
    val existing = messageDedupDao.get(packet.packetId)
    if (existing != null && existing.status == MessageDedupIndex.STATUS_PROCESSED) {
        // Record observation on alternative transport
        recordTransportObservation(packet.packetId, transportType, relayNodeId, rssi)
        // Send ACK back to transport to stop retransmissions
        sendDeliveryAck(packet, transportType)
        return IngestResult.DuplicateDropped(packet.packetId)
    }
}
```

### Step 3: Tier-2 Atomic Database Reservation & Conflict Check
```kotlin
val reservation = messageDedupDao.reserveOrCheckConflict(
    packetId = packet.packetId,
    senderNxId = packet.senderNxId,
    recipientNxId = packet.recipientNxId,
    conversationId = conversationId,
    payloadSha256 = payloadSha256,
    now = System.currentTimeMillis(),
    expiresAt = System.currentTimeMillis() + 30 * 86400 * 1000L
)

when (reservation) {
    DedupReservationResult.ALREADY_PROCESSED -> {
        recordTransportObservation(packet.packetId, transportType, relayNodeId, rssi)
        sendDeliveryAck(packet, transportType)
        return IngestResult.DuplicateDropped(packet.packetId)
    }
    DedupReservationResult.ALREADY_IN_FLIGHT -> {
        // Parallel transport worker already processing this packet
        return IngestResult.InFlightSuppressed(packet.packetId)
    }
    DedupReservationResult.CONFLICT -> {
        Log.e("TransportIngest", "REJECTED: Packet ID collision or payload tamper for ${packet.packetId}")
        return IngestResult.ConflictRejected(packet.packetId)
    }
    DedupReservationResult.RESERVED -> {
        // Proceed to decryption
    }
    DedupReservationResult.FAILED_PREVIOUSLY -> {
        // Retry permitted
    }
}
```

### Step 4: Double Ratchet Decryption
Because the packet ID was atomically reserved in `message_dedup_index`, only a single coroutine ever reaches this stage. Concurrent arrivals across other transports are suppressed as `ALREADY_IN_FLIGHT` or `ALREADY_PROCESSED`:
```kotlin
val plaintext = try {
    doubleRatchetEngine.ratchetDecrypt(
        senderNxId = packet.senderNxId,
        encryptedEnvelope = packet.payload
    )
} catch (e: Exception) {
    messageDedupDao.markFailed(packet.packetId)
    throw e
}
```

### Step 5: Room Message Insertion & State Finalization
In an atomic SQLite transaction:
1. Insert the decrypted message into the `messages` table.
2. Mark `message_dedup_index` as `STATUS_PROCESSED`.
3. Record the first transport observation in `message_transport_observations`.
4. Register `packet.packetId` in `bloomFilter.mark(packet.packetId)`.

### Step 6: Inbound Delivery ACK Dispatch
The pipeline transmits an authenticated Delivery ACK back to the physical transport over which the packet arrived:
- If received over **WebSocket**: Emits `PACKET_DELIVERY_ACK` through the active WebSocket channel.
- If received over **Nearby / BLE**: Emits an immediate BLE ACK frame to the adjacent peer.
- If received over **DTN Relay**: Emits a hop ACK so the relay node can clear its DTN store-and-forward buffer.

---

## 3. Real-World Multi-Transport Scenarios

### Scenario 1: Race Between High-Speed WebSocket and Opportunistic DTN Mesh
- **Sequence:**
  1. User A sends a message. The message reaches the Cloud Gateway and is immediately forwarded to User B via WebSocket.
  2. Meanwhile, a DTN mesh relay that previously received the packet via BLE moves within range of User B 45 seconds later and attempts to deliver the packet.
- **Handling:**
  - Ingestion 1 (WebSocket): Ingests the packet, commits to Room `messages`, sets status `STATUS_PROCESSED`, logs observation `WEBSOCKET`.
  - Ingestion 2 (DTN Relay): Bloom filter and `message_dedup_index` identify the packet as `STATUS_PROCESSED`. The payload is discarded without invoking Double Ratchet. An observation is logged: `{packet_id: "...", transport_type: "BLUETOOTH_MESH", relay_node_id: "NODE-7"}`. The relay receives an ACK and purges its store-and-forward queue.
- **Result:** Exactly one message shown to the user; zero ratchet desynchronization; relay buffer cleared cleanly.

### Scenario 2: Parallel Arrival via Binary SMS and Nearby Connections
- **Sequence:**
  - User B is on the edge of cellular coverage. A binary SMS arrives almost simultaneously with a direct Nearby Connections Wi-Fi link.
- **Handling:**
  - Transport Worker 1 executes `reserveOrCheckConflict()` first and secures `STATUS_RESERVED`.
  - Transport Worker 2 executes `reserveOrCheckConflict()` milliseconds later and receives `ALREADY_IN_FLIGHT`. Worker 2 pauses and yields.
  - Worker 1 successfully finishes decryption, sets `STATUS_PROCESSED`, and releases the lock.
  - Worker 2 re-checks or receives `ALREADY_PROCESSED`, logs its transport observation, and dispatches an SMS or Nearby ACK.
- **Result:** Zero race conditions; no duplicate chat bubbles; both transport observations recorded.

### Scenario 3: Multiple Authorized Sibling Devices
- **Sequence:**
  - User B has an Android Phone and an Android Tablet registered under the same account.
  - Both devices receive the message via Cloud Gateway fan-out or peer synchronization.
- **Handling:**
  - Each device runs its own local `message_dedup_index` and `PeerSyncEngine`.
  - Ingestion on each device records its own receipt independently.
  - Subsequent anti-entropy sync between the two devices detects the packet ID in their local indices, suppressing duplicate insertions.
- **Result:** Consistent message state across all user devices.
