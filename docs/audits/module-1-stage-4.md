# NEXA — Module 1 Phase 4 Integration & Failure-Safety Report

**Scope:** End-to-end HTTP authentication flows, failure-safety behaviour under dependency faults, and live PostgreSQL/Redis integration suites.

**Baseline:** `docs/audits/module-1-stage-3.md` (Phases 0–3 implemented; live Redis/Postgres paths and runtime failure tests still open).

**Status:** Phase 4 test infrastructure and the failure-safety corrections it exposed are implemented. All runnable suites pass. The live PostgreSQL/Redis suites are written and compile-verified, but were **not executed on this host** because Docker, PostgreSQL, and Redis are unavailable here. They skip with explicit messages when `TEST_DATABASE_URL` / `TEST_REDIS_ADDR` are unset.

All changes remain **uncommitted** in a working tree that also contains unrelated pre-existing modifications.

**Legend:** `Implemented + tested here` · `Implemented, env-gated (not executed here)` · `Partial` · `Blocked` · `Not implemented`

---

## 1. Verdict

Phase 4 adds three layers that Phase 3 lacked:

1. **Real HTTP end-to-end coverage.** The actual auth router runs over `httptest.NewServer` with the JSON identity store and mock SMS provider, exercising the same endpoints the Android client calls.
2. **Failure-safety coverage.** Fault-injecting repositories and SMS outages prove the server returns errors instead of fake success, preserves still-valid sessions when rotation/login cannot persist a replacement, and fails closed in production without distributed OTP state.
3. **Live dependency suites.** Build-tagged PostgreSQL and Redis tests cover migrations, phone-at-rest protection, transactional device replacement, session rotation, Lua-based OTP/ticket state, distributed rate limits, and concurrent single-use behaviour.

Two production corrections came out of the failure tests:

- A distributed OTP/ticket backend outage during **registration** or **account linking** previously surfaced as `401`/`400`. It now returns `503 AUTH_STATE_UNAVAILABLE`, matching OTP request/verify.
- PostgreSQL device replacement now takes a per-account **transaction-scoped advisory lock**, so two concurrent first-time registrations for the same account cannot both leave an active device.

---

## 2. HTTP end-to-end suite

**File:** `backend/internal/auth/e2e_test.go` — 10 tests.

The harness (`newE2EHarness`) wires the production handler exactly as `cmd/server` does for the JSON store:

```go
store   := identity.NewStore(tempFile)
sms     := auth.NewMockSmsProvider()
otp     := auth.NewOtpService(sms)
handler := auth.NewAuthHandler(store, otp, nil)
mux     := http.NewServeMux(); handler.RegisterRoutes(mux)
server  := httptest.NewServer(mux)
```

Registration signatures are real Ed25519 signatures over
`NEXA_REGISTER:<ticket>:<pubkey>:<deviceID>`; login challenges are signed with the
registered device key.

| Test | Proves |
|---|---|
| `TestE2E_PhoneOtpRegistrationDeviceListAndLogout` | request → verify → register over HTTP; NX-ID + session issued; device list authenticated; logout invalidates the token |
| `TestE2E_ReRegistrationRevokesPriorDeviceAndSessions` | re-registering the same identity on a new device revokes the old device, its session, and records `REVOKED_REPLACED`; old device cannot get a login challenge |
| `TestE2E_LoginChallengeRotationAndReplayRejection` | challenge-response login issues a fresh token, rotates out the prior device session, and rejects challenge replay |
| `TestE2E_AuthTicketReplayRejected` | an auth ticket is single-use; replay cannot create a second account |
| `TestE2E_BruteForceOtpLockout` | three wrong codes are rejected; the fourth invalidates the record (`429`); the original correct code then fails |
| `TestE2E_OtpResendCooldown` | immediate resend returns `429 RATE_LIMITED` |
| `TestE2E_ConcurrentOtpVerificationSingleSuccess` | concurrent redemption of one OTP creates **at most one** ticket |
| `TestE2E_ConcurrentTicketConsumptionSingleRegistration` | concurrent redemption of one ticket creates exactly one account |
| `TestE2E_RevokedDeviceSessionRejected` | user-initiated device revocation invalidates the session; rotate returns `401` |
| `TestE2E_SessionRotateEndpoint` | `/session/rotate` and `/session/refresh` exchange a token, revoke the old one, and keep the new one usable |

---

## 3. Failure-safety suite

**File:** `backend/internal/auth/failure_safety_test.go` — 9 tests.

`faultInjectingRepository` embeds `identity.IdentityRepository` and overrides only
`CreateSession`, `RevokeDevice`, and `RevokeSessionsForDevice`, so handlers run
their real control flow against simulated storage failures.

| Test | Proves |
|---|---|
| `TestFailure_RegistrationSessionErrorReturns500WithoutFakeToken` | session write failure → `500`; response contains no token; no fake success |
| `TestFailure_LoginSessionErrorKeepsPriorSessionActive` | login session write failure → `500`; the device's existing session is **not** revoked |
| `TestFailure_SessionRotationCreateErrorKeepsOldSessionActive` | rotation cannot create a replacement → `500`; caller's session stays valid (no lockout) |
| `TestFailure_SessionRotationRevokeErrorStillReturnsReplacement` | sibling revocation failure is logged, but the replacement token is returned and active |
| `TestFailure_SmsProviderOutageReturns503AndStoresNoOtp` | provider outage → `503 SMS_UNAVAILABLE`; pending OTP is removed |
| `TestFailure_ProductionWithoutDistributedOtpStateFailsClosed` | production without Redis → `503 AUTH_STATE_UNAVAILABLE` for request, verify, and register |
| `TestFailure_MalformedRegisterRequestCreatesNothing` | malformed JSON → `400`; zero accounts created |
| `TestFailure_DeviceRevokeErrorReturns500` | device revoke persistence failure → `500`; device stays active |
| `TestFailure_OtpStateOutageDuringLinkReturns503` | account-link consume failure → `503 AUTH_STATE_UNAVAILABLE` |

### 3.1 Handler correction

**File:** `backend/internal/auth/handler.go`

- Added `writeAuthStateUnavailable`.
- `handleRegister` and the `PHONE_OTP` branch of `handleAccountLink` now map
  `ErrOtpStateUnavailable` to `503`, matching `handleOtpRequest` / `handleOtpVerify`.
- Invalid/expired/consumed tickets still return `401` and are not retryable.

---

## 4. Live PostgreSQL suite

**File:** `backend/internal/database/integration_test.go` (`//go:build integration`) — 6 tests.

Each test creates a disposable database (`nexa_it_<random>`), runs the
authoritative runtime migrations, and drops the database with `WITH (FORCE)` in
cleanup. Skip conditions are explicit: missing `TEST_DATABASE_URL`, unreachable
server, URL not `postgres://`, or a role without `CREATE DATABASE`.

| Test | Proves |
|---|---|
| `TestIntegration_MigrationsCreateProtectedSchema` | ≥7 migrations applied; `phone_hmac`, `phone_encrypted`, `device_events` exist; `RunMigrations` is idempotent |
| `TestIntegration_PhoneProtectionAndHMACLookup` | phone lookup uses HMAC; ciphertext decrypts back to the phone; no match for unknown numbers |
| `TestIntegration_DeviceReplacementRevokesSessionsAndAudits` | replacement revokes the prior device + its sessions, keeps revocation history, and writes `REVOKED_REPLACED` |
| `TestIntegration_DeviceReplacementRollsBackOnFailure` | a failed replacement rolls back the earlier revocations and does not persist the broken device |
| `TestIntegration_ConcurrentDeviceReplacementLeavesSingleActiveDevice` | the new advisory lock leaves exactly one active device under concurrent replacement |
| `TestIntegration_SessionRotationCutoffAndExceptToken` | `createdBefore`/`exceptToken` retire older sessions but keep the replacement and same-millisecond sibling |

### 4.1 Transaction correction

**File:** `backend/internal/database/repository.go`

`RegisterDeviceReplacingOthers` now acquires
`pg_advisory_xact_lock(hashtextextended(nx_id, 0))` immediately after `Begin`.
The previous `SELECT ... FOR UPDATE` only locked existing rows, so a race between
two first-time registrations could leave two active devices. The lock is
released automatically at commit/rollback.

This does **not** add a unique partial index, because the trusted-phone
re-verification path intentionally allows multiple active devices
(`Test21_SharedPipeline_NewDeviceLoginAndRevocationIdentical`).

---

## 5. Live Redis suite

**File:** `backend/internal/auth/redis_integration_test.go` (`//go:build integration`) — 6 tests.

Tests use a dedicated logical database (`TEST_REDIS_DB`, default `15`), flush it
before and after each test, and skip when `TEST_REDIS_ADDR` is unset or
unreachable.

| Test | Proves |
|---|---|
| `TestIntegration_RedisOtpLifecycleAndSingleUseTicket` | record TTL ≤5 min; distributed cooldown; wrong code rejected; correct code deletes the record; ticket consumes once and its key is gone |
| `TestIntegration_RedisBruteForceLockout` | attempt Lua script invalidates after 3 failures; fourth returns max-attempts; correct code then fails |
| `TestIntegration_RedisPerPhoneRequestRateLimit` | sixth request for one phone within an hour is rejected with `ErrRateLimitExceeded` |
| `TestIntegration_RedisPerIPRequestRateLimit` | sixteenth request from one IP within an hour is rejected |
| `TestIntegration_RedisConcurrentOtpVerification` | at most one of 8 concurrent verifications succeeds |
| `TestIntegration_RedisConcurrentTicketConsumption` | exactly one of 8 concurrent ticket consumptions succeeds |

---

## 6. Integration infrastructure

| File | Purpose |
|---|---|
| `backend/docker-compose.test.yml` | Disposable PostgreSQL 16 (`55432`) and Redis 7 (`56379`) with health checks and tmpfs storage; non-default ports avoid collisions |
| `backend/scripts/run-integration-tests.ps1` | Sets `TEST_DATABASE_URL`, `TEST_REDIS_ADDR`, `TEST_REDIS_DB`, `TEST_REDIS_PASSWORD`, then runs `go vet -tags=integration ./...` and `go test -tags=integration -count=1 ./...` |

Usage:

```powershell
docker compose -f backend/docker-compose.test.yml up -d
backend/scripts/run-integration-tests.ps1
docker compose -f backend/docker-compose.test.yml down -v
```

---

## 7. Verification performed here

| Check | Command | Result |
|---|---|---|
| Backend build | `go build ./...` | Pass |
| Backend vet (normal) | `go vet ./...` | Pass |
| Backend vet (integration tag) | `go vet -tags=integration ./...` | Pass |
| Backend unit/integration tests | `go test ./...` | All packages pass |
| E2E stress | `go test ./internal/auth -run TestE2E -count=20` | Pass |
| Failure-safety stress | `go test ./internal/auth -run TestFailure -count=30` | Pass |
| Combined E2E + failure | `go test ./internal/auth -run "TestE2E\|TestFailure" -count=10` | Pass |
| Live Postgres suite | `go test -tags=integration ./internal/database -run TestIntegration -v` | Compiles; **6/6 SKIP** (`TEST_DATABASE_URL` unset) |
| Live Redis suite | `go test -tags=integration ./internal/auth -run TestIntegration -v` | Compiles; **6/6 SKIP** (`TEST_REDIS_ADDR` unset) |
| Android unit suite (no Phase 4 client changes) | `.\gradlew.bat :app:testDebugUnitTest --rerun-tasks` | `BUILD SUCCESSFUL` |

Skip messages are explicit:

```
TEST_DATABASE_URL is not set; skipping live PostgreSQL integration tests
TEST_REDIS_ADDR is not set; skipping live Redis integration tests
```

---

## 8. Coverage status

### 8.1 Implemented and verified by runnable tests here

- Phone OTP request → verify → register over HTTP.
- Ed25519 registration proof-of-possession and device-bound login challenge-response.
- Session issuance, device listing, logout, `/session/rotate`, `/session/refresh`.
- Single-active-device replacement in the JSON store and its session/audit effects.
- Ticket replay rejection, OTP brute-force lockout, resend cooldown.
- Concurrent OTP/ticket single-use semantics.
- Revoked device/session rejection.
- Storage-failure and SMS-provider failure behaviour, including production fail-closed OTP state.
- 503 mapping for state outages on all OTP/ticket-consuming handlers.

### 8.2 Implemented, env-gated, **not executed on this host**

- Live PostgreSQL: migrations, HMAC/ciphertext phone lookup, transactional replacement/rollback, concurrent replacement, session rotation cutoff.
- Live Redis: Lua attempt/consume scripts, TTLs, distributed phone/IP limits, concurrent single-use.
- Reason: Docker CLI/Compose is not installed, nothing listens on `127.0.0.1:6379`, and no PostgreSQL instance/credentials are available. `miniredis` is not in the module cache.

### 8.3 Not implemented / blocked

- Android instrumentation/E2E tests against a running backend (unit suite remains the client-side coverage).
- CI wiring for the integration suites (no pipeline configuration was changed).
- Go race-detector runs (`-race` requires cgo; no C compiler is installed on this host).
- Real SMS providers, Play Integrity, Truecaller, Firebase Phone Auth, SIM ICCID, StrongBox attestation, Supabase production JWT.
- Client-scheduled session rotation / refresh-token loop.

---

## 9. Residual gaps carried forward

| # | Gap | Status |
|---|---|---|
| 1 | Plaintext `accounts.phone_number` is still written alongside HMAC/AES | Transitional, non-destructive; needs a reviewed drop migration |
| 2 | Phone-change uniqueness is check-then-write | A race can surface as HTTP 500 instead of 409 |
| 3 | Account-deletion verify confirms ownership only | No destructive erasure endpoint |
| 4 | `/ws` upgrades before token validation | Unchanged from earlier phases |
| 5 | Trusted-phone re-verification allows multiple active devices | Intentional; `Test21` asserts it |
| 6 | Client still generates `dev_<uuid16>` registration IDs | Unchanged |
| 7 | Device audit events are returned but not rendered in the app | Unchanged |
| 8 | Android does not schedule `/session/rotate` | Rotation still happens at login/register |
| 9 | First-time concurrent registration safety depends on PostgreSQL | JSON store is protected by its process lock; multi-process Postgres is covered by the advisory lock |
| 10 | Live integration suites have not run on this host | Must be executed in an environment with Docker or external Postgres/Redis |

---

## 10. Constraints honored

- Trusted-phone allowlist, bypass conditions, integration, and all 21 `trusted_phone_test.go` tests were not modified.
- No duplicate auth system, table, migration, or service was introduced.
- No schema was changed in Phase 4; the advisory lock and 503 mapping are behavioral corrections.

---

## 11. Phase 4 live execution attempt (2026-11-14)

### 11.1 Infrastructure startup result — BLOCKED

Attempted to start the existing disposable infrastructure per
`backend/docker-compose.test.yml`:

- `docker` / `docker compose`: **not installed** on this host (`CommandNotFoundException`).
- `podman`, `nerdctl`: **not installed**.
- Local binaries: `pg_ctl`, `psql`, `redis-server`, `redis-cli`: **not installed**.
- WSL (Ubuntu) has no PostgreSQL/Redis packages and no passwordless `sudo`
  (`sudo: interactive authentication is required`), so packages could not be
  installed. Network is reachable, but installation is blocked by privileges.
- Nothing listens on `127.0.0.1:5432`, `6379`, `55432`, or `56379`.

**Conclusion:** The disposable PostgreSQL 16 and Redis 7 instances required by the
integration suites could not be started on this host. No production or
development database/Redis was touched.

### 11.2 PostgreSQL test results

- Suite: `backend/internal/database/integration_test.go` (`//go:build integration`), 6 tests.
- Command: `go test -tags=integration -v -run 'TestIntegration' ./internal/database`
- Result: **0 executed, 6 skipped** (all with explicit message
  `TEST_DATABASE_URL is not set; skipping live PostgreSQL integration tests`).
- Skipped tests:
  - `TestIntegration_MigrationsCreateProtectedSchema`
  - `TestIntegration_PhoneProtectionAndHMACLookup`
  - `TestIntegration_DeviceReplacementRevokesSessionsAndAudits`
  - `TestIntegration_DeviceReplacementRollsBackOnFailure`
  - `TestIntegration_ConcurrentDeviceReplacementLeavesSingleActiveDevice`
  - `TestIntegration_SessionRotationCutoffAndExceptToken`
- No failures. No pass claim: these tests did not run against a real PostgreSQL.

### 11.3 Redis test results

- Suite: `backend/internal/auth/redis_integration_test.go` (`//go:build integration`), 6 tests.
- Command: `go test -tags=integration -v -run 'TestIntegration' ./internal/auth`
- Result: **0 executed, 6 skipped** (all with explicit message
  `TEST_REDIS_ADDR is not set; skipping live Redis integration tests`).
- Skipped tests:
  - `TestIntegration_RedisOtpLifecycleAndSingleUseTicket`
  - `TestIntegration_RedisBruteForceLockout`
  - `TestIntegration_RedisPerPhoneRequestRateLimit`
  - `TestIntegration_RedisPerIPRequestRateLimit`
  - `TestIntegration_RedisConcurrentOtpVerification`
  - `TestIntegration_RedisConcurrentTicketConsumption`
- No failures. No pass claim: these tests did not run against a real Redis.

### 11.4 Compile and skip verification (performed here)

| Check | Command | Result |
|---|---|---|
| Backend vet (integration tag) | `go vet -tags=integration ./...` | Pass (exit 0) |
| Backend tests (integration tag) | `go test -tags=integration -count=1 ./...` | Pass (exit 0); all live tests skipped with explicit messages |
| Normal backend build | `go build ./...` | Pass |
| Normal backend tests | `go test ./...` | All packages `ok` / `[no test files]` |

### 11.5 Cleanup verification

- Not applicable: no containers or databases were started, so there was nothing to
  clean up. The integration suites' own cleanup paths (`DROP DATABASE ... WITH (FORCE)`,
  per-test Redis `FLUSHDB` on logical DB 15) were not exercised because the suites
  skipped before reaching them.
- No test touched production services: `TEST_DATABASE_URL`/`TEST_REDIS_ADDR` were
  unset, and the suites skip explicitly in that case.

### 11.6 Remaining limitations

- Live PostgreSQL verification (migrations, HMAC/ciphertext lookup, transactional
  replacement/rollback, concurrent replacement, session rotation cutoff) remains
  **not executed** on this host.
- Live Redis verification (Lua attempt/consume scripts, TTLs, distributed phone/IP
  limits, concurrent single-use) remains **not executed** on this host.
- Go race detector (`-race`) remains unavailable (no C compiler / cgo).
- Android instrumentation/E2E against a running backend was not run.
- These suites must be executed in an environment with Docker or reachable
  PostgreSQL 16 + Redis 7 instances (see `backend/docker-compose.test.yml` and
  `backend/scripts/run-integration-tests.ps1`).
- Login/onboarding UI and navigation were not changed.
- Local-first behaviour is preserved; no user data was deleted.
- No universal SIM ICCID claim or guaranteed SIM detection.
- No client-supplied identity is trusted without server verification.
- No fake success or hardcoded auth in production paths.
- Production still fails closed on missing OTP pepper or missing distributed OTP state.

---

## 11. How to run the full Phase 4 verification

```powershell
# Always-runnable suites
cd backend
go build ./...
go vet ./...
go test ./...

# Compile-check the env-gated suites (tests skip without services)
go vet -tags=integration ./...
go test -tags=integration ./...

# Run live suites against disposable dependencies
docker compose -f docker-compose.test.yml up -d
./scripts/run-integration-tests.ps1
docker compose -f docker-compose.test.yml down -v
```
