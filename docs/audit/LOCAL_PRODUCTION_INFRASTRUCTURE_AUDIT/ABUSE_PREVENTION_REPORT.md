# NEXA — Phase K–L: Abuse Prevention & Resource Protection Report
**Project:** NEXA Secure Mobile Messenger & Multi-Hop DTN Mesh  
**Workspace:** `H:\chat_app`  
**Phase:** K–L — Security, Abuse Prevention & Multi-Device Testing  
**Audit Date:** November 2026  
**Status:** COMPLETE & VERIFIED  

---

## 1. Executive Summary

This report documents the abuse prevention mechanisms, rate limiting architectures, resource quotas, and fail-closed security policies evaluated and hardened in Phase K–L.

In decentralized and DTN networks, abuse resistance is essential: adversarial peers may flood intermediate relays with garbage envelopes, spam SMS endpoints to exhaust developer credits, or scrape user identities. NEXA enforces strict resource protection at both the network boundary and the mobile client.

---

## 2. Abuse Scenarios & Protective Controls

### 2.1 OTP & SMS Credit Depletion Protection
* **Threat:** Automated botnets trigger mass OTP requests, exhausting SMS provider balances and causing denial of service.
* **Mitigation Controls (`internal/auth/otp_service.go`):**
  * **Phone Rate Limit:** Maximum 5 OTP requests per phone number per hour.
  * **IP Rate Limit:** Maximum 15 OTP requests per IP address per hour.
  * **Resend Cooldown:** 60-second mandatory cooldown between consecutive requests for the same phone number.
  * **Hard Invalidation:** Exceeding 3 failed attempts deletes the pending OTP record immediately.
  * **Atomic Single-Use Token:** Successful verification consumes the OTP and issues a 10-minute `AuthTicket` that can only be consumed once during account creation or login.

### 2.2 Mass User Enumeration & Contact Scraping
* **Threat:** Attackers systematically crawl phone numbers or NX-IDs via discovery endpoints to harvest user profiles.
* **Mitigation Controls (`internal/auth/handler.go`):**
  * **Strict Session Gate on `/api/v1/users/resolve`:** Remediated in Phase K–L. Unauthenticated callers receive HTTP 401 Unauthorized.
  * **Device Revocation Check:** Session tokens belonging to revoked devices are rejected.
  * **Privacy Filter (`internal/auth/reverse_sms.go`):** Non-verified accounts cannot be resolved; private discovery flags (`IsDiscoverableByPhone`) are enforced.

### 2.3 DTN Envelope Flooding & Memory Exhaustion
* **Threat:** A compromised peer dumps thousands of giant envelopes to exhaust Cloud Gateway RAM or Redis buffer capacity.
* **Mitigation Controls (`internal/api/dtn_handler.go`):**
  * **HTTP Max Body Limit:** Enforces `http.MaxBytesReader(w, r.Body, 10*1024*1024)` (10 MB payload ceiling).
  * **Batch Ceiling:** Rejects any dump containing more than 100 envelopes per request.
  * **Envelope Payload Ceiling:** Individual envelopes capped at 256 KB (`DtnConstants.MAX_PAYLOAD_SIZE`).
  * **Hop Limit Enforcement:** Envelopes with `hopCount >= hopLimit` (default 7) are dropped immediately.

### 2.4 Connection Request Spam & Message Bloat
* **Threat:** An attacker sends huge spam messages in introductory connection requests to consume disk on mobile devices.
* **Mitigation Controls (`internal/connections/handler.go`):**
  * **Request Body Ceiling:** Enforces `http.MaxBytesReader(w, r.Body, 64*1024)`.
  * **Intro Message Cap:** Enforces `len(reqBody.IntroMessage) <= 256` characters. Longer messages are rejected with HTTP 400 Bad Request.
  * **Rate Limiting:** Maximum 10 connection requests per minute per account via `ratelimit:conn_req`.

### 2.5 Media Upload Ticket Abuse
* **Threat:** Adversaries request unbounded pre-signed upload URLs to Cloudflare R2 / S3 storage.
* **Mitigation Controls (`cmd/server/main.go`):**
  * **Authentication & IDOR Guard:** Requires valid Bearer session; binds `req.NxId` to the caller's session `NxID`.
  * **Max File Ceiling:** Strict 100 MB ceiling. Files larger than 100 MB are rejected with instructions to use direct P2P mesh transfer.
  * **Short Ticket TTL:** Ephemeral upload URLs expire in 900 seconds (15 minutes).

---

## 3. Rate Limiting Architecture & Fail-Closed Enforcement

```
Incoming Request
      |
      v
+-------------------------------+
| Distributed Rate Limiter      |
| (`distributed_limiter.go`)    |
+-------------------------------+
      |
      |---> Redis Available?
      |         |
      |         |-- YES -> Run Atomic Sliding-Window Lua Script -> ALLOW / DENY
      |         |
      |         `-- NO  -> In Production Environment (`isProduction = true`)?
      |                         |
      |                         |-- YES -> FAIL-CLOSED (Return Error / HTTP 503)
      |                         |          [Guarantees No Bypass During Redis Outage]
      |                         |
      |                         `-- NO  -> Fallback to In-Memory Leaky Bucket
```

* **Production Policy:** When `isProduction = true`, Redis unavailability triggers immediate `fail-closed` behavior, preventing rate limit bypass when backend Redis instances restart or partition.
* **Development Resilience:** In dev mode, gracefully falls back to local in-memory tracking without crashing the service.

---

## 4. Verification Test Matrix

| Abuse Scenario | Test Vector | Expected Outcome | Execution Result |
| :--- | :--- | :--- | :--- |
| **OTP 3-Attempt Lockout** | Submit 4 wrong OTP codes, then 1 correct code. | Lockout triggered; correct code rejected. | **PASS** (`security_audit_test.go`) |
| **OTP Expiration** | Verify code after 5-minute TTL. | Expired code rejected with error. | **PASS** (`security_audit_test.go`) |
| **Auth Ticket Replay** | Consume same ticket twice. | Second attempt rejected with error. | **PASS** (`security_audit_test.go`) |
| **Unauthenticated User Resolve** | `GET /api/v1/users/resolve` without auth. | HTTP 401 Unauthorized. | **PASS** (`security_audit_test.go`) |
| **Oversized DTN Dump** | Send 11 MB payload to opportunistic dump. | HTTP 400 Bad Request (MaxBytesReader). | **PASS** (`dtn_handler_test.go`) |
| **Oversized Dump Batch** | Send batch of 101 envelopes. | HTTP 400 Bad Request. | **PASS** (`dtn_handler_test.go`) |
| **Connection Intro Message Bloat** | Send connection request with 257 chars. | HTTP 400 Bad Request. | **PASS** (`connections_test.go`) |
| **Cross-Transport Flood** | Same packet sent over WS and Mesh. | Exact duplicate suppressed; no re-decrypt. | **PASS** (`SecurityAndMultiDeviceTests.kt`) |
