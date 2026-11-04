# NEXA — Identity System & NX-ID Audit

**Audit Date:** November 4, 2026  
**Auditor:** Senior Cryptographer & Systems Architect  
**Subject:** Public NX-ID Specification vs. Existing Implementation  

---

## 1. The Locked NX-ID Specification

According to Section 2.3 of the architectural mandate:
- **Format**: `NX-XXXXXXXX` (e.g. `NX-29AB731Q`, `NX-A7K92B4M`, `NX-8Z3P1R6C`)
- **Prefix**: `NX-` is mandatory, uppercase, and fixed.
- **Length**: Exactly 8 characters following the prefix (Total string length = 11 characters).
- **Character Set**: `ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789` (36 possible characters per position).
- **Entropy**: $36^8 \approx 2.82 \times 10^{12}$ (over 2.8 trillion possible unique IDs).
- **Randomness**: All 8 characters must be randomly generated using a cryptographically secure random generator (`crypto/rand`).
- **No Fixed Patterns**: Numbers and letters can appear in any position; no character ordering rules.
- **Database Validation**: Enforced via PostgreSQL `CHECK (nx_id ~ '^NX-[A-Z0-9]{8}$')` and `UNIQUE` constraint.
- **Stability**: An assigned NX-ID is immutable and persistent across multi-device logins.
- **Public Nature**: The NX-ID is a public discovery identifier, not a secret authentication credential.

---

## 2. Existing Implementation & Deviation Analysis

### A. Existing Code in `backend/internal/identity/nxid.go`
```go
const base32Charset = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

func DeriveNxID(pubKeyBytes []byte, salt int) string {
    h := sha256.New()
    h.Write(pubKeyBytes)
    ...
    numVal := binary.BigEndian.Uint16(hash[0:2])
    digits := 1000 + int(numVal%9000)
    c1 := base32Charset[int(hash[2])%len(base32Charset)]
    c2 := base32Charset[int(hash[3])%len(base32Charset)]
    c3 := base32Charset[int(hash[4])%len(base32Charset)]

    return fmt.Sprintf("NX-%04d-%c%c%c", digits, c1, c2, c3)
}
```

### B. Critical Architectural Deviations
1. **Format Mismatch**:
   Existing code outputs `NX-XXXX-XXX` (e.g. `NX-8492-ABC`), which contains a hyphen between the digit and letter segments.
   The locked specification strictly prohibits internal hyphens: `NX-XXXXXXXX` (e.g. `NX-29AB731Q`).
2. **Deterministic Public-Key Derivation**:
   Existing code calculates NX-ID as a deterministic SHA-256 hash of the client's Ed25519 public key.
   The locked specification states:
   > "Do not derive NX-ID directly from a public key or expose sensitive internal identifiers through it. All 8 characters must be randomly generated."
   Deriving NX-ID from the public key creates key-binding rigidity, preventing key rotation or device replacement without breaking the user's public handle.
3. **Android Client Verification**:
   `AuthApiService.kt` and `Phase2AuthAndIdentityTests.kt` historically tested against `^NX-\\d{4}-[A-Z0-9]{3}$`.
   These client-side test assertions must be updated to the locked standard `^NX-[A-Z0-9]{8}$`.

---

## 3. Required Implementation Plan
1. **Refactor `nxid.go`**:
   Implement `GenerateRandomNxID() (string, error)` using `crypto/rand` reading indices into charset `ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789`.
2. **Implement Collision Detection in `Store`**:
   Attempt database insertion with retry loop (up to 5 attempts) to guarantee uniqueness under concurrent account creation.
3. **Database Constraint**:
   Add `CHECK (nx_id ~ '^NX-[A-Z0-9]{8}$')` to the `profiles` and `nx_identities` PostgreSQL tables.
