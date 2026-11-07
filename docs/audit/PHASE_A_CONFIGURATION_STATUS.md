# NEXA — Phase A Configuration Status: Production Environment & Gateway Matrix

**Date:** November 6, 2026  
**Target:** Render Production Deployment (`https://nexa-relay.onrender.com`)  
**Scope:** Phase A Runtime Requirements  

---

## 1. Environment Variable Audit & Configuration Status

The following table documents the exact environment variables required by the Phase A production relay service on Render.

> [!IMPORTANT]
> In accordance with zero-trust security guidelines, no secret values or credentials are listed in this report. Variables must be populated directly within the **Render Dashboard → Environment** tab.

| Environment Variable | Required For | Expected Format | Status in Codebase | Recommended Production Setting |
| :--- | :--- | :--- | :--- | :--- |
| `APP_ENV` | Production safety enforcement | `production` | **SUPPORTED** | Set to `production` |
| `PORT` | HTTP Server port binding | Standard integer (e.g., `8080` or `10000`) | **CONFIGURED** | Render automatically injects `10000` |
| `INBOUND_SMS_RECEIVER_NUMBER` | Client-Initiated Reverse SMS | E.164 telecommunications format (`+9198XXXXXXXX`) | **ENFORCED** (Fails closed if dummy) | Must be set to active inbound SMS receiving SIM/virtual number |
| `GATEWAY_WEBHOOK_SECRET` | Inbound SMS Webhook HMAC/Token | High-entropy alphanumeric string (min 32 chars) | **ENFORCED** (Constant-time check) | Shared secret matching SMS gateway configuration |
| `REDIS_URL` | Ephemeral message queue & packet buffer | `rediss://default:<password>@<host>:<port>` | **SUPPORTED** (Auto-TLS & credentials) | Connect Render Redis or Upstash instance |
| `GOOGLE_CLIENT_ID` | Google Identity token verification | `*.apps.googleusercontent.com` | **ENFORCED** (Fails closed if unset) | Android OAuth 2.0 Web Client ID from Google Cloud Console |
| `IDENTITY_STORE_PATH` | File-backed identity database | Path string (e.g. `/var/data/identity.json`) | **SUPPORTED** | Set to persistent disk path or use ephemeral storage |
| `FCM_PROJECT_ID` | Cloud background wake-up | String (Firebase project ID) | *PHASE B DEFERRED* | Documented as remaining Phase B blocker |
| `FCM_CREDENTIALS_JSON` | Firebase service account key | Minified JSON string | *PHASE B DEFERRED* | Documented as remaining Phase B blocker |
| `DATABASE_URL` | PostgreSQL identity storage | `postgres://user:pass@host:5432/nexa` | *PHASE C DEFERRED* | Documented as remaining Phase C blocker |

---

## 2. Inbound SMS Gateway Configuration Guide

To ensure inbound SMS payloads successfully trigger user verification:

1. **Receiving Telecommunications Provider:**
   - Configure your inbound SMS provider (e.g. Twilio, MSG91 Inbound, Telnyx, or Android GSM SMS Forwarder Gateway).
   - Inbound number must match `INBOUND_SMS_RECEIVER_NUMBER`.
2. **Webhook Forwarding URL:**
   - Destination: `https://nexa-relay.onrender.com/api/v1/webhooks/sms-inbound`
   - Method: `POST`
   - Content-Type: `application/json`
3. **Payload Structure:**
   The backend natively parses:
   ```json
   {
     "sender_number": "+919876543210",
     "body": "NEXA-VERIFY:8F92AK39:SESSION_ID"
   }
   ```
   *(Also accepts form-encoded `From` and `Body` fields from Twilio-compatible webhooks).*
4. **Authentication Header:**
   The gateway must include either:
   - HMAC Header: `X-Gateway-Signature: <hex(hmac_sha256(body, GATEWAY_WEBHOOK_SECRET))>`
   - Or Token Header: `X-Webhook-Secret: <GATEWAY_WEBHOOK_SECRET>`
   - Or Authorization: `Authorization: Bearer <GATEWAY_WEBHOOK_SECRET>`

---

## 3. Render Diagnostics & Health Verification

Once environment variables are saved in Render:

1. **Verify `/health` endpoint:**
   ```bash
   curl -s https://nexa-relay.onrender.com/health
   ```
   Expected healthy response:
   ```json
   {
     "status": "UP",
     "redis": "CONNECTED",
     "mode": "Ephemeral-RAM-Only",
     "dtnBridge": "Active",
     "diskUsage": "0 Bytes (Zero-Disk)",
     "timestamp": 1730910000000
   }
   ```
   *(If `"redis": "DEGRADED_IN_MEMORY"` is returned, verify `REDIS_URL` credentials and network access in Render).*

2. **Verify Reverse SMS Initiation:**
   The mobile client triggers `POST /api/v1/auth/initiate-reverse-sms`. If configured properly, the server responds with a fresh cryptographic nonce and the live inbound phone number.
