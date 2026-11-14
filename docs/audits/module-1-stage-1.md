# NEXA — Module 1 Audit Report (Stage 1)

**Scope:** Authentication, identity, device security, local persistence, backend auth, DB schema/migrations, docs alignment.

**Mode:** Read-only audit. No source, config, schema, migration, or UI file was modified during Stage 1. Tests were inventoried, not executed.

**Repo state at audit:** branch `main`, 1 commit (`1498aa4`), with ~100 pre-existing modified files and many untracked files. The correction plan avoids touching unrelated uncommitted work.

**Legend:** `Working` · `Fully Implemented` · `Partial` · `Needs Improvement` · `Incorrect` · `Missing`

---

## 1. Verdict

Module 1 is **not implemented to the locked architecture**. It is a working prototype auth flow with several genuine building blocks (Google token verification, NX-ID allocation, reverse-SMS/trusted-phone verification, Ed25519 challenge for same-device login, revocable sessions), but it has **4 release-blocking defects** and **6 substantial architecture gaps**.

The most serious are shipped behaviours that undermine the security model:

1. Client continues login when server-side Google verification fails.
2. Phone-change and account-deletion OTPs accept any 6-digit string, purely on-device.
3. `clearSession()` wipes the SQLCipher DB passphrase, making local chat history unreadable after logout.
4. Secure storage silently falls back to plaintext preferences.

Also critical in the backend: no single-active-device rule, no revocation history, phone stored and matched in plaintext, and identity tickets that cannot link a Google/Supabase user.

**Recommendation:** Fix the P0 set first. Do not add Truecaller/Firebase/Play Integrity/SIM until the identity core is fail-closed.

---

## 2. Capability matrix

| # | Module 1 capability | Status | Key evidence |
|---|---|---|---|
| 1 | Google Credential Manager sign-in (client) | Working | `WelcomeViewModel` credential flow |
| 2 | Server-side Google ID-token verification | Working | `googleVerifier.VerifyIdToken`; `handler.go:889` |
| 3 | **Fail-closed verification** | **Incorrect** | `WelcomeViewModel.kt:181-205` sets `isSuccess=true` on backend failure |
| 4 | Supabase Auth identity | Partial | verifier + ticket, but ticket has no `BoundUser` (`handler.go:326-331`); email placed in `PhoneNumber` |
| 5 | NX-ID allocation + uniqueness | Working | identity store + DB unique index |
| 6 | Phone uniqueness | Partial | plaintext `phone_number` + unique index (`migrations.go:44`) |
| 7 | HMAC phone hash at rest | Missing | no `phone_hash` in backend |
| 8 | Device identity key (Android Keystore) | Partial | `keystoreManager.getOrCreateIdentityKeyPair` works |
| 9 | StrongBox / TEE attestation | Incorrect | `NexaHardwareSecurity()` constructed without `Context` (`AuthRepositoryImpl.kt:202,269`; `AuthApiService.kt:186`) |
| 10 | Device public-key challenge-response | Partial | Google same-device path yes (`handler.go:926-950`); reverse-SMS path no |
| 11 | **Single active device** | **Missing** | `reverse_sms.go:386-436,738-792` adds device+session without revoking old |
| 12 | Device revocation endpoint | Partial | works, but no single-device cascade |
| 13 | Revocation history / audit records | Missing | `GetDevicesForAccount` filters revoked (`identity/store.go:370-382`) |
| 14 | Truecaller OAuth (primary phone) | Missing | docs only |
| 15 | Firebase Phone Auth (fallback) | Missing | docs only |
| 16 | Reverse-SMS / trusted-phone verification | Working | `reverse_sms.go`, 21 trusted-phone tests — preserve as-is |
| 17 | Play Integrity | Missing | docs only |
| 18 | SIM ICCID signal | Missing | docs only (correctly not mandatory) |
| 19 | Rate limiting | Partial | in-memory maps; not distributed |
| 20 | Redis ephemeral state | Partial | challenges support Redis; OTP tickets/rate limits in-memory |
| 21 | Session tokens | Partial | opaque token + expiry; no rotation/refresh policy |
| 22 | Phone change flow | Incorrect | `AuthRepositoryImpl.kt:66-78` accepts any 6-digit, local only |
| 23 | Account deletion flow | Incorrect | `AuthRepositoryImpl.kt:100-125` request always succeeds; verify accepts any 6-digit |
| 24 | Local-first persistence integrity | Incorrect | `clearSession()` → `clearAll()` (`AuthRepositoryImpl.kt:548-551`) |
| 25 | Secure storage hardening | Incorrect | plaintext fallback (`SecureStorage.kt:241`) |
| 26 | OTP pepper configuration | Needs Improvement | hardcoded default (`otp_service.go:93-96`) |
| 27 | DB schema consistency | Incorrect | Go runtime schema diverges from SQL `001-017` |
| 28 | Registration device ID round-trip | Incorrect | `AuthApiService.kt:178` invents `dev_<uuid12>`; response drops server `device_id` |
| 29 | Device management UI | Partial | active list only (`SecurityScreens.kt:294-319`) |
| 30 | Login/onboarding UI & nav preserved | Working | screens intact |
| 31 | Dev bypass / demo seeding | Needs Improvement | `DEV_BYPASS_AUTH_TO_HOME_SCREEN=true`, DEBUG-gated |

---

## 3. Detailed findings

### P0-1 — Google auth fails open
`WelcomeViewModel.kt:181-205` catches backend verification failure and proceeds with `isSuccess=true` using only the client token. Backend verification at `handler.go:889` is correct, but the client does not require it.

**Impact:** server verification becomes optional; any failure becomes a local login.
**Correction:** fail closed with an error state; no token persisted; retry only.

### P0-2 — Phone change & deletion OTP are client-side
`verifyChangePhoneOtp` (`:66-78`) and `verifyDeletionOtp` (`:114-125`) accept any 6 characters and update local state. `requestDeletionOtp` (`:100-112`) always succeeds.

**Impact:** phone can be changed without proving ownership; deletion identity check is bypassable.
**Correction:** real backend request/verify endpoints, server-side HMAC-hashed OTP, uniqueness on commit, rate limits.

### P0-3 — Logout destroys local encrypted DB access
`clearSession()` calls `secureStorage.clearAll()`, clearing the DB passphrase, device ID, and identity keys.

**Impact:** encrypted local history becomes unreadable after logout.
**Correction:** split credential clearing from identity wipe; never delete the DB passphrase on logout.

### P0-4 — Plaintext secure-storage fallback
EncryptedSharedPreferences failure falls back to `nexa_fallback_preferences` in plaintext.

**Impact:** silent downgrade of token/key storage and masking of Keystore corruption.
**Correction:** fail closed with a typed error; no plaintext path in release.

### P1-1 — No single-active-device enforcement
New device/session is registered on re-verify without revoking prior devices.

### P1-2 — No revocation history
Revoked devices are filtered out and only a boolean remains. No audit table.

### P1-3 — Tickets cannot link Google/Supabase identity
`handleSupabaseVerify` and `handleGoogleVerify` omit `BoundUser`; `handleRegister` reads it.

### P1-4 — Registration discards server device ID
Client-generated `dev_<uuid>` is sent, but the server `device_id` is not surfaced or persisted, so the next login cannot match the device and is misrouted to `REQUIRE_SIM_VERIFICATION`.

### P1-5 — StrongBox/TEE claims are not real
Auth call sites construct `NexaHardwareSecurity()` with no `Context`, so StrongBox is always skipped.

### P1-6 — Device challenge-response missing on reverse-SMS login
SMS code alone issues a session; the registered device key is not proven.

### P1-7 — Phone stored and matched in plaintext
No HMAC lookup key. Uniqueness is on the plaintext column.

### P1-8 — Schema divergence
`backend/internal/database/migrations.go` and `database/migrations/*.sql` define incompatible models (`devices.account_id/nx_id` vs `devices.user_id`).

### P1-9 — Hardcoded OTP pepper default
A shipped default is used when `OTP_SERVER_PEPPER` is unset.

### P2 — Hardening & UX
- In-memory rate limiting and OTP tickets.
- No session rotation/refresh.
- Device screen lacks current-device details, history, SIM state, hardware badge, masked phone.
- `getVerificationStatus` returns an incomplete DTO.
- Play Integrity/Truecaller/Firebase/SIM absent.
- `/ws` relies on post-upgrade challenge.

---

## 4. Database & security impact

| Area | Current | Required |
|---|---|---|
| Phone at rest | plaintext | HMAC lookup + encrypted value |
| Phone uniqueness | plaintext unique index | unique index on HMAC |
| Device state | boolean, filtered | active state + append-only audit log |
| Active devices | unlimited | exactly 1 |
| Schema | divergent | one authoritative, contract-tested |
| Local DB after logout | unreadable | preserved |
| Key storage failure | plaintext fallback | fail closed |
| OTP pepper | hardcoded default | required env in prod |

---

## 5. Test coverage

**Backend auth:** 9 test files, 52 test functions covering OTP, challenge login, reverse SMS, trusted-phone (21 tests), webhook HMAC, Google verifier, identity, security audit, Twilio/MSG91.

**Android:** 73 unit test files including onboarding, auth/identity, challenge-response, hardware security, multi-device, and account lifecycle. No instrumentation coverage beyond the template.

**Not covered:** all four P0 defects, single-active-device, device ID round-trip, post-logout DB readability, revocation history, ticket binding.

**Execution status:** inventoried, not run, in Stage 1.

---

## 6. Blocked / external dependencies

SIM/ICCID (carrier + OS restrictions), Truecaller (partner credentials/SDK), Firebase Phone Auth (project + quota), Play Integrity (Play-signed build + Cloud project + attested device), Supabase JWT (project keys), Twilio/MSG91 (live credentials), StrongBox (real hardware), device attestation (real devices).

These must be reported as not implemented/blocked, never as done.

---

## 7. Approved correction plan

Decisions taken:
- **Scope:** full plan.
- **Phone at rest:** HMAC lookup + encrypted value.
- **Schema authority:** Go runtime migrations short-term, with a contract test against SQL migrations.

**Phase 0 — Safety net:** regression tests for P0-1…P0-4; schema contract test; baseline runs.

**Phase 1 — P0:** fail-closed Google; secure storage split + no plaintext fallback; real phone-change/deletion verification; required OTP pepper; registration device ID round-trip; complete verification-status DTO.

**Phase 2 — Identity core:** single-active-device transaction + revocation audit; ticket binding; device challenge on reverse-SMS; real security-level reporting; HMAC phone + encrypted value with non-destructive backfill; schema contract test; device management UI additions.

**Phase 3 — Signals & hardening:** Play Integrity, Truecaller + Firebase fallback, optional SIM signal, Redis rate limits/tickets, session rotation, config hygiene.

**Hard constraints:** do not alter the trusted-phone allowlist/bypass or its tests; preserve onboarding/navigation; no duplicate auth systems; no destructive migrations; no plaintext secrets; no fake success; every new signal is additive.

---

## 8. Stage 2 results

See `docs/audits/module-1-stage-2.md` for implemented/tested vs untested/blocked status.

## 9. Stage 3 results

See `docs/audits/module-1-stage-3.md` for distributed OTP state, distributed rate limiting, session rotation, and configuration-hygiene status.

## 10. Stage 4 results

See `docs/audits/module-1-stage-4.md` for HTTP end-to-end coverage, failure-safety behaviour, and the env-gated live PostgreSQL/Redis integration suites.
