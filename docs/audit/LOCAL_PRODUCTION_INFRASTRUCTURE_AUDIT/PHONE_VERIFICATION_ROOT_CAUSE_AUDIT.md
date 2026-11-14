# NEXA — Phone Verification Root Cause & Gateway Audit

**Audit Mode:** STRICT READ-ONLY INSPECTION  
**Target:** Client-Initiated Reverse SMS Verification Flow  
**Date:** November 2026  

---

## 1. Problem Statement

During user onboarding, the application reaches the Phone Number Entry screen (`ProfileSetupScreen`), initiates verification, and transitions to the Cryptographic Verification screen (`VerificationRadarScreen`). However, **successful verification is never confirmed**.

After 90 seconds, the screen displays:
`Verification Window Expired: The 90-second security window expired before the carrier SMS was matched.`

---

## 2. Root Cause Breakdown

The failure is caused by an **unconnected telecom loop** combined with **missing production deployment configuration**:

```
[Android Device] ──(Dispatches SMS)──> [+919800000000 (Dummy / Black Hole)]
                                                     │
                                                     ▼
                                          [No Carrier Receiver]
                                                     │
                                              (Webhook NEVER fired)
                                                     │
                                                     ▼
                                   [Render Backend: Status stays PENDING]
                                                     │
                                                     ▼
                                   [Android Polling: Times out after 90s]
```

### Detailed Factor Analysis:

#### Factor 1: Hardcoded Dummy Inbound Number (`+919800000000`)
- **Code:** `backend/internal/auth/reverse_sms.go:142-146`:
  ```go
  inbound := os.Getenv("INBOUND_SMS_RECEIVER_NUMBER")
  if inbound == "" {
      inbound = "+919800000000"
  }
  ```
- **Evidence:** `INBOUND_SMS_RECEIVER_NUMBER` is not configured in Render. The backend returns `+919800000000`.
- **Result:** The user's phone sends the SMS beacon to `+919800000000`. This is not an active cellular receiver or GSM modem.

#### Factor 2: Missing Webhook URL Configuration at Carrier
- Even if a developer owns an inbound number (e.g., Twilio Virtual Number or GSM Modem), the inbound SMS webhook URL (`https://nexa-relay.onrender.com/api/v1/webhooks/sms-inbound` or `/api/v1/auth/inbound-sms`) must be configured in the telecom provider's dashboard.

#### Factor 3: Webhook Fails Closed on Missing Secret
- **Code:** `backend/internal/auth/webhook_auth.go:32-35`:
  ```go
  secret := strings.TrimSpace(os.Getenv("GATEWAY_WEBHOOK_SECRET"))
  if secret == "" {
      return ErrMissingWebhookSecret
  }
  ```
- **Evidence:** `GATEWAY_WEBHOOK_SECRET` is not set on Render.
- **Result:** Even if an inbound SMS is forwarded to Render via webhook, `VerifyGatewaySignature` rejects the request with `HTTP 401 Unauthorized`.

#### Factor 4: Volatile RAM Storage of Nonces
- **Code:** `backend/internal/auth/reverse_sms.go:126`:
  ```go
  pending map[string]*PendingVerification // nonce -> record
  ```
- **Evidence:** Pending verification nonces are stored solely in an in-memory Go map.
- **Result:** If the Render web service restarts or wakes from sleep during the 90-second window, all pending nonces are erased.

---

## 3. Checklist of Possible Failures Investigated

| # | Potential Failure Scenario | Status | Findings |
| :---: | :--- | :---: | :--- |
| **1** | SMS provider not configured | **CONFIRMED** | `INBOUND_SMS_RECEIVER_NUMBER` unset on Render. |
| **2** | Backend cannot dispatch SMS | **NOT APPLICABLE** | Reverse SMS is client-initiated; backend doesn't dispatch. |
| **3** | Android SmsManager reports send failure | **RESOLVED** | Android now uses `Intent.ACTION_SENDTO` (Zero Permission). |
| **4** | SMS gateway does not receive outgoing message | **CONFIRMED** | Sent to non-existent placeholder `+919800000000`. |
| **5** | Inbound webhook URL incorrect | **CONFIRMED** | Provider webhook not configured to point to Render. |
| **6** | Webhook authentication fails | **CONFIRMED** | `GATEWAY_WEBHOOK_SECRET` unset; fails closed with 401. |
| **7** | Webhook payload format mismatch | **RESOLVED** | Code updated to parse `NEXA-VERIFY:<nonce>:<sessionId>` and `NX-VERIFY`. |
| **8** | Sender phone normalization failure | **POTENTIAL RISK** | Requires E.164 canonicalization (`+91...`). |
| **9** | Nonce does not match pending challenge | **POTENTIAL RISK** | If container restarts, pending map is wiped. |
| **10**| Challenge expires before verification | **CONFIRMED** | 90s countdown expires because webhook is never received. |
| **11**| Redis is disconnected | **CONFIRMED** | Server dials `localhost:6379`. |
| **12**| Android UI does not process verification response | **RESOLVED** | Tested; UI auto-navigates when `is_verified == true`. |
