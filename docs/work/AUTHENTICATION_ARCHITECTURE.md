# NEXA Authentication Architecture: Carrier-Grade OTP & Google OAuth

---

## 1. Authentication Pipeline Overview

```text
[ User Selects Auth Method ]
       │
       ├────────────────────────────────────────┬────────────────────────────────────────┐
       ▼                                        ▼                                        ▼
[ Phone Number Entry ]                  [ Google Sign-In ]                       [ Manual Mode ]
       │                                        │                                        │
  Format E.164                            Credential Manager                       Phone Auth Only
  POST /auth/otp/request                  Retrieve Google ID Token                       │
       │                                  POST /auth/google/verify                       │
  Carrier Dispatches SMS                        │                                        │
  User Enters 6-Digit OTP                       │                                        │
  POST /auth/otp/verify                         │                                        │
       │                                        │                                        │
       └───────────────────┬────────────────────┘                                        │
                           ▼                                                             │
                 [ AuthProofTicket Issued ]                                              │
                 (15-Minute Strict TTL)                                                  │
                           │                                                             │
                           ▼                                                             │
                 [ POST /auth/register ] <───────────────────────────────────────────────┘
                 - Client submits AuthTicket + Device Public Key
                 - Server verifies Ticket, generates NX-ID, issues Session Token
```

---

## 2. Phone OTP Security Specifications

1. **E.164 Canonicalization:** All phone numbers are validated against regex `^\+[1-9]\d{7,14}$`.
2. **Zero-Plaintext Storage:**
   - Server computes: `OTP_HASH = SHA-256(Salt || PlaintextCode)`
   - Only `OTP_HASH` and `Salt` are kept in RAM.
   - Even if the relay server memory is inspected, plaintext codes cannot be recovered without solving a 6-digit preimage.
3. **Brute-Force Defense:**
   - Maximum 3 verification attempts per OTP challenge.
   - On the 3rd failed attempt, the challenge is permanently destroyed.
4. **Rate Limiting:**
   - 60-second cooldown between successive OTP requests for the same number.
   - Maximum 5 OTP requests per phone number per hour.
   - Maximum 15 OTP requests per client IP address per hour.
5. **Provider Agnostic Architecture:**
   - `SmsProvider` interface allows plugging in Twilio, MSG91, or custom telco gateways without changing authentication logic.

---

## 3. Google OAuth & Account Linking

### JWT Verification
- Validates Google ID tokens signed with Google's public RSA keys (fetched from Google's JWKS endpoint).
- Asserts:
  - `iss == "https://accounts.google.com"` or `"accounts.google.com"`
  - `aud == <configured_web_client_id>`
  - `exp > current_timestamp`
  - `email_verified == true`

### Safe Account Linking (409 Conflict Prevention)
When a user links their Google account to an existing Phone account (or vice versa):
1. Client calls `POST /api/v1/auth/link` with caller's active session token and target credential proof.
2. Server queries secondary indices (`byGoogle` and `byPhone`).
3. If target credential is already bound to another NX-ID, server immediately halts and returns `409 Conflict`:
   ```json
   {
     "error": "This Google account is already linked to another NEXA profile",
     "code": "CREDENTIAL_CONFLICT"
   }
   ```
4. Only unlinked credentials can be associated with an account.
