# NEXA — Module 1 Implementation Report (Stage 2)

**Scope:** Authentication, identity, device security, phone-at-rest protection, local persistence, backend auth, DB schema/migrations, and the device-management UI.

**Baseline:** `docs/audits/module-1-stage-1.md` (Stage 1 verdict: Module 1 not implemented to the locked architecture; 4 release-blocking defects, 6 architecture gaps).

**Status:** Phase 0 (safety net), Phase 1 (P0), and Phase 2 (identity core) are implemented. Phase 3 external signals are not implemented/blocked. All changes remain **uncommitted** in the working tree, which also contains unrelated pre-existing modifications.

**Legend:** `Implemented + tested` · `Implemented (unit only)` · `Partial` · `Blocked` · `Not implemented`

---

## 1. Verdict

All four Stage 1 release-blocking defects are corrected and covered by tests:

1. Google verification now fails closed on the client.
2. Phone-change and account-deletion OTPs are real, server-side, purpose-bound flows.
3. Logout no longer destroys the SQLCipher passphrase, device identity, or local identity keys.
4. Secure storage has no plaintext fallback.

The identity core now enforces: server-verified ticket binding, unique NX-ID, phone uniqueness via HMAC, a single active device on real sign-in paths, ownership-checked revocation, an append-only device audit trail, HMAC + AES-GCM phone protection with a non-destructive backfill, and startup fail-closed configuration checks.

The remaining Module 1 work is external (Play Integrity, Truecaller, Firebase Phone Auth, SIM ICCID, real StrongBox attestation, live SMS vendors) plus internal hardening items listed in §7. The trusted-phone bypass was deliberately left behaviorally unchanged.

---

## 2. Phase 0 — Safety net

| Guard | File | Tests |
|---|---|---|
| Server-side phone-change/deletion and OTP-pepper regression tests | `backend/internal/auth/account_security_test.go` | 9 |
| Client fail-closed Google, logout-preserves-identity, server device ID, DTO guards | `app/src/test/java/com/nexa/app/AuthHardeningUnitTests.kt` | 7 |
| Go runtime vs SQL migration contract | `backend/internal/database/schema_contract_test.go` | 1 |

The contract test parses both migration sources, fails on any un-allowlisted table drift, and pins the `devices.user_id UUID` (SQL) vs `devices.account_id/nx_id` (runtime) divergence so it cannot change silently.

---

## 3. Phase 1 — P0 corrections

### P0-1 — Google auth fails closed
`WelcomeViewModel.kt` no longer maps a backend verification failure to success. `googleVerificationFailedState()` returns `isSuccess=false`, `isInstantLogin=false`, and the only recovery is retry. No token is persisted on failure.

### P0-2 — Real phone-change and account-deletion verification
New server endpoints in `backend/internal/auth/account_security.go`:

- `POST /api/v1/auth/phone/change/request`
- `POST /api/v1/auth/phone/change/verify`
- `POST /api/v1/auth/account/deletion/request`
- `POST /api/v1/auth/account/deletion/verify`

Properties:

- Requires a live, non-revoked, non-expired bearer session.
- OTP is bound to the account **and** to the operation (`phone_change:<NX-ID>`, `account_deletion:<NX-ID>`).
- Phone change re-checks ownership/uniqueness at commit time and updates the server record first; the client only updates local state after success.
- Deletion verify proves identity only and returns a short-lived confirmation. It deliberately does **not** destroy data inside an OTP handler.
- Client wired through `AuthApiService` (`requestPhoneChange`/`verifyPhoneChange`, `requestAccountDeletion`/`verifyAccountDeletion`) and `AuthRepositoryImpl`.

### P0-3 — Logout preserves local identity
`clearSession()` clears credentials/session state but keeps the SQLCipher passphrase, device ID, hardware-security level, and local identity key material. A separate `clearAll()` remains only for explicit permanent wipe.

### P0-4 — No plaintext secure-storage fallback
`SecureStorage` no longer creates `nexa_fallback_preferences`. Initialisation failure raises `SecureStorageUnavailableException`; callers surface it as an auth failure.

### P0-5 — OTP pepper fails closed
`resolveOtpPepper()` requires `OTP_SERVER_PEPPER` when `APP_ENV`/`ENV` is `production`; the development-only fallback is clearly labelled and unreachable in production.

### P0-6 — Server device ID round-trip
`handleRegister` returns the server-issued `device_id`; `AuthRepositoryImpl` persists it via `SecureStorage.saveDeviceId()`. The server record is authoritative on the next login.

### P0-7 — Ticket binding
`AuthTicket` now carries `BoundUser`, `Provider`, and `Email`. Google and Supabase tickets no longer overload `PhoneNumber` with an email. `handleRegister` binds Google subjects to `GoogleID`, Supabase subjects to `SupabaseUserID`, and phone OTP to a verified phone.

---

## 4. Phase 2 — Identity core

### 4.1 Single active device
- `identity.Store.RegisterDeviceReplacingOthers()` and `PostgresRepository.RegisterDeviceReplacingOthers()` revoke every other active device and its sessions in the same write/transaction, record a `REVOKED_REPLACED` audit event, then register the new device.
- Enforced in `handleRegister` and the non-trusted `ProcessInboundWebhook` paths.
- `RegisterDevice()` keeps its previous semantics (used where multi-device behavior is expected and where a pre-set revoked flag must be preserved).
- **Intentional exception:** the trusted-phone `InitiateVerification` route still uses `RegisterDevice`. `Test21_SharedPipeline_NewDeviceLoginAndRevocationIdentical` asserts two active devices after trusted re-verification; the bypass must not be modified.

### 4.2 Revocation history and audit
- `Device` gained `RevokedAt` and `RevokedReason`.
- New `DeviceEvent` model and repository methods `RecordDeviceEvent` / `GetDeviceEvents`.
- Migration `007_phone_protection_and_device_audit` adds `devices.revoked_at`, `devices.revoked_reason`, `device_events`, indexes, and RLS/privilege handling for the new table.
- `RevokeDeviceForUser(nxID, deviceID)` verifies ownership before revoking; a non-owner cannot revoke another account's device.
- `GET /api/v1/auth/devices` now returns `devices` (active), `revoked_devices`, and `events`.

### 4.3 Phone protection at rest
- `identity/phonecrypto.go`: deterministic HMAC-SHA256 lookup hash with a domain separator, AES-256-GCM encryption with a random nonce and AAD, typed errors, key parsing for raw/hex/base64 32-byte keys, and a distinctness check.
- Production fails closed when `PHONE_HMAC_KEY`/`PHONE_ENC_KEY` are missing; startup calls `ValidatePhoneCryptoConfig()` and aborts.
- `accounts.phone_hmac` + `accounts.phone_encrypted` added by migration 007; `CreateAccount`/`UpdateAccount` populate them.
- Reads prefer HMAC (`GetAccountByPhone`, contact discovery); the plaintext column is only a transitional fallback for un-backfilled rows.
- `BackfillPhoneProtection()` runs idempotently after migrations and reports/skips individual failures instead of blocking startup.

### 4.4 Registration ticket purpose guard
Tickets issued for phone change or account deletion are bound to a `phone_change:`/`account_deletion:` subject. `handleRegister` now rejects those tickets (and any ticket with an unsupported provider) with `401` before allocating an account, so a sensitive-operation OTP cannot be replayed into registration. Covered by subtests for phone-change, deletion, and unknown-provider tickets.

### 4.5 Android identity/device UX
- `NexaHardwareSecurity` is constructed with the application context at every call site, so TEE/StrongBox probing actually runs.
- The verified key security level is persisted (`key_hardware_security_level`) and survives logout.
- `ConnectedDevicesScreen` shows: this device (name, id, hardware-security badge, last active), active devices with revoke actions, and revocation history with timestamps/reasons.
- `DeviceInfoDto` and the device-list parser handle `revoked_at`, `revoked_reason`, and the separate `revoked_devices` array.

### 4.6 Schema authority
Go runtime migrations remain short-term authoritative. The contract test pins the known divergence rather than claiming the two schemas match. Migration count is asserted at 7.

---

## 5. Files changed

### Backend
| File | Change |
|---|---|
| `backend/internal/identity/phonecrypto.go` (new) | HMAC/AES-GCM primitives, key loading, production fail-closed |
| `backend/internal/identity/phonecrypto_test.go` (new) | 6 tests |
| `backend/internal/identity/device_lifecycle_test.go` (new) | 3 tests |
| `backend/internal/identity/model.go` | phone protection fields, revocation fields, `DeviceEvent` |
| `backend/internal/identity/repository.go` | new repository interface methods |
| `backend/internal/identity/store.go` | HMAC index, single-device replacement, audited revocation, ownership check |
| `backend/internal/database/repository.go` | phone columns, HMAC-first lookups, device transactions, audit events |
| `backend/internal/database/migrations.go` | migration 007 |
| `backend/internal/database/phone_backfill.go` (new) | idempotent phone backfill |
| `backend/internal/database/schema_contract_test.go` (new) | schema contract guard |
| `backend/internal/database/database_test.go` | migration count 7 |
| `backend/internal/auth/handler.go` | ticket binding, provider guard, single-device register, devices payload |
| `backend/internal/auth/otp_service.go` | `Provider`/`Email` on tickets, pepper fail-closed |
| `backend/internal/auth/account_security.go` (new) | phone-change/deletion request+verify |
| `backend/internal/auth/account_security_test.go` (new) | 9 tests |
| `backend/internal/auth/register_test.go` (new) | 3 tests (incl. purpose guard subtests) |
| `backend/internal/auth/reverse_sms.go` | single-device on non-trusted paths only |
| `backend/internal/prekey/handler_test.go` | interface mock updated |
| `backend/cmd/server/main.go` | startup phone-key validation, backfill call |

### Android
| File | Change |
|---|---|
| `NexaApplication.kt` | inject `applicationContext` into auth API/repository |
| `SecureStorage.kt` | no plaintext fallback, hardware-level persistence, credential-only session clear |
| `AuthApiService.kt` | context-aware hardware key, server device ID, revoked-device parsing |
| `AuthRepositoryImpl.kt` | context wiring, security-level recording, server device ID persistence |
| `DeviceInfoDto.kt` | revocation fields |
| `SecurityScreens.kt` | current device, hardware badge, active list, revocation history |
| `AuthNavGraph.kt` | pass hardware level to the screen |
| `AuthHardeningUnitTests.kt` | 7 regression guards |

---

## 6. Verification evidence

| Check | Command | Result |
|---|---|---|
| Backend build | `go build ./...` (from `backend`) | Pass |
| Backend vet | `go vet ./...` | Pass |
| Backend tests | `go test ./...` | All packages pass |
| Android compile | `.\gradlew.bat :app:compileDebugKotlin` | `BUILD SUCCESSFUL` |
| Android unit suite | `.\gradlew.bat :app:testDebugUnitTest` | `BUILD SUCCESSFUL`; 74 test classes, **640 tests, 0 failures** |
| New backend tests | `go test ./internal/identity ./internal/database ./internal/auth` | Pass |

New/updated tests: 6 phone-crypto, 3 device-lifecycle, 3 registration (one with 3 subtests), 9 account-security, 1 schema-contract, 7 Android hardening guards. The 21 trusted-phone tests still pass unchanged.

---

## 7. Residual risks and known gaps

| # | Gap | Status / impact |
|---|---|---|
| 1 | Plaintext `accounts.phone_number` still exists and is written | Transitional, non-destructive decision. HMAC is preferred for lookup, but plaintext remains at rest until a reviewed migration drops it. **Not** "no plaintext phone at rest" yet. |
| 2 | Phone-change uniqueness is check-then-write, not transactional | A concurrent change can hit the unique constraint and surface as HTTP 500 instead of 409. |
| 3 | Account deletion verify confirms ownership only | No destructive deletion/erasure endpoint exists; irreversible deletion is intentionally out of an OTP handler. |
| 4 | `/ws` upgrades before token validation | The socket receives an Ed25519 challenge after upgrade; unauthenticated sockets can consume resources for up to 60s. `/ws/signaling` does validate a token. |
| 5 | Trusted-phone re-verification allows multiple active devices | Intentional: preserving the verified bypass and `Test21`. This is a documented exception to the single-device rule. |
| 6 | Rate limiting and OTP tickets were in-memory | Closed in Phase 3 when Redis is configured: OTP records, tickets and rate limits are Redis-backed with production fail-closed behaviour. Client-scheduled session refresh remains unimplemented. See `docs/audits/module-1-stage-3.md`. |
| 7 | Postgres paths are not integration-tested | No live Postgres in the test run: migration 007, HMAC/AES SQL paths, transactional device replacement, and backfill are compile/unit verified only. |
| 8 | Hardware-security level depends on real Keystore/StrongBox | Unit tests use fakes; real TEE/StrongBox behavior and attestation are not verified on physical devices. |
| 9 | Registration still generates a client `dev_<uuid16>` | The server accepts it and persists/returns it; the client stores the server value. A client-chosen ID is still allowed for first registration. |
| 10 | Device audit events are exposed by API but not rendered | UI shows revocation history from device records; the `events` array is available for a future audit view. |
| 11 | Legacy plaintext phone fallback remains | Disappears once `BackfillPhoneProtection` has run; intentionally retained for safety. |

---

## 8. Blocked / not implemented (external)

These are **not done** and must not be reported as done:

- **Play Integrity** — requires a Play-signed build, Cloud project, and attested device.
- **Truecaller OAuth** — partner credentials/SDK agreement.
- **Firebase Phone Auth fallback** — Firebase project, quota, and client SDK configuration.
- **SIM ICCID signal** — carrier/OS restrictions; SIM is an additional signal, never primary auth.
- **Real StrongBox attestation** — needs physical hardware and a verification service.
- **Live SMS providers (MSG91/Twilio)** — requires production credentials; current tests use mock providers.
- **Supabase production JWT configuration** — requires project keys.
- **Destructive account erasure workflow** — intentionally unimplemented pending a reviewed retention design.

Phase 3 has since implemented Redis-backed OTP/ticket state, distributed OTP rate limiting, and server-side session rotation. See `docs/audits/module-1-stage-3.md` for what is tested and what remains blocked (live Redis/Postgres integration tests, client-scheduled refresh). Phase 4 added HTTP end-to-end and failure-safety suites plus env-gated live PostgreSQL/Redis tests; see `docs/audits/module-1-stage-4.md`.

---

## 9. Constraints honored

- Trusted-phone allowlist, bypass conditions, integration, and the 21 `trusted_phone_test.go` tests were not modified.
- Login/onboarding UI and navigation were preserved; the device screen was extended, not rebuilt.
- No duplicate auth system or table was introduced.
- Local-first behavior is preserved: logout keeps the encrypted DB usable, and no user data is deleted.
- No guaranteed SIM detection and no universal ICCID claim.
- No client-supplied Google identity is trusted without server verification.
- No fake success or hardcoded auth was added to production paths.
- No destructive migration: the plaintext phone column is retained and backfilled.
- Production startup fails closed on missing phone-key or OTP-pepper configuration.
