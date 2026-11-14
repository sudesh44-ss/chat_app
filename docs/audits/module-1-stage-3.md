# NEXA — Module 1 Phase 3 Hardening Report

**Scope:** Distributed OTP/auth-ticket state, distributed OTP rate limiting, session rotation, and authentication configuration hygiene.

**Baseline:** `docs/audits/module-1-stage-2.md` (Phase 0–2 complete; Phase 3 internal hardening open).

**Status:** Internal Phase 3 hardening is implemented and unit-tested. External identity signals remain blocked. All changes remain **uncommitted** in the working tree, which also contains unrelated pre-existing modifications.

**Legend:** `Implemented + tested` · `Implemented (unit only)` · `Partial` · `Blocked` · `Not implemented`

---

## 1. Verdict

Phase 3 closed the two internal gaps that Stage 2 left open:

1. **OTP records, resend cooldowns, auth tickets and OTP rate limits are no longer process-local.** When Redis is configured they live in Redis with TTLs and atomic single-use consumption. Production fails closed (`503 AUTH_STATE_UNAVAILABLE`) instead of silently using per-instance memory.
2. **Sessions rotate.** Re-registration or re-authentication on the same device revokes that device's earlier sessions while keeping the new token active. A dedicated rotation endpoint can exchange a live token for a fresh one and revoke the old token plus same-device siblings.

The trusted-phone bypass, its conditions, and the 21 `trusted_phone_test.go` tests were not modified. No duplicate auth system, table, or service was introduced.

---

## 2. Distributed OTP state

**File:** `backend/internal/auth/otp_state.go` (new), `backend/internal/auth/otp_service.go`

`OtpService` now accepts a cluster backend:

```go
otpService.SetDistributedState(buffer.GetClient(), isProduction)
```

Wiring is in `backend/cmd/server/main.go`. Behaviour by configuration:

| Configuration | OTP records / cooldown / tickets | Rate limiting |
|---|---|---|
| Production + Redis | Redis, TTL-bound | Redis sliding window; Redis errors fail closed |
| Production, no Redis | **Fail closed** (`ErrOtpStateUnavailable` → HTTP 503) | Fail closed |
| Dev/test, no Redis | Existing in-memory maps | Existing in-memory maps |
| Dev/test, Redis present | Redis | Redis |

### 2.1 OTP records and resend cooldown
- Record key: `auth:otp:record:<E.164 phone>`, stored as JSON with a **5-minute TTL** (covers the 60-second resend cooldown and code validity).
- Cooldown is evaluated from the stored `LastSentAt`; a failed SMS dispatch deletes the pending record.
- Rate-limit slots intentionally are **not** rolled back when the SMS provider fails, so a broken provider cannot be used to bypass throttling.

### 2.2 Attempts and single-use verification
Two Redis Lua scripts make verification atomic:

- `otpAttemptScript` — rejects missing/expired records, increments attempts, invalidates after **3** failed attempts, preserves the record TTL.
- `otpConsumeScript` — deletes the record only if its salt and HMAC still match the record that was verified in Go. Two racing verifications cannot both succeed.

The OTP hash is still computed in Go with HMAC-SHA256 and compared with `subtle.ConstantTimeCompare`; Redis never receives the OTP or pepper.

### 2.3 Auth tickets
- Key: `auth:otp:ticket:<token>`, TTL 10 minutes.
- `IssueTicket` is now the single write path. Google (`handleGoogleVerify`) and Supabase (`handleSupabaseVerify`) no longer mutate `otpService.tickets` directly.
- `ConsumeTicket` uses an atomic GET+DEL Lua script; a second redemption fails.
- The dev/test in-memory map remains for the existing tests and single-instance development.

### 2.4 Distributed rate limits
Using the existing `ratelimit.DistributedLimiter` (Redis sorted-set sliding window):

| Action | Key | Limit |
|---|---|---|
| OTP request per phone | `ratelimit:otp:phone:<phone>` | 5 / hour |
| OTP request per IP | `ratelimit:otp:ip:<ip>` | 15 / hour |
| OTP verification per phone | `ratelimit:otp:verify:<phone>` | 15 / 15 minutes |

A limiter error returns `ErrOtpStateUnavailable`; the HTTP handler maps it to `503` with code `AUTH_STATE_UNAVAILABLE`. A normal limit breach stays `429`.

---

## 3. Session rotation

### 3.1 Repository contract
**Files:** `backend/internal/identity/repository.go`, `backend/internal/identity/store.go`, `backend/internal/database/repository.go`

```go
RevokeSessionsForDevice(nxID string, deviceID string, exceptToken string, createdBefore int64) (int, error)
```

- JSON `Store`: revokes matching active sessions under its lock and persists once.
- `PostgresRepository`: one `UPDATE sessions SET is_revoked = TRUE ...` with optional `device_id` and `created_at <` filters, returning rows affected.
- An empty `deviceID` revokes all active sessions for the account; an empty `exceptToken` keeps none.
- `createdBefore` (when non-zero) leaves sessions created at or after that instant alone, so a fresh/concurrent token is not retired; callers pass the rotation request start (plus one millisecond) and protect their own new token with `exceptToken`.

### 3.2 Login and registration rotation
- `handleLoginVerify`: creates the new 30-day session, then revokes all earlier sessions for `(nx_id, device_id)` except the new token. A previously leaked token for that device stops working.
- Both paths also ask the session revoker to close any live WebSocket bound to the replaced/prior device, so an already-open socket does not outlive its session.
- `handleRegister`: after `RegisterDeviceReplacingOthers`, revokes stale sessions for the same device except the newly issued token. Rotation runs **after** the new session is created so a failure cannot lock the user out with no replacement token.
- Revocation failure is logged and does not fabricate success; the response is only sent after the session exists.

### 3.3 Rotation endpoint
`POST /api/v1/auth/session/rotate` (alias `POST /api/v1/auth/session/refresh`):

- Requires `Authorization: Bearer <active session token>`.
- Rejects missing, expired, or revoked sessions and revoked devices (`401`).
- Creates a fresh 30-day token for the same account/device, revokes the presented token and same-device siblings, and returns `SESSION_ROTATED` with `session_auth_token`, `expires_at`, `nx_id`, and `device_id`.
- The Android client does **not** schedule this endpoint yet; see §6.

---

## 4. Configuration hygiene

- New `auth.ValidateOtpConfig(isProduction)` in `backend/internal/auth/otp_service.go`.
- `cmd/server/main.go` now aborts startup in production when `OTP_SERVER_PEPPER` is missing, instead of discovering it on the first OTP request.
- Existing production fail-closed checks remain: phone HMAC/AES keys, degraded Redis buffer, PostgreSQL repositories.
- Production OTP state additionally fails closed when no Redis client is attached, even if the pepper is valid.

---

## 5. Verification

| Check | Command | Result |
|---|---|---|
| Backend build | `go build ./...` (from `backend`) | Pass |
| Backend vet | `go vet ./...` | Pass |
| Backend tests | `go test ./...` | All packages pass |
| Android compile | `.\gradlew.bat :app:compileDebugKotlin` | Pass |
| Android unit suite | `.\gradlew.bat :app:testDebugUnitTest` | Pass |

New Phase 3 tests:

| File | Coverage | Count |
|---|---|---|
| `backend/internal/auth/otp_state_test.go` | production fail-closed without Redis; dev fallback still works; ticket single-use; OTP config validation (3 subtests) | 4 functions + 3 subtests |
| `backend/internal/auth/session_rotation_test.go` | rotate revokes old+sibling tokens; missing token rejected; revoked device rejected; login-verify rotates prior device session | 4 |
| `backend/internal/auth/register_test.go` | same-device re-registration revokes prior sessions (extended existing file) | 1 new |
| `backend/internal/identity/session_rotation_test.go` | per-device revoke; `exceptToken`; all-device revoke; unknown account no-op; `createdBefore` cutoff (same-millisecond safety) | 5 |

The 21 trusted-phone tests remain unchanged and pass.

---

## 6. Untested, residual, and blocked

### 6.1 Untested / not exercised

| # | Item | Status |
|---|---|---|
| 1 | Redis Lua paths (`otpAttemptScript`, `otpConsumeScript`, `ticketConsumeScript`, sliding-window limiter) | Compile/vet verified here. Phase 4 added live `//go:build integration` tests for all of these in `backend/internal/auth/redis_integration_test.go`; they skip on hosts without `TEST_REDIS_ADDR` and have not yet been executed in this environment. See `docs/audits/module-1-stage-4.md`. |
| 2 | Postgres `RevokeSessionsForDevice` | Compile/unit verified here. Phase 4 added live `//go:build integration` tests (including rotation cutoff and transactional device replacement) in `backend/internal/database/integration_test.go`; they have not yet been executed in this environment. See `docs/audits/module-1-stage-4.md`. |
| 3 | Android scheduling of `/api/v1/auth/session/rotate` | Not implemented. Rotation currently happens transparently at login/register; the client has no periodic refresh or 401-retry rotation. |
| 4 | Redis rate-limit rollback on SMS failure | Intentional: distributed slots are not rolled back. Memory fallback still rolls back. |
| 5 | Multi-instance development without Redis | Unsupported: dev without Redis remains single-instance memory. |

### 6.2 Still open from Stage 2

- Plaintext `accounts.phone_number` remains transitionally.
- Phone-change uniqueness is still check-then-write (possible HTTP 500 instead of 409 on a race).
- Account deletion verify confirms ownership only; no destructive erasure endpoint.
- `/ws` upgrades before token validation.
- Trusted-phone re-verification intentionally allows multiple active devices (`Test21`).
- Client registration still generates `dev_<uuid16>`.
- Device audit events are exposed but not rendered.

### 6.3 Blocked / external (not implemented)

- Play Integrity.
- Truecaller OAuth.
- Firebase Phone Auth fallback.
- SIM ICCID signal.
- Real StrongBox attestation.
- Live SMS providers (MSG91/Twilio) — tests use mock providers.
- Supabase production JWT configuration.
- Destructive account-erasure workflow.

These must not be reported as done.

---

## 8. Stage 4 results

See `docs/audits/module-1-stage-4.md` for the HTTP end-to-end suite, the failure-safety corrections (503 on OTP-state outages, advisory-locked device replacement), and the env-gated live PostgreSQL/Redis integration suites.

---

## 7. Constraints honored

- Trusted-phone allowlist, bypass conditions, integration, and all 21 `trusted_phone_test.go` tests were not modified.
- No duplicate auth system, table, or service was introduced.
- Login/onboarding UI and navigation were not changed in this phase.
- Local-first behaviour is preserved; no user data was deleted.
- No universal SIM ICCID claim or guaranteed SIM detection.
- No client-supplied identity is trusted without server verification.
- No fake success or hardcoded auth in production paths.
- Production fails closed on missing OTP pepper or missing distributed OTP state.
