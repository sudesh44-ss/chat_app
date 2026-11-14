# PHASE A — FINAL VERIFICATION REPORT

**Execution Mode:** STRICT READ-ONLY AUDIT  
**Audit Scope:** NEXA Phase A (Reverse SMS Gateway, Nonce Anti-Replay, Webhook Security, Ephemeral Redis Buffer, Android Zero-Permission Radar UI)  
**Classification Standard:** `VERIFIED`, `VERIFIED WITH LIMITATIONS`, `NOT VERIFIED`, `FAILED`  

---

## 1. Reverse SMS Receiver Verification (`reverse_sms.go`)

### 1.1 Production Rejection of Missing Receiver Numbers
- **Classification:** `VERIFIED`
- **Code Inspection:** `backend/internal/auth/reverse_sms.go:230-237`
- **Details:** In `InitiateVerification`, the backend inspects `APP_ENV` / `ENV`. When `isProduction` is true and `s.inboundReceiverNum == ""`, the method immediately aborts and returns:
  `"reverse verification unavailable: real INBOUND_SMS_RECEIVER_NUMBER is required in production environment (dummy placeholder rejected)"`.

### 1.2 Production Rejection of Dummy Receiver Numbers
- **Classification:** `VERIFIED WITH LIMITATIONS`
- **Code Inspection:** `backend/internal/auth/reverse_sms.go:235-237`
- **Details:** The production check explicitly checks for two hardcoded strings:
  `s.inboundReceiverNum == "+919800000000" || s.inboundReceiverNum == "+910000000000"`.
  Any other dummy, test, or invalid sequence (such as `+919999999999` or `+911111111111`) is not rejected by this check. Furthermore, `s.inboundReceiverNum` is not parsed through `CanonicalizeDomesticPhone` during `NewReverseSmsService` initialization.

### 1.3 Domestic Number Format Validation
- **Classification:** `VERIFIED`
- **Code Inspection:** `backend/internal/auth/reverse_sms.go:31-60`
- **Details:** `CanonicalizeDomesticPhone` enforces `^(\+91)?[6-9]\d{9}$`. Strips whitespace, dashes, and parentheses. Foreign numbers (e.g. US `+1`, UK `+44`) and invalid prefixes (0-5) are rejected. Valid numbers are canonicalized to `+91XXXXXXXXXX`.

### 1.4 Verification Issuance & Challenge Usability
- **Classification:** `VERIFIED`
- **Code Inspection:** `backend/internal/auth/reverse_sms.go:307-315`
- **Details:** Returns `InitiateReverseVerifyResponse` with `SmsPayload: "NEXA-VERIFY:<nonce>:<sessionId>"`, `InboundReceiverNumber`, `Nonce`, and `ExpiresInSeconds: 90`. If receiver number is unconfigured in production, request fails before issuing challenges.

### 1.5 Telecom Reachability Assumptions
- **Classification:** `VERIFIED WITH LIMITATIONS`
- **Details:** The backend verifies that the environment variable is non-empty and non-dummy. It cannot and does not perform active signaling to confirm SIM/SMPP routing, carrier SMS inbound forwarding, or SMSC deliverability. Reachability is an external infrastructure dependency.

---

## 2. Anti-Replay Architecture

### 2.1 Single-Use Nonce Invalidation
- **Classification:** `VERIFIED WITH LIMITATIONS`
- **Code Inspection:** `backend/internal/auth/reverse_sms.go:375-377`
- **Details:** In `ProcessInboundWebhook`, if `record.Status == "VERIFIED"`, subsequent webhook deliveries are rejected with `"nonce %s has already been verified (replay attack rejected)"`.
- **Limitation:** In `GetVerificationStatus` (lines 595-608), once a challenge reaches `VERIFIED`, polling clients can repeatedly read `SessionAuthToken` throughout the 5-minute cache window. The status is not transitioned to `CONSUMED`.

### 2.2 Concurrent Webhook Requests
- **Classification:** `VERIFIED WITH LIMITATIONS`
- **Code Inspection:** `backend/internal/auth/reverse_sms.go:353-354`
- **Details:** Protected by local mutex `s.mu.Lock()`.
- **Limitation:** In a multi-instance cluster, two concurrent webhooks routed to different backend instances acquire separate local mutexes. Neither Redis distributed locking (`SETNX`) nor a Redis Lua script is used in `ProcessInboundWebhook`, allowing a potential race window across instances.

### 2.3 Expired Nonces
- **Classification:** `VERIFIED`
- **Code Inspection:** `backend/internal/auth/reverse_sms.go:379-383`
- **Details:** Enforces strict 90-second expiration (`record.ExpiresAt`). If `now > record.ExpiresAt`, the record status is updated to `EXPIRED` and `ErrNonceExpired` is returned.

### 2.4 Verified Challenge Reuse
- **Classification:** `VERIFIED`
- **Code Inspection:** `backend/internal/auth/reverse_sms.go:375-377`
- **Details:** Webhook replay fails closed.

### 2.5 Atomic Consumption
- **Classification:** `VERIFIED WITH LIMITATIONS`
- **Details:** Single-instance execution is atomic via mutex. Multi-instance execution is not coordinated via distributed atomic primitives.

---

## 3. Gateway Authentication (`webhook_auth.go`)

### 3.1 Generic HMAC-SHA256 Validation
- **Classification:** `VERIFIED`
- **Code Inspection:** `backend/internal/auth/webhook_auth.go:94-101`
- **Details:** Computes HMAC-SHA256 over raw byte payload using `GATEWAY_WEBHOOK_SECRET`. Compares using `hmac.Equal`.

### 3.2 `X-Hub-Signature-256` Behavior
- **Classification:** `VERIFIED`
- **Code Inspection:** `backend/internal/auth/webhook_auth.go:56, 66`
- **Details:** Strips `sha256=` prefix and compares hex digest in constant time.

### 3.3 Bearer & Header Token Validation
- **Classification:** `VERIFIED`
- **Code Inspection:** `backend/internal/auth/webhook_auth.go:39-48`
- **Details:** Evaluates `X-Webhook-Secret` and `Authorization: Bearer <secret>` using `subtle.ConstantTimeCompare`.

### 3.4 Timestamp Freshness Validation
- **Classification:** `VERIFIED`
- **Code Inspection:** `backend/internal/auth/webhook_auth.go:69-91`
- **Details:** Evaluates `X-Gateway-Timestamp` or `X-Timestamp` (seconds or milliseconds). Rejects requests older than 5 minutes or skewed in future by > 60 seconds.

### 3.5 Constant-Time Comparison
- **Classification:** `VERIFIED`
- **Code Inspection:** `backend/internal/auth/webhook_auth.go:44, 99`
- **Details:** Employs `subtle.ConstantTimeCompare` and `hmac.Equal`.

### 3.6 Request Body Integrity
- **Classification:** `VERIFIED`
- **Code Inspection:** `backend/internal/auth/webhook_auth.go:32, 95`
- **Details:** Signature computed directly against raw unparsed HTTP request body slice before JSON unmarshalling.

### 3.7 Twilio `X-Twilio-Signature` Algorithm Compliance
- **Classification:** `FAILED`
- **Code Inspection:** `backend/internal/auth/webhook_auth.go:59-66, 94-101`
- **Root Cause:** Twilio does NOT compute hex HMAC-SHA256 over raw JSON bodies. Twilio's official specification mandates:
  1. Base64-encoded HMAC-SHA1.
  2. Canonical string formed by: `URL + sorted POST form parameters (name + value)`.
  Because `webhook_auth.go` applies hex HMAC-SHA256 over raw body, native Twilio webhooks will consistently fail authentication with `ErrInvalidWebhookSignature`.

---

## 4. Redis Buffer & Offline Persistence (`redis_buffer.go`)

### 4.1 `REDIS_URL` Support
- **Classification:** `VERIFIED`
- **Code Inspection:** `backend/internal/queue/redis_buffer.go:39, 66-79`
- **Details:** Uses `redis.ParseURL(redisURL)`.

### 4.2 TLS (`rediss://`) Support
- **Classification:** `VERIFIED`
- **Code Inspection:** `backend/internal/queue/redis_buffer.go:126`
- **Details:** `redis.ParseURL` configures TLS when the scheme is `rediss://`.

### 4.3 Silent Fallback to In-Memory RAM
- **Classification:** `FAILED`
- **Code Inspection:** `backend/internal/queue/redis_buffer.go:113-122`
- **Details:** When Redis connection ping fails in production, the constructor logs a critical error but returns `rb, nil` with `isDegraded = true`. The system silently falls back to an in-memory map instead of failing closed.

### 4.4 Health Endpoint Accuracy
- **Classification:** `VERIFIED WITH LIMITATIONS`
- **Code Inspection:** `backend/cmd/server/main.go:251-266`
- **Details:** `/health` correctly outputs `"redis": "DEGRADED_IN_MEMORY"`, but the overall HTTP status code is `200 OK` and `"status": "UP"`. Render's health checker interprets HTTP 200 as healthy.

### 4.5 Reconnection Behavior
- **Classification:** `VERIFIED`
- **Details:** `go-redis/v9` implements internal automatic connection retries with exponential backoff.

---

## 5. Android Client Verification (`VerificationRadarViewModel.kt` & `Screen.kt`)

### 5.1 `ACTION_SENDTO` URI Format
- **Classification:** `VERIFIED`
- **Code Inspection:** `VerificationRadarViewModel.kt:156-160`
- **Details:** Uses `Intent(Intent.ACTION_SENDTO)` with `Uri.parse("smsto:$inbound")` and `putExtra("sms_body", payload)`.

### 5.2 Correct Inbound Receiver Number
- **Classification:** `VERIFIED`
- **Details:** Passed dynamically from backend response `payload.inboundReceiverNumber`.

### 5.3 Nonce/Session Payload
- **Classification:** `VERIFIED`
- **Details:** Dispatches exact compound format `NEXA-VERIFY:<nonce>:<sessionId>`.

### 5.4 Zero Unnecessary SMS Permissions
- **Classification:** `VERIFIED`
- **Code Inspection:** `AndroidManifest.xml`
- **Details:** `SEND_SMS`, `RECEIVE_SMS`, and `READ_PHONE_STATE` are completely absent.

### 5.5 Polling Loop & Timeout
- **Classification:** `VERIFIED`
- **Code Inspection:** `VerificationRadarViewModel.kt:124-139, 205-244`
- **Details:** Polling interval is 2.5s; countdown timer ticks every 1s down from 90s, triggering `EXPIRED` status at 0s.

### 5.6 Navigation on Confirmed Verified Status
- **Classification:** `VERIFIED`
- **Code Inspection:** `VerificationRadarScreen.kt:118-127`
- **Details:** Navigation callback `onVerificationSuccess` fires only when `uiState.status == RadarVerificationStatus.VERIFIED` and `allocatedNxId` is non-blank.

---

## 6. Test Suite Coverage Analysis

### 6.1 Tests Executed & Passed
- `TestDomesticPhoneValidation`: **PASS**
- `TestReverseSmsEndToEndWorkflow`: **PASS**
- `TestReverseSmsSecurityAttacks`: **PASS**
- `TestReverseSmsZeroPermissionPayloadsAndSessionLookup`: **PASS**
- `TestReverseSmsProductionDummyRejection`: **PASS**
- `TestReverseSmsAntiReplayProtection`: **PASS**
- `TestVerifyGatewaySignature`: **PASS**

### 6.2 Missing Tests Identified
1. **Concurrent Webhook Replay:** No concurrent Go routines testing race conditions against the same nonce simultaneously.
2. **Twilio Canonicalization Test:** Zero tests validating native Twilio signature canonicalization.
3. **Redis Outage Fail-Closed Test:** No test enforcing that production must refuse to serve requests when Redis is unavailable.
4. **Physical Device Carrier Test:** Verification tests rely on mocks and HTTP test servers rather than live GSM/CDMA carrier delivery.
