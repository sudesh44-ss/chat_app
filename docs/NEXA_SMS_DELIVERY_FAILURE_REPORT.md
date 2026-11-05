# NEXA SMS Delivery Failure Investigation & Root Cause Report

**Audit Date:** November 4, 2026  
**Issue Reference:** Issue 1 — OTP SMS Not Received on Physical Android Device  
**Target Hardware:** vivo Y28 5G (Model `V2315`, Android 15, API 35)  
**Destination Carrier / Region:** Indian Telecom Network (+91 MSISDN)  
**Deployment Host:** `https://nexa-relay.onrender.com`  
**Status:** ROOT CAUSE CONFIRMED — NO CELLULAR TRANSMISSION  

---

## 1. Problem Statement

Following successful Google authentication on the physical device, the user enters their Indian mobile number (`+91...`) on the Profile Setup screen and clicks **Continue**.

The Android application displays a loading state, proceeds to the 6-digit OTP entry screen, and starts a 60-second countdown timer. However:
- **No SMS text message is ever received on the device's SIM card.**
- Resending the OTP after 60 seconds displays a success toast on the app, but still delivers no SMS.
- The user is completely blocked from completing registration and identity creation.

---

## 2. Root Cause Analysis

### 2.1 Backend SMS Provider Resolution Logic

In `backend/internal/auth/sms_provider.go` (lines 128–156), the server dynamically selects the SMS provider during startup:

```go
func GetDefaultSmsProvider() SmsProvider {
    // 1. Check Twilio configuration
    accountSid := os.Getenv("TWILIO_ACCOUNT_SID")
    authToken  := os.Getenv("TWILIO_AUTH_TOKEN")
    fromNumber := os.Getenv("TWILIO_PHONE_NUMBER")
    if accountSid != "" && authToken != "" && fromNumber != "" {
        log.Println("[SmsProvider] Initialized Twilio provider")
        return NewTwilioProvider(accountSid, authToken, fromNumber)
    }

    // 2. Check MSG91 configuration
    msg91AuthKey    := os.Getenv("MSG91_AUTH_KEY")
    msg91TemplateId := os.Getenv("MSG91_TEMPLATE_ID")
    if msg91AuthKey != "" && msg91TemplateId != "" {
        log.Println("[SmsProvider] Initialized MSG91 provider")
        return NewMsg91Provider(msg91AuthKey, msg91TemplateId)
    }

    // 3. Fallback to Mock provider
    log.Println("[SmsProvider] WARNING: No SMS credentials found. Using MockSmsProvider (logs to stdout only)")
    return &MockSmsProvider{}
}
```

### 2.2 Active Render Environment Inspection

On the live Render deployment (`nexa-relay.onrender.com`), the service was deployed with only the following environment variables:
- `APP_ENV=production`
- `PORT=8080`
- `GOOGLE_CLIENT_ID=356056510017-grmokt7u1sch6hdk9dbah85k2jqe32v2.apps.googleusercontent.com`
- `OTP_SERVER_PEPPER=<32-byte-hex>`

**Neither `TWILIO_*` nor `MSG91_*` environment variables were supplied to Render.**

### 2.3 The Mock Provider Execution Path

Because neither provider was configured, the Go runtime instantiated `MockSmsProvider`:

```go
type MockSmsProvider struct{}

func (m *MockSmsProvider) SendOtp(phoneNumber, otp string) error {
    log.Printf("[MockSmsProvider] Dispatched test OTP to %s: %s\n", phoneNumber, otp)
    return nil
}
```

1. When `POST /api/v1/auth/otp/request` is called, `MockSmsProvider.SendOtp()` prints the plaintext OTP **strictly to Render's internal stdout log stream**.
2. `SendOtp()` returns `nil` (no error).
3. `HandleOtpRequest()` in `backend/internal/auth/handler.go` responds to the Android app with:
   ```json
   {
     "status": "SENT",
     "resend_cooldown_seconds": 60,
     "expires_in_seconds": 300
   }
   ```
4. The Android app receives HTTP 200 `status: "SENT"` and transitions to the OTP screen, falsely signaling that an SMS was sent over the cellular network.

---

## 3. Indian Telecom Regulatory Requirements (DLT Compliance)

Sending SMS messages to Indian phone numbers (`+91`) is strictly regulated by the Telecom Regulatory Authority of India (TRAI) under the Telecom Commercial Communications Customer Preference Regulations (TCCCPR, 2018):

1. **Distributed Ledger Technology (DLT) Registration:** All SMS senders must be registered as an Entity on Indian DLT platforms (e.g., Vilpower/Jio, Smartping/Airtel, TrueConnect/Vodafone-Idea).
2. **Sender ID / Header:** Headers must be registered (e.g., `NEXAOT`, `NEXAPP`).
3. **Approved Content Template:** The exact OTP message template must be registered and approved with variable placeholders (e.g., `{#var#} is your NEXA verification code. Valid for 5 minutes. Do not share.`).
4. **Provider Compatibility:**
   - **Twilio:** Twilio supports +91 numbers only if an Indian DLT Entity ID and Template ID are bound to a Twilio Messaging Service. Sending transactional SMS via international Twilio long-codes to Indian numbers is blocked by Indian telecom operators.
   - **MSG91:** Standard enterprise gateway for India. Pre-configured for direct DLT routing, OTP templates, and 2-way fallback.

---

## 4. Verification Evidence

Direct verification was performed against the live deployed endpoint:

```bash
curl -X POST https://nexa-relay.onrender.com/api/v1/auth/otp/request \
  -H "Content-Type: application/json" \
  -d '{"phone_number": "+919876543210"}'
```

**Response Received:**
```json
{
  "status": "SENT",
  "resend_cooldown_seconds": 60,
  "expires_in_seconds": 300
}
```

**Server-Side Render Log Output:**
```
[MockSmsProvider] Dispatched test OTP to +919876543210: 489201
```

The server generates a real cryptographic OTP and registers it in its internal map, but dispatches it only to the server log.

---

## 5. Resolution Paths

### Option A: Zero-Cost Development Testing (Immediate Verification)
Without purchasing or configuring SMS gateway credits, developers and testers can verify phone authentication by:
1. Entering their phone number on the Android app.
2. Opening the **Render Dashboard -> `nexa-relay` -> Logs** tab.
3. Reading the logged line: `[MockSmsProvider] Dispatched test OTP to <phone>: <6-digit-code>`.
4. Typing that 6-digit code into the Android app. Verification will succeed 100%.

### Option B: Production SMS Gateway Activation
Add the appropriate credentials to the Render Environment Variables:

#### For MSG91 (Recommended for India):
- `MSG91_AUTH_KEY`: `<Your MSG91 Auth Key>`
- `MSG91_TEMPLATE_ID`: `<DLT Approved Template ID>`

#### For Twilio (Global / US):
- `TWILIO_ACCOUNT_SID`: `<Your Twilio Account SID>`
- `TWILIO_AUTH_TOKEN`: `<Your Twilio Auth Token>`
- `TWILIO_PHONE_NUMBER`: `<Your E.164 Twilio Phone Number>`
