# NEXA — Render Production Environment Setup Checklist

**Target:** Render Web Service Dashboard (`dashboard.render.com`)  
**Service:** `nexa-relay` (Backend Go Web Service)  
**Classification:** Production Readiness Guide  
**Date:** 2026-11-10  

---

## 1. Overview & Setup Instructions

To configure the NEXA backend on Render:
1. Navigate to [Render Dashboard](https://dashboard.render.com) &rarr; **Web Services** &rarr; Select your NEXA backend service.
2. In the left navigation, click **Environment**.
3. Use the checklist below to add each environment variable.
4. Click **Save Changes** (Render will automatically trigger a zero-downtime rolling deployment).

---

## 2. Master Render Configuration Checklist

### Group A: REQUIRED (P0 — Mandatory for Production Startup & Core Onboarding)

Failure to set any of these variables results in either immediate process termination at startup or complete failure of public user onboarding.

- [ ] **`APP_ENV`**
  - **Classification:** `REQUIRED`
  - **Value to Enter:** `production`
  - **Purpose:** Enforces fail-closed security mode, rejects mock SMS providers, rejects volatile in-memory fallback queues, and enforces single-instance DB safety.
  - **Validation:** Must be lowercase `production`.

- [ ] **`DATABASE_URL`**
  - **Classification:** `REQUIRED`
  - **Value to Enter:** `postgresql://postgres:[PASSWORD]@[HOST]:[PORT]/postgres?sslmode=require`
  - **Where to Obtain:** Supabase Dashboard &rarr; Project Settings &rarr; Database &rarr; Connection String &rarr; URI.
  - **Validation:** Must support SSL (`sslmode=require`).
  - **Startup Behavior if Missing:** Server immediately aborts startup with `log.Fatalf`.

- [ ] **`REDIS_URL`**
  - **Classification:** `REQUIRED`
  - **Value to Enter:** `rediss://default:[PASSWORD]@[HOST]:[PORT]`
  - **Where to Obtain:** Upstash Console or Render Redis Service.
  - **Validation:** Must use `rediss://` scheme (with double `s`) for TLS encryption.
  - **Startup Behavior if Missing:** Server immediately aborts startup with `log.Fatalf` (degraded in-memory fallback rejected in production).

- [ ] **`GOOGLE_CLIENT_ID`**
  - **Classification:** `REQUIRED` (Core Google Auth)
  - **Value to Enter:** `356056510017-grmokt7u1sch6hdk9dbah85k2jqe32v2.apps.googleusercontent.com`
  - **Where to Obtain:** Google Cloud Console &rarr; APIs & Services &rarr; Credentials &rarr; OAuth 2.0 Web Client.
  - **Validation:** Must strictly match Android `R.string.google_web_client_id`.
  - **Behavior if Missing:** `POST /api/v1/auth/google` fails closed with `401 Unauthorized`.

- [ ] **`INBOUND_SMS_RECEIVER_NUMBER`**
  - **Classification:** `REQUIRED` (Core Reverse SMS)
  - **Value to Enter:** `+91XXXXXXXXXX` (Real domestic Indian GSM number)
  - **Where to Obtain:** Dedicated Indian telecom SIM or virtual long-code gateway provider.
  - **Validation:** E.164 format starting with `+91` followed by 10 digits starting with 6–9. Placeholders like `+919800000000` are rejected in production.
  - **Behavior if Missing:** Non-trusted onboarding fails closed with `HTTP 500`.

- [ ] **`GATEWAY_WEBHOOK_SECRET`**
  - **Classification:** `REQUIRED` (Core Reverse SMS Webhook)
  - **Value to Enter:** `[32+ byte high-entropy secret string or 64-char Hex]`
  - **How to Generate:** Run `openssl rand -hex 32` in terminal.
  - **Validation:** Minimum 32 bytes. Shared with the telecom webhook sender.
  - **Behavior if Missing:** Inbound carrier webhooks fail closed with `401 Unauthorized`.

---

### Group B: FEATURE-DEPENDENT (P1 — Mandatory if Feature is Enabled)

These variables activate specific ecosystem features. If omitted, the corresponding feature is disabled or runs in mock mode without halting core server messaging.

- [ ] **`NEXA_TRUSTED_PHONE_NUMBERS`**
  - **Classification:** `FEATURE-DEPENDENT` (Internal QA & Developer Testing)
  - **Value to Enter:** `+91XXXXXXXXXX,+91YYYYYYYYYY` (Comma-separated E.164 numbers)
  - **Purpose:** Allows registered developer/QA devices to bypass cellular SMS transmission and auto-verify immediately.
  - **Validation:** If any number in the list is malformed in production, the entire allowlist fails closed (cleared).

- [ ] **`FCM_PROJECT_ID`**
  - **Classification:** `FEATURE-DEPENDENT` (Background Wake-Up Push)
  - **Value to Enter:** `nexa-chat-prod` (Firebase Project ID)
  - **Where to Obtain:** Firebase Console &rarr; Project Settings &rarr; Project ID.
  - **Behavior if Missing:** `FcmService` runs in mock log-only mode. Active WebSockets work, but backgrounded devices will not receive wake-up pings.

- [ ] **`MSG91_AUTH_KEY` & `MSG91_TEMPLATE_ID`**
  - **Classification:** `FEATURE-DEPENDENT` (Legacy Outbound SMS OTP — India)
  - **Value to Enter:** MSG91 Auth Key and DLT-approved Template ID.
  - **Behavior if Missing:** Outbound OTP is disabled (`UnavailableSmsProvider`); Client-Initiated Reverse SMS remains active as primary.

- [ ] **`TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN`, `TWILIO_FROM_NUMBER`**
  - **Classification:** `FEATURE-DEPENDENT` (Legacy Outbound SMS OTP — Global)
  - **Value to Enter:** Twilio credentials (`AC...`, Auth Token, E.164 phone number).
  - **Behavior if Missing:** Twilio outbound SMS is disabled.

---

### Group C: OPTIONAL (P2 — Legacy / Fallback / Diagnostics)

- [ ] **`SUPABASE_JWT_SECRET`** & **`SUPABASE_URL`**
  - **Classification:** `OPTIONAL` (Legacy Supabase Auth Integration)
  - **Where to Obtain:** Supabase Dashboard &rarr; Project Settings &rarr; API.
  - **Behavior if Missing:** HMAC verification skipped on `/api/v1/auth/supabase/verify`.

- [ ] **`OTP_SERVER_PEPPER`**
  - **Classification:** `OPTIONAL` (Legacy OTP Code Hashing)
  - **Value to Enter:** 64-character hex string generated via `openssl rand -hex 32`.

---

### Group D: DO NOT CONFIGURE ON RENDER (Automatic / Development Only)

| Variable Name | Reason to Exclude from Render Dashboard |
|---|---|
| `PORT` | **Do not manually set.** Render automatically injects `$PORT` (typically `10000`). Manually setting it may break Render routing. |
| `DEBUG_SHOW_OTP` | **Prohibited in production.** Security risk. |
| `IDENTITY_STORE_PATH` | **Prohibited in production.** Production requires `DATABASE_URL`; running on local JSON store in production halts server boot. |
| `FIREBASE_SERVICE_ACCOUNT_JSON` | **Unused in Go code.** Not referenced by current backend. |
| `UPSTASH_REDIS_REST_URL` / `_TOKEN` | **Unused in Go code.** Backend connects via native Redis protocol (`REDIS_URL`). |
| `client_secret` | **Unused in Go code.** Unused by backend verifier. |
| `RELEASE_STORE_PASSWORD` etc. | **Android only.** Used in Gradle for APK/AAB signing, not by Go backend. |

---

## 3. Render Dashboard Copy-Paste Summary

For rapid configuration, copy these exact variable names into the Render Environment editor:

```text
APP_ENV=production
DATABASE_URL=postgresql://postgres:[PASSWORD]@[HOST]:[PORT]/postgres?sslmode=require
REDIS_URL=rediss://default:[PASSWORD]@[HOST]:[PORT]
GOOGLE_CLIENT_ID=356056510017-grmokt7u1sch6hdk9dbah85k2jqe32v2.apps.googleusercontent.com
INBOUND_SMS_RECEIVER_NUMBER=+91XXXXXXXXXX
GATEWAY_WEBHOOK_SECRET=[GENERATED_32_BYTE_HEX_SECRET]
NEXA_TRUSTED_PHONE_NUMBERS=+91XXXXXXXXXX
FCM_PROJECT_ID=nexa-chat-prod
```
