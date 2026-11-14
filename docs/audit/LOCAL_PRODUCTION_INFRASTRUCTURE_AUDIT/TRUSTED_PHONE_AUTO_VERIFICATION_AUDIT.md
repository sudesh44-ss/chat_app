# NEXA Backend — Trusted Phone Number Auto-Verification Audit

**Document Reference:** `docs/audit/TRUSTED_PHONE_AUTO_VERIFICATION_AUDIT.md`  
**Date:** 2026-11-08  
**Component:** NEXA Identity, Registration & Reverse SMS Verification Engine  
**Module:** `backend/internal/auth`  
**Classification:** Security Architecture & Implementation Audit  

---

## 1. Executive Summary

This document provides a comprehensive security and architectural audit of the server-controlled **Trusted Phone Number Auto-Verification Route** implemented in the NEXA backend (`backend/internal/auth`).

The implementation introduces an administrative bypass strictly limited to the phone possession verification step for authorized phone numbers configured via the `NEXA_TRUSTED_PHONE_NUMBERS` environment variable on Render (or local backend configuration).

### Key Architectural Tenets:
1. **Preservation of Route A (Reverse SMS):** The existing Client-Initiated Reverse SMS verification engine remains 100% untouched and functional for all normal users.
2. **Server-Side Sovereign Routing:** The verification path decision is made exclusively on the backend via cryptographic normalization and allowlist lookup. Client-side flags (`verified`, `isTrusted`, `skipVerification`) are never evaluated.
3. **Unified Identity Pipeline Convergence:** Both verification routes immediately converge into the exact same common registration pipeline. Trusted accounts receive authentic `NX-XXXXXXXX` IDs, identical Ed25519 identity bindings, X25519 Diffie-Hellman keys, Android hardware device records, 30-day session tokens, Signal-protocol prekeys, and encrypted backup vaults.
4. **Zero Account Privilege Distinction:** A user registered via the trusted allowlist has no special roles, privileges, flags, or access rights compared to standard users.
5. **Zero Database Migrations:** Uses existing PostgreSQL schema fields and in-memory/Redis ledgers, requiring zero database migrations or table modifications.
6. **Production Fail-Closed Guarantee:** In production (`APP_ENV=production`), any syntax or formatting error in `NEXA_TRUSTED_PHONE_NUMBERS` automatically clears the allowlist, preventing unintended bypasses.

---

## 2. Architecture Comparison: Route A vs Route B

| Architectural Dimension | Route A (Standard Reverse SMS) | Route B (Trusted Number Auto-Verification) |
| :--- | :--- | :--- |
| **Trigger Mechanism** | Phone number not in `NEXA_TRUSTED_PHONE_NUMBERS` | Phone number matches `NEXA_TRUSTED_PHONE_NUMBERS` |
| **Geographic Constraint** | Strictly Indian domestic numbers (`+91`) | E.164 valid phone numbers |
| **Phone Proof Mechanism** | Client dispatches outbound SMS; carrier webhook verifies sender | Server-side environment variable allowlist match |
| **Verification State on `/initiate`** | `PENDING` (awaits carrier webhook within 90s TTL) | `VERIFIED` (instantaneous return) |
| **Nonce Generation** | `NX-XXXXX` single-use cryptographic challenge | `NX-XXXXX` audit nonce generated for ledger continuity |
| **Carrier SMS Sent?** | Yes (Client-Initiated Reverse SMS) | No (Completely bypassed) |
| **Recorded Verification Method** | `reverse_sms` | `trusted_allowlist` |
| **Google ID Token Validation** | Validated via `GoogleVerifier` (JWKS/RS256) | Validated via `GoogleVerifier` (JWKS/RS256) |
| **NX-ID Allocation Format** | `NX-XXXXXXXX` (Base32, CSPRNG, Luhn mod-32) | `NX-XXXXXXXX` (Base32, CSPRNG, Luhn mod-32) |
| **Identity & DH Keys** | Ed25519 (32 bytes) + X25519 (32 bytes) | Ed25519 (32 bytes) + X25519 (32 bytes) |
| **Session Token Generation** | 30-day cryptographically random token (`nexa_sess_...`) | 30-day cryptographically random token (`nexa_sess_...`) |
| **Rate Limiting** | IP (5/15m) + Phone (3/24h) + Redis Sliding Window | IP (5/15m) + Phone (3/24h) + Redis Sliding Window |
| **Account Permissions** | Standard NEXA User (`is_admin = false`) | Standard NEXA User (`is_admin = false`) |

---

## 3. Environment Configuration Specification

The trusted allowlist is governed by a single environment variable:

```env
NEXA_TRUSTED_PHONE_NUMBERS=+919876543210,+919876543211
```

### Configuration Specifications:
- **Variable Name:** `NEXA_TRUSTED_PHONE_NUMBERS`
- **Location:** Render Dashboard -> NEXA Backend Web Service -> Environment Variables.
- **Format:** Comma-separated list of telephone numbers.
- **Normalization Engine:** `CanonicalizePhone()` normalizes entries into strict E.164 format:
  - Strips spaces, hyphens, parentheses, and tabs.
  - Automatically prepends `+91` to standard 10-digit Indian mobile numbers (`9876543210` -> `+919876543210`).
  - Preserves international standard country codes (`+1`, `+44`, etc.) if valid E.164.
- **Missing / Empty Variable:** If `NEXA_TRUSTED_PHONE_NUMBERS` is omitted or empty, the allowlist contains 0 numbers; 100% of registrations follow standard Route A (Reverse SMS).
- **Whitespace Tolerance:** Spaces surrounding entries are automatically trimmed (e.g. ` +919876543210 , +919876543211 `).
- **Production Fail-Closed Behavior:** In `APP_ENV=production` or `ENV=production`, any invalid entry causes `TrustedPhoneRegistry` to log a critical error and **clear the allowlist entirely** (0 trusted numbers).

---

## 4. Registration Flow Diagram (ASCII Text)

```
                            +-----------------------------------------+
                            |      Client Registration Request        |
                            |   (POST /auth/initiate-reverse-verify)  |
                            +-----------------------------------------+
                                                 |
                                                 v
                            +-----------------------------------------+
                            |       Normalize Phone (E.164)           |
                            +-----------------------------------------+
                                                 |
                                                 v
                                 /-------------------------------\
                                /   Is Phone in Server Allowlist  \
                               <   (NEXA_TRUSTED_PHONE_NUMBERS)?   >
                                \                                 /
                                 \-------------------------------/
                                         /               \
                             YES (Trusted)               NO (Standard User)
                                    /                         \
    ===============================/                           \===============================
    ROUTE B: TRUSTED AUTO-VERIFICATION                          ROUTE A: REVERSE SMS
    ===================================                         ====================
    - Check IP / Abuse Limits                                   - Verify Domestic (+91) Series
    - Mutual Exclusion Lock (s.mu)                              - Check IP / Device Rate Limits
    - Check Account Uniqueness                                  - Generate Single-Use Nonce (90s TTL)
    - If Google Token: Verify RS256/JWKS                        - Store Pending Verification ("PENDING")
    - Set Status = "VERIFIED"                                   - Return Inbound Receiver & SMS Payload
    - Set Method = "trusted_allowlist"                          - Client Sends Inbound SMS via SIM
                                                                - Carrier Webhook Received by Server
                                                                - Match Nonce + Sender Phone
                                                                - Set Status = "VERIFIED"
                                                                - Set Method = "reverse_sms"
    ===============================\                           /===============================
                                    \                         /
                                     v                       v
                         +-----------------------------------------------+
                         |          COMMON IDENTITY PIPELINE             |
                         |-----------------------------------------------|
                         | 1. Allocate Authentic NX-ID (NX-XXXXXXXX)     |
                         | 2. Create UserAccount Record                  |
                         |    - PhoneVerified = true                     |
                         |    - IsDiscoverableByPhone = true             |
                         | 3. Register Hardware Device                   |
                         |    - Ed25519 Public Key (32 bytes)            |
                         |    - X25519 DH Public Key (32 bytes)          |
                         |    - Device Fingerprint & Push Token          |
                         | 4. Issue 30-Day Cryptographic Session Token   |
                         | 5. Initialize E2EE Prekey Bundle              |
                         | 6. Setup Encrypted Backup Ledger              |
                         +-----------------------------------------------+
                                                 |
                                                 v
                         +-----------------------------------------------+
                         |           Authenticated NEXA Client           |
                         +-----------------------------------------------+
```

---

## 5. Shared Identity Pipeline Verification

To ensure zero divergence between Route A and Route B, the identity pipeline was audited at the code level:

1. **Google Identity Verification:** Both routes execute `s.googleVerifier.VerifyIdToken(req.GoogleIdToken)` when an ID token is supplied. Google UID, email, and signature validations are identical.
2. **NX-ID Allocation:** Both routes call `s.store.AllocateNxID()`, drawing from the same CSPRNG generator, 8-character Base32 format with checksum, guaranteed unique across the `accounts` table.
3. **Cryptographic Key Binding:** Both routes store `PublicKeyHex` (Ed25519) and `DhPublicKeyHex` (X25519) in `identity.Device`, ensuring full Signal-protocol compatibility.
4. **Session Token Issuance:** Both routes call `s.store.CreateSession(user.ID, device.ID, user.NxID, ...)` issuing high-entropy tokens with 30-day TTLs.
5. **No Account Distinctions:** The `identity.UserAccount` struct contains no `is_trusted` field; database storage for trusted users is completely indistinguishable from standard users.

---

## 6. Security Analysis

### Administrative Authorization vs. Physical Possession
> [!IMPORTANT]
> Inclusion of a phone number in `NEXA_TRUSTED_PHONE_NUMBERS` represents an **administrative grant of trust**, not cryptographic proof of SIM card possession.
> - Access to configure environment variables in Render must be strictly restricted to authorized engineering administrators.
> - Numbers placed in the allowlist must be intended for automated test harnesses, QA personnel, or development devices.

### Anti-Spoofing & Client-Side Immunity
The request parser (`json.NewDecoder(r.Body)`) binds to `InitiateReverseVerifyRequest`. Any extra fields supplied in JSON payloads (such as `is_trusted: true`, `bypass_sms: true`, `verified: true`) are disregarded by Go's typed unmarshaling. The server solely checks `s.trustedRegistry.IsTrusted(phone)`.

### First-Claim Race Protection
All account creation and lookup logic inside `InitiateVerification()` executes under `s.mu.Lock()`. If multiple concurrent requests attempt to register the same trusted phone number, exactly one executes the creation while subsequent requests trigger account uniqueness validation.

---

## 7. Rate Limiting and Abuse Prevention

1. **IP-Level Rate Limiting:** Both routes pass through `s.rateLimiter.AllowIP(clientIP, 5, 15*time.Minute)`. Rapid initiation requests from any IP address are throttled with HTTP 429 Too Many Requests.
2. **Phone-Level Rate Limiting:** Enforced via `s.rateLimiter.AllowUser(phone, 3, 24*time.Hour)`. Even allowlisted numbers cannot be used to flood the server or generate excessive registration cycles.
3. **Redis Sliding Window:** In distributed environments, Redis tracks token bucket attempts across all container instances.

---

## 8. Account Uniqueness and Collision Prevention

1. **New User Registration (`IsReverify = false`):**
   - If an account already exists with the phone number, registration fails with `ErrPhoneAlreadyRegistered` (HTTP 409 Conflict).
   - If the public key or Google UID is already claimed, the request fails with HTTP 409 Conflict.
2. **Device Addition (`IsReverify = true`):**
   - If an account exists, the new device is bound to the existing account.
   - If no account exists for the phone number, re-verification fails.
3. **Database Constraints:** The PostgreSQL database enforces `UNIQUE (phone_number)` and `UNIQUE (nx_id)`.

---

## 9. Zero-Migration Verification

- **Schema Check:** The PostgreSQL database schema already defines:
  ```sql
  phone_number VARCHAR(32),
  phone_verified BOOLEAN NOT NULL DEFAULT FALSE
  ```
- **Index Check:** Unique partial index exists:
  ```sql
  CREATE UNIQUE INDEX idx_accounts_phone ON accounts(phone_number) 
  WHERE phone_number IS NOT NULL AND phone_number != '';
  ```
- **Audit Ledger:** `verification_method` (`"trusted_allowlist"` vs `"reverse_sms"`) and `verified_at` are stored in memory/Redis session tracking and surfaced via API responses without requiring schema migrations.
- **Migration Count:** **0 database migrations applied or required.**

---

## 10. Test Suite Summary and Results

A comprehensive test suite containing **21 individual automated tests** was executed in `backend/internal/auth/trusted_phone_test.go`:

```bash
cd H:\chat_app\backend
go test -v ./internal/auth -run "Test[0-9]{2}_"
```

### Complete Test Results Matrix

| Group | Test ID & Function | Objective | Result |
| :--- | :--- | :--- | :---: |
| **Group 1: Trusted Route** | `Test01_TrustedRoute_SingleNumberSkipsReverseSms` | Single trusted number skips Reverse SMS, returns VERIFIED immediately | **PASS** |
| | `Test02_TrustedRoute_MultipleNumbersRecognized` | Multiple comma-separated numbers in allowlist all recognized | **PASS** |
| | `Test03_TrustedRoute_E164NormalizationWorks` | E.164 normalization correctly maps formatting variations | **PASS** |
| | `Test04_TrustedRoute_VerificationRecordedCorrectly` | Verification method recorded as `trusted_allowlist` with timestamp | **PASS** |
| | `Test05_TrustedRoute_ContinuesToCommonPipeline` | Trusted route completes full common identity pipeline | **PASS** |
| **Group 2: Normal Route** | `Test06_NormalRoute_NonTrustedRequiresReverseSms` | Non-trusted number still requires Reverse SMS (status PENDING) | **PASS** |
| | `Test07_NormalRoute_ExistingReverseSmsUnchanged` | Existing Reverse SMS flow continues working identically | **PASS** |
| | `Test08_NormalRoute_EmptyAllowlistPreservesNormalRegistration` | Empty allowlist preserves existing behavior for all users | **PASS** |
| **Group 3: Security & Abuse** | `Test09_Security_ClientProvidedFlagsCannotBypass` | Client-provided flags cannot force trusted bypass | **PASS** |
| | `Test10_Security_ExistingAccountsCannotBeTakenOver` | Existing registered accounts cannot be overwritten or taken over | **PASS** |
| | `Test11_Security_DuplicateRegistrationHandledSafely` | Duplicate registration attempts handled safely without state corruption | **PASS** |
| | `Test12_Security_ConcurrentRequestsCannotCreateDuplicateAccounts` | Concurrent registration requests cannot create duplicate accounts | **PASS** |
| | `Test13_Security_MalformedAllowlistEntriesCannotAuthorizeUnintended` | Malformed allowlist entries fail closed in production mode | **PASS** |
| | `Test14_Security_TrustedUsersSubjectToAbuseProtection` | Trusted users still subject to rate limits and abuse protection | **PASS** |
| **Group 4: Shared Pipeline** | `Test15_SharedPipeline_GoogleLoginValidationIdentical` | Google login validation works identically on both routes | **PASS** |
| | `Test16_SharedPipeline_SameAccountStructure` | Both routes produce identical account structures in the database | **PASS** |
| | `Test17_SharedPipeline_SameNxIdAllocation` | Both routes allocate authentic NX IDs through the standard allocator | **PASS** |
| | `Test18_SharedPipeline_SameDeviceRegistration` | Both routes register Android devices with identical cryptographic keys | **PASS** |
| | `Test19_SharedPipeline_SameE2EESetup` | Both routes set up identical E2EE capabilities | **PASS** |
| | `Test20_SharedPipeline_EncryptedBackupAndRecovery` | Both routes work with encrypted backup and recovery systems | **PASS** |
| | `Test21_SharedPipeline_NewDeviceLoginAndRevocationIdentical` | New device login and revocation work identically for both routes | **PASS** |

### Full Backend Package Regression Test
```bash
go test -count=1 ./...
```
```
ok  	nexa/wire/internal/auth          4.320s
ok  	nexa/wire/internal/connection    0.769s
ok  	nexa/wire/internal/connections   0.644s
ok  	nexa/wire/internal/database      0.667s
ok  	nexa/wire/internal/identity      0.471s
ok  	nexa/wire/internal/prekey        0.557s
ok  	nexa/wire/internal/queue         0.697s
ok  	nexa/wire/internal/ratelimit     0.556s
ok  	nexa/wire/internal/wire          0.800s
```
**Static Code Analysis (`go vet ./...`):** 0 errors, 0 warnings.

---

## 11. Production Deployment Checklist

Before deploying this feature to Render production:
- [x] Code audited for zero hardcoded phone numbers.
- [x] Logging audited: full phone numbers masked via `maskPhone()` (`+91****3210`).
- [x] Unit and integration tests passing (`21/21` test suites).
- [x] Full backend regression tests passing across all 9 packages.
- [x] Database migration verification: confirm 0 migrations needed.
- [x] Render environment variable prepared (`NEXA_TRUSTED_PHONE_NUMBERS`).

---

## 12. Operational Runbook

### Service Initialization
When the NEXA backend starts up, it logs the allowlist initialization:
```
2026/11/08 22:16:20 [TrustedPhone] Initialized allowlist with 2 trusted phone number(s).
```
If the environment variable is empty or unset:
```
2026/11/08 22:16:20 [TrustedPhone] Allowlist initialized: 0 numbers configured.
```

### Telemetry & Log Monitoring
When a trusted user registers:
```
2026/11/08 22:16:20 [ReverseSMS] Trusted phone auto-verified: Phone=+91****3210, Nonce=NX-LZSMW, NxID=NX-FDT7L5LE, Method=trusted_allowlist
```
When a normal user initiates Reverse SMS:
```
2026/11/08 22:16:20 [ReverseSMS] Initiated reverse verification for +91****7777 (Nonce: NX-NM5XG, Inbound: +919800000000, TTL: 90s)
```

---

## 13. Phone Number Rotation Procedure

To add, update, or remove trusted numbers:
1. Access the **Render Dashboard** -> **NEXA Backend Web Service** -> **Environment**.
2. Modify `NEXA_TRUSTED_PHONE_NUMBERS`.
   - To add a number: append `,+91XXXXXXXXXX`.
   - To remove a number: delete the number and comma.
   - To disable all bypasses: delete the environment variable or set to empty.
3. Save changes; Render performs a rolling restart.
4. Verify backend logs confirm:
   `[TrustedPhone] Initialized allowlist with N trusted phone number(s).`

---

## 14. Security Considerations

1. **Confidentiality of the Allowlist:** Treat the Render environment variable configuration as a privileged credential.
2. **Ephemeral Testing:** When using trusted numbers for QA or automated CI, rotate or remove them once testing concludes.
3. **No Special Rights:** Even if a malicious actor gained control of an allowlisted number, they only bypass SMS verification—they gain no administrative authority over NEXA chat servers, encryption keys, or other users' data.

---

## 15. Verification Checklist

| Item | Requirement | Status |
| :---: | :--- | :---: |
| 1 | Existing Reverse SMS route preserved 100% | **CONFIRMED** |
| 2 | Separate Trusted Number route created | **CONFIRMED** |
| 3 | Both routes converge into same registration pipeline | **CONFIRMED** |
| 4 | Google Login remains identical | **CONFIRMED** |
| 5 | NX ID allocation remains identical | **CONFIRMED** |
| 6 | Device registration remains identical | **CONFIRMED** |
| 7 | E2EE remains unchanged | **CONFIRMED** |
| 8 | Backup and recovery remain unchanged | **CONFIRMED** |
| 9 | Production environment untouched | **CONFIRMED** |
| 10 | Production database untouched | **CONFIRMED** |
