# PHASE A — SECURITY GAPS & ARCHITECTURAL LIMITATIONS

## 1. Webhook Signature Mismatch on Twilio Gateways
- **Severity:** HIGH
- **Component:** `backend/internal/auth/webhook_auth.go`
- **Impact:** Production failure if Twilio is deployed as the inbound SMS provider.
- **Vulnerability:** `VerifyGatewaySignature` checks header `X-Twilio-Signature`, but validates it using hex HMAC-SHA256 over raw JSON. Twilio's standard webhook computes base64-encoded HMAC-SHA1 over the full URL concatenated with sorted POST parameters. All incoming Twilio requests will be rejected with HTTP 401.
- **Remediation:** Implement provider-specific signature verification routing or employ a standard gateway proxy (e.g. AWS API Gateway / Cloudflare Worker) that normalizes inbound webhooks to standard generic HMAC-SHA256 headers before forwarding to NEXA.

---

## 2. Unconsumed Token Leak via Status Polling
- **Severity:** MEDIUM
- **Component:** `backend/internal/auth/reverse_sms.go:595-608`
- **Impact:** Replay / eavesdropping on verified sessions within TTL window.
- **Vulnerability:** When a verification challenge is completed, its record status remains `"VERIFIED"`. Subsequent calls to `GET /api/v1/auth/verification-status?nonce=...` return the allocated `SessionAuthToken` repeatedly until the 5-minute cache expires.
- **Remediation:** Atomically transition status to `"CONSUMED"` on the first successful fetch of the `SessionAuthToken`, invalidating the token from the polling response thereafter.

---

## 3. Silent RAM Fallback on Redis Failure
- **Severity:** HIGH
- **Component:** `backend/internal/queue/redis_buffer.go:113-122`
- **Impact:** Loss of ephemeral offline messages and nonces on instance restart.
- **Vulnerability:** If Redis crashes or fails to connect during startup, `NewRedisBufferWithConfig` logs a critical alert but does not exit. It returns an active in-memory buffer instance with `isDegraded = true`. If multiple Render instances run, each maintains its own disjoint volatile RAM queue.
- **Remediation:** In production mode (`isProduction == true`), enforce a fail-closed policy (`log.Fatalf` or return error that halts server startup) if Redis is unavailable.

---

## 4. Multi-Instance Nonce Race Condition
- **Severity:** MEDIUM
- **Component:** `backend/internal/auth/reverse_sms.go:353-370`
- **Impact:** Concurrent duplicate account provisioning in multi-instance clusters.
- **Vulnerability:** Synchronization is enforced via Go's in-process `sync.RWMutex`. If two inbound webhooks with the same nonce hit two different backend instances simultaneously, both may read the unverified state from Redis and attempt to provision accounts before the first instance updates Redis.
- **Remediation:** Utilize atomic Redis `SETNX` or a Lua script to claim and lock the nonce across all backend instances before executing `ProcessInboundWebhook`.

---

## 5. Inbound Receiver Number Sanity Checks
- **Severity:** LOW
- **Component:** `backend/internal/auth/reverse_sms.go:160-162, 235-237`
- **Impact:** Misconfiguration risk.
- **Vulnerability:** Only "+919800000000" and "+910000000000" are rejected as dummy numbers. Other placeholder values (such as "+911234567890") bypass the check.
- **Remediation:** Pass `INBOUND_SMS_RECEIVER_NUMBER` through `CanonicalizeDomesticPhone` and validate that it matches an active registered gateway inventory.
