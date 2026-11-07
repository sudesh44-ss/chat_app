# NEXA Phase C — Redis Implementation Report

**Author:** Antigravity Senior Security & Systems Architect  
**Date:** 2026-11-06  
**Status:** IMPLEMENTED & VERIFIED  
**Component:** Distributed Ephemeral Caching & Reverse SMS Nonces (`nexa/wire/internal/queue`, `nexa/wire/internal/auth`)  

---

## 1. Executive Summary

Phase C addresses the single-instance in-memory limitations of NEXA's authentication and onboarding infrastructure by integrating Redis (`github.com/redis/go-redis/v9`) as a distributed ephemeral state store for:
1. **Offline Packet Buffering:** 24-hour TTL blind envelope queue (`queue.RedisBuffer`).
2. **WebSocket & REST Authentication Challenges:** Single-use atomic extraction via Lua script (`auth.RedisChallengeStore`).
3. **Reverse SMS Nonce & Verification Session Management:** 90-second TTL distributed state for multi-instance horizontal scaling (`auth.ReverseSmsService`).

---

## 2. Reverse SMS Redis Integration Architecture

### 2.1 The Multi-Instance Challenge
In NEXA's client-initiated reverse SMS flow:
1. Client device calls `POST /api/v1/auth/initiate-reverse-sms` on Server Instance A.
2. Inbound SMS carrier webhook calls `POST /api/v1/webhooks/sms-inbound` on Server Instance B (behind load balancer).
3. Client device polls `GET /api/v1/auth/verification-status` on Server Instance C.

Previously, `ReverseSmsService` stored pending nonces only in Go RAM maps (`s.pending`, `s.bySession`, `s.byPhone`). In a multi-instance deployment, Instance B would fail with `ErrNonceNotFound`.

### 2.2 Implemented Redis Data Model
When Redis is connected, `ReverseSmsService` writes verification state into Redis:

| Key Pattern | Data Type | Value | TTL |
| :--- | :--- | :--- | :--- |
| `reverse_sms:nonce:<nonce>` | `STRING` (JSON) | Serialized `PendingVerification` | 95s (initial) / 300s (verified) |
| `reverse_sms:session:<session_id>` | `STRING` | `<nonce>` | 95s (initial) / 300s (verified) |
| `reverse_sms:phone:<canonical_phone>` | `STRING` | `<nonce>` | 95s (initial) |

### 2.3 Verification Lifecycle Across Instances
1. **Initiation (`InitiateVerification`):**
   - Nonce and SessionID generated via cryptographically secure random bytes.
   - Pipelined write to Redis with 95-second TTL.
   - Fallback to in-memory maps maintained for fast local lookup.
2. **Inbound Webhook (`ProcessInboundWebhook`):**
   - If nonce is not in local memory, it is fetched from Redis key `reverse_sms:nonce:<nonce>`.
   - Single-use verification confirmed; account created or device registered in `identity.IdentityRepository`.
   - Updated record (`Status = "VERIFIED"`, `AllocatedNxID`, `SessionAuthToken`) written back to Redis with a 5-minute TTL to allow completion polling.
3. **Status Polling (`GetVerificationStatus`):**
   - Queries local memory or Redis `reverse_sms:session:<session_id>` -> `reverse_sms:nonce:<nonce>`.
   - Returns verified credentials immediately to the polling client.

---

## 3. Atomic Challenge Store (`auth.RedisChallengeStore`)

WebSocket authentication challenges require strict single-use semantics to prevent replay attacks:
- Challenge keys: `auth:challenge:<challenge_id>` and `auth:challenge:<challenge_hex>`.
- TTL: 60 seconds.
- Atomic Extraction via Lua Script:
  ```lua
  local val = redis.call('GET', KEYS[1])
  if val then
      redis.call('DEL', KEYS[1])
      if #KEYS > 1 then
          redis.call('DEL', KEYS[2])
      end
  end
  return val
  ```
- Result: Race conditions between simultaneous connections attempting to consume the same challenge are mathematically impossible.

---

## 4. Ephemeral Offline Message Queue (`queue.RedisBuffer`)

- Key: `pending:<recipient_nx_id>`.
- Push: `RPUSH` with automatic `EXPIRE` set to 24 hours (86,400s).
- Pop: `LRANGE 0 -1` followed by immediate `DEL`.
- Zero Plaintext Disk Footprint: Only encrypted E2EE ciphertext packets are buffered. Once delivered, memory is immediately released.
- Graceful Degradation: If Redis fails, `RedisBuffer` automatically falls back to an in-memory synchronized map with a warning logged.

---

## 5. Verification & Health Reporting

- In `main.go`, `buffer.GetClient()` is wired to both:
  ```go
  authHandler.SetChallengeStore(auth.NewChallengeStore(buffer.GetClient()))
  authHandler.SetRedisClient(buffer.GetClient())
  ```
- The `/health` endpoint reports:
  ```json
  {
    "status": "UP",
    "database": "CONNECTED",
    "redis": "CONNECTED",
    "mode": "Ephemeral-RAM-Only"
  }
  ```
  If Redis is down, it reports `"redis": "DEGRADED_IN_MEMORY"`.
