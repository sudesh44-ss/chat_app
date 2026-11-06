# NEXA — Client-Initiated Reverse SMS Verification Architecture & Security Audit
**Audit Mode:** Strict Read-Only Architecture & Security Inspection  
**Target:** Android Application & Go Backend Infrastructure  
**Date:** March 2026  

---

## 1. Executive Summary

NEXA replaces traditional inbound SMS OTP verification with **Client-Initiated Reverse SMS Verification**. Instead of the server sending an SMS to the user's phone, the Android device sends an outbound SMS beacon to NEXA's inbound telecom gateway, proving physical SIM possession and phone number ownership.

### Audit Verdict: **IMPLEMENTED WITH CRITICAL SECURITY GAPS**
* **The Core Mechanism is Genuinely Implemented:** The system does **NOT** merely send an SMS and assume verification succeeded. The Go backend maintains an active verification challenge, waits for the incoming SMS webhook from the gateway, matches the extracted nonce against the pending record, and strictly verifies that the webhook's sender phone number matches the claimed registration phone number.
* **CRITICAL VULNERABILITY — Completely Unauthenticated Webhook:** The Go backend endpoints `/api/v1/webhooks/sms-inbound` and `/api/v1/webhooks/sms-received` possess **zero authentication**. There is no HMAC signature check, no secret bearer token, and no IP allowlist. An attacker knowing a user's phone number and nonce can spoof the webhook via a basic HTTP POST request, bypassing physical SIM verification completely.
* **No SMS Dispatch Callbacks on Android:** `SmsManager.sendTextMessage()` is invoked with `sentIntent = null` and `deliveryIntent = null`. If the carrier blocks the SMS, if roaming restrictions apply, or if the device lacks SMS balance, the app receives no failure notification and blindly polls until timeout.
* **Ephemeral In-Memory Nonce Storage:** Nonces and pending verification records are stored solely in Go memory (`map[string]*PendingVerification`). A server restart or horizontal scale-out wipes all active verification states.
* **HTTPS Polling, Not WebSockets:** Status detection is implemented via client-side HTTPS polling every 2 seconds against `GET /api/v1/auth/verification-status`. No WebSocket push notification is utilized.
* **Manual Fallback Works Without Permissions:** A manual fallback using `Intent(Intent.ACTION_SENDTO)` launches the device's default SMS app and successfully completes verification without requiring `android.permission.SEND_SMS`.

---

## 2. End-to-End Verification Pipeline Audit

```
┌─────────────────┐       1. Google Sign-In       ┌────────────────────────┐
│  Android Client │ ────────────────────────────> │  NEXA Go Backend       │
│                 │ <──────────────────────────── │                        │
│                 │    REQUIRE_SIM_VERIFICATION   └────────────────────────┘
│                 │
│                 │ 2. POST /api/v1/auth/initiate-reverse-verify
│                 │ ────────────────────────────> ┌────────────────────────┐
│                 │ <──────────────────────────── │ Nonce: "NX-8F92A"      │
│                 │    Nonce, Inbound, 90s TTL    │ Pending Record Stored  │
│                 │                               └────────────────────────┘
│ 3. SIM Detect   │
│    Dual-SIM Pick│
│                 │
│ 4. Outbound SMS │ 5. Physical GSM Outbound
│    via SIM      │ ────────────────────────────> ┌────────────────────────┐
│                 │                               │ Inbound Telecom Gateway│
│                 │                               │ (Virtual Receiver)     │
│                 │                               └───────────┬────────────┘
│                 │                                           │
│                 │ 7. Webhook POST                           │ 6. Inbound SMS
│                 │    (sender, body)                         │    Received
│                 │    [CRITICAL: UNAUTHENTICATED]            ▼
│                 │                               ┌────────────────────────┐
│                 │ <───────────────────────────  │ Validate Sender Number │
│                 │ 8. Poll Status (Every 2s)     │ Match Nonce            │
│                 │    GET /verification-status   │ Mark VERIFIED          │
│                 │ ────────────────────────────> │ Issue 30-Day Session   │
│                 │ <──────────────────────────── └────────────────────────┘
│                 │    Status: VERIFIED, Session  
└─────────────────┘
```

---

## 3. Step-by-Step Code Verification

### Step 1: Trigger on New Device
* **Android Location:** [`WelcomeViewModel.kt:127-139`](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/welcome/WelcomeViewModel.kt#L127-L139)
* **Backend Location:** [`handler.go:875-885`](file:///H:/chat_app/backend/internal/auth/handler.go#L875-L885)
* **Behavior:** When an existing user logs in with Google from a new device ID, the backend returns status `REQUIRE_SIM_VERIFICATION`. The client transitions to [`ProfileSetupScreen`](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/setup/ProfileSetupScreen.kt) or [`VerificationRadarScreen`](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/radar/VerificationRadarScreen.kt).

### Step 2: Challenge & Nonce Generation
* **Backend Location:** [`reverse_sms.go:172-186`](file:///H:/chat_app/backend/internal/auth/reverse_sms.go#L172-L186)
* **Code:**
  ```go
  func (s *ReverseSmsService) generateCryptoNonce() (string, error) {
      const charset = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
      charsetLen := big.NewInt(int64(len(charset)))
      buf := make([]byte, 5)

      for i := 0; i < 5; i++ {
          idx, err := rand.Int(rand.Reader, charsetLen)
          if err != nil {
              return "", err
          }
          buf[i] = charset[idx.Int64()]
      }

      return "NX-" + string(buf), nil
  }
  ```
* **Evaluation:** Cryptographically secure using `crypto/rand`. Generates a 5-character Base32 string prefixed with `NX-` (entropy: $32^5 = 33,554,432$ possible combinations).

### Step 3: Nonce Storage & Expiry Enforcement
* **Backend Location:** [`reverse_sms.go:218-239`](file:///H:/chat_app/backend/internal/auth/reverse_sms.go#L218-L239)
* **Code:**
  ```go
  now := time.Now().UnixMilli()
  record := &PendingVerification{
      Nonce:       nonce,
      PhoneNumber: canonicalPhone,
      Status:      "PENDING",
      CreatedAt:   now,
      ExpiresAt:   now + (90 * 1000), // 90 seconds strict TTL
  }
  s.pending[nonce] = record
  s.byPhone[canonicalPhone] = nonce
  ```
* **Storage Defect:** Stored strictly in Go RAM `map[string]*PendingVerification`. If the server container restarts or scales horizontally, active nonces evaporate.
* **Expiry Enforcement:** Enforced in [`reverse_sms.go:275-279`](file:///H:/chat_app/backend/internal/auth/reverse_sms.go#L275-L279) during webhook processing and in [`reverse_sms.go:449-453`](file:///H:/chat_app/backend/internal/auth/reverse_sms.go#L449-L453) during status polling.

### Step 4: SMS Payload Construction & Inbound Gateway Number
* **Payload Format:** `NX-VERIFY NX-XXXXX`
* **Gateway Configuration:** Set via environment variable `INBOUND_SMS_RECEIVER_NUMBER` (defaults to `+919800000000` in [`reverse_sms.go:134-138`](file:///H:/chat_app/backend/internal/auth/reverse_sms.go#L134-L138)).

### Step 5: Dual-SIM Detection & Subscription Selection
* **Android Location:** [`VerificationRadarViewModel.kt:131-169`](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/radar/VerificationRadarViewModel.kt#L131-L169)
* **Code:** Uses `SubscriptionManager.getActiveSubscriptionInfoList()`. If more than one active SIM is detected, state updates to `WAITING_SIM_SELECTION`, presenting the user with an interactive SIM card selector to choose the slot matching the claimed phone number. Requires `READ_PHONE_STATE`.

### Step 6: Outbound SMS Dispatch via `SmsManager`
* **Android Location:** [`VerificationRadarViewModel.kt:195-212`](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/radar/VerificationRadarViewModel.kt#L195-L212)
* **Code:**
  ```kotlin
  val smsManager = if (subId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      context.getSystemService(SmsManager::class.java).createForSubscriptionId(subId)
  } else if (subId != null) {
      @Suppress("DEPRECATION")
      SmsManager.getSmsManagerForSubscriptionId(subId)
  } else {
      @Suppress("DEPRECATION")
      SmsManager.getDefault()
  }

  smsManager.sendTextMessage(inbound, null, payload, null, null)
  ```
* **Critical Missing Callbacks:** The 4th and 5th arguments (`sentIntent` and `deliveryIntent`) are `null`! The application cannot detect if the SMS was dropped, rejected by carrier, or blocked by airplane mode.

### Step 7: Gateway Webhook Processing & Sender Validation
* **Backend Location:** [`handler.go:928-1016`](file:///H:/chat_app/backend/internal/auth/handler.go#L928-L1016) and [`reverse_sms.go:254-284`](file:///H:/chat_app/backend/internal/auth/reverse_sms.go#L254-L284)
* **Matching Logic:**
  1. Parses incoming sender phone and body from JSON or form-urlencoded parameters.
  2. Extracts nonce using regex `NX-[2-9A-HJ-NP-Z0-9]{4,10}`.
  3. Looks up `record = s.pending[nonce]`.
  4. Checks `now > record.ExpiresAt`.
  5. **Validates Sender Number:** `record.PhoneNumber == canonicalSender`.
  6. Rejects mismatches with `ErrPhoneMismatch`.
* **Finding:** The backend **genuinely validates that the SMS sender phone matches the registration phone number**.

---

## 4. Critical Security Vulnerability: Unauthenticated Webhook

### Vulnerability Analysis ([`handler.go:928-1016`](file:///H:/chat_app/backend/internal/auth/handler.go#L928-L1016))
```go
func (h *AuthHandler) handleInboundSmsWebhook(w http.ResponseWriter, r *http.Request) {
    if r.Method != http.MethodPost {
        http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
        return
    }

    sender := ""
    body := ""
    // Decodes JSON or form-urlencoded ...
    // NO HMAC check
    // NO secret token check
    // NO IP allowlisting

    record, err := h.reverseSmsService.ProcessInboundWebhook(sender, body)
    ...
}
```

### Exploit Scenario
1. Target victim registers phone number `+919876543210`.
2. Backend returns nonce `NX-7K99M` (valid for 90 seconds).
3. If an adversary discovers or brute-forces the 5-character nonce within the 90-second window, or intercepts the HTTP traffic, the adversary sends a single HTTP POST request to the NEXA server:
   ```bash
   curl -X POST https://api.nexa.im/api/v1/webhooks/sms-inbound \
     -H "Content-Type: application/json" \
     -d '{"sender_number": "+919876543210", "body": "NX-VERIFY NX-7K99M"}'
   ```
4. The backend marks the verification `VERIFIED`, registers the adversary's device key, and returns a 30-day authenticated session.
5. **Severity:** **CRITICAL**. Bypasses the entire reverse SMS security architecture.

---

## 5. Rate Limiting & Abuse Prevention

Implemented in [`reverse_sms.go:198-211`](file:///H:/chat_app/backend/internal/auth/reverse_sms.go#L198-L211):

| Dimension | Threshold | Window | Error Code |
|---|---|---|---|
| Client IP | Max 5 verification initiations | 15 minutes | `429 RATE_LIMITED` |
| Google UID / Device ID | Max 3 verification initiations | 24 hours | `429 RATE_LIMITED` |

* **Observation:** The observed error:
  `"Reverse verification request failed: rate limit exceeded for reverse verification: maximum attempts reached: maximum 3 verification attempts per 24 hours"`
  is triggered by line 208 when a single Google UID or Device ID attempts more than 3 verifications within 24 hours.

---

## 6. Manual Fallback Architecture

### Implementation in [`VerificationRadarScreen.kt:424-442`](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/radar/VerificationRadarScreen.kt#L424-L442)
```kotlin
val intent = Intent(Intent.ACTION_SENDTO).apply {
    data = Uri.parse("smsto:${uiState.inboundNumber}")
    putExtra("sms_body", uiState.smsPayload)
}
context.startActivity(intent)
```
* **How it works:**
  1. The app launches the user's default SMS app (Google Messages / Samsung Messages) with the gateway number and payload pre-filled.
  2. The user taps "Send" in their SMS app.
  3. The user switches back to NEXA.
  4. NEXA's background coroutine `startPollingStatus()` continues polling `GET /api/v1/auth/verification-status`.
  5. When the gateway webhook receives the SMS, polling succeeds and logs the user in.
* **Architectural Advantage:** This flow requires **ZERO Android permissions** (`SEND_SMS` and `READ_PHONE_STATE` are completely unnecessary).

---

## 7. Implementation Status Checklist

| Step / Feature | Status | Evaluation |
|---|---|---|
| Cryptographic Nonce Generation | **IMPLEMENTED** | High entropy Base32 `NX-XXXXX` using `crypto/rand`. |
| 90-Second Strict Expiry | **IMPLEMENTED** | Validated in memory on poll and webhook. |
| In-Memory Nonce Persistence | **PARTIAL** | Ephemeral RAM map; non-persistent across restarts. |
| Inbound Gateway Configuration | **IMPLEMENTED** | Configurable via environment variable. |
| Outbound SMS Dispatch (`SmsManager`) | **IMPLEMENTED** | Dispatches to specified subscription ID. |
| Dual-SIM Selection UI | **IMPLEMENTED** | Detects multiple subscriptions and displays picker. |
| SMS Sent / Delivery Callbacks | **NOT IMPLEMENTED** | `sentIntent` and `deliveryIntent` are passed as `null`. |
| Inbound Gateway Webhook | **IMPLEMENTED** | Parses JSON and form-urlencoded payloads. |
| Webhook Authentication / HMAC | **NOT IMPLEMENTED (CRITICAL)** | Open endpoint without secret, signature, or IP filter. |
| Sender Phone Matching | **IMPLEMENTED** | Strict domestic Indian canonicalization and sender validation. |
| Verification Status Polling | **IMPLEMENTED** | HTTPS polling every 2 seconds. |
| WebSocket Verification Push | **NOT IMPLEMENTED** | Relies purely on polling; no WebSocket push. |
| Manual Fallback (`ACTION_SENDTO`) | **IMPLEMENTED** | Launches system SMS app with pre-filled body. |
| Rate Limiting | **IMPLEMENTED** | 5/15m per IP and 3/24h per UID/device enforced. |

---

## 8. Remediation Plan

1. **Secure the Webhook Endpoint (Urgent):**
   Add HMAC-SHA256 signature verification to `handleInboundSmsWebhook`:
   ```go
   sig := r.Header.Get("X-Gateway-Signature")
   expected := hmacSha256(webhookSecret, rawBody)
   if !hmac.Equal([]byte(sig), []byte(expected)) {
       http.Error(w, "Unauthorized webhook", http.StatusUnauthorized)
       return
   }
   ```
2. **Add `PendingIntent` Result Listeners to Android SMS Dispatch:**
   Pass broadcast `PendingIntent` objects for `sentIntent` and `deliveryIntent` in `smsManager.sendTextMessage()` to immediately detect and report cellular transmission failures.
3. **Persist Pending Nonces in Redis:**
   Store pending verification challenges in Redis with a 90-second TTL (`SETEX nexa:reverse:<nonce> 90 <json>`) to support multi-instance backend clusters and resilience against restarts.
