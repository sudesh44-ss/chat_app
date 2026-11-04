# NEXA Multi-Device Synchronization & Session Routing Architecture

---

## 1. Multi-Device Architectural Principles

In standard client-server messengers (e.g. Telegram), the server holds message plaintext and fans it out. In privacy-first E2EE messengers (Signal, NEXA):
1. **Private Keys are Device-Bound:** Private keys NEVER sync over the network. Each device owns its own identity and prekey bundles.
2. **Account Device Registry:** An account maps to an array of registered devices (`[]Device`).
3. **Multi-Recipient Double Ratchet:**
   - When Alice sends a message to Bob, Alice's client sends an encrypted packet addressed to each of Bob's active devices.
   - For self-sync, Alice also sends a copy encrypted for her other devices.
   - At the wire transport layer, the relay server fans out incoming packets to all active WebSocket sessions associated with the recipient NX-ID.

---

## 2. In-Memory Multi-Device Routing Table

The Go relay server manages active socket sessions using a two-level concurrency map:

```text
Hub
 └─ sessions: map[string]map[string]*ClientSession
      ├─ "NX-8419-K7M" (User Account)
      │    ├─ "dev_pixel_9"   --> *ClientSession (Online)
      │    └─ "dev_tablet_1"  --> *ClientSession (Online)
      └─ "NX-3201-P9Q"
           └─ "dev_galaxy_s24" --> *ClientSession (Online)
```

### Fan-Out Logic:
When a packet arrives for `recipientNxId`:
1. Hub reads `sessions[recipientNxId]`.
2. For each active device session, Hub queues `rawBytes` into `session.Send`.
3. If at least one device receives the packet in RAM, delivery is considered immediate.
4. If no devices are online, the packet is buffered into the ephemeral 24h Redis queue and an FCM wake-up ping is dispatched.

### Sibling Self-Sync:
When sender sends a message from `dev_pixel_9`:
1. Hub checks if sender has other connected devices under `sessions[senderNxId]`.
2. Sibling devices receive a copy of the outgoing wire packet.
3. Sibling device Room DB decrypts and records the outgoing message as `isOutgoing = true, status = 'SENT'`, keeping chat histories synchronized across devices.

---

## 3. Versioned Delta Sync Log (`device_sync_log`)

On the client side, Room Database version 11 introduces `device_sync_log`:

```sql
CREATE TABLE IF NOT EXISTS `device_sync_log` (
    `syncId` TEXT NOT NULL PRIMARY KEY,
    `sourceDeviceId` TEXT NOT NULL,
    `sequenceNumber` INTEGER NOT NULL,
    `entityType` TEXT NOT NULL,
    `encryptedPayloadHex` TEXT NOT NULL,
    `timestamp` INTEGER NOT NULL,
    `isSynced` INTEGER NOT NULL DEFAULT 0
);
```

When an action occurs (e.g., contact added, conversation deleted, message marked read):
1. Client writes an entry into `device_sync_log`.
2. Client packs a `PACKET_DEVICE_SYNC` wire packet with `DeviceSyncPayload`.
3. Wire packet is transmitted to the relay, which forwards it to sibling devices.
4. Sibling devices apply the delta state and mark the sync ID as completed.
