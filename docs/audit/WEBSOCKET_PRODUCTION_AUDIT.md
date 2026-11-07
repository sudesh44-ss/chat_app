# NEXA — WebSocket & Network Connectivity Production Audit

**Audit Mode:** STRICT READ-ONLY INSPECTION  
**Target:** WebSocket Relay Endpoints & Android Network Manager  
**Date:** November 2026  

---

## 1. Deployed Endpoints & Protocol

### Server Endpoints (`backend/cmd/server/main.go`)
- `/ws`: Messaging WebSocket Endpoint.
- `/ws/signaling`: WebRTC P2P Signaling Endpoint.
- Listening on `ws://0.0.0.0:10000/ws` internally on Render.
- Publicly exposed via Render's TLS termination at:
  - `wss://nexa-relay.onrender.com/ws`
  - `wss://nexa-relay.onrender.com/ws/signaling`

### Android Client Configuration
- **File:** `app/src/main/java/com/nexa/app/core/network/ServerEndpointConfig.kt`:
  ```kotlin
  const val RENDER_HOST = "nexa-relay.onrender.com"
  const val USE_PRODUCTION_CLOUD = true

  val httpBaseUrl: String
      get() = if (USE_PRODUCTION_CLOUD) "https://$RENDER_HOST" else ...

  val wsBaseUrl: String
      get() = if (USE_PRODUCTION_CLOUD) "wss://$RENDER_HOST" else ...
  ```
- **Verdict:** Android client is correctly configured with `USE_PRODUCTION_CLOUD = true` and points to `wss://nexa-relay.onrender.com`.

---

## 2. WebSocket Handshake & Authentication Protocol

### Handshake Lifecycle (`backend/internal/connection/client.go`)

```
[Android Client] ────── Connect wss://.../ws?nx_id=... ──────> [Render Server]
                                                                      │
[Android Client] <───── PACKET_AUTH_CHALLENGE (Nonce) ───────────────┤ (60s Timer)
        │
 (Signs Nonce with Ed25519)
        │
[Android Client] ────── PACKET_AUTH_RESPONSE (Sig, PubKey) ──────────>│
                                                                      │
                                                   (Verifies against Identity Store)
                                                                      │
                                                [PubKeys Do NOT Match! Disconnect]
```

### Critical Handshake Failure Root Cause
In `backend/internal/connection/client.go:173`:
```go
account, found := c.Hub.identityStore.GetAccountByNxID(c.NxId)
...
if !bytes.Equal(resp.PublicKey, expectedPubBytes) {
    log.Printf("[Session %s] Auth failed: packet public key does not match registered account public key", c.NxId)
    c.ChallengeNonce = nil
    return false
}
```
1. `expectedPubBytes` comes from `account.PublicKeyHex`, registered by Android `CryptoKeystoreManager`.
2. `resp.PublicKey` comes from `WebSocketManager.setIdentitySigner`, derived by `Ed25519Signer`.
3. Because the two keys are derived using different methods, `bytes.Equal` evaluates to `false`.
4. The server closes the socket.
5. The client enters reconnect backoff loop indefinitely.

---

## 3. Keepalive & Reconnection Strategy

- **Ping Interval:** OkHttp configured with `.pingInterval(25, TimeUnit.SECONDS)`.
- **Application Ping:** `WebSocketManager.kt` sends `PACKET_PING` every 30 seconds.
- **Render Timeout Defense:** Render disconnects inactive WebSockets after 55 seconds. The 25-second HTTP ping keepalive prevents Render from dropping active connections.
