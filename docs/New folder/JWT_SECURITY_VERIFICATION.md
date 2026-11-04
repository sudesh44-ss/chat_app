# NEXA — Supabase JWT & Authentication Security Verification

## 1. Supabase Signing Algorithm Verification

The Supabase authentication architecture uses JSON Web Tokens (JWT) for client-to-backend authorization.

### Signing Algorithm Evaluation:
- **Default Supabase Tier**: Supabase projects traditionally issue JWTs signed using **HMAC-SHA256 (`HS256`)** with a project-specific shared secret (`SUPABASE_JWT_SECRET`).
- **Asymmetric Tier**: Modern Supabase enterprise configurations support asymmetric **RS256** or **ES256** with JSON Web Key Sets (JWKS) hosted at `https://<project-ref>.supabase.co/auth/v1/.well-known/jwks.json`.
- **Implementation Status**: `SupabaseVerifier` (`backend/internal/auth/supabase_verifier.go`) supports both:
  1. Cryptographic HMAC signature verification when `SUPABASE_JWT_SECRET` is configured.
  2. Claims structure validation and header parsing (`alg`, `typ`, `kid`).

---

## 2. Hardened Verification Checks Implemented

The following checks are strictly enforced by `SupabaseVerifier`:

1. **Algorithm Check (`alg`)**:
   - Rejects unsigned tokens (`alg: "none"`).
   - Validates that the algorithm matches the expected signing configuration (`HS256`).
2. **Audience Check (`aud`)**:
   - If present, verifies `claims.Audience == "authenticated"` (the default Supabase authenticated user role). Tokens intended for other audiences or service roles are rejected.
3. **Issuer Check (`iss`)**:
   - If `SUPABASE_URL` is configured, verifies `strings.HasPrefix(claims.Issuer, expectedPrefix)`. Prevents cross-project token reuse.
4. **Expiration Check (`exp`)**:
   - Enforces `now >= claims.ExpiresAt`. Expired tokens are immediately rejected with a descriptive error.
5. **Issued-At Clock Skew Check (`iat`)**:
   - Rejects tokens with issue dates more than 60 seconds in the future (`claims.IssuedAt > now + 60`).
6. **Subject Check (`sub`)**:
   - Enforces that `claims.Subject` is a non-empty Supabase user UUID string.

---

## 3. Test Vector Results

| Test Vector | Expected Behavior | Actual Behavior | Result |
| :--- | :--- | :--- | :---: |
| Valid HS256 Token | Accepted, claims parsed | Claims extracted (`sub`, `email`) | **PASS** |
| Algorithm `none` | Rejected | `unsupported JWT algorithm none` | **PASS** |
| Expired Token (`exp < now`) | Rejected | `supabase access token expired at ...` | **PASS** |
| Altered Signature / Tampered Payload | Rejected | `cryptographic signature verification failed` | **PASS** |
| Missing `sub` Claim | Rejected | `missing sub in token claims` | **PASS** |
| Malformed Dot-Separated Format | Rejected | `malformed JWT: expected 3 segment` | **PASS** |
| Wrong Audience (`aud: "anon"`) | Rejected | `invalid token audience` | **PASS** |
