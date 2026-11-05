# NEXA OTP Production Security Architecture

**Component:** `backend/internal/auth/otp_service.go`  
**Security Standard:** NIST SP 800-63B Out-of-Band Authentication  
**Status:** **ACTIVE & HARDENED**  

---

## 1. Cryptographic Security Standards

| Parameter | Implemented Specification | Security Objective |
| :--- | :--- | :--- |
| **Entropy Source** | `crypto/rand.Reader` | Non-deterministic CSPRNG from OS kernel |
| **OTP Format** | Uniform 6-digit decimal (`[100000, 999999]`) | Rejection sampling eliminates modulo bias |
| **Salt** | 16-byte random cryptographically secure salt | Preimage resistance per OTP attempt |
| **Hashing Algorithm** | HMAC-SHA256 (`OTP_SERVER_PEPPER` + Salt) | Plaintext OTP is NEVER stored in memory or disk |
| **Constant-Time Compare**| `subtle.ConstantTimeCompare` | Eliminates side-channel timing attack vectors |
| **Validity TTL** | Strictly 300,000 ms (5 minutes) | Automatic expiration enforcement |
| **Max Attempts** | Maximum 3 attempts before instant purge | 1 in 333,333 brute-force probability |
| **Resend Cooldown** | 60 seconds strict cooldown | Anti-flooding / rate-limiting defense |
| **Session Binding** | Bound Google UID / Supabase UID | Prevents cross-user ticket swapping |

---

## 2. Mutex Concurrency & Non-Blocking Architecture

### Concurrency Flaw Fixed:
In previous versions, `s.mu.Lock()` was held while `s.provider.SendOtp()` performed network HTTP requests. Under network latency or gateway timeouts, this blocked the entire backend authentication pipeline for up to 10 seconds.

### Hardened Non-Blocking Flow:
1. `s.mu.Lock()` is acquired to check rate limits, verify cooldown, generate crypto OTP, and write the pending record.
2. `s.mu.Unlock()` is called **before** `provider.SendOtp()`.
3. SMS dispatch occurs concurrently without blocking other users or requests.
4. If dispatch fails, `s.mu.Lock()` is re-acquired to roll back the record and rate limiter.
