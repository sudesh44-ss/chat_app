# NEXA — Supabase, Google OAuth, NX ID & Android↔Go↔PostgreSQL/Redis Connectivity Audit

**Date:** 2026-11-14
**Classification:** Read-only audit (no source, SQL, migration, environment, Google Cloud, Supabase, or database state was changed)
**Scope:** Supabase schema/identity, Google OAuth, NX ID identity, Android ↔ Go backend ↔ PostgreSQL/Redis connectivity
**Output:** `docs/audits/supabase-google-nxid-connectivity-audit.md`
**Status legend:** `Verified (code)` · `Verified (live)` · `Not verified` · `Blocked`

---

## 1. Executive verdict

- The Android client has **no Supabase client SDK** (no GoTrue/PostgREST/Ktor dependency). It talks only to the Go backend over HTTPS/WSS at `https://nexa-relay.onrender.com` / `wss://nexa-relay.onrender.com` (`app/src/main/java/com/nexa/app/core/network/ServerEndpointConfig.kt`).
- Google sign-in is a **Google ID-token flow verified by the Go backend**, not a Supabase Auth flow. Android obtains an ID token via Android Credential Manager (`GetGoogleIdOption`) and posts it to `POST /api/v1/auth/google`; the backend verifies the RS256 signature against Google JWKS (`backend/internal/auth/google_verifier.go`).
- NX ID is **authoritative in the Go backend**. `identity.GenerateRandomNxID()` + `PostgresRepository.AllocateNxID()` create `NX-XXXXXXXX` IDs; PostgreSQL enforces format and uniqueness. No Android or SQL code generates production NX IDs.
- The Go backend connects **directly to PostgreSQL** via `DATABASE_URL` (pgxpool) and to **Redis** via `REDIS_URL`/`REDIS_ADDR`. There is no Supabase client library in the backend.
- **Two incompatible schemas coexist:** Go runtime migrations (`backend/internal/database/migrations.go`, versions 1–7) create `accounts`/`sessions`/etc.; the SQL files in `database/migrations/001`–`017` create a legacy Supabase-shaped `profiles`/`devices`/etc. They are not the same schema and must not be treated as one.
- **CRITICAL (live-confirmed):** Supabase JWT verification is optional when `SUPABASE_JWT_SECRET` is unset. A live probe of `POST /api/v1/auth/supabase/verify` with a JWT that had an empty signature and no `sub` claim returned `401 missing sub (Supabase user ID)`, proving the signature check was skipped. A token carrying a `sub` claim would be accepted and a 10-minute auth ticket issued. This is a critical authentication-integrity finding.
- Live Render health at 2026-11-14 02:52:59Z reported `database: CONNECTED`, `redis: CONNECTED`, `status: UP`. Live Google endpoint probe returned `401 Google token verification failed: malformed JWT`, which confirms `GOOGLE_CLIENT_ID` is configured on the server (otherwise the error would be `GOOGLE_CLIENT_ID is not configured on server`).

---

## 2. Verification matrix (code vs live)

| Area | Code-level verification | Live verification | Notes |
|---|---|---|---|
| Android network clients | Verified | Verified (host reachable) | OkHttp/WebSocket only; no Supabase SDK |
| Google client ID in repo | Verified (two identical literals) | Not verified on Render | Live probe only proves *some* client ID is set |
| Google token verification | Verified (`RS256` + JWKS + audience/issuer/exp/subject) | Not verified end-to-end | Would need a real Google ID token |
| Supabase verifier | Verified (HS256, optional) | **Confirmed bypass** via negative probe | Secret appears unset in production |
| NX ID generation/allocation | Verified | Not verified against live DB | Format/uniqueness enforced by DB constraints |
| Go runtime schema | Verified (`go test` contract test) | Not verified (no DB access) | Health says migrations succeeded |
| SQL contract schema | Verified (file inspection) | **Not applied/verified live** | Supabase Dashboard not accessible |
| PostgreSQL connectivity | Verified (`pgxpool`) | Verified (`/health` CONNECTED) | Actual `DATABASE_URL` not visible |
| Redis connectivity | Verified (`go-redis`) | Verified (`/health` CONNECTED) | Actual `REDIS_URL` not visible |
| WebSocket auth | Verified (code) | Not verified | Signaling requires token before upgrade |
| Android session rotation | Verified absent | n/a | Backend endpoint exists but unused |
| Local `.env` | 0 active lines (all commented) | n/a | No secrets committed; startup needs env injection |

---

## 3. Implemented architecture

### 3.1 Components

- **Android app** (`app/`): Kotlin/Compose, Retrofit is not used; `OkHttpClient`-based `AuthApiServiceImpl` (`app/src/main/java/com/nexa/app/data/remote/api/AuthApiService.kt`), Credential Manager, local Room + SQLCipher.
- **Go backend** (`backend/`): `net/http`, `pgx/v5`, `go-redis/v9`, WebSocket hub, FCM stub.
- **PostgreSQL**: either Supabase (production) or local JSON fallback (`data/identity_store.json`) in development.
- **Redis**: ephemeral 24h offline queue, challenge nonces, distributed locks/state.

### 3.2 Android network and auth stack

- Base URLs: `ServerEndpointConfig.httpBaseUrl` / `wsBaseUrl` = `https://nexa-relay.onrender.com` / `wss://nexa-relay.onrender.com` (hardcoded, no build flavor/env override).
- Google entry point: `WelcomeViewModel.signInWithCredentialManager()` uses `CredentialManager.create()`, `GetGoogleIdOption.Builder().setServerClientId(webClientId).setFilterByAuthorizedAccounts(false)`, `GetCredentialRequest`.
- Client ID source: `R.string.google_web_client_id` (also duplicated in `app/build.gradle.kts` as `buildConfigField("String","GOOGLE_WEB_CLIENT_ID",...)`). Both literals currently equal `356056510017-grmokt7u1sch6hdk9dbah85k2jqe32v2.apps.googleusercontent.com`. `BuildConfig.GOOGLE_WEB_CLIENT_ID` is not referenced anywhere in `app/src`.
- Session storage: `SecureStorage` (EncryptedSharedPreferences, AES256-GCM, hardware-backed MasterKey). No plaintext fallback.
- No Supabase/Gotrue/PostgREST dependency exists in `app/build.gradle.kts` or `gradle/libs.versions.toml`.

### 3.3 Go backend persistence

- `cmd/server/main.go`: reads `DATABASE_URL`; on non-empty value creates a pgxpool, runs `database.RunMigrations`, backfills phone protection, then selects `PostgresRepository`; in production, missing DB/Redis is fatal.
- Fallback: `identity.NewStore("data/identity_store.json")` only in non-production.
- Redis: `queue.NewRedisBuffer` prefers `REDIS_URL`, then `REDIS_ADDR`+`REDIS_PASSWORD`; degraded mode is fatal in production.
- Identity repository interface: `identity.IdentityRepository` (`backend/internal/identity/repository.go`); implementations: `Store` (JSON) and `PostgresRepository`.

### 3.4 Supabase's actual role

Supabase is used in **three, separate, non-client ways**:

1. **PostgreSQL host** for `DATABASE_URL` (Go backend connects directly via pgx).
2. **Legacy JWT verifier** (`backend/internal/auth/supabase_verifier.go`) for the optional `/api/v1/auth/supabase/verify` and `/api/v1/auth/link` (`SUPABASE` link type) endpoints.
3. **SQL contract artifact** (`database/migrations/001`–`017`) — Supabase-shaped DDL that is **not executed by the Go backend**.

There is no Supabase Realtime, Storage, Edge Functions, or PostgREST usage anywhere in the code.

---

## 4. Google OAuth (Android + Go backend)

### 4.1 Client flow

| Step | File / function | Detail |
|---|---|---|
| Trigger | `app/.../presentation/welcome/WelcomeViewModel.kt` `signInWithCredentialManager()` | `GetGoogleIdOption` with `setServerClientId(webClientId)`, `setFilterByAuthorizedAccounts(false)`, `setAutoSelectEnabled(false)` |
| Backend call | `AuthRepositoryImpl.loginWithGoogle()` | Calls `AuthApiService.googleAuth(idToken, deviceId, publicKeyHex)` → `POST /api/v1/auth/google` |
| Same-device proof | `AuthApiService.verifyLoginChallenge()` | `POST /api/v1/auth/login-verify` after hardware signature |
| Status handling | `WelcomeViewModel` `when (resp.status)` | `LOGIN_SUCCESS`, `REQUIRE_DEVICE_CHALLENGE`, `REQUIRE_SIM_VERIFICATION`, `NEW_USER` (else branch) |
| Failure | `googleVerificationFailedState()` | Fail-closed: no token persisted on failure |
| New-user profile setup | `SetupViewModel` + `AuthRepositoryImpl` | `POST /api/v1/auth/initiate-reverse-verify` (or aliases) with `google_uid` + `google_auth_token` |

### 4.2 Server-side Google verification

- File: `backend/internal/auth/google_verifier.go`
- `NewGoogleVerifier()` reads `GOOGLE_CLIENT_ID` from env at construction.
- `VerifyIdToken()`:
  1. Fail-closed if `clientID == ""` (`ErrMissingGoogleClientID`).
  2. Requires exactly 3 JWT segments and `alg == RS256`.
  3. Validates `exp > now`, issuer ∈ {`accounts.google.com`, `https://accounts.google.com`}, `aud == clientID`, non-empty `sub`.
  4. Verifies RS256 signature via `rsa.VerifyPKCS1v15` against keys fetched from `https://www.googleapis.com/oauth2/v3/certs` (cached 6h).
- `handleGoogleAuth()` (`backend/internal/auth/handler.go`) returns:
  - `NEW_USER` with `google_uid`, `email`, `prefill_name`, `prefill_avatar` for unknown Google subjects.
  - `REQUIRE_DEVICE_CHALLENGE` (with a 60s challenge) when the device/key matches the registered account.
  - `REQUIRE_SIM_VERIFICATION` for a known account on a new device, including the full registered phone and `inbound_receiver_number`.
- `handleGoogleVerify()` (`/api/v1/auth/google/verify`) issues a 10-minute Google auth ticket (`ticket_g_…`) bound to the verified subject; it is implemented but **not called by the Android client** (dead client path).
- `handleOtpRequest()` optionally verifies a supplied `google_auth_token` and binds `boundUser` to the issued ticket (fail-open on invalid token: it simply leaves `boundUser` empty).

### 4.3 Session issuance after Google login

- Same device: `login-verify` issues `nexa_sess_<64 hex chars>` with `ExpiresAt = now + 30 days`, registers/rotates sessions, revokes prior device sessions.
- New device: reverse-SMS verification must complete first; then a session is issued.
- Android persists the token in `SecureStorage.saveAuthToken()` and the active NX ID in `saveActiveNxId()`.

### 4.4 Google configuration status

- Repo literals match across `app/build.gradle.kts` and `app/src/main/res/values/strings.xml`.
- Live probe `POST /api/v1/auth/google` with `{"id_token":"not-a-real-token"}` returned `401 {"error":"Google token verification failed: malformed JWT: expected 3 parts, got 1"}`, which confirms the server has `GOOGLE_CLIENT_ID` configured (the verifier checks the client ID before parsing, but the error shown is the parse error, meaning the client ID branch passed).
- **Not verified:** whether the Render `GOOGLE_CLIENT_ID` equals the repo literal; whether the Google Cloud OAuth consent screen, Web client, and Android client (package `com.nexa.app` + release SHA-1) are configured; whether the release keystore SHA-1 is registered.

### 4.5 Google findings

1. **Medium:** client ID is hardcoded in two places with no single source of truth; `BuildConfig.GOOGLE_WEB_CLIENT_ID` is never read.
2. **Low:** `email_verified` is parsed but not enforced; subject-based identity makes this non-blocking.
3. **Low:** no `nonce` is used; acceptable for Credential Manager but means no replay binding beyond token expiry.
4. **Info:** Android `GetGoogleIdOption` uses `setAutoSelectEnabled(false)`, so account selection is shown every time.

---

## 5. Supabase integration

### 5.1 What exists

- No Supabase client SDK in Android or Go.
- `backend/internal/auth/supabase_verifier.go`: HS256 JWT verifier using `SUPABASE_JWT_SECRET` and `SUPABASE_URL`.
- Endpoints: `POST /api/v1/auth/supabase/verify`, `POST /api/v1/auth/link` (`LinkType: "SUPABASE"`).
- Database: `accounts.supabase_user_id` (partial unique index) maps a NEXA account to a Supabase user.
- SQL contract: `database/migrations/001`–`017` are Supabase/PostgreSQL-shaped and reference `auth.users`/`profiles`.

### 5.2 Verifier behavior (code)

- `NewSupabaseVerifier()` sets `enforceHMAC = (SUPABASE_JWT_SECRET != "")`.
- If `enforceHMAC` is true: requires `alg == HS256` and a valid HMAC-SHA256 signature.
- Validates `exp`, `iat` (not in future by >60s), audience ∈ {`""`, `"authenticated"`}, issuer prefix (only if `SUPABASE_URL` set), non-empty `sub`.
- **If `enforceHMAC` is false:** signature is not checked at all; only claim shape/temporal checks remain.

### 5.3 CRITICAL finding: optional signature verification (live-confirmed)

- Live probe: `POST /api/v1/auth/supabase/verify` with a JWT whose header/payload were valid base64url and whose signature was empty, and whose payload had no `sub`, returned:
  ```
  401 {"error":"Supabase token verification failed: missing sub (Supabase user ID) in token claims"}
  ```
- Because the request failed on `sub` rather than on signature, the server is running with `SUPABASE_JWT_SECRET` unset/empty, i.e. `enforceHMAC == false`.
- Consequence: any request containing a JWT with `sub`, `exp` in the future, `aud: "authenticated"`, and a matching issuer (if `SUPABASE_URL` is set) would pass verification, and `handleSupabaseVerify` would issue a 10-minute `ticket_sb_…` auth ticket bound to that `sub`. `handleRegister` would then create an account with `supabase_user_id = <forged sub>` (or, for a pre-existing matching public key, bind the ticket to that account path). `handleAccountLink` (`LinkType: SUPABASE`) would attach a forged `sub` to an already-authenticated account.
- The request did not create an account or ticket because `sub` was absent; no state was modified.
- **Root cause:** `supabase_verifier.go` treats signature verification as optional.
- **Secondary compatibility risk:** even if the secret is set, the verifier supports only HS256 (legacy Supabase secret). Newer Supabase projects sign JWTs with asymmetric keys (ES256/RS256) and may not expose a usable HS256 secret, which would make real Supabase tokens fail verification (availability) or, worse, tempt operators to leave the secret unset (security).

### 5.4 Endpoint usage

- Android has no call to `/api/v1/auth/supabase/verify` (grep of `app/src` for `supabase` returned no matches). The endpoint is effectively server-exposed but unused by the shipped client.
- `handleAccountLink` supports `LinkType: "SUPABASE"`.

### 5.5 Supabase dashboard/live state

- **Not verified:** Supabase project settings, JWT signing configuration, RLS, applied migrations, table contents, `auth.users` triggers, pooler region.
- Historical repo docs (e.g. `docs/audit/POSTGRESQL_MIGRATION_001_NX_ID_FAILURE_REPORT.md`) describe a legacy Supabase schema and a collision that blocked Go migrations; the current live `/health` shows the backend is connected and running, which implies the current deployment's migrations succeeded, but the live schema contents were not inspected.

---

## 6. NX ID identity

### 6.1 Format and validation

- Canonical format: `NX-` + exactly 8 uppercase alphanumeric characters. Regex: `^NX-[A-Z0-9]{8}$` (`backend/internal/identity/nxid.go` `nxIdRegex`; SQL `chk_nx_id_format`).
- Generation charset: `23456789ABCDEFGHJKLMNPQRSTUVWXYZ` (32 chars, excludes visually ambiguous `0`, `O`, `1`, `I`), selected via `crypto/rand.Int` (`GenerateRandomNxID()`).
- `IsValidNxID()` validates the broader regex (not the narrower charset), so generated IDs are a subset of valid IDs.
- Legacy helpers `DeriveNxID` / `DeriveNxIDFromHex` produce a different, deprecated `NX-XXXX-XXX` format and are not used in production registration.

### 6.2 Allocation and stability

- `PostgresRepository.AllocateNxID(pubKeyHex)` (`backend/internal/database/repository.go`):
  1. Returns the existing `nx_id` if `public_key_hex` is already registered (stability by public key).
  2. Otherwise generates up to 10 random candidates, checking `SELECT EXISTS(... WHERE nx_id = $1)` for collisions, and returns the first free one.
  3. Returns `failed to allocate unique random NX-ID after 10 attempts` if all collide.
- `identity.Store.AllocateNxID()` mirrors this for the JSON fallback store (mutex-protected, 10 attempts).
- `CreateAccount()` relies on `UNIQUE(nx_id)` and `UNIQUE(public_key_hex)`; a race between two `AllocateNxID` calls can surface as a constraint violation at insert time (HTTP 409) rather than a retry.

### 6.3 Database constraints

- `accounts.nx_id VARCHAR(32) NOT NULL UNIQUE` + `CONSTRAINT chk_nx_id_format CHECK (nx_id ~ '^NX-[A-Z0-9]{8}$')`.
- Partial unique indexes: `idx_accounts_phone`, `idx_accounts_google_id`, `idx_accounts_supabase_uid`, `idx_accounts_phone_hmac` (added in migration 7).
- `accounts.public_key_hex VARCHAR(128) NOT NULL UNIQUE`.
- Foreign keys from `devices`, `sessions`, `connection_requests`, `connections`, `user_blocks`, `identity_key_bindings`, `signed_prekeys`, `one_time_prekeys`, `prekey_claims`, `phone_visibility_grants`, `device_events` reference `accounts(nx_id)` and/or `accounts(id)` with `ON DELETE CASCADE`.

### 6.4 Creation paths (all server-side)

| Path | Function | NX ID source |
|---|---|---|
| Phone OTP registration | `handleRegister` | `AllocateNxID(req.PublicKeyHex)` |
| Google new user → reverse SMS | `ReverseSmsService.InitiateVerification` (fresh registration) | `AllocateNxID(req.PublicKeyHex)` |
| Google known user, new device | `InitiateVerification` (re-verify) | Reuses existing account's NX ID |
| Supabase ticket registration | `handleRegister` (Provider `SUPABASE`) | `AllocateNxID(req.PublicKeyHex)` |
| Trusted-phone bypass | `InitiateVerification` (trusted path) | `AllocateNxID` (uses `RegisterDevice`, not replacing) |

### 6.5 SQL-side NX ID artifacts (unused)

- `database/migrations/013_create_database_functions.sql` defines `public.generate_random_nx_id()` using `random()` (not cryptographically secure) and `public.lookup_nexa_contacts()`. The Go backend does not call either function; it uses `identity.GenerateRandomNxID()`. The SQL function also checks uniqueness against `public.profiles`, which does not exist in the Go runtime schema.
- SQL `profiles.nx_id` is `VARCHAR(11)`; Go `accounts.nx_id` is `VARCHAR(32)` with the same regex. Both enforce 11 characters.

### 6.6 NX ID findings

1. **Info:** two different generation implementations (Go crypto/rand vs SQL `random()`) with different uniqueness tables; only Go is authoritative.
2. **Low:** allocation has a 10-attempt cap and no automatic retry after a constraint violation at insert time.
3. **Low:** `IsValidNxID` accepts any `A-Z0-9` 8-char suffix, while generation only emits a 32-char subset; this is intentional (validation is permissive).

---

## 7. Schema and migrations

### 7.1 Schema authority

- Decision recorded in repo: Go runtime migrations in `backend/internal/database/migrations.go` are the short-term authoritative schema; SQL files under `database/migrations/` are the long-term contract artifact and are currently divergent.
- `backend/internal/database/schema_contract_test.go` pins this divergence: it allowlists runtime-only and SQL-only tables and asserts the `devices` column divergence (`SQL: user_id UUID` vs `runtime: account_id`/`nx_id`).
- The contract test currently passes (`go test ./...` green).

### 7.2 Go runtime schema (authoritative) — 7 migrations

| Version | Name | Tables created |
|---|---|---|
| 1 | `001_create_identity_schema` | `accounts`, `devices`, `sessions`, `phone_verifications` |
| 2 | `002_create_connections_schema` | `connection_requests`, `connections`, `user_blocks` |
| 3 | `003_create_prekeys_schema` | `identity_key_bindings`, `signed_prekeys`, `one_time_prekeys`, `prekey_claims` |
| 4 | `004_create_relationship_graph_and_phone_privacy` | `phone_visibility_grants` (and migrates legacy grants) |
| 5 | `005_create_message_idempotency_table` | `message_idempotency` |
| 6 | `006_enforce_row_level_security_fail_closed` | enables RLS on 13 tables; revokes `anon`/`authenticated` privileges if those roles exist |
| 7 | `007_phone_protection_and_device_audit` | adds `accounts.phone_hmac`, `accounts.phone_encrypted`, `devices.revoked_at`, `devices.revoked_reason`; creates `device_events` |

Plus `schema_migrations` (tracking table, created by `RunMigrations`).

**Runtime-only tables (not in SQL contract):** `accounts`, `sessions`, `connection_requests`, `connections`, `user_blocks`, `message_idempotency`, `device_events`.

### 7.3 SQL contract schema (`database/migrations/001`–`017`)

| File | Tables / objects |
|---|---|
| 001 | `profiles` |
| 002 | `devices` |
| 003 | `nx_identities` |
| 004 | `phone_verifications` |
| 005 | `contacts` |
| 006 | `identity_keys` |
| 007 | `prekeys` |
| 008 | `message_metadata` |
| 009 | `device_sessions` |
| 010 | indexes only |
| 011 | constraints, `check_device_count()` function/trigger |
| 012 | RLS policies (profiles, devices, nx_identities, contacts, prekeys, phone_verifications, device_sessions, message_metadata) |
| 013 | functions `generate_random_nx_id()`, `lookup_nexa_contacts()` |
| 014 | trigger function `set_updated_at()` |
| 015 | `user_telemetry_events` + 12 monthly partitions (`_2026_01` … `_2026_12`) + `drop_expired_telemetry_partition()` |
| 016 | `phone_visibility_grants` |
| 017 | `identity_key_bindings`, `signed_prekeys`, `one_time_prekeys`, `prekey_claims` |

**SQL-only tables (not in Go runtime):** `profiles`, `nx_identities`, `contacts`, `identity_keys`, `prekeys`, `message_metadata`, `device_sessions`, `user_telemetry_events` (+12 partitions).

**Shared names with incompatible columns:** `devices` (`SQL: user_id UUID REFERENCES profiles`; `Go: account_id VARCHAR(64)`, `nx_id VARCHAR(32)`), `phone_verifications` (`SQL: hashed_otp`/`salt`/`attempts`/`max_attempts`/`last_sent_at`/`is_consumed`; `Go: nonce`/`session_id`/`verified`), `phone_visibility_grants` (`SQL: granter_id`/`grantee_id UUID REFERENCES profiles`; `Go: granter_nx_id`/`grantee_nx_id REFERENCES accounts(nx_id)`).

The prekey tables (`identity_key_bindings`, `signed_prekeys`, `one_time_prekeys`, `prekey_claims`) are name-aligned and column-compatible between Go migration 3 and SQL 017; the main difference is constraint/index naming and the fact that SQL 017 also references `accounts` (Go-only).

### 7.4 Execution mechanics

- `RunMigrations(ctx, pool)` (`backend/internal/database/migrations.go`):
  - Creates `schema_migrations(version, name, applied_at)`.
  - Acquires a transaction-scoped PostgreSQL advisory lock (`8472910384729102`).
  - For each migration in order: checks `schema_migrations`; if absent, runs `m.SQL` in a transaction and records the version.
  - Timeout passed by `cmd/server/main.go`: 30s.
- `BackfillPhoneProtection()` runs after migrations (idempotent, skips individual failures).
- Identity JSON→PostgreSQL one-time migrator runs after that.
- Go migrations are **idempotent on a clean Go schema** (`CREATE TABLE IF NOT EXISTS`, `ALTER TABLE … ADD COLUMN IF NOT EXISTS`, `CREATE INDEX IF NOT EXISTS`) but **not idempotent against the legacy Supabase schema** for `devices`/`phone_verifications`/`phone_visibility_grants`.

### 7.5 Ordering conflicts and applyability

- **Go first, then SQL 001–016:** fails — legacy SQL assumes `profiles`/`auth.users`, `devices.user_id`, `prekeys.user_id UUID`, etc. and would collide or break on missing columns.
- **SQL 001–016 first, then Go:** fails — `devices` and `phone_verifications` already exist without Go's columns; `CREATE TABLE IF NOT EXISTS` becomes a no-op and the next `CREATE INDEX … ON devices(nx_id)` raises `SQLSTATE 42703`.
- **SQL 017:** references `accounts` (Go-only) and `devices(device_id)`; it is designed to run **after** Go migrations 1 and 3. It is the only SQL file aligned with the Go lineage, but its `CREATE TABLE IF NOT EXISTS` is a no-op when Go already created equivalent tables, and constraint/index names may collide.
- **Conclusion:** the two lineages are mutually exclusive except for 017, which is a hybrid. A database must be provisioned with exactly one lineage; mixing them requires the archival/rename remediation described in `docs/audit/POSTGRESQL_MIGRATION_001_NX_ID_FAILURE_REPORT.md`.

### 7.6 RLS and privilege posture

- Go migration 6 enables RLS on all 13 core Go tables and revokes `ALL` on all tables/sequences/routines from `anon`/`authenticated` if those roles exist (Supabase environment).
- Migration 7 enables RLS on `device_events` and revokes privileges, with `insufficient_privilege` guards.
- SQL 012 enables RLS only on the legacy tables it creates (`profiles`, `devices`, `nx_identities`, `contacts`, `prekeys`, `phone_verifications`, `device_sessions`, `message_metadata`).
- SQL 015 (`user_telemetry_events`) and SQL 017 tables (`identity_key_bindings`, `signed_prekeys`, `one_time_prekeys`, `prekey_claims`) have **no RLS enablement** in the SQL files. If applied to Supabase without Go migration 6, they would be exposed via PostgREST.
- Go migration 6's blanket `REVOKE ALL ON ALL TABLES` covers SQL-created tables too, but only if Go migration 6 has run.

### 7.7 Live schema state

- **Not verified.** No `DATABASE_URL`/Supabase credentials are available on this host, and no direct DB inspection was performed.
- Inference: the live Render `/health` returned `database: CONNECTED`, and the server runs `RunMigrations` before serving; therefore the currently connected database passed migrations 1–7 at least once. Which lineage/tables are present, and whether legacy tables remain, is unknown.
- `docs/New folder/SUPABASE_LIVE_SCHEMA_VERIFICATION.md` explicitly states all 14 SQL migrations were only **VALIDATED**, not **APPLIED**, and live inspection was blocked.

---

## 8. Connectivity (Android ↔ Go ↔ PostgreSQL/Redis)

### 8.1 Backend startup sequence (`cmd/server/main.go`)

1. `identity.ValidatePhoneCryptoConfig()` — fatal if `PHONE_HMAC_KEY`/`PHONE_ENC_KEY` missing in production (dev uses deterministic fallback keys).
2. `auth.ValidateOtpConfig(isProduction)` — fatal in production if `OTP_SERVER_PEPPER` missing.
3. Redis buffer init (fatal in production if unavailable).
4. PostgreSQL pool + `RunMigrations` + `BackfillPhoneProtection` + JSON migrator (fatal in production if unavailable).
5. Auth handler, FCM, WebSocket hub, cluster subscriber.
6. Connections repository (PostgreSQL or in-memory; fatal in production if unavailable).
7. Prekey repository (PostgreSQL or in-memory; fatal in production if unavailable).
8. HTTP route registration, `/health`, `/ws`, `/ws/signaling`.

### 8.2 PostgreSQL

- Driver: `github.com/jackc/pgx/v5/pgxpool`, configured by `database.DefaultConfig(databaseURL)` (max 25 conns, min 5, 1h lifetime, 30m idle, 1m health check, 5s connect timeout).
- URL format expected: `postgresql://USER:PASSWORD@HOST:PORT/DATABASE?sslmode=require`.
- Historical blocker: Supabase direct hostnames resolve to IPv6 only on free/standard tiers, while Render containers have no IPv6 route (`docs/audit/LOCAL_PRODUCTION_INFRASTRUCTURE_AUDIT/05_PRODUCTION_SUPABASE_CONNECTIVITY.md`). The documented fix is to use the Supabase session-mode pooler (`aws-0-<region>.pooler.supabase.com:5432`) with username `postgres.<project-ref>`. The live instance is currently connected, so the deployed URL is presumably pooler-capable, but the actual URL was not verified.
- `sslmode=require` is documented; no cert verification beyond that is configured.

### 8.3 Redis

- `queue.NewRedisBuffer` prefers `REDIS_URL` (`redis://`/`rediss://`), then `REDIS_ADDR`+`REDIS_PASSWORD`.
- Used for: 24h ephemeral offline queue, single-use auth tickets, challenge nonces (Redis-backed `ChallengeStore`), distributed rate limits, cluster pub/sub.
- Degraded mode is fatal in production.
- Live `/health` reported `redis: CONNECTED`.

### 8.4 WebSocket authentication

- `/ws` (`main.go`): upgrades to WebSocket **before** authentication; sends an Ed25519 challenge; client must prove possession. No session token is checked on upgrade.
- `/ws/signaling`: requires `token` (query or `Authorization: Bearer …`) before upgrade; validates via `identityRepo.GetSession()`, checks expiry/revocation, NX ID match, and device revocation. Returns 401/403 before upgrading.
- `/ws/swarm` (separate binary): similar pattern.

### 8.5 Android endpoints

- HTTP base: `https://nexa-relay.onrender.com` (hardcoded, `USE_PRODUCTION_CLOUD = true`).
- WebSocket: `wss://nexa-relay.onrender.com`.
- Auth API: `AuthApiServiceImpl` calls `/api/v1/auth/*`, `/api/v1/profile`, `/api/v1/contacts/lookup`, `/api/v1/users/resolve`, `/api/v1/devices/*`.
- Signaling: `SignalingClient` → `/ws/signaling?nx_id=…&token=…`; `SwarmSignalingClient` similar.
- Chat: `WebSocketManager` → `/ws?nx_id=…&device_id=…`.

### 8.6 Live probes (read-only, 2026-11-14)

1. `GET https://nexa-relay.onrender.com/health` → `200 {"status":"UP","database":"CONNECTED","redis":"CONNECTED",...}` (timestamp 1794624779729).
2. `POST /api/v1/auth/google` with `{"id_token":"not-a-real-token"}` → `401 {"error":"Google token verification failed: malformed JWT: expected 3 parts, got 1"}` (confirms client ID configured).
3. `POST /api/v1/auth/supabase/verify` with a 3-part JWT (valid header/payload, empty signature, no `sub`) → `401 {"error":"Supabase token verification failed: missing sub (Supabase user ID) in token claims"}` (confirms signature verification is skipped).

No state was modified by these probes.

### 8.7 Health endpoint caveat

- `/health` reports `database` from a value captured at startup (`dbStatus`) and `redis` from `buffer.IsDegraded()` captured at startup. It does **not** re-probe either dependency. A "CONNECTED" response means the backend connected at boot, not that it is currently healthy.

---

## 9. Endpoint inventory (auth and identity)

All routes registered in `AuthHandler.RegisterRoutes()` (`backend/internal/auth/handler.go`):

| Method | Path | Auth | Purpose |
|---|---|---|---|
| POST | `/api/v1/auth/otp/request` | None | Request OTP (optional `google_auth_token`) |
| POST | `/api/v1/auth/otp/verify` | None | Verify OTP, issue 10-min ticket |
| POST | `/api/v1/auth/google` | None | Verify Google ID token; returns status machine |
| POST | `/api/v1/auth/google/verify` | None | Verify Google ID token, issue ticket (unused by client) |
| POST | `/api/v1/auth/supabase/verify` | None | Verify Supabase JWT, issue ticket |
| POST | `/api/v1/auth/login-challenge` | None | Issue hardware challenge for same device |
| POST | `/api/v1/auth/login-verify` | None | Verify hardware signature, issue 30-day session |
| POST | `/api/v1/auth/initiate-reverse-verify` | None | Reverse-SMS verification (registration/re-verify) |
| POST | `/api/v1/auth/initiate-reverse-sms` | None | Alias of above |
| POST | `/api/v1/auth/initiate-registration` | None | Alias of above |
| POST | `/api/v1/webhooks/sms-inbound` | Webhook HMAC | Carrier inbound SMS callback |
| POST | `/api/v1/webhooks/sms-received` | Webhook HMAC | Alias of above |
| GET | `/api/v1/auth/verification-status` | None (nonce) | Poll reverse-SMS status |
| GET | `/api/v1/auth/status` | None (nonce) | Alias of above |
| GET | `/api/v1/users/resolve` | Bearer session | Resolve NX ID/phone to profile |
| POST | `/api/v1/auth/register` | Ticket (body) | Register account + device + session |
| POST | `/api/v1/auth/link` | Bearer session | Link phone/Google/Supabase to account |
| POST | `/api/v1/auth/logout` | Bearer (optional) | Revoke session |
| POST | `/api/v1/auth/session/rotate` | Bearer | Rotate session token |
| POST | `/api/v1/auth/session/refresh` | Bearer | Alias of above |
| GET/PUT | `/api/v1/profile` | Bearer | Read/update profile (de facto session validation) |
| POST | `/api/v1/contacts/lookup` | Bearer | Discover contacts by phone |
| GET | `/api/v1/auth/devices` | Bearer | List active/revoked devices + events |
| POST | `/api/v1/devices/{id}/revoke` | Bearer | Revoke a device |
| POST | `/api/v1/devices/fcm-token` | Bearer | Update FCM token |
| POST | `/api/v1/auth/phone/change/request` | Bearer | Start phone change (OTP) |
| POST | `/api/v1/auth/phone/change/verify` | Bearer | Commit phone change |
| POST | `/api/v1/auth/account/deletion/request` | Bearer | Start account deletion (OTP) |
| POST | `/api/v1/auth/account/deletion/verify` | Bearer | Prove deletion identity |

WebSocket endpoints (not in auth handler): `/ws` (messaging, post-upgrade Ed25519 challenge), `/ws/signaling` (token-gated before upgrade).

---

## 10. Configuration checklist

### 10.1 Supabase

- [ ] Decide schema authority (Go runtime vs SQL contract) and archive the other lineage before provisioning.
- [ ] If reusing a DB that previously ran legacy SQL, archive/rename `devices`, `phone_verifications`, `phone_visibility_grants` (and drop `safe_profiles` view / `toggle_phone_visibility` / `check_device_count` trigger) per `docs/audit/POSTGRESQL_MIGRATION_001_NX_ID_FAILURE_REPORT.md`.
- [ ] Inspect `auth.users` triggers; drop or repoint any `on_auth_user_created` trigger before renaming `profiles`.
- [ ] Use the session-mode pooler (`aws-0-<region>.pooler.supabase.com:5432`) in `DATABASE_URL` (not the direct IPv6 hostname) for Render.
- [ ] Username for pooler: `postgres.<project-ref>`.
- [ ] `sslmode=require`.
- [ ] Verify `schema_migrations` contains versions 1–7 after first boot.
- [ ] Verify RLS is enabled on all Go tables and `anon`/`authenticated` have no privileges.
- [ ] Decide whether Supabase Auth is used at all. If not, remove/disable `/api/v1/auth/supabase/verify` and the `SUPABASE` link branch, or set `SUPABASE_JWT_SECRET` (HS256) and verify real token compatibility (asymmetric keys).
- [ ] If Supabase Auth is used, ensure `SUPABASE_URL` is set so issuer is checked.

### 10.2 Google Cloud

- [ ] OAuth consent screen configured (test users if in testing mode).
- [ ] Web application client created → this is the value used as `GOOGLE_CLIENT_ID` and `R.string.google_web_client_id`.
- [ ] Android client created for package `com.nexa.app` with SHA-1 of the debug and release keystores.
- [ ] Render `GOOGLE_CLIENT_ID` equals `356056510017-grmokt7u1sch6hdk9dbah85k2jqe32v2.apps.googleusercontent.com`.
- [ ] Android `R.string.google_web_client_id` and Gradle `GOOGLE_WEB_CLIENT_ID` stay in sync (ideally single source).
- [ ] Release keystore (`app/release.keystore`) SHA-1 registered for production builds.

### 10.3 Go backend (Render)

- [ ] `APP_ENV=production` (otherwise dev fallbacks and optional signatures weaken security).
- [ ] `PORT` (Render provides, e.g. 10000).
- [ ] `DATABASE_URL` (pooler URL, session mode).
- [ ] `REDIS_URL` (`rediss://…`).
- [ ] `GOOGLE_CLIENT_ID`.
- [ ] `PHONE_HMAC_KEY` (32 bytes, raw/hex/base64) — **missing from `.env.example`**.
- [ ] `PHONE_ENC_KEY` (32 bytes, distinct from HMAC key) — **missing from `.env.example`**.
- [ ] `OTP_SERVER_PEPPER`.
- [ ] `INBOUND_SMS_RECEIVER_NUMBER` (real E.164, not dummy).
- [ ] `GATEWAY_WEBHOOK_SECRET`.
- [ ] `FCM_PROJECT_ID` (note: FCM v1 OAuth2 token exchange is not implemented; service runs mock).
- [ ] Optional: `MSG91_AUTH_KEY`/`MSG91_TEMPLATE_ID` or Twilio vars.
- [ ] Optional: `NEXA_TRUSTED_PHONE_NUMBERS` (QA only).
- [ ] Do **not** set `DEBUG_SHOW_OTP=true` in production.
- [ ] `SUPABASE_JWT_SECRET` and `SUPABASE_URL` only if Supabase Auth is used; otherwise disable the endpoints.

### 10.4 Android

- [ ] `USE_PRODUCTION_CLOUD` / base URLs point at the intended backend.
- [ ] `google_web_client_id` matches Render `GOOGLE_CLIENT_ID`.
- [ ] Release signing configured (`RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`).
- [ ] Device clock/timezone correct (affects OTP and token expiry).
- [ ] Google Play Services updated (Credential Manager).

### 10.5 Local development

- [ ] Set `PHONE_HMAC_KEY` and `PHONE_ENC_KEY` (or accept dev fallback keys).
- [ ] Set `OTP_SERVER_PEPPER` if testing production mode.
- [ ] Set `DATABASE_URL` to a local Postgres or accept JSON fallback.
- [ ] Set `REDIS_URL` or accept in-memory fallback (dev only).
- [ ] `.env` is entirely commented in this repo; env vars must be injected by the shell/IDE.

---

## 11. Manual setup and verification runbook

1. **Provision PostgreSQL (Supabase).**
   - Create project; record project ref and pooler region.
   - In SQL editor, run the pre-flight legacy-table inspection:
     ```sql
     SELECT table_name FROM information_schema.tables
     WHERE table_schema='public' AND table_name IN ('devices','phone_verifications','phone_visibility_grants','profiles');
     ```
   - If legacy tables exist, archive them per §7.5 / the migration failure report before starting the backend.
2. **Set Render environment variables** per §10.3 (include `PHONE_HMAC_KEY`/`PHONE_ENC_KEY`).
3. **Deploy the backend.** Watch logs for:
   ```
   [Database] Applying migration 1: 001_create_identity_schema...
   ...
   [Database] Migration 7 (007_phone_protection_and_device_audit) applied successfully.
   ```
4. **Verify schema:**
   ```sql
   SELECT version, name FROM schema_migrations ORDER BY version;
   SELECT table_name FROM information_schema.tables WHERE table_schema='public' ORDER BY 1;
   SELECT column_name FROM information_schema.columns WHERE table_name='devices' ORDER BY ordinal_position;
   ```
   Expected: versions 1–7; `devices` has `account_id`, `nx_id`; `accounts` has `phone_hmac`, `phone_encrypted`.
5. **Verify connectivity:**
   ```powershell
   curl.exe -sS https://nexa-relay.onrender.com/health
   ```
   Expect `database: CONNECTED`, `redis: CONNECTED`.
6. **Verify Google wiring:**
   ```powershell
   $f = "$env:TEMP\nexa_probe.json"; '{"id_token":"not-a-real-token"}' | Set-Content -NoNewline $f
   curl.exe -sS -X POST -H "Content-Type: application/json" --data-binary "@$f" https://nexa-relay.onrender.com/api/v1/auth/google
   Remove-Item $f
   ```
   Expect `401` with a malformed-JWT message (not "GOOGLE_CLIENT_ID is not configured").
7. **Build and test backend:**
   ```powershell
   Set-Location H:\chat_app\backend
   go build ./...
   go test ./...
   ```
   Expected: build succeeds; all packages `ok` or `[no test files]`.
8. **Android:**
   - Build debug APK; run Google sign-in on a device with a Google account; confirm `/api/v1/auth/google` returns `NEW_USER` or a challenge and a session is persisted.
   - Verify session survives app restart and `/api/v1/profile` returns 200.
9. **Security hardening (before production use):**
   - Set `SUPABASE_JWT_SECRET` or disable Supabase endpoints.
   - Confirm `APP_ENV=production`.
   - Re-run the Supabase negative probe; it must now fail on signature (not on `sub`).

---

## 12. Blockers, risks, and unverified items

| Severity | Item | Evidence / location |
|---|---|---|
| Critical | Supabase JWT signature verification optional; live server accepts unsigned tokens | `backend/internal/auth/supabase_verifier.go` (`enforceHMAC`); live probe §8.6 |
| Critical | Supabase endpoint exposed and unused by client; forged ticket issuance possible | `/api/v1/auth/supabase/verify`, `handleSupabaseVerify` |
| High | Two mutually incompatible schemas; SQL 001–016 cannot be applied alongside Go migrations | `schema_contract_test.go`, `POSTGRESQL_MIGRATION_001_NX_ID_FAILURE_REPORT.md` |
| High | SQL 017 depends on `accounts` (Go-only) and cannot run standalone | `database/migrations/017_create_e2ee_prekey_claims.sql:9` |
| High | Android does not call `/api/v1/auth/session/rotate`; 30-day bearer tokens never proactively rotated | grep of `app/src` for `session/rotate` = 0 matches |
| High (conditional) | `handleRegister` skips proof-of-possession when `APP_ENV != production`, enabling account takeover with a victim's public key | `backend/internal/auth/handler.go` lines ~517–525 |
| Medium | `google_uid` is trusted when `google_auth_token` is absent; `is_reverify` uses it for account lookup | `backend/internal/auth/reverse_sms.go` lines 310–328, 745–746 |
| Medium | Hardcoded client ID in two places; live Render value unverified | `app/build.gradle.kts`, `app/src/main/res/values/strings.xml` |
| Medium | `/api/v1/auth/google/verify` is dead code from the client perspective | `AuthApiService.verifyGoogleIdToken` unused |
| Medium | `/health` is a startup snapshot, not a live dependency probe | `cmd/server/main.go` `/health` handler |
| Medium | `.env.example` omits `PHONE_HMAC_KEY`/`PHONE_ENC_KEY` and claims migrations 001–005 | `.env.example` sections 2 and 9 |
| Medium | SQL contract leaves telemetry and 017 tables without RLS; exposure if applied without Go migration 6 | `database/migrations/015`, `017` |
| Low | Google `email_verified` not enforced | `google_verifier.go` |
| Low | SQL `generate_random_nx_id()` uses non-crypto `random()` and is unused | `database/migrations/013_create_database_functions.sql` |
| Low | NX ID allocation has no retry after insert-time constraint violation | `PostgresRepository.AllocateNxID` |
| Low | FCM v1 OAuth2 token exchange not implemented (mock in practice) | `backend/internal/fcm/fcm_service.go` |
| Not verified | Supabase Dashboard state, applied migrations, `schema_migrations`, RLS, triggers | no credentials on host |
| Not verified | Render environment values (`APP_ENV`, `DATABASE_URL`, `REDIS_URL`, `GOOGLE_CLIENT_ID`, phone keys, pepper) | only `/health` + negative probes |
| Not verified | Google Cloud Console configuration (consent screen, clients, SHA-1) | external |
| Blocked | Live PostgreSQL/Redis integration tests (Docker not running locally) | `backend/internal/database/integration_test.go`, `redis_integration_test.go` |
| Blocked | Go race detector (`-race`) — no C compiler (cgo) on this host | toolchain limitation |

---

## 13. Recommended next steps (no implementation performed)

1. **P0 — Fix Supabase verifier:** make signature verification mandatory whenever the endpoint is enabled, or disable the endpoint if Supabase Auth is not used. Add support for asymmetric (JWKS) verification or document the HS256 limitation. Do not leave `SUPABASE_JWT_SECRET` unset in production.
2. **P0 — Verify production environment:** confirm `APP_ENV=production`, `PHONE_HMAC_KEY`, `PHONE_ENC_KEY`, `OTP_SERVER_PEPPER`, `DATABASE_URL` (pooler), `REDIS_URL`, and `GOOGLE_CLIENT_ID` on Render; update `.env.example` to document the phone keys.
3. **P1 — Schema reconciliation:** choose one lineage as authoritative, archive the other, and make `schema_contract_test.go` shrink its allowlists as divergence closes. Re-run the full migration on a clean database and verify `schema_migrations`.
4. **P1 — Session lifecycle:** implement client-side rotation scheduling and 401-triggered rotation using the existing `/api/v1/auth/session/rotate` endpoint.
5. **P1 — Google client ID hygiene:** single source of truth; wire `BuildConfig.GOOGLE_WEB_CLIENT_ID` or remove it.
6. **P2 — Remove dead path:** either wire `/api/v1/auth/google/verify` into the client or remove it.
7. **P2 — Health endpoint:** make `/health` probe PostgreSQL and Redis (with short timeouts) instead of reporting startup snapshots.
8. **P2 — Auth hardening:** require `google_auth_token` (or remove `google_uid` trust) in reverse-SMS initiation; require `signature_hex` in all environments if feasible.
9. **P3 — FCM:** implement Google OAuth2 service-account token exchange or document mock-only status.
10. **P3 — Cleanup:** remove or clearly mark the unused SQL `generate_random_nx_id()` and align documentation that claims zero PostgreSQL connectivity (superseded by the current pgx implementation).

---

## 14. Evidence and commands run

- `go build ./...` (in `backend/`): success (no output).
- `go test ./...` (in `backend/`): all packages `ok` or `[no test files]`; `database`, `auth`, `identity` packages green.
- `git status --porcelain`: working tree has many pre-existing uncommitted modifications; this audit added only the report file.
- Live probes (read-only): see §8.6.
- Files inspected (representative): `backend/internal/database/migrations.go`, `schema_contract_test.go`, `repository.go`, `db.go`, `phone_backfill.go`; `backend/internal/auth/handler.go`, `google_verifier.go`, `supabase_verifier.go`, `reverse_sms.go`; `backend/internal/identity/nxid.go`, `store.go`, `model.go`, `phonecrypto.go`; `backend/cmd/server/main.go`; `app/build.gradle.kts`, `app/src/main/java/com/nexa/app/core/network/ServerEndpointConfig.kt`, `app/src/main/java/com/nexa/app/presentation/welcome/WelcomeViewModel.kt`, `app/src/main/java/com/nexa/app/data/repository/AuthRepositoryImpl.kt`, `app/src/main/java/com/nexa/app/data/remote/api/AuthApiService.kt`; `database/migrations/001`–`017`; `.env.example`, `.env` (names/status only, no values printed); `docs/audit/*`, `docs/New folder/SUPABASE_LIVE_SCHEMA_VERIFICATION.md`.

---

## Appendix A — File map (audit-relevant)

| Concern | File |
|---|---|
| Go migrations (authoritative) | `backend/internal/database/migrations.go` |
| Schema contract test | `backend/internal/database/schema_contract_test.go` |
| PostgreSQL repository | `backend/internal/database/repository.go` |
| Pool / config | `backend/internal/database/db.go` |
| Phone backfill | `backend/internal/database/phone_backfill.go` |
| Auth routes / handlers | `backend/internal/auth/handler.go` |
| Google verifier | `backend/internal/auth/google_verifier.go` |
| Supabase verifier | `backend/internal/auth/supabase_verifier.go` |
| Reverse SMS / NX allocation paths | `backend/internal/auth/reverse_sms.go` |
| OTP service | `backend/internal/auth/otp_service.go` |
| NX ID generation | `backend/internal/identity/nxid.go` |
| Identity store (JSON + repo interface) | `backend/internal/identity/store.go`, `repository.go`, `model.go` |
| Phone crypto | `backend/internal/identity/phonecrypto.go` |
| Server bootstrap | `backend/cmd/server/main.go` |
| Android base URLs | `app/src/main/java/com/nexa/app/core/network/ServerEndpointConfig.kt` |
| Android Google sign-in | `app/src/main/java/com/nexa/app/presentation/welcome/WelcomeViewModel.kt` |
| Android auth repo | `app/src/main/java/com/nexa/app/data/repository/AuthRepositoryImpl.kt` |
| Android HTTP API | `app/src/main/java/com/nexa/app/data/remote/api/AuthApiService.kt` |
| Android client ID (Gradle) | `app/build.gradle.kts` |
| Android client ID (resources) | `app/src/main/res/values/strings.xml` |
| SQL contract | `database/migrations/001_create_profiles.sql` … `017_create_e2ee_prekey_claims.sql` |
| Env template | `.env.example` |
| Live schema verification doc | `docs/New folder/SUPABASE_LIVE_SCHEMA_VERIFICATION.md` |
| Supabase connectivity doc | `docs/audit/LOCAL_PRODUCTION_INFRASTRUCTURE_AUDIT/05_PRODUCTION_SUPABASE_CONNECTIVITY.md` |

---

## Appendix B — Glossary

- **NX ID:** public user identifier in `NX-XXXXXXXX` format, generated server-side.
- **accounts.id:** internal row key (`usr_<pubkey prefix>`), not user-facing.
- **Auth ticket:** short-lived (10 min) Redis-backed proof issued after Google/Supabase/OTP verification; consumed once by `/api/v1/auth/register`.
- **Session token:** `nexa_sess_<64 hex>` bearer token, 30-day expiry, stored hashed in `sessions`.
- **Lineage:** one of the two incompatible schema families (Go runtime vs SQL/Supabase).
- **enforceHMAC:** flag in `SupabaseVerifier` that is `false` when `SUPABASE_JWT_SECRET` is empty.

---

## 15. JWT verification security fix (P0) — implemented

### 15.1 Root cause

`SupabaseVerifier` (`backend/internal/auth/supabase_verifier.go`) treated signature
verification as optional: `enforceHMAC` was `true` only when `SUPABASE_JWT_SECRET`
was set, and `VerifyToken` skipped the HMAC check entirely when it was `false`.
With the secret unset in production, any JWT carrying a `sub` claim (plus
acceptable `exp`/`aud`/`iss`) passed verification and minted a 10-minute auth
ticket via `handleSupabaseVerify`.

### 15.2 Files and functions changed

- `backend/internal/auth/supabase_verifier.go` — `NewSupabaseVerifier`, `VerifyToken`; added `ErrSupabaseVerifierUnconfigured`, `supabaseSubjectRE`; added `NotBefore` claim and `nbf` validation.
- `backend/internal/auth/handler.go` — `handleSupabaseVerify`, `handleAccountLink` (SUPABASE branch): map `ErrSupabaseVerifierUnconfigured` to `503 AUTH_CONFIG_UNAVAILABLE`.
- `.env.example` — documented `PHONE_HMAC_KEY`/`PHONE_ENC_KEY`; corrected migration count.
- `backend/internal/auth/supabase_verifier_test.go` (new) — 18 tests.

### 15.3 Previous vulnerable behaviour

- `enforceHMAC == false` when `SUPABASE_JWT_SECRET` empty → no signature check.
- `sub` presence alone (plus claim shape) was sufficient to issue a ticket.
- `alg` was checked only inside the `enforceHMAC` block, so `alg: none` was not rejected when the secret was unset.
- Handlers returned `401`/`400` for a missing secret, indistinguishable from a bad token.

### 15.4 New fail-closed behaviour

- `VerifyToken` returns `ErrSupabaseVerifierUnconfigured` immediately when the
  secret is unset/empty/whitespace. Every token is rejected.
- HS256 signature verification is mandatory and unconditional; `alg` must be exactly
  `HS256` (rejects `none`, RS256, ES256, etc.).
- Validates `exp`, `iat` (not in future by >60s), `nbf`, audience (`""` or
  `authenticated`), issuer prefix (when `SUPABASE_URL` set), and `sub` (non-empty
  UUID).
- No decoding-only fallback; malformed/empty tokens are rejected.
- Handlers return `503 AUTH_CONFIG_UNAVAILABLE` for a missing secret, and `401`
  for invalid signatures or other verification failures. No secret or token is
  logged or returned.
- Google ID-token verification (`google_verifier.go`) is untouched.

### 15.5 Configuration required for legitimate verification

Set `SUPABASE_JWT_SECRET` to the project's legacy HS256 JWT secret and
`SUPABASE_URL` to the project URL. Note: newer Supabase projects sign JWTs with
asymmetric keys; this verifier supports HS256 only. If Supabase Auth is not used,
leave the secret unset — the endpoint then fails closed (503) for every request.

### 15.6 Tests added and results

`backend/internal/auth/supabase_verifier_test.go`: 18 tests covering valid signed
token, forged modified `sub`, invalid signature, missing config, regression that an
unset secret can never authenticate, expired, not-yet-valid, incorrect issuer,
incorrect audience, missing `sub`, bad `sub` format, unsupported algorithm,
`alg: none`, malformed JWT, empty token, and two handler-level cases (503 on
missing config, 401 on bad signature).

Result: `go test ./internal/auth/ -run Supabase` — 18/18 PASS.
Full suite: `go test ./...` — all packages ok.

### 15.7 Remaining production configuration actions

- Set `SUPABASE_JWT_SECRET` + `SUPABASE_URL` on Render if Supabase Auth is used,
  otherwise leave unset (endpoint fails closed).
- Confirm `APP_ENV=production`.
- If Supabase Auth is not used at all, consider removing the route in a future
  change (not done here).

### 15.8 Is the endpoint still required?

No. The Android client never calls `/api/v1/auth/supabase/verify` (no `supabase`
references in `app/src`). Google ID tokens are the primary login path. The endpoint
is retained but securely unavailable until explicitly configured.

---

*End of audit. No schema or Google-auth changes were made. All changes remain uncommitted.*
