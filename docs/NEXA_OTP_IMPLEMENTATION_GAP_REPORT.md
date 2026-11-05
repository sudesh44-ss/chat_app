# NEXA OTP Implementation Gap Analysis & Action Plan

**Audit Date:** November 4, 2026  
**Scope:** End-to-End OTP Delivery, Verification, Infrastructure & Operational Readiness  
**Target Platform:** NEXA Android Mobile App & Render Go Backend  
**Audit Status:** GAPS IDENTIFIED — ACTIONABLE REMEDIATION PROVIDED  

---

## 1. Executive Summary

This report synthesizes all architectural, cryptographic, and operational findings across the NEXA authentication pipeline. It benchmarks the existing codebase against the operational requirements of a production-grade, secure messaging platform.

While the core security, cryptography, and client-server API contracts are implemented to the highest standards, operational gaps in **telecom configuration** and **runtime infrastructure** currently prevent automated SMS delivery to end-user devices.

---

## 2. Capability Implementation Matrix

| Component / Requirement | Status | Current Implementation | Identified Gap |
| :--- | :--- | :--- | :--- |
| **Cryptographic OTP Generation** | **COMPLETE** | `crypto/rand` 6-digit uniform distribution | None |
| **Salted HMAC-SHA256 Storage** | **COMPLETE** | 16-byte random salt + `OTP_SERVER_PEPPER` | None |
| **Brute-Force Protection** | **COMPLETE** | 3-attempt limit; instant purge | None |
| **Timing-Attack Resistance** | **COMPLETE** | `subtle.ConstantTimeCompare` | None |
| **TTL & Expiry Management** | **COMPLETE** | 5-minute strict TTL | None |
| **Resend Cooldown** | **COMPLETE** | 60-second cooldown enforcement | None |
| **Single-Use Auth Tickets** | **COMPLETE** | 256-bit hex tickets; deleted on registration | None |
| **Android HTTPS Integration** | **COMPLETE** | `ServerEndpointConfig` points to Render TLS | None |
| **Cellular SMS Transmission** | **BLOCKED** | Falls back to `MockSmsProvider` | Missing SMS provider credentials in Render |
| **Indian DLT Compliance** | **BLOCKED** | No DLT Entity / Template ID configured | SMS to +91 blocked by Indian telcos |
| **Container Ephemerality** | **RISK** | In-memory RAM map (`sync.RWMutex`) | Container restart wipes active OTPs |
| **Root Web Route Handling** | **PARTIAL** | Unmapped root path `/` returned 404 | Needs informative service status handler |

---

## 3. Detailed Gap Descriptions

### Gap 1: Missing SMS Provider Credentials in Render
- **Severity:** HIGH (Blocks real-device user onboarding without console access)
- **Root Cause:** The Go server's `GetDefaultSmsProvider()` checks for `TWILIO_*` or `MSG91_*` environment variables. Since neither was added to the Render dashboard, it defaults to `MockSmsProvider`.
- **Impact:** The backend prints OTP codes to the Render server logs instead of dispatching them over the cellular telecommunications network.

### Gap 2: Indian Telecom DLT Compliance (TRAI Regulations)
- **Severity:** HIGH (Affects all Indian `+91` phone numbers)
- **Root Cause:** Under TRAI guidelines, transactional SMS to Indian numbers requires an approved DLT Principal Entity ID, registered Header (Sender ID), and registered Content Template.
- **Impact:** Using an unverified international Twilio number to send SMS to Indian carriers (Jio, Airtel, Vi, BSNL) results in carrier-level silent dropping.

### Gap 3: Ephemeral RAM Storage vs Render Free Tier Spin-Down
- **Severity:** MEDIUM (Operational stability concern on free tier)
- **Root Cause:** Render Free Web Services sleep after 15 minutes of inactivity. When a new request arrives, Render spins up a fresh container instance, re-initializing `s.records = make(map[string]*otpRecord)`.
- **Impact:** If an OTP was issued immediately before a container restart, the OTP record will be gone when the user attempts verification 30 seconds later.
- **Mitigation:** The OTP TTL is short (5 minutes). Under active user onboarding, the container remains awake. For production scale, an external Redis or Supabase cache can back the OTP store if persistent sessions across server restarts are required.

---

## 4. Operational Action Plan

### Phase 1: Zero-Cost Developer Verification (Immediate)
To test and demonstrate full phone verification right now without incurring telecom expenses:
1. Open the Android app on the vivo Y28 5G.
2. Enter the phone number and tap **Continue**.
3. Open your web browser to the **Render Dashboard -> `chat_app` (or `nexa-relay`) -> Logs**.
4. Observe the log line:
   ```
   [MockSmsProvider] Dispatched test OTP to +91XXXXXXXXXX: 123456
   ```
5. Enter that 6-digit code in the Android app.
6. The app verifies the OTP, acquires the `auth_ticket`, and completes profile setup.

### Phase 2: Live Cellular SMS Gateway Setup (Production)
To enable real SMS delivery to physical phones:
1. **Choose Provider:**
   - For India (+91 numbers): Register on **MSG91** (supports DLT registration).
   - For Global / US (+1 numbers): Create a **Twilio** account and purchase an SMS-enabled number.
2. **Add Environment Variables in Render Dashboard:**
   - Go to: `https://dashboard.render.com` -> Select Service -> **Environment**.
   - Add credentials:
     - For MSG91: `MSG91_AUTH_KEY` and `MSG91_TEMPLATE_ID`.
     - For Twilio: `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN`, and `TWILIO_PHONE_NUMBER`.
3. Render will automatically redeploy the service with cellular SMS dispatch enabled.
