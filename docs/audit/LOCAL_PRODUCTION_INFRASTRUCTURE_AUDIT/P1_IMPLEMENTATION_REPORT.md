# NEXA — P1 Critical Remediation Implementation Report

**Date:** 2026-11-07  
**Branch:** `main`  
**Commit Context:** P1 Critical Remediation  
**Status:** IMPLEMENTED & FULLY VERIFIED (100% Tests Passing)

---

## 1. Executive Summary

The P1 Critical Remediation phase addressed fundamental security, multi-instance horizontal scalability, and distributed abuse prevention across the NEXA Go backend while preserving end-to-end encryption (E2EE), zero-disk telemetry architectures, and backward compatibility with the Android client.

Prior to this phase, session/device revocations were purely database-level flag flips that left active WebSocket connections intact indefinitely; connection and presence events were localized to single instance memory; rate limiting was tracked in volatile process memory vulnerable to horizontal bypass; and incoming packets from blocked or unconnected peers relied solely on client filtering.

All P1 requirements have been implemented and verified with exhaustive regression suites across Go (`go test -count=1 ./...`) and Android (`gradlew testDebugUnitTest`).

---

## 2. Gate Verification (Step 0)

Before executing P1, all P0 baseline invariants were re-verified:
1. **PostgreSQL Fail-Closed Invariant:** Production startup fails immediately (`log.Fatalf`) if `pgPool == nil` or PostgreSQL connection is unconfigured.
2. **Redis Fail-Closed Invariant:** Production startup aborts immediately if Redis buffer fails or operates in degraded mode (`buffer.IsDegraded()`).
3. **Mandatory Registration Proof-of-Possession:** `handleRegister` in production strictly requires Ed25519 `signature_hex` verifying `(phone + ":" + nxId + ":" + publicKeyHex + ":" + deviceId)`.
4. **Single-Use Reverse SMS Session Token:** `record.Status` transitions atomically to `CONSUMED` upon the first read of `session_auth_token` in `GetVerificationStatus`, rejecting duplicate consumption.

---

## 3. Step 1: Active WebSocket Revocation

### 3.1 Problem Statement
When a user logged out (`/api/v1/auth/logout`) or revoked a compromised device (`/api/v1/devices/{deviceID}/revoke`), the backend updated storage but did not terminate live WebSocket connections. An attacker possessing an active connection on a stolen or revoked device could continue sending and receiving packets until connection drop or server restart.

### 3.2 Implementation Details
1. **Local Eviction in Hub (`internal/connection/pubsub.go`):**
   - Added `Hub.RevokeDeviceSessionLocal(nxId string, deviceId string, reason string) int`.
   - Acquires `sessionsMu.Lock()`.
   - Locates target `ClientSession` matching `(nxId, deviceId)` or all sessions of `nxId` if `deviceId == "*"`.
   - Dispatches close frame with WebSocket Close Code `4001` (`CloseWithUnauthorized`) containing the audit reason.
   - Cleans up associated session entry in `h.sessions` and removes any registered blind tokens in `h.blindTokens`.
   - Closes the session's `Send` channel with safe recover guards.

2. **Wiring into Auth Handlers (`internal/auth/handler.go`):**
   - Defined `SessionRevoker` interface:
     ```go
     type SessionRevoker interface {
         RevokeDeviceSession(nxId string, deviceId string, reason string) error
     }
     ```
   - Integrated into `handleLogout`: Resolves session by token, extracts `(NxID, DeviceID)`, and calls `h.revoker.RevokeDeviceSession`.
   - Integrated into `handleDeviceAction` (`/api/v1/devices/{deviceID}/revoke`): Checks caller ownership and calls `h.revoker.RevokeDeviceSession(targetDev.NxID, deviceID, "Device revoked by user")`.
   - Wired in `main.go` via `authHandler.SetSessionRevoker(hub)`.

---

## 4. Step 2: Redis Pub/Sub for Multi-Instance Events

### 4.1 Problem Statement
In a horizontally scaled deployment with multiple Render or containerized backend instances, WebSockets are distributed across arbitrary instances. If Alice is connected to Instance A and Bob is on Instance B:
- Alice's connection requests, accepts, rejects, and blocks were lost because Instance B was unaware of events broadcast on Instance A.
- Revoking a device on Instance A had zero effect on Instance B where the device's WebSocket was actually connected.

### 4.2 Implementation Details
1. **Cluster Event Specification (`internal/connection/pubsub.go`):**
   - Channel: `nexa:events:cluster`
   - Data structure:
     ```go
     type ClusterEvent struct {
         EventID          string      `json:"event_id"`
         Type             string      `json:"type"`
         OriginInstanceID string      `json:"origin_instance_id"`
         NxID             string      `json:"nx_id"`
         DeviceID         string      `json:"device_id,omitempty"`
         Payload          interface{} `json:"payload,omitempty"`
         Timestamp        int64       `json:"timestamp"`
     }
     ```
2. **Instance Identity & Loop Prevention:**
   - Every `Hub` generates a cryptographically random instance identifier on startup (`inst_<timestamp>_<randomHex>`).
   - All published cluster events carry `OriginInstanceID: h.instanceID`.
   - When subscriber receives an event where `OriginInstanceID == h.instanceID`, the event is dropped immediately. This eliminates redundant local processing and ping-pong event storms.
3. **Cluster Subscriber (`Hub.StartClusterSubscriber`):**
   - Runs background listener on `nexa:events:cluster`.
   - On `device:revoked` or `session:revoked`: Invokes `RevokeDeviceSessionLocal(evt.NxID, evt.DeviceID, reason)`.
   - On `connection:*`, `contact:*`, `user:*`: Invokes `broadcastLocal(evt.NxID, evt.Type, evt.Payload)` to deliver real-time push frames to any local WebSocket sessions.
4. **Dual-Dispatch in `BroadcastEventToNxID`:**
   - Directly enqueues to local sessions in RAM for zero latency.
   - Publishes to `nexa:events:cluster` to distribute to all other cluster instances.

---

## 5. Step 3: Redis-Backed Distributed Rate Limiting

### 5.1 Problem Statement
Rate limiters in `connections.Handler` and `ReverseSmsService` used in-memory Go maps. In multi-instance deployments:
- Attackers could bypass user discovery (30/min) or connection request (10/min) limits by rotating requests across cluster instances.
- Inbound reverse SMS initiation limits (5/15min per IP, 3/24h per user) could be exhausted or brute-forced.

### 5.2 Implementation Details
1. **Package `internal/ratelimit` (`internal/ratelimit/limiter.go`):**
   - Atomic sliding window via Redis sorted set executed through Lua script:
     ```lua
     local key = KEYS[1]
     local now = tonumber(ARGV[1])
     local window = tonumber(ARGV[2])
     local limit = tonumber(ARGV[3])
     local member = ARGV[4]

     local clearBefore = now - window
     redis.call('ZREMRANGEBYSCORE', key, '-inf', clearBefore)
     local currentCount = redis.call('ZCARD', key)

     if currentCount < limit then
         redis.call('ZADD', key, now, member)
         redis.call('PEXPIRE', key, window)
         return 1
     else
         return 0
     end
     ```
   - **Production Fail-Closed Policy:** If `isProduction == true`, any Redis connection failure or missing client returns `false` with `ErrLimiterDown` to prevent bypass attacks.
   - **Dev/Test Fallback:** If `isProduction == false` and Redis is absent, gracefully falls back to an in-memory `MemoryLimiter` to allow seamless offline test runs.
2. **Integration Touchpoints:**
   - `connections.Handler.handleDiscover`: Key `ratelimit:discover:<nxId>:<ip>`, limit 30 / 1 minute.
   - `connections.Handler.handleSendRequest`: Key `ratelimit:conn_req:<nxId>`, limit 10 / 1 minute.
   - `ReverseSmsService.InitiateVerification`:
     - IP rate limit: `ratelimit:reversesms:ip:<clientIP>`, limit 5 / 15 minutes.
     - User rate limit: `ratelimit:reversesms:user:<googleUid|deviceId>`, limit 3 / 24 hours.

---

## 6. Step 4: Server-Side Block Enforcement

### 6.1 Problem Statement
Prior to remediation, blocked users could still dispatch wire packets (`PACKET_MESSAGE`, `PACKET_TYPING`, `PACKET_SIGNALING`) through the Hub, relying on recipient Android clients to discard them.

### 6.2 Implementation Details
1. **Wire-Level Enforcement in `Hub.RouteDecodedPacket` (`internal/connection/hub.go`):**
   - Consults authoritative `ConnectionValidator.IsBlocked(ctx, sender.NxId, recipient.NxId)`.
   - If blocked in either direction, packet is dropped at the edge and never relayed or queued offline.
   - Consults `ConnectionValidator.IsConnected(ctx, sender.NxId, recipient.NxId)` for `PACKET_MESSAGE` and `PACKET_TYPING`: Strangers cannot relay messages or typing indicators unless a mutual connection is active.
2. **Test Validation:**
   - Verified via `TestWireMessageRoutingAuthorization` in `internal/connection/auth_test.go` confirming packet drops and zero memory/offline leakage.

---

## 7. Step 5: Twilio Signature Adapter

### 7.1 Problem Statement & Strategy
While the live deployment currently targets domestic GSM gateway forwarding via HMAC-SHA256, Twilio uses a custom RFC 2104 HMAC-SHA1 algorithm sorting POST parameters alphabetically.

### 7.2 Implementation Details
1. **Standalone Verifier (`internal/auth/twilio_adapter.go`):**
   - Implemented `ValidateTwilioSignature(authToken, requestURL, params, signature) bool`.
   - Sorts POST parameters alphabetically by key.
   - Appends keys and values to the base URL string.
   - Computes HMAC-SHA1 using `authToken` and encodes as Base64.
   - Executes constant-time comparison via `subtle.ConstantTimeCompare`.
2. **Test Coverage (`internal/auth/twilio_adapter_test.go`):**
   - Tested canonical Twilio test vectors, tampered signatures, missing parameters, and empty auth tokens. All tests passing.

---

## 8. Summary of Modified and Created Files

| File | Status | Description |
|---|---|---|
| `backend/internal/connection/pubsub.go` | **CREATED** | Redis Pub/Sub cluster events, local & remote session revocation |
| `backend/internal/connection/pubsub_test.go` | **CREATED** | Unit tests for local revocation, echo prevention, remote revocation, and event routing |
| `backend/internal/ratelimit/limiter.go` | **CREATED** | Redis Lua sliding window limiter + fail-closed production policy + memory fallback |
| `backend/internal/ratelimit/limiter_test.go` | **CREATED** | Concurrency, expiration, dev fallback, and production fail-closed tests |
| `backend/internal/auth/twilio_adapter.go` | **CREATED** | Canonical Twilio HMAC-SHA1 webhook signature validator |
| `backend/internal/auth/twilio_adapter_test.go` | **CREATED** | Test vectors and tamper tests for Twilio signature validator |
| `backend/internal/connection/hub.go` | **MODIFIED** | Added cluster publisher, instance ID, Redis client integration, and safe Send close |
| `backend/internal/connection/client.go` | **MODIFIED** | Added safe recover and nil checks to `CloseWithUnauthorized` |
| `backend/internal/auth/handler.go` | **MODIFIED** | Added `SessionRevoker` to logout and device revoke endpoints |
| `backend/internal/auth/reverse_sms.go` | **MODIFIED** | Integrated distributed rate limiter into `InitiateVerification` |
| `backend/internal/connections/handler.go` | **MODIFIED** | Replaced memory rate maps with distributed sliding window rate limiter |
| `backend/internal/queue/redis_buffer.go` | **MODIFIED** | Added `GetClient() *redis.Client` to `Buffer` interface |
| `backend/cmd/server/main.go` | **MODIFIED** | Wired `SessionRevoker` and initialized `StartClusterSubscriber` |

---

## 9. Conclusion

Phase P1 remediations successfully close all critical gaps identified in the audit. The backend is now fully capable of coordinating multi-instance clusters, revoking sessions actively at the wire level, and enforcing cryptographic and abuse controls across instances.
