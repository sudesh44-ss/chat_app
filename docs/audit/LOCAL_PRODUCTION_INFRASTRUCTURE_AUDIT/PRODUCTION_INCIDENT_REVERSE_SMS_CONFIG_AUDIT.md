# Production Incident Audit: Reverse SMS Configuration (`INBOUND_SMS_RECEIVER_NUMBER`)

## 1. Incident Overview & Log Signature

```text
2026/11/10 10:13:40 [ReverseSMS] CRITICAL:
INBOUND_SMS_RECEIVER_NUMBER is not configured in production!
```

- **Timestamp:** 2026-11-10 10:13:40 UTC
- **Severity:** **P1 (Critical Feature Unavailable / Registration Blocked)**
- **Subsystem:** Phone Verification & Client-Initiated Reverse SMS Engine (`backend/internal/auth/reverse_sms.go`)
- **Impact:** Server logs a critical warning during startup, but **continues booting**. However, any user attempting to register or verify their phone number via Reverse SMS receives **HTTP 500 Internal Server Error**.

---

## 2. Forensic Investigation & Code Trace

### 2.1 Configuration Ingestion at Startup
In [`backend/internal/auth/reverse_sms.go:160-178`](file:///H:/chat_app/backend/internal/auth/reverse_sms.go#L160-L178):
```go
func NewReverseSmsService(store identity.IdentityRepository) *ReverseSmsService {
    inbound := strings.TrimSpace(os.Getenv("INBOUND_SMS_RECEIVER_NUMBER"))
    env := strings.ToLower(os.Getenv("APP_ENV"))
    if env == "" {
        env = strings.ToLower(os.Getenv("ENV"))
    }
    isProduction := env == "production"

    if inbound == "" {
        if isProduction {
            log.Println("[ReverseSMS] CRITICAL: INBOUND_SMS_RECEIVER_NUMBER is not configured in production!")
        } else {
            // Default automated inbound virtual receiver / GSM gateway number for local dev/testing
            inbound = "+919800000000"
        }
    } else if isProduction && (inbound == "+919800000000" || inbound == "+910000000000") {
        log.Printf("[ReverseSMS] CRITICAL: Dummy or placeholder INBOUND_SMS_RECEIVER_NUMBER (%s) detected in production!", inbound)
    }

    return &ReverseSmsService{
        ...
        inboundReceiverNum: inbound,
        trustedRegistry:    NewTrustedPhoneRegistry(),
    }
}
```

### 2.2 Runtime Failure Point: Verification Initiation
In [`backend/internal/auth/reverse_sms.go:305-308`](file:///H:/chat_app/backend/internal/auth/reverse_sms.go#L305-L308):
```go
// If not trusted, require configured inbound SMS receiver number in production
if !isTrusted && isProduction && (s.inboundReceiverNum == "" || s.inboundReceiverNum == "+919800000000" || s.inboundReceiverNum == "+910000000000") {
    return nil, fmt.Errorf("reverse verification unavailable: real INBOUND_SMS_RECEIVER_NUMBER is required in production environment (dummy placeholder rejected)")
}
```
And in [`backend/internal/auth/handler.go:977-1006`](file:///H:/chat_app/backend/internal/auth/handler.go#L977-L1006):
```go
resp, err := h.reverseSmsService.InitiateVerification(&req, getClientIP(r))
if err != nil {
    w.Header().Set("Content-Type", "application/json")
    switch {
    ...
    default:
        w.WriteHeader(http.StatusInternalServerError)
        json.NewEncoder(w).Encode(map[string]interface{}{
            "error": err.Error(),
        })
    }
    return
}
```

### 2.3 The Failure Sequence
1. In Render (or production container), `APP_ENV=production` is set, but `INBOUND_SMS_RECEIVER_NUMBER` is either missing, empty, or set to a placeholder (`+919800000000`).
2. On server start, `NewReverseSmsService()` logs the critical message but does **not** abort startup (`log.Println` instead of `log.Fatalf`).
3. An Android client makes an HTTP POST request to `/api/v1/auth/initiate-reverse-verify` or `/api/v1/auth/initiate-registration`.
4. `InitiateVerification()` checks `s.inboundReceiverNum`. Because the service is running in `production` with an empty or placeholder receiver number, it rejects the request with an error:
   `"reverse verification unavailable: real INBOUND_SMS_RECEIVER_NUMBER is required in production environment (dummy placeholder rejected)"`.
5. The HTTP handler writes `500 Internal Server Error`.
6. The Android application cannot retrieve an inbound GSM number or verification payload, displaying a registration failure error to the user.

---

## 3. Detailed Answers to Audit Questions

| Audit Question | Independent Finding |
|---|---|
| **1. Where is the variable read?** | [`backend/internal/auth/reverse_sms.go:161`](file:///H:/chat_app/backend/internal/auth/reverse_sms.go#L161) during `NewReverseSmsService()` initialization. |
| **2. Is it mandatory or optional?** | **MANDATORY in production** (`APP_ENV=production`). Optional in development (defaults to mock number `+919800000000`). |
| **3. What happens when it is missing?** | Logs `[ReverseSMS] CRITICAL: ...`, and all non-trusted verification attempts fail closed with HTTP 500. |
| **4. Is the feature enabled in production?** | Yes, Client-Initiated Reverse SMS is the primary production authentication mechanism for NEXA. |
| **5. Does startup continue despite the log?** | **YES**. `log.Println` is used, allowing the server process to continue booting. |
| **6. Is inbound SMS processing unavailable?** | Webhook processing (`/api/v1/webhooks/sms-inbound`) is technically capable of parsing incoming SMS webhooks, but **no verification can complete** because clients can never initiate a session to generate a valid nonce. |
| **7. Is outbound SMS affected?** | **NO**. Outbound OTP dispatch operates through `OtpService` using `MSG91_AUTH_KEY` / `TWILIO_ACCOUNT_SID`. However, if outbound SMS is unconfigured, Reverse SMS was the intended fallback, leaving both paths down. |
| **8. Does webhook verification depend on this number?** | **NO**. Webhook HMAC authentication (`VerifyGatewaySignature`) depends on `GATEWAY_WEBHOOK_SECRET` and `X-Gateway-Signature` header, not `INBOUND_SMS_RECEIVER_NUMBER`. |
| **9. Is the expected format E.164?** | **YES**. Valid domestic Indian mobile/virtual number starting with `+91` followed by 10 digits (`+91XXXXXXXXXX`). |
| **10. Is deployment documentation accurate?** | Yes. `REQUIRED_ENVIRONMENT_VARIABLES.md` and `RENDER_ENVIRONMENT_CONFIGURATION_MATRIX.md` documented this variable as mandatory. The defect is an **operational omission** in the Render production environment variables. |

---

## 4. Security & Operational Assessment

- **Security Verification (Fail-Closed Architecture):**
  The fail-closed check in `reverse_sms.go:306` functioned as designed. It prevented the application from returning a dummy or non-functional telephone number to real users, preventing SMS messages containing authentication nonces from being sent into a carrier black hole or unmonitored number.
- **Trusted Phone Bypass Exception:**
  Phone numbers defined in `NEXA_TRUSTED_PHONE_NUMBERS` bypass this check ([`reverse_sms.go:285-290`](file:///H:/chat_app/backend/internal/auth/reverse_sms.go#L285-L290)) and continue to onboard successfully under `verification_method: "trusted_allowlist"`.
- **Public User Onboarding Impact:**
  All standard public users cannot onboard or register.
