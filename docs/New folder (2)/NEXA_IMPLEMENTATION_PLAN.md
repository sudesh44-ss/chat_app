# NEXA Production Implementation Plan

**Scope:** Production Deployment, MSG91 Telecom SMS Delivery, & Android Release Verification  
**Status:** **READY FOR DEPLOYMENT**  

---

## 1. Implementation Steps Completed in Codebase

### Go Backend:
1. **SMS Provider Hardening (`sms_provider.go`):**
   - Implemented official MSG91 OTP v5 API adapter.
   - Enforced zero-mock policy in production: `MockSmsProvider` cannot be activated when `APP_ENV=production`.
   - Added `UnavailableSmsProvider` returning structured errors when telecom credentials are absent.
   - Masked phone numbers in server logs (`+91****43210`) and removed plaintext OTP printing.
2. **OTP Concurrency & State Management (`otp_service.go`):**
   - Removed mutex locks during external SMS network calls to eliminate server-wide thread contention.
   - Added atomic state rollback: if provider dispatch fails, the OTP record and rate limit counters are immediately purged.
   - Added user session binding: bound Google UID is stamped on OTP records and preserved in `AuthTicket`.
3. **HTTP REST Handlers (`handler.go`):**
   - Return HTTP 503 for SMS provider failure/unavailability.
   - Return HTTP 429 for rate limit or cooldown violations.
   - Return HTTP 401 for invalid OTP, HTTP 410 for expired OTP.
   - Supported both `session_auth_token` and `session_token` in registration response for dual compatibility.
4. **Root Status Route (`main.go`):**
   - Registered `/` returning JSON service status and `/favicon.ico` returning HTTP 204.

### Android Client:
1. **Google Credential Manager Debounce & Exception Handling (`WelcomeViewModel.kt`):**
   - Added guard against concurrent clicks (`if (_uiState.value.isLoading) return`).
   - Differentiated `NoCredentialException`, `GetCredentialCancellationException`, `GetCredentialInterruptedException`, `GetCredentialProviderConfigurationException`, `GetCredentialUnsupportedException`.
2. **API Client Error Mapping (`AuthApiService.kt`):**
   - Handled HTTP 503 and `SMS_UNAVAILABLE` by displaying user-friendly message.
   - Supported dual token fields (`session_auth_token` and `session_token`).

---

## 2. Deployment & Verification Steps

### Step 1: Render Environment Variables
In **Render Dashboard $\rightarrow$ Web Service $\rightarrow$ Environment**:
```env
APP_ENV=production
PORT=8080
GOOGLE_CLIENT_ID=356056510017-grmokt7u1sch6hdk9dbah85k2jqe32v2.apps.googleusercontent.com
OTP_SERVER_PEPPER=<existing-secret>
MSG91_AUTH_KEY=<your-msg91-auth-key>
MSG91_TEMPLATE_ID=<approved-template-id>
```

### Step 2: Push Changes to GitHub
Push the committed backend changes to `main` on GitHub:
```bash
git push origin main
```
Render will automatically trigger a clean build and deploy the updated container.

### Step 3: End-to-End Handset Test
1. Open the NEXA Android app on `vivo Y28 5G`.
2. Tap **Continue with Google** $\rightarrow$ select Google account.
3. Enter phone number $\rightarrow$ tap **Continue**.
4. Confirm physical cellular SMS arrives on the device.
5. Enter 6-digit OTP $\rightarrow$ complete identity registration $\rightarrow$ verify NX-ID allocation.
