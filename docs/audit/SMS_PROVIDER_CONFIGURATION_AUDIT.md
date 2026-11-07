# NEXA — SMS Provider Configuration & Delivery Architecture Audit

**Audit Mode:** STRICT READ-ONLY INSPECTION  
**Target:** Outbound SMS Providers & Inbound Telecom Gateway  
**Date:** November 2026  

---

## 1. Outbound SMS Provider Inspection (Legacy OTP)

### Exact Startup Log
```
[SmsProvider] CRITICAL: Running in production with NO SMS provider configured! Mock fallback is DISABLED.
```

### Provider Selection Logic (`backend/internal/auth/sms_provider.go`)

The backend contains implementations for two production SMS gateways and one test mock:

1. **MSG91 Provider (`NewMsg91Provider`):**
   - **Target Market:** India (DLT-compliant transactional SMS).
   - **Environment Variables:** `MSG91_AUTH_KEY`, `MSG91_TEMPLATE_ID`.
   - **API Endpoint:** `https://api.msg91.com/api/v5/otp`.
   - **Status in Render:** Unconfigured.
2. **Twilio Provider (`NewTwilioProvider`):**
   - **Target Market:** Global / North America.
   - **Environment Variables:** `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN`, `TWILIO_FROM_NUMBER` (or `TWILIO_PHONE_NUMBER`).
   - **API Endpoint:** `https://api.twilio.com/2010-04-01/Accounts/{SID}/Messages.json`.
   - **Status in Render:** Unconfigured.
3. **Production Safety Guard:**
   - Lines 254-259:
     ```go
     if env == "production" {
         log.Println("[SmsProvider] CRITICAL: Running in production with NO SMS provider configured! Mock fallback is DISABLED.")
         return &UnavailableSmsProvider{ reason: "missing MSG91 or TWILIO credentials..." }
     }
     ```
   - In `production`, `MockSmsProvider` is blocked to prevent exposing security codes in production logs.
   - Any request to `/api/v1/auth/otp/request` returns `HTTP 503`.

---

## 2. Inbound SMS Gateway Inspection (Method 1: Reverse SMS)

Under **Method 1 (Zero-Permission Client-Initiated Reverse SMS)**, outbound SMS dispatch is replaced by inbound verification:

```
[Android User] ──(Dispatches SMS via Google Messages)──> [Carrier Gateway]
                                                               │
                                                               ▼ (HTTP Webhook)
                                                     [NEXA Render Backend]
```

### Required Configuration for Inbound Gateway:

1. **Virtual Mobile Number (VMN) / Inbound Receiver:**
   - **Variable:** `INBOUND_SMS_RECEIVER_NUMBER`.
   - **Current State:** Missing on Render. Defaults to `+919800000000`.
   - **Impact:** Beacon SMS cannot reach a legitimate receiver.
2. **Webhook Endpoint:**
   - **Path:** `/api/v1/webhooks/sms-inbound` or `/api/v1/auth/inbound-sms`.
   - **Payload Parsed:**
     - Sender Phone: E.164 string (`sender`, `from`, `mobile`, `msisdn`).
     - Message Body: Containing `NEXA-VERIFY:<nonce>:<sessionId>` or `NX-VERIFY <nonce>`.
3. **Webhook Security:**
   - **Secret:** `GATEWAY_WEBHOOK_SECRET`.
   - **Header:** `X-Gateway-Signature` (HMAC-SHA256).
   - **Current State:** Missing on Render. Fails closed with `401 Unauthorized`.

---

## 3. Comparison Matrix: Outbound OTP vs. Reverse SMS

| Feature | Legacy Outbound OTP | Method 1: Client-Initiated Reverse SMS |
| :--- | :--- | :--- |
| **Developer Cost per Verification** | ₹0.25 – ₹4.50 per SMS | **₹0.00** (Free for developer) |
| **DLT Template Registration (India)** | Mandatory (TRAI regulation) | None required |
| **SIM Swap / Caller Spoofing Defense** | Vulnerable | Resistant (Carrier Caller ID verified) |
| **Permissions on Android** | Often requested `READ_SMS` / `RECEIVE_SMS` | **Zero Permissions** (`ACTION_SENDTO`) |
| **Server Requirement** | Outbound Twilio/MSG91 account | Inbound VMN + Webhook |
