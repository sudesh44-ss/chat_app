# NEXA — Phase B WebSocket Authentication Report: Protocol Handshake & Security Enforcement

**Date:** November 6, 2026  
**Status:** IMPLEMENTED & VERIFIED  
**Production Endpoint:** `wss://nexa-relay.onrender.com/ws`  

---

## 1. Handshake Protocol Specification

The NEXA WebSocket authentication protocol establishes bidirectional cryptographic verification over a single persistent TCP socket:

```
Mobile Client                                            Go Relay Server (/ws)
      │                                                           │
      │ 1. HTTP Upgrade: GET /ws?nx_id=...&device_id=...          │
      ├──────────────────────────────────────────────────────────►│
      │ 2. 101 Switching Protocols                                │
      │◄──────────────────────────────────────────────────────────┤
      │                                                           │
      │ 3. PACKET_AUTH_CHALLENGE (Type 4)                         │
      │    - 32-byte random ChallengeNonce                        │
      │    - Timestamp (unix millis)                              │
      │    - ExpiresAt (now + 60,000ms)                           │
      │◄──────────────────────────────────────────────────────────┤
      │                                                           │
      │ [Client signs canonical payload with Ed25519 private seed]│
      │                                                           │
      │ 4. PACKET_AUTH_RESPONSE (Type 5)                          │
      │    - 64-byte Ed25519 Signature                            │
      │    - 32-byte Ed25519 Public Key                           │
      │    - Client Timestamp                                     │
      ├──────────────────────────────────────────────────────────►│
      │                                                           │
      │ [Server validates: Challenge Active, Non-Expired,         │
      │  Account Exists, Device Registered & Not Revoked,         │
      │  Public Key Matches Store, Ed25519 Signature Valid]       │
      │                                                           │
      │ 5. PACKET_AUTH_SUCCESS (Type 6)                           │
      │◄──────────────────────────────────────────────────────────┤
      │                                                           │
      │ [Session Registered in Hub; Normal Encrypted Wire Routing] │
      │◄═════════════════════════════════════════════════════════►│
```

---

## 2. Canonical Challenge Signing Payload

The signing payload is strictly connection-bound and tamper-resistant:

```
dataToSign = ChallengeNonce (32 bytes) + ":<sender_nx_id>:<challenge_timestamp>"
```

The Go server additionally supports:
- Hex-encoded nonce variant: `"<hex_nonce>:<sender_nx_id>:<challenge_timestamp>"`
- Device-bound variant: `ChallengeNonce + ":<sender_nx_id>:<device_id>:<challenge_timestamp>"`

---

## 3. Security Requirements & Verification Matrix

| Security Rule | Implementation Mechanism | Verified Status |
| :--- | :--- | :--- |
| **Unique Random Nonce** | Generated via `crypto/rand.Read(32)` on each connection | **PASS** |
| **Challenge Expiry** | 60-second strict window (`ChallengeExpiry`); rejected if expired | **PASS** |
| **Single-Use Challenge** | `ChallengeNonce` cleared to `nil` immediately upon receipt of response | **PASS** |
| **Replay Defense** | Replay of intercepted challenge responses fails with "no active challenge" | **PASS** |
| **No Arbitrary Key Trust** | Rejects authentication if authoritative `identityStore` is nil or unavailable | **PASS** |
| **Unknown Identity Rejection** | Rejects connection if `nx_id` is not registered in `identityStore` | **PASS** |
| **Device Authorization** | Validates device registration, association to account, and active status | **PASS** |
| **Revoked Device Rejection** | Rejects connection if `device.IsRevoked == true` | **PASS** |
| **Cross-Account Impersonation** | Rejects connection if `device.NxID != c.NxId` | **PASS** |
| **Pre-Auth Traffic Gating** | All packet routing gated behind `c.Authenticated`; early packets drop with 4001 | **PASS** |
| **Heartbeat & Reconnection** | 30s ping/pong keepalive; exponential backoff reconnect without key churn | **PASS** |

---

## 4. Client-Side Reliability & State Management

In [`WebSocketManager.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/network/WebSocketManager.kt):
1. **Production URL Enforcement:**
   Routes exclusively to `wss://nexa-relay.onrender.com/ws` via [`ServerEndpointConfig.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/network/ServerEndpointConfig.kt).
2. **Connection State Transitions:**
   - Transport open: Remains in `ConnectionState.Connecting`.
   - `PACKET_AUTH_SUCCESS`: Transitions to `ConnectionState.Connected` and initiates 30s ping loop.
   - `PACKET_AUTH_FAILURE`: Transitions to `ConnectionState.Error` and closes with code 4001.
3. **Exponential Backoff:**
   Reconnects with jittered exponential backoff (1.5s to 30s max), preserving the user's persisted identity key without re-generation.
