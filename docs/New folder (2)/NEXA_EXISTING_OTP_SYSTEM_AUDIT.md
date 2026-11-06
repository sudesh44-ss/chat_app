# NEXA Existing OTP System Audit

**Audit Date:** November 4, 2026  
**Auditors:** Senior Go Backend Engineer, Android Security Architect, DevOps Specialist  
**Target System:** NEXA Identity & Authentication Pipeline (Android App & Go Backend)  
**Status:** COMPLETE — ARCHITECTURE INSPECTED  

---

## 1. Executive Summary

This audit evaluates the end-to-end design, implementation, and operational readiness of the NEXA One-Time Password (OTP) authentication system. The audit traces the OTP lifecycle from user input on the physical Android application (`vivo Y28 5G`), network transmission over HTTPS, cryptographic generation and hashing in the Go backend, SMS gateway dispatch, to verification and session ticket generation.

### High-Level Findings
1. **Core Cryptographic Engine:** **ROBUST & SOUND**. OTP generation uses `crypto/rand` with uniform distribution. OTP storage uses salted HMAC-SHA256 with server pepper. Plaintext OTPs are never stored. Timing attacks are mitigated using constant-time comparisons.
2. **Rate Limiting & Lifecycle:** **STRICT**. 3-attempt brute-force lockout, 5-minute time-to-live (TTL), 60-second resend cooldown, and immediate purge upon consumption.
3. **SMS Dispatch Defect (Issue 1):** **CONFIGURATION DEFICIT**. The Go backend is functioning properly, but the SMS dispatch mechanism falls back to `MockSmsProvider` because no external telecom credentials (`TWILIO_*` or `MSG91_*`) are present in the Render deployment environment.
4. **Storage Architecture:** **EPHEMERAL RAM**. OTPs and authorization proof tickets are stored in Go runtime memory protected by `sync.RWMutex`. This fits NEXA's zero-disk privacy architecture, but OTP state resets if the Render container restarts.

---

## 2. Architecture & File Inventory

### Backend Components
| File Path | Role | Key Structures / Methods |
| :--- | :--- | :--- |
| `backend/internal/auth/otp_service.go` | Cryptographic OTP generation, salted HMAC-SHA256 verification, in-memory store, auth ticket issuance | `OtpService`, `RequestOtp()`, `VerifyOtp()`, `ConsumeTicket()` |
| `backend/internal/auth/sms_provider.go` | SMS gateway abstraction and adapter layer | `SmsProvider` interface, `TwilioProvider`, `Msg91Provider`, `MockSmsProvider`, `GetDefaultSmsProvider()` |
| `backend/internal/auth/handler.go` | HTTP REST endpoints handling JSON serialization/deserialization | `HandleOtpRequest()`, `HandleOtpVerify()`, `HandleRegister()` |
| `backend/cmd/server/main.go` | HTTP server initialization and routing | Routes `/api/v1/auth/otp/request` and `/api/v1/auth/otp/verify` |

### Android Client Components
| File Path | Role | Key Structures / Methods |
| :--- | :--- | :--- |
| `app/.../auth/presentation/otp/OtpViewModel.kt` | UI state, 6-digit code entry, resend timer, submission | `verifyOtp()`, `resendOtp()`, `OtpUiState` |
| `app/.../auth/presentation/profile/ProfileSetupViewModel.kt` | Phone entry screen, country code picker, initial OTP trigger | `requestOtp()`, `onPhoneChanged()` |
| `app/.../core/network/ServerEndpointConfig.kt` | Dynamic endpoint configuration | `RENDER_HOST`, `USE_PRODUCTION_CLOUD`, `getBaseUrl()` |
| `app/.../auth/data/repository/AuthRepositoryImpl.kt` | Network repository invoking backend endpoints | `sendOtp()`, `verifyOtp()` via OkHttp |

---

## 3. End-to-End OTP Lifecycle Trace

```
[Android Client: vivo Y28 5G]
       │
       │ 1. User inputs Phone (+91 98765 43210) & taps Continue
       ▼
[ProfileSetupViewModel.kt]
       │
       │ 2. POST /api/v1/auth/otp/request {"phone_number": "+919876543210"}
       ▼
[Render Edge / Go Backend: main.go]
       │
       │ 3. HandleOtpRequest() parses JSON, validates E.164 format
       ▼
[otp_service.go: RequestOtp()]
       │
       ├─► Check resend cooldown (< 60s -> 429 Too Many Requests)
       ├─► Generate 6-digit cryptographic integer via crypto/rand (e.g., "741852")
       ├─► Generate 16-byte random salt via crypto/rand
       ├─► Compute HMAC-SHA256(OTP, ServerPepper + Salt)
       ├─► Store {Salt, Hash, Attempts: 0, CreatedAt, LastSentAt} in memory map
       ▼
[sms_provider.go: GetDefaultSmsProvider()]
       │
       ├─► Check TWILIO_* env vars (NOT CONFIGURED)
       ├─► Check MSG91_* env vars  (NOT CONFIGURED)
       ▼
[MockSmsProvider.SendOtp()]
       │
       ├─► Log to stdout: "[MockSmsProvider] Dispatched test OTP to +919876543210: 741852"
       ├─► Cellular Network Transmission: NONE
       ▼
[Go Backend -> Android Client]
       │
       │ 4. HTTP 200 {"status":"SENT","resend_cooldown_seconds":60,"expires_in_seconds":300}
       ▼
[Android Client]
       │
       │ 5. Navigates to OTP Entry Screen. User waits for SMS that never arrives on SIM.
```

---

## 4. In-Memory Store & Concurrency Analysis

In `backend/internal/auth/otp_service.go`, the storage mechanism is defined as:

```go
type OtpService struct {
    mu           sync.RWMutex
    records      map[string]*otpRecord
    validTickets map[string]ticketRecord
    pepper       []byte
    smsProvider  SmsProvider
}
```

### Mutex Locking Pattern
- `RequestOtp()`: Acquires `s.mu.Lock()` (exclusive write lock) when writing new salt, hash, and timestamps.
- `VerifyOtp()`: Acquires `s.mu.Lock()` (exclusive write lock) because verification increments the attempt counter and deletes the record upon 3 failures or success.
- `ConsumeTicket()`: Acquires `s.mu.Lock()` when validating and removing an authentication proof ticket.

### Concurrency Assessment
- **Deadlock Potential:** NONE. Mutex locks are held only during memory map lookups and cryptographic hashing; no blocking I/O (network or SMS API calls) is performed while holding `s.mu`.
- **Race Conditions:** NONE. Read-modify-write cycles on `Attempts` and `records` are fully encapsulated inside `s.mu.Lock()` and `s.mu.Unlock()`.

---

## 5. Audit Conclusion

The OTP service is architecturally complete, thread-safe, and cryptographically secure. The primary barrier preventing end-to-end SMS reception on physical devices is the lack of third-party SMS gateway credentials in Render's runtime environment, triggering the fallback mock provider.
