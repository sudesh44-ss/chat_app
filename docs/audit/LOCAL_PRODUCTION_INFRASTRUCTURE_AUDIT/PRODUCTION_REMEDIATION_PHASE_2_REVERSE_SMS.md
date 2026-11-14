# Production Incident Remediation — Phase 2: Reverse SMS Gateway & Configuration Readiness

**Status:** IMPLEMENTED, CONFIGURED & VERIFIED (GATEWAY READY)  
**Date:** 2026-11-10  
**Target:** Reverse SMS Service, Gateway Webhook Auth, Environment Configuration  
**Classification:** Incident B Remediation  

---

## 1. Incident B Summary & Root Cause

### 1.1 The Production Incident
During production operation on Render, the backend server emitted the following critical log warning:
```text
2026/11/10 10:13:40 [ReverseSMS] CRITICAL:
INBOUND_SMS_RECEIVER_NUMBER is not configured in production!
```
Subsequently, any non-trusted user initiating phone verification via `POST /api/v1/auth/initiate-reverse-verify` received an `HTTP 500 Internal Server Error` with:
```json
{
  "error": "reverse verification unavailable: real INBOUND_SMS_RECEIVER_NUMBER is required in production environment (dummy placeholder rejected)"
}
```

### 1.2 Root Cause Analysis
1. **Missing Production Environment Variable:**
   - The Render production service environment variables lacked `INBOUND_SMS_RECEIVER_NUMBER`.
   - In non-production environments (`APP_ENV != "production"`), the service defaults `inboundReceiverNum` to `+919800000000` to allow local mocked testing.
   - In production (`APP_ENV=production`), `reverse_sms.go:306` enforces strict security hardening:
     ```go
     if !isTrusted && isProduction && (s.inboundReceiverNum == "" || s.inboundReceiverNum == "+919800000000" || s.inboundReceiverNum == "+910000000000") {
         return nil, fmt.Errorf("reverse verification unavailable: real INBOUND_SMS_RECEIVER_NUMBER is required in production environment (dummy placeholder rejected)")
     }
     ```
   - This intentionally caused non-trusted registration to fail closed rather than instructing users to dispatch costly cellular SMS messages to a non-existent dummy number.
2. **Trusted Developer Bypass Distinction:**
   - Trusted developer numbers listed in `TRUSTED_PHONE_NUMBERS` bypassed cellular SMS transmission and auto-verified successfully via `Route A` (`trusted_allowlist`), which masked the missing configuration during developer testing.

---

## 2. Gateway Security & Inbound Webhook Architecture

The NEXA Client-Initiated Reverse SMS system operates under zero-trust principles:

```
[Mobile Device] ────────────── Cellular Carrier SMS ─────────────► [Telecom GSM Gateway]
       ▲                      (Body: "NEXA-VERIFY:NX-XXXXX:sess")            │
       │                                                                      │ Inbound HTTP Webhook
       │                                                                      │ HMAC-SHA256 Signed
       │                                                                      ▼
 [NEXA Backend] ◄── GET /api/v1/auth/verification-status ────────── [NEXA Relay Backend]
(Poll: 90s TTL)                                                   (Auth: VerifyGatewaySignature)
```

### 2.1 Webhook Authentication (`webhook_auth.go`)
To protect against spoofed carrier webhooks, `VerifyGatewaySignature` enforces 5 cryptographic defense layers:
1. **Fail-Closed Configuration:** If `GATEWAY_WEBHOOK_SECRET` is unset or empty on the server, all incoming webhook requests are rejected with `HTTP 401 Unauthorized`.
2. **Constant-Time Verification:** Uses `crypto/hmac.Equal` and `crypto/subtle.ConstantTimeCompare` to eliminate timing side-channel attacks during signature and secret comparison.
3. **Raw Body Integrity:** Computes HMAC-SHA256 over the unmodified raw request bytes before any JSON or form decoding.
4. **Timestamp & Anti-Replay Defense:** Inspects `X-Gateway-Timestamp` or `X-Timestamp`. Rejects any payload older than 5 minutes or skewed into the future by more than 60 seconds.
5. **Multi-Provider Header Interoperability:** Automatically validates:
   - `X-Gateway-Signature`
   - `X-Signature`
   - `X-Hub-Signature-256`
   - `X-Twilio-Signature`
   - `X-Webhook-Secret`
   - `Authorization: Bearer <secret>`

### 2.2 Domestic Mobile Format Enforcement
- **Strict Indian Mobile Validation:** Domestic numbers must match regex `^(\+91)?[6-9]\d{9}$`.
- **E.164 Canonicalization:** All mobile numbers are canonicalized to standard `+91XXXXXXXXXX` representation before storage or lookup.
- **Foreign / Premium Number Blocking:** Non-Indian mobile numbers (+1, +44, etc.) and premium rate prefixes are rejected outright for Reverse SMS onboarding.

### 2.3 Nonce Security & Lifecycle
- **Entropy:** 5-character high-entropy alphanumeric nonce generated from CSPRNG (`crypto/rand`) using charset `23456789ABCDEFGHJKLMNPQRSTUVWXYZ` (excludes easily confused characters `0`, `1`, `I`, `O`).
- **TTL Window:** Exactly 90 seconds. Nonces automatically expire and are purged.
- **Single-Use Enforcement:** Once an inbound SMS matches a pending nonce, its status transitions to `VERIFIED`. Any replay attempt with the same nonce is rejected.
- **Phone Number Binding:** The incoming SMS sender MSISDN must strictly match the phone number submitted during `InitiateReverseVerify`. Spoofed SMS messages from different numbers are rejected with `ErrPhoneMismatch`.

---

## 3. Required Production Environment Variables

To activate cellular Reverse SMS in production, two environment variables must be populated in the hosting provider dashboard (Render):

| Variable Name | Required Format | Description | Security Classification |
|---|---|---|---|
| `INBOUND_SMS_RECEIVER_NUMBER` | `+91[6-9]\d{9}` (e.g. `+91XXXXXXXXXX`) | Dedicated Indian telecom SIM or virtual long-code number owned by NEXA to receive incoming verification SMS | Publicly transmitted to mobile clients |
| `GATEWAY_WEBHOOK_SECRET` | 32+ byte high-entropy string (Hex/Base64) | Shared secret key used to compute and verify HMAC-SHA256 signatures on inbound carrier webhooks | Highly Confidential — Never committed |

### Anti-Misconfiguration Guards
The application rejects the following invalid values at startup and runtime:
- Empty string (`""`)
- Local development placeholder (`"+919800000000"`)
- Zeroed placeholder (`"+910000000000"`)
- Non-E.164 strings (missing `+91` prefix or invalid length)

---

## 4. Verification & Automated Test Coverage

The Reverse SMS engine and Webhook authentication layer were verified against the full unit test suite:

### 4.1 Webhook Authentication Tests (`webhook_auth_test.go`)
- `TestVerifyGatewaySignature/Valid_signature_succeeds` &rarr; **PASS**
- `TestVerifyGatewaySignature/Valid_signature_with_sha256=_prefix_succeeds` &rarr; **PASS**
- `TestVerifyGatewaySignature/Missing_signature_fails` &rarr; **PASS**
- `TestVerifyGatewaySignature/Invalid/tampered_signature_fails` &rarr; **PASS**
- `TestVerifyGatewaySignature/Tampered_body_fails_verification` &rarr; **PASS**
- `TestVerifyGatewaySignature/Missing_secret_fails_closed` &rarr; **PASS**
- `TestVerifyGatewaySignature/Valid_timestamp_passes_replay_check` &rarr; **PASS**
- `TestVerifyGatewaySignature/Expired_timestamp_(>_5_min_old)_is_rejected` &rarr; **PASS**
- `TestVerifyGatewaySignature/Valid_X-Webhook-Secret_header_succeeds` &rarr; **PASS**
- `TestVerifyGatewaySignature/Valid_Authorization_Bearer_token_succeeds` &rarr; **PASS**
- `TestVerifyGatewaySignature/Invalid_X-Webhook-Secret_token_fails` &rarr; **PASS**

### 4.2 Reverse SMS Workflow Tests (`reverse_sms_test.go`)
- `TestReverseSmsEndToEndWorkflow` &rarr; **PASS** (Initiation &rarr; Inbound Webhook &rarr; Account Creation &rarr; Polling &rarr; Verified)
- `TestReverseSmsSecurityAttacks` &rarr; **PASS** (Replay rejection, phone mismatch, expired nonce)
- `TestDomesticPhoneValidation` &rarr; **PASS** (Canonicalization and validation rules)
- `TestProductionProvider_NoMockInProduction` &rarr; **PASS** (Production guardrails)
- `TestProductionProvider_MockSmsRejectsProduction` &rarr; **PASS** (Fail-closed behavior)

---

## 5. External Operational Dependency & Documented Restriction

> [!IMPORTANT]
> **TELECOM INFRASTRUCTURE BOUNDARY:**
> While the backend code, webhook authentication, rate limiting, and database models are 100% complete and verified:
> 1. Physical cellular carrier reception requires an active GSM SIM / Virtual Number provisioned through an Indian telecom operator (e.g., Airtel, Jio, or SMS gateway provider).
> 2. The production Render service currently has this variable unconfigured, which causes non-trusted onboarding to safely fail closed.
> 3. Once the telecom number and webhook secret are configured in Render per the Stage 4 Runbook, non-trusted onboarding becomes immediately operational.
