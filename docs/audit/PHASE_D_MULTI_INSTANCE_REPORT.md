# PHASE D — MULTI-INSTANCE & WEBSOCKET EVENT REPORT

## 1. Executive Summary: Multi-Instance Status

**Status: NOT IMPLEMENTED FOR CROSS-INSTANCE NOTIFICATIONS**

In single-instance deployments, Phase D functions with 100% reliability. However, for clustered deployments with multiple backend instances (e.g. Render autoscaling), real-time WebSocket event dispatching is currently confined to the local instance where the HTTP request was handled.

---

## 2. Multi-Instance Evaluation Breakdown

| Scenario | Behavior in Current Codebase | Status |
| :--- | :--- | :---: |
| **Same-Instance Notification** | Dispatches immediately over local active WebSocket channels. | **VERIFIED** |
| **Cross-Instance Notification** | Discarded locally because target user's WebSocket is connected to another instance. | **FAILED (NOT IMPLEMENTED)** |
| **Disconnected Recipient** | Discarded in RAM (not stored in Redis offline buffer). | **VERIFIED WITH LIMITATIONS** |
| **Client Reconnection & Sync** | Client executes REST sync (`/api/v1/connections/requests/incoming`) on launch, restoring full state. | **VERIFIED** |
| **Duplicate Events** | Single local broadcast; no duplicate event generation. | **VERIFIED** |
| **Unauthorized Event Delivery** | Filtered by authenticated NX-ID matching; no leak to unauthorized connections. | **VERIFIED** |

---

## 3. Required Multi-Instance Architecture (Phase E Roadmap)

To support multi-instance cluster deployments:
1. **Redis Pub/Sub Event Bus:**
   - When a connection state change occurs on any instance, publish an event to Redis channel `nexa:events:connections`.
   - Each backend instance runs a background Redis subscriber.
   - When an instance receives a published event from Redis, it checks its local `h.sessions[recipientNxId]` and forwards the packet if the user is connected locally.
2. **Offline Event Persistence:**
   - Critical connection lifecycle events can be written to the recipient's offline Redis buffer so that upon next WebSocket connection, events stream alongside pending messages.
