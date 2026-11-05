# NEXA SMS Provider Configuration & Delivery Audit

**Report Date:** November 4, 2026  
**Subject:** Investigation of Render Warning & SMS Provider Configuration  
**Status:** **AUDITED & HARDENED**  

---

## 1. Production Issue: Provider Warning in Render Logs

### Render Log Evidence:
```text
2026/11/04 13:23:57 [SmsProvider] WARNING: No production SMS provider configured! Check TWILIO_* or MSG91_* env vars.
2026/11/04 12:45:19 [SmsProvider] WARNING: No production SMS provider configured! Check TWILIO_* or MSG91_* env vars.
```

### Investigation Findings:

1. **Does the project already contain Twilio integration?**
   **YES.** Located in `backend/internal/auth/sms_provider.go` (`TwilioProvider`).
2. **Does the project already contain MSG91 integration?**
   **YES.** Located in `backend/internal/auth/sms_provider.go` (`Msg91Provider`).
3. **Is there a provider abstraction or SmsProvider interface?**
   **YES.** `type SmsProvider interface { SendOtp(phone string, otp string) error; Name() string }`.
4. **Which provider was selected on Render?**
   Because neither `MSG91_*` nor `TWILIO_*` environment variables were supplied to the Render service, `GetDefaultSmsProvider()` fell back to `MockSmsProvider`.
5. **What exact environment variable names does the code expect?**
   - MSG91: `MSG91_AUTH_KEY` and `MSG91_TEMPLATE_ID`.
   - Twilio: `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN`, and `TWILIO_FROM_NUMBER` (or `TWILIO_PHONE_NUMBER`).
6. **Are environment variables missing from Render?**
   **YES.** Render had only `APP_ENV`, `GOOGLE_CLIENT_ID`, `OTP_SERVER_PEPPER`, and `PORT`.
7. **Did the backend report false success?**
   **YES (PREVIOUSLY).** `MockSmsProvider` logged the code to stdout and returned `nil`. The API returned HTTP 200 `{"status":"SENT"}` to Android, falsely indicating cellular delivery.

---

## 2. Hardened Architecture (No Production Mock Bypass)

The code in `backend/internal/auth/sms_provider.go` has been refactored to enforce strict production security:

1. **MockSmsProvider Disabled in Production:**
   - If `APP_ENV=production` and no SMS credentials are provided, `GetDefaultSmsProvider()` initializes `UnavailableSmsProvider`.
   - Any attempt to invoke `SendOtp()` returns an explicit error: `SMS delivery service is unavailable: missing MSG91_AUTH_KEY/MSG91_TEMPLATE_ID`.
   - If `MockSmsProvider` is directly invoked when `APP_ENV=production`, it logs a security alert and rejects the dispatch with an error.
2. **HTTP 503 Service Unavailable:**
   - In `backend/internal/auth/handler.go`, when provider dispatch fails, the server responds with **HTTP 503 Service Unavailable**:
     ```json
     {
       "error": "SMS delivery service is unavailable",
       "code": "SMS_UNAVAILABLE"
     }
     ```
   - The false HTTP 200 `status: "SENT"` is **completely eliminated**.
3. **Plaintext OTP Redaction:**
   - No plaintext OTPs are logged in production logs. Phone numbers are masked (`+91****43210`).
4. **Atomic Rollback on Dispatch Failure:**
   - If MSG91 network delivery fails, the pending OTP record is deleted from RAM and rate limit trackers are rolled back so the user is not locked out by provider errors.

---

## 3. Provider Configuration Guide

### For MSG91 (Recommended for Indian `+91` Numbers):
Add these environment variables in **Render Dashboard $\rightarrow$ Environment**:
```env
MSG91_AUTH_KEY=<your_msg91_auth_key>
MSG91_TEMPLATE_ID=<dlt_approved_template_id>
```

#### DLT India Regulatory Steps:
1. Register company on Indian DLT portal (Vilpower/Smartping).
2. Register Sender ID / Header (e.g. `NEXAOT`).
3. Create Content Template: `Your NEXA verification code is ##OTP##. Valid for 5 minutes.`
4. Copy the approved Template ID into `MSG91_TEMPLATE_ID`.

### For Twilio:
```env
TWILIO_ACCOUNT_SID=<your_account_sid>
TWILIO_AUTH_TOKEN=<your_auth_token>
TWILIO_FROM_NUMBER=<your_twilio_phone_number>
```
