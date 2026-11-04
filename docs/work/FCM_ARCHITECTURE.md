# NEXA Firebase Cloud Messaging (FCM) Architecture: Zero-Cost & Zero-Plaintext

---

## 1. Zero-Cost, Privacy-First Model

In traditional applications, push notification payloads carry notification titles, preview text, unread badges, and user metadata. In NEXA:

```text
===================================================================================================
                                PRIVACY & COST COMPARISON
===================================================================================================
Feature                      Standard Messenger                  NEXA Architecture
---------------------------------------------------------------------------------------------------
Push Payload Contents        "Bob: Hey, kahan ho?"               { "data": { "type": "WAKE_UP" } }
User PII Exposed to Google   Yes (Full Chat Snippet)             None (Zero Message Text)
Cryptographic Key Exposure   Possible                            Zero (Never transmitted to FCM)
Push Operating Cost          High (Paid notification tiers)      ₹0 (Standard FCM Data-Only Ping)
De-Googled ROM Support       Fails or breaks                     Supported (Direct WebSocket Fallback)
```

---

## 2. Wake-Up Lifecycle

```text
[ Sender Dispatches Packet ]
             │
             ▼
[ Relay Server Hub ]
  - Checks if recipient has active WebSocket sessions
  - Recipient is OFFLINE
             │
             ▼
[ Step 1: Buffer in Ephemeral Redis ]
  - Raw encrypted binary stored with 24-Hour strict TTL
             │
             ▼
[ Step 2: Trigger FCM Wake-Up ]
  - Query Identity Store for recipient's registered device FCM tokens
  - Dispatch HTTP v1 silent push:
    {
      "message": {
        "token": "recipient_fcm_token",
        "data": { "type": "WAKE_UP", "timestamp": "1730680000000" },
        "android": { "priority": "HIGH" }
      }
    }
             │
             ▼
[ Step 3: Client Receives Push ]
  - NexaPushWakeUpReceiver wakes in background
  - Initiates WebSocket connection: ws://relay/ws?nx_id=...
             │
             ▼
[ Step 4: Ephemeral Queue Flush ]
  - Hub detects connection, validates Ed25519 challenge
  - Flushes all pending Redis packets into active WebSocket stream
  - Packets deleted from Redis immediately upon flush
```

---

## 3. Fallback for De-Googled Devices (GrapheneOS / CalyxOS / microG)

If an Android device does not have Google Play Services installed:
1. `NexaPushManager` detects missing Play Services.
2. The client relies on `WebSocketManager` with persistent HTTP keep-alive pings (interval: 25 seconds) and foreground service prioritization when active.
3. In local mesh or offline scenarios, messages route through Delay-Tolerant Network (DTN) mesh mules or Port 8901 binary Data SMS without touching FCM.
