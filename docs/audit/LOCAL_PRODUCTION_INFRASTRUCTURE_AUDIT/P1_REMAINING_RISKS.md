# NEXA — P1 Remaining Risks & Deployment Safeguards

**Date:** 2026-11-07  
**Branch:** `main`  
**Evaluation:** Residual Risk Assessment Post-P1 Remediation

---

## 1. Executive Summary

Phase P1 has systematically resolved all high-severity runtime vulnerabilities identified in earlier audits:
- Active WebSocket connections are now evicted in real time upon session logout or device revocation.
- Horizontally distributed instances now communicate state changes seamlessly via Redis Pub/Sub.
- Sliding window rate limiting is enforced atomically in Redis and fails closed in production.
- Blocked peers and unconnected strangers are rejected at the edge before message relay.

This document identifies remaining operational risks, deferred live carrier items, and safeguards recommended prior to executing Phase E.

---

## 2. Deferred Items & Operational Risks

### 2.1 Twilio Live Carrier Deployment (Deferred)
- **Status:** Standalone HMAC-SHA1 validation utility implemented (`ValidateTwilioSignature`) and tested in `internal/auth/twilio_adapter_test.go`.
- **Reason for Deferral:** The primary production carrier integration is domestic Indian GSM gateway forwarding using HMAC-SHA256 (`VerifyGatewaySignature`). Twilio is maintained as an alternate adapter.
- **Risk Assessment:** Low. The standalone validator conforms strictly to Twilio's specification. If Twilio is selected as the primary carrier in the future, it can be plugged into the webhook router by calling `ValidateTwilioSignature`.

### 2.2 Redis Connection Partition in High-Throughput Scenarios
- **Scenario:** If Redis encounters severe network partitioning or connection exhaustion under spike load.
- **Current Behavior:** The distributed rate limiter fails closed in production (`ErrLimiterDown`), blocking unverified abuse.
- **Operational Risk:** Users attempting discovery or reverse SMS initiation during a Redis outage will receive `HTTP 503 / 429` errors until Redis connectivity recovers.
- **Mitigation:** Ensure managed Redis has automated failover (multi-AZ) and adequate connection pool settings (`maxActive`, `idleTimeout`).

### 2.3 Carrier SMS Delivery Latency
- **Scenario:** The user initiates reverse verification, generating a 90-second challenge. In poor signal conditions, the carrier SMS might take >90 seconds to reach the gateway webhook.
- **Mitigation:**
  - Android client displays a countdown radar and allows retry after expiration.
  - Gateway webhook gracefully rejects expired nonces with `ErrNonceExpired`.
  - Rate limiter allows up to 5 attempts per 15 minutes per IP, giving legitimate users ample retry budget without opening brute-force exposure.

---

## 3. Recommended Production Monitoring & Alerts

| Alert Metric | Condition | Severity | Recommended Operator Action |
|---|---|---|---|
| **Redis Health Degraded** | `buffer.IsDegraded() == true` | **P0 - Critical** | Inspect Redis instance status and network security groups. |
| **Cluster Eviction Spike** | `> 500 device:revoked / min` | **P1 - High** | Investigate possible compromised credential wave or credential stuffing. |
| **Rate Limit Reject Rate** | `> 1000 429s / 5 min` | **P2 - Medium** | Inspect target IP addresses in reverse SMS or user discovery endpoints. |
| **Gateway Auth Failure Spike** | `> 50 401s / 5 min` on webhook | **P1 - High** | Verify gateway secret synchronization with carrier portal. |

---

## 4. Phase E Readiness Checklist

- [x] Step 0 Gate: P0 security and persistence invariants satisfied.
- [x] Step 1: Active WebSocket revocation implemented and tested.
- [x] Step 2: Redis Pub/Sub cluster events and deduplication verified.
- [x] Step 3: Atomic distributed sliding window rate limiting active.
- [x] Step 4: Server-side wire block and stranger rejection enforced.
- [x] Step 5: Twilio signature adapter standalone implementation complete.
- [x] Step 6: 100% test pass on Go backend and Android client.
- [x] Step 7: 6 audit reports created in `docs/audit/`.

**The codebase is in a stable, verified, and production-hardened state. Awaiting user approval to proceed to Phase E.**
