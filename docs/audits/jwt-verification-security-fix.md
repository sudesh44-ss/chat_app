# NEXA — JWT Verification Security Fix (P0)

**Date:** 2026-11-14
**Scope:** Critical JWT signature verification vulnerability in `/api/v1/auth/supabase/verify`
**Classification:** Security fix, audit-only follow-up; no deployment performed
**Status:** Implemented and locally verified; production verification pending

---

## 1. Root cause

`backend/internal/auth/supabase_verifier.go` made HMAC signature verification
optional. `enforceHMAC` was `true` only when `SUPABASE_JWT_SECRET` was set, and
`VerifyToken` skipped the signature check entirely when it was `false`. In
production the secret appears unset, so any JWT with a `sub` claim (plus
acceptable `exp`/`aud`/`iss`) passed verification and `handleSupabaseVerify`
issued a 10-minute auth ticket. A live negative probe confirmed the bypass:
a JWT with an empty signature and no `sub` failed on `sub`, not on signature.

## 2. Files and functions changed

- `backend/internal/auth/supabase_verifier.go`
  - `NewSupabaseVerifier`, `VerifyToken`
  - Added `ErrSupabaseVerifierUnconfigured`, `supabaseSubjectRE`
  - Added `NotBefore` claim and `nbf` validation
- `backend/internal/auth/handler.go`
  - `handleSupabaseVerify`, `handleAccountLink` (SUPABASE branch): map missing
    config to `503 AUTH_CONFIG_UNAVAILABLE`
- `backend/internal/auth/supabase_verifier_test.go` (new, 18 tests)
- `.env.example` — documented `PHONE_HMAC_KEY`/`PHONE_ENC_KEY`; corrected migration count
- `docs/audits/supabase-google-nxid-connectivity-audit.md` — added §15

## 3. Previous vulnerable behaviour

- `enforceHMAC == false` when `SUPABASE_JWT_SECRET` empty → no signature check.
- `sub` presence alone (plus claim shape) was sufficient to issue a ticket.
- `alg` was checked only inside the `enforceHMAC` block, so `alg: none` was not
  rejected when the secret was unset.
- Handlers returned `401`/`400` for a missing secret, indistinguishable from a
  bad token.

## 4. New fail-closed behaviour

- `VerifyToken` returns `ErrSupabaseVerifierUnconfigured` immediately when the
  secret is unset/empty/whitespace. Every token is rejected.
- HS256 signature verification is mandatory and unconditional; `alg` must be
  exactly `HS256` (rejects `none`, RS256, ES256, etc.).
- Validates `exp`, `iat` (not in future by >60s), `nbf`, audience (`""` or
  `authenticated`), issuer prefix (when `SUPABASE_URL` set), and `sub`
  (non-empty UUID).
- No decoding-only fallback; malformed/empty tokens are rejected.
- Handlers return `503 AUTH_CONFIG_UNAVAILABLE` for a missing secret, and `401`
  for invalid signatures or other verification failures. No secret or token is
  logged or returned.
- Google ID-token verification (`google_verifier.go`) is untouched.

## 5. Configuration required for legitimate verification

Set `SUPABASE_JWT_SECRET` to the project's legacy HS256 JWT secret and
`SUPABASE_URL` to the project URL. Note: newer Supabase projects sign JWTs with
asymmetric keys; this verifier supports HS256 only. If Supabase Auth is not used,
leave the secret unset — the endpoint then fails closed (503) for every request.

## 6. Tests added and exact results

`backend/internal/auth/supabase_verifier_test.go`: 18 tests:
valid signed token; forged modified `sub`; invalid signature; missing config;
regression that an unset secret can never authenticate; expired; not-yet-valid;
incorrect issuer; incorrect audience; missing `sub`; bad `sub` format; unsupported
algorithm; `alg: none`; malformed JWT; empty token; two handler-level cases
(503 on missing config, 401 on bad signature).

Results:
- `go test ./internal/auth/ -run Supabase -v` — 18/18 PASS
- `go test ./...` — all packages ok
- `go build ./...` — pass
- `go vet ./...` and `go vet -tags=integration ./...` — pass

## 7. Remaining production configuration actions

- Set `SUPABASE_JWT_SECRET` + `SUPABASE_URL` on Render if Supabase Auth is used;
  otherwise leave unset (endpoint fails closed).
- Confirm `APP_ENV=production`.
- If Supabase Auth is not used at all, consider removing the route in a future
  change (not done here).

## 8. Is the endpoint still required by the current architecture?

No. The Android client never calls `/api/v1/auth/supabase/verify` (no `supabase`
references in `app/src`). Google ID tokens are the primary login path. The
endpoint is retained but securely unavailable until explicitly configured.

---

*No schema, Google-auth, NX ID, OTP, device, or session changes were made. All
changes remain uncommitted. No deployment performed.*