# NEXA — OTP Security & Verification Architecture Report

## 1. Cryptographic Evaluation: Salted SHA-256 vs. Server-Secret Keyed HMAC

### The Vulnerability of Simple Salted SHA-256 on 6-Digit OTPs:
A standard 6-digit numeric OTP has exactly $1,000,000$ possible permutations ($10^6$).
- Even with a per-record random 16-byte salt, computing SHA-256 for all $1,000,000$ combinations takes **under 5 milliseconds** on a standard modern CPU or GPU.
- If an attacker obtains a database read snapshot (or SQL injection), the per-record salt provides **virtually zero protection** against an offline dictionary / brute-force attack.

### The Upgraded Defense: Server-Secret Keyed HMAC-SHA256 (Peppered HMAC):
To eliminate offline brute-force attacks on low-entropy secrets, the hashing implementation was upgraded in `backend/internal/auth/otp_service.go`:

$$\text{HashedOtp} = \text{HMAC-SHA256}\Big(\text{ServerSecretPepper}, \text{Salt} \parallel \text{OTP} \parallel \text{DomainTag}\Big)$$

- **Server-Side Pepper**: Stored strictly in the server environment (`OTP_SERVER_PEPPER`) or hardware key vault. It is **never** written to the database.
- **Security Guarantee**: Even with full read access to the database (salts and hashes), an external attacker cannot verify or brute-force any 6-digit code without executing inside the server environment.

---

## 2. In-Memory & Database Security Controls

| Control | Specification | Implementation Verification | Status |
| :--- | :--- | :--- | :---: |
| **Code Length** | Exactly 6 numeric digits | Generated via `crypto/rand.Int(rand.Reader, 900000)` ($100000$ to $999999$) | **PASS** |
| **TTL Expiration** | 5 minutes (300 seconds) | `ExpiresAt = now + (5 * 60 * 1000)`. Stale records rejected and purged | **PASS** |
| **Attempt Limit** | Maximum 3 failed attempts | `record.Attempts++`. Record deleted immediately upon reaching 4 attempts | **PASS** |
| **Single-Use** | Anti-replay enforcement | Record deleted immediately upon successful verification | **PASS** |
| **Resend Cooldown** | 60 seconds | Re-requests within 60s return `429 Too Many Requests` with remaining seconds | **PASS** |
| **Phone Rate Limit**| Max 5 requests / hour / phone | Sliding window timestamp tracker per E.164 phone number | **PASS** |
| **IP Rate Limit** | Max 15 requests / hour / IP | Sliding window timestamp tracker per client IP address | **PASS** |
| **Phone Format** | Strict E.164 normalization | Normalizes Indian 10-digit mobile, removes dashes, spaces, and formatting | **PASS** |
| **Real SMS Provider**| Live SMS Dispatch | External provider credentials (Twilio/Fast2SMS) not in environment | **BLOCKED** |

---

## 3. AuthProofTicket Single-Use Token
Upon successful OTP verification:
- The OTP record is deleted from memory.
- A cryptographically random 32-byte hex ticket (`ticketToken`) is generated.
- The ticket has a strict 10-minute validity window.
- The ticket can only be consumed once via `ConsumeTicket()` during account registration or linking.
