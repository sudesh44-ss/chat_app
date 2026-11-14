# Client-Initiated Reverse SMS Production Readiness Audit

**Target:** Reverse SMS Service, Gateway Webhook Auth, Environment Readiness  
**Date:** 2026-11-10  
**Classification:** Incident Review & Production Readiness  

---

## 1. Production Incident Review

During live operation, the backend emitted:
```text
2026/11/10 10:13:40 [ReverseSMS] CRITICAL:
INBOUND_SMS_RECEIVER_NUMBER is not configured in production!
```
Any public user initiating reverse SMS onboarding (`POST /api/v1/auth/initiate-reverse-verify`) received `HTTP 500`:
```json
{
  "error": "reverse verification unavailable: real INBOUND_SMS_RECEIVER_NUMBER is required in production environment (dummy placeholder rejected)"
}
```

### Root Cause:
`INBOUND_SMS_RECEIVER_NUMBER` was missing in Render environment variables. The server refused to dispatch users to a dummy placeholder (`+919800000000`), failing closed by design.

---

## 2. Cryptographic & Security Subsystem Audit

### 2.1 Webhook Authentication ([`backend/internal/auth/webhook_auth.go`](file:///H:/chat_app/backend/internal/auth/webhook_auth.go))
- **HMAC-SHA256 Signature Verification:** `VerifyGatewaySignature` computes HMAC-SHA256 over raw request body using `GATEWAY_WEBHOOK_SECRET`.
- **Constant-Time Comparison:** Uses `crypto/hmac.Equal` and `crypto/subtle.ConstantTimeCompare` to prevent timing attacks.
- **Fail-Closed Default:** If `GATEWAY_WEBHOOK_SECRET` is unset, all incoming webhooks are rejected with `401 Unauthorized`.
- **Timestamp Tolerance & Anti-Replay:** Parses `X-Gateway-Timestamp` / `X-Timestamp`. Rejects requests older than 5 minutes or skewed in the future by > 60 seconds.
- **Header Flexibility:** Supports `X-Gateway-Signature`, `X-Signature`, `X-Hub-Signature-256`, `X-Twilio-Signature`, `X-Webhook-Secret`, and Bearer token.

### 2.2 Nonce Lifecycle & State Transitions ([`backend/internal/auth/reverse_sms.go`](file:///H:/chat_app/backend/internal/auth/reverse_sms.go))
- **CSPRNG Generation:** 5-character high-entropy alphanumeric nonce (`NX-XXXXX`) generated via `crypto/rand`.
- **TTL Window:** Exactly 90 seconds. Expired nonces return `ErrNonceExpired`.
- **Single-Use State Machine:**
  - `PENDING`: Nonce issued; waiting for carrier SMS.
  - `VERIFIED`: Inbound webhook received with valid signature and matching phone number.
  - `CONSUMED`: Client polls `/api/v1/auth/verification-status` and retrieves session auth token. Nonce immediately transitions to `CONSUMED`. Subsequent status requests cannot retrieve the token.
- **Anti-Replay Attack Protection:** Submitting an already-verified nonce to the inbound webhook returns: `replay attack rejected`.

### 2.3 Sender Phone Number Binding
- Sender MSISDN in inbound webhook is canonicalized using `CanonicalizeDomesticPhone` (`+91[6-9]\d{9}`).
- Must strictly match the initiating phone number (`record.PhoneNumber == canonicalSender`). Mismatched phone numbers are rejected with `ErrPhoneMismatch`.

### 2.4 Trusted Phone Allowlist Bypass ([`backend/internal/auth/trusted_phone.go`](file:///H:/chat_app/backend/internal/auth/trusted_phone.go))
- Environment variable: `NEXA_TRUSTED_PHONE_NUMBERS` (comma-separated E.164 numbers).
- In `production`: If any entry is malformed, registry fails closed and clears the entire allowlist.
- Valid trusted devices bypass cellular Reverse SMS transmission and immediately auto-verify during QA/developer testing.

---

## 3. Operator Production Configuration Checklist

The system operator must populate the following values in the **Render Web Service Dashboard** (`dashboard.render.com`) under **Environment**:

| Environment Variable | Required Format | Description | Sensitive |
|---|---|---|---|
| `INBOUND_SMS_RECEIVER_NUMBER` | `+91[6-9]\d{9}` (e.g. `+91XXXXXXXXXX`) | Dedicated Indian telecom SIM or virtual long-code number owned by NEXA | No |
| `GATEWAY_WEBHOOK_SECRET` | 32+ byte hex string (generated via `openssl rand -hex 32`) | Shared secret key for HMAC-SHA256 inbound carrier webhook authentication | **Yes** (Secret) |
| `NEXA_TRUSTED_PHONE_NUMBERS` | `+91XXXXXXXXXX,+91YYYYYYYYYY` | Comma-separated developer and QA phone numbers for testing bypass | **Yes** (Internal) |
| `APP_ENV` | `production` | Production runtime environment flag | No |

---

## 4. Verification & Readiness Status

- **Code Implementation:** **VERIFIED (100% Passing Unit & Security Tests)**.
- **Fail-Closed Defenses:** **VERIFIED**.
- **External Dependency:** **PENDING OPERATOR CONFIGURATION** on Render Dashboard.
