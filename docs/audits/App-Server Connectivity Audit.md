Error: The stream was interrupted. Please continue the task you were working on. (Error ID: 12192b63-fe17-4d12-9694-e1b7a69be6cb-329)

Ran list database directory contents
Ran find all SQL files in repo
Ran list backend directory
Ran list backend cmd and internal
Ran list internal packages
Ran list files in database, auth, and identity
Analyzed migrations.go:L1-100
Analyzed migrations.go:L100-250
Analyzed migrations.go:L251-400
Analyzed migrations.go:L401-536
Analyzed 001_create_profiles.sql:L1-27
Analyzed 002_create_devices.sql:L1-24
Analyzed 003_create_nx_identity.sql:L1-18
Ran extract SQL statements from database/migrations
Ran find DB and Supabase audit docs
Analyzed SUPABASE_LIVE_SCHEMA_VERIFICATION.md:L1-38
Analyzed POSTGRESQL_SCHEMA_RECONCILIATION_PLAN.md:L1-100
Ran list env files in root
Ran list variable keys in .env.example
Ran check configured keys in .env · Exited with code 1
Ran check configured status in .env
Ran scan all keys in .env
Ran check env files in backend directory
Analyzed main.go:L1-100
Analyzed main.go:L101-200
Analyzed main.go:L201-350
Analyzed main.go:L351-450
Ran find RegisterRoutes in auth handler
Analyzed google_verifier.go:L1-100
Analyzed supabase_verifier.go:L1-100
Analyzed nxid.go:L1-80
Ran find calls to GenerateRandomNxID
Analyzed repository.go:L30-70
Ran search for NX ID generation in Android
Ran search supabase in Android source
Ran check supabase in build.gradle.kts
Ran find ServerEndpointConfig.kt
Analyzed ServerEndpointConfig.kt:L1-46
Ran search Google client ID in Android
Analyzed AuthNavGraph.kt:L270-305
Ran find webClientId in WelcomeScreen.kt
Ran find google_web_client_id in strings.xml
Ran read google-services.json
Analyzed GOOGLE_AUTHENTICATION_AUDIT.md:L1-70
Ran build Go server
Ran run core backend unit tests
Ran find keystores
Ran find SHA fingerprints in docs
Checked task Find SHA fingerprints in docs
Ran check audit report existence
Analyzed supabase-google-nxid-connectivity-audit.md:L1-50
Analyzed supabase-google-nxid-connectivity-audit.md:L51-150
Analyzed supabase-google-nxid-connectivity-audit.md:L151-250
Analyzed supabase-google-nxid-connectivity-audit.md:L251-350
Analyzed supabase-google-nxid-connectivity-audit.md:L351-480
Analyzed supabase-google-nxid-connectivity-audit.md:L481-681

# NEXA — Supabase Database, Google OAuth, NX ID & App-Server Connectivity Audit

**Audit Date:** 2026-11-14  
**Audit Document:** [`supabase-google-nxid-connectivity-audit.md`](file:///H:/chat_app/docs/audits/supabase-google-nxid-connectivity-audit.md)  
**Execution Mode:** Read-only audit. No production configurations, active secrets, or database schemas were altered.

---

## Executive Summary & Core Verdict

1. **Android Client Architecture:** The Android application has **zero direct connectivity to Supabase**. There is no Supabase SDK (`gotrue-kt`, `postgrest-kt`) anywhere in `app/build.gradle.kts`. Android talks exclusively to the Go backend via HTTP/REST and WebSocket (`https://nexa-relay.onrender.com` / `wss://nexa-relay.onrender.com` configured in [`ServerEndpointConfig.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/network/ServerEndpointConfig.kt)).
2. **Google OAuth Flow:** Google Sign-In is executed on Android via Android Credential Manager (`GetGoogleIdOption`), producing a signed Google ID Token. The token is sent to the Go backend (`POST /api/v1/auth/google`), which verifies the RS256 signature against Google's public JWKS. Supabase Auth is **not** involved in the Google Sign-In flow.
3. **NX ID Authority:** NX ID is **authoritatively generated and enforced by the Go backend**. Android never generates an NX ID. The Go backend generates cryptographically secure 11-character identifiers (`^NX-[A-Z0-9]{8}$`) via `crypto/rand` across a 32-character non-ambiguous alphabet and enforces format and uniqueness constraints in PostgreSQL (`accounts.nx_id`).
4. **Dual Incompatible SQL Schemas:** Two distinct database lineages exist:
   - **Go Runtime Migrations (Authoritative):** Defined in [`backend/internal/database/migrations.go`](file:///H:/chat_app/backend/internal/database/migrations.go) (Versions 1–7), operating on `accounts`, `devices`, `sessions`, `phone_verifications`, `prekeys`, etc.
   - **Legacy Supabase SQL Contract:** Located in [`database/migrations/`](file:///H:/chat_app/database/migrations) (001–017), designed around Supabase PostgREST and `public.profiles`. These files **cannot be run alongside Go migrations without column collision**.
5. **Backend Persistence Engine:** The Go backend connects directly to PostgreSQL using `pgxpool` via `DATABASE_URL` and to Redis via `REDIS_URL`. Supabase acts purely as a hosted PostgreSQL database provider.

---

## 1. Supabase Database — SQL Files & Table Inventory

### 1.1 SQL Migration Definitions
NEXA defines database tables across two locations:
- **Authoritative Engine:** Embedded Go migrations in [`backend/internal/database/migrations.go`](file:///H:/chat_app/backend/internal/database/migrations.go) (7 migration versions).
- **Legacy Specification:** Standalone SQL files in [`database/migrations/`](file:///H:/chat_app/database/migrations) (`001_create_profiles.sql` through `017_create_e2ee_prekey_claims.sql`).

### 1.2 Table Inventory

| Table | SQL File / Definition | Purpose | Local Migration | Exists in Live Supabase | RLS Status |
|---|---|---|---|---|---|
| `accounts` | `migrations.go` (v1, v7) | Authoritative user account, NX ID, phone hash, public key | Yes (Go v1) | **Not verified** | Enabled (Go v6) |
| `devices` | `migrations.go` (v1, v7) | Registered device keys, platform, push tokens, revocations | Yes (Go v1) | **Not verified** | Enabled (Go v6) |
| `sessions` | `migrations.go` (v1) | 30-day session hashes, expiry, token rotation | Yes (Go v1) | **Not verified** | Enabled (Go v6) |
| `phone_verifications` | `migrations.go` (v1) | Reverse-SMS nonce and inbound carrier verification | Yes (Go v1) | **Not verified** | Enabled (Go v6) |
| `connection_requests` | `migrations.go` (v2) | Peer-to-peer contact exchange requests | Yes (Go v2) | **Not verified** | Enabled (Go v6) |
| `connections` | `migrations.go` (v2) | Established peer contacts between NX IDs | Yes (Go v2) | **Not verified** | Enabled (Go v6) |
| `user_blocks` | `migrations.go` (v2) | User blocklists between NX IDs | Yes (Go v2) | **Not verified** | Enabled (Go v6) |
| `identity_key_bindings` | `migrations.go` (v3), `017_*.sql` | Signal identity keys bound to device IDs | Yes (Go v3 / SQL 017) | **Not verified** | Enabled (Go v6) |
| `signed_prekeys` | `migrations.go` (v3), `017_*.sql` | Cryptographic signed prekeys for E2EE | Yes (Go v3 / SQL 017) | **Not verified** | Enabled (Go v6) |
| `one_time_prekeys` | `migrations.go` (v3), `017_*.sql` | One-time prekey pool for Signal protocol | Yes (Go v3 / SQL 017) | **Not verified** | Enabled (Go v6) |
| `prekey_claims` | `migrations.go` (v3), `017_*.sql` | Idempotency log of claimed prekeys | Yes (Go v3 / SQL 017) | **Not verified** | Enabled (Go v6) |
| `phone_visibility_grants` | `migrations.go` (v4), `016_*.sql` | Direct permissions to view phone numbers | Yes (Go v4 / SQL 016) | **Not verified** | Enabled (Go v6) |
| `message_idempotency` | `migrations.go` (v5) | Prevents replay of messaging envelopes | Yes (Go v5) | **Not verified** | Enabled (Go v6) |
| `device_events` | `migrations.go` (v7) | Audit log for device registrations, revocations | Yes (Go v7) | **Not verified** | Enabled (Go v7) |
| `schema_migrations` | `migrations.go` (Bootstrap) | Tracks applied Go migration versions | Yes (Internal) | **Not verified** | No (Internal table) |
| `profiles` *(Legacy)* | `001_create_profiles.sql` | Supabase auth.users-bound profiles | Yes (SQL 001) | **Not verified** | Enabled (SQL 012) |
| `nx_identities` *(Legacy)* | `003_create_nx_identities.sql`| Old NX ID registry | Yes (SQL 003) | **Not verified** | Enabled (SQL 012) |
| `contacts` *(Legacy)* | `005_create_contacts.sql` | Client contact book sync | Yes (SQL 005) | **Not verified** | Enabled (SQL 012) |
| `identity_keys` *(Legacy)* | `006_create_identity_keys.sql`| Old identity keys | Yes (SQL 006) | **Not verified** | Enabled (SQL 012) |
| `prekeys` *(Legacy)* | `007_create_prekeys.sql` | Old prekey store | Yes (SQL 007) | **Not verified** | Enabled (SQL 012) |
| `message_metadata` *(Legacy)*| `008_create_message_metadata.sql`| Unencrypted message routing metadata | Yes (SQL 008) | **Not verified** | Enabled (SQL 012) |
| `device_sessions` *(Legacy)* | `009_create_device_sessions.sql`| Old device session store | Yes (SQL 009) | **Not verified** | Enabled (SQL 012) |
| `user_telemetry_events` *(Legacy)*| `015_create_user_telemetry_events.sql` | 12 monthly partitioned analytics tables | Yes (SQL 015) | **Not verified** | **Disabled** (Omitted) |

> **Crucial Finding on Lineages:**  
> The Go runtime tables (`accounts`, `devices`, `sessions`) and the standalone SQL contract files (`profiles`, legacy `devices`, `nx_identities`) are structurally incompatible. For example, `database/migrations/002_create_devices.sql` defines `user_id UUID REFERENCES profiles(id)`, whereas the active Go backend requires `devices` with `account_id VARCHAR(64)` and `nx_id VARCHAR(32)`. If both are run without renaming, migration fails with `SQLSTATE 42703 (undefined column)`.

---

## 2. Supabase Connection — Complete Setup Process

```
┌─────────────────────────────────┐
│        Android Application      │
│  (No Supabase SDK / No secrets) │
└────────────────┬────────────────┘
                 │ HTTPS (TLS 1.3) / WSS
                 ▼
┌─────────────────────────────────┐
│        NEXA Go Backend          │
│    (nexa-relay.onrender.com)    │
└───────┬─────────────────┬───────┘
        │                 │
        │ pgxpool         │ go-redis
        │ (TCP:5432)      │ (TCP:6379)
        ▼                 ▼
┌──────────────────┐    ┌──────────────────┐
│  Supabase Cloud  │    │  Upstash Redis   │
│ (PostgreSQL DB)  │    │ (Ephemeral Cache)│
└──────────────────┘    └──────────────────┘
```

1. **Direct vs Backend-Routed:** The Android app **never connects directly** to Supabase. All database access is mediated by the Go backend.
2. **Credential Placement:**
   - **Android Client:** Zero Supabase credentials belong in Android (`SUPABASE_URL` and `SUPABASE_ANON_KEY` are not packaged).
   - **Go Backend Server:** `DATABASE_URL` (PostgreSQL connection pooler string) is required on the server.
3. **Connection Method:** The backend uses `github.com/jackc/pgx/v5/pgxpool` with a connection pool (Min: 5, Max: 25, 5s connect timeout).
4. **Supabase IPv6 vs IPv4 Pooler:** Direct Supabase database hostnames resolve only to IPv6 on standard tiers, which fails on Render containers. The backend must connect through Supabase's **Session Pooler** (`aws-0-<region>.pooler.supabase.com:5432`) with `postgres.<project_ref>` as the username.
5. **Database Migration Execution:** The Go backend executes migrations automatically upon server boot inside [`backend/cmd/server/main.go`](file:///H:/chat_app/backend/cmd/server/main.go) via `database.RunMigrations()`. It acquires a PostgreSQL advisory lock (`8472910384729102`), checks the `schema_migrations` table, and applies unapplied migrations in a transaction.

---

## 3. Google OAuth — Complete Configuration Audit

### 3.1 Trace of Actual Authentication Flow

```
1. Android User triggers "Continue with Google"
        ↓
2. Android CredentialManager prompts user via GetGoogleIdOption
   (Server Client ID: 356056510017-grmokt7u1sch6hdk9dbah85k2jqe32v2.apps.googleusercontent.com)
        ↓
3. Google Play Services returns signed Google ID Token (RS256 JWT)
        ↓
4. Android posts to Go Backend: POST /api/v1/auth/google
   Body: { id_token, device_id, public_key_hex }
        ↓
5. Go Backend (google_verifier.go) verifies:
   - Header alg == RS256
   - aud == GOOGLE_CLIENT_ID
   - iss in {"accounts.google.com", "https://accounts.google.com"}
   - exp > now()
   - RSA PKCS1v15 signature against Google JWKS (https://www.googleapis.com/oauth2/v3/certs)
        ↓
6. Go Backend checks Account Identity:
   ├─► Unknown Google Subject: Returns NEW_USER { google_uid, email, prefill_name }
   │   (User proceeds to Reverse-SMS phone binding)
   ├─► Known User, Same Device: Returns REQUIRE_DEVICE_CHALLENGE (60s nonce)
   │   (Client signs with Ed25519 private key → POST /login-verify → 30-day session)
   └─► Known User, New Device: Returns REQUIRE_SIM_VERIFICATION
       (User must complete carrier reverse-SMS proof)
```

### 3.2 Key Configuration Discrepancies & Findings
1. **Google Client ID Hardcoding:** The Web Client ID `356056510017-grmokt7u1sch6hdk9dbah85k2jqe32v2.apps.googleusercontent.com` is duplicated across [`app/src/main/res/values/strings.xml`](file:///H:/chat_app/app/src/main/res/values/strings.xml) (`google_web_client_id`) and `app/build.gradle.kts` (`GOOGLE_WEB_CLIENT_ID`).
2. **`google-services.json` Mismatch:** In [`app/google-services.json`](file:///H:/chat_app/app/google-services.json), `project_number` is `53682598396` (`nexa-665c5`), but `oauth_client` is an **empty array `[]`**. The web client ID in `strings.xml` belongs to project number prefix `356056510017`.
3. **Server-Side Enforcement:** [`backend/internal/auth/google_verifier.go`](file:///H:/chat_app/backend/internal/auth/google_verifier.go) reads `GOOGLE_CLIENT_ID` from the environment. Live probing of `POST /api/v1/auth/google` on `nexa-relay.onrender.com` returns `401 Google token verification failed: malformed JWT`, confirming `GOOGLE_CLIENT_ID` is configured in production.

---

## 4. NX ID — Account Identity and Generation

### 4.1 Specification and Generation Rules
1. **Generation Component:** Authoritative generation occurs in Go backend: [`backend/internal/identity/nxid.go`](file:///H:/chat_app/backend/internal/identity/nxid.go) (`GenerateRandomNxID()`). Android does not generate NX IDs.
2. **Format & Regex:** Exactly 11 characters starting with `NX-` followed by 8 characters:  
   `^NX-[A-Z0-9]{8}$` (e.g., `NX-4K9W72A8`).
3. **Charset:** Base32 non-ambiguous: `23456789ABCDEFGHJKLMNPQRSTUVWXYZ` (excludes `0`, `O`, `1`, `I` to avoid human transcription errors).
4. **Authoritative Uniqueness:**
   - In [`backend/internal/database/repository.go`](file:///H:/chat_app/backend/internal/database/repository.go) (`AllocateNxID()`): Checks if `public_key_hex` already has an NX ID. If not, it loops up to 10 attempts checking `SELECT EXISTS(SELECT 1 FROM accounts WHERE nx_id = $1)`.
   - In PostgreSQL: Guaranteed by `UNIQUE(nx_id)` and `CONSTRAINT chk_nx_id_format CHECK (nx_id ~ '^NX-[A-Z0-9]{8}$')`.
5. **Stability & Immutability:**
   - Once assigned, NX ID is permanently tied to the account's Ed25519 public key.
   - NX ID **never changes** upon login, logout, session rotation, app uninstallation, or device addition.
   - Devices do not get their own NX ID; each registered device has its own `device_id` referencing `accounts(nx_id)`.

---

## 5. End-to-End Request Lifecycle Trace

```
[Android App]
      │
      │ 1. Request Reverse-SMS Proof
      │    POST /api/v1/auth/initiate-reverse-verify
      ▼
[NEXA Go Server]
      │
      │ 2. Allocate Unique NX ID (Crypto/Rand + DB Existence check)
      │ 3. Store Verification Nonce in PostgreSQL (phone_verifications)
      ▼
[Inbound Carrier Webhook / Trusted Verification]
      │
      │ 4. Verifies sender phone matches Carrier SMS destination
      ▼
[Android App]
      │
      │ 5. Complete Account Registration
      │    POST /api/v1/auth/register (ticket, public_key_hex, device_id)
      ▼
[NEXA Go Server]
      │
      │ 6. Begin Postgres Transaction:
      │    - INSERT INTO accounts (nx_id, public_key_hex, phone_hmac)
      │    - INSERT INTO devices (device_id, nx_id, public_key_hex)
      │    - INSERT INTO sessions (session_token_hash, nx_id, device_id, expires_at)
      │ 7. Cache session token in Redis (30-day TTL)
      ▼
[Android App]
      │
      │ 8. SecureStorage.saveAuthToken(token) & saveActiveNxId(nx_id)
      │ 9. Opens Chat WebSocket: /ws?nx_id=...&device_id=...
      │ 10. Opens Signaling WebSocket: /ws/signaling?token=...
```

---

## 6. Backend Server Status & Live Verification

- **Code Compilation:** `go build ./...` across all packages in `backend/` passes with **0 errors**.
- **Unit Test Suite:**
  - `backend/internal/auth/...` $\rightarrow$ **PASS**
  - `backend/internal/identity/...` $\rightarrow$ **PASS**
  - `backend/internal/database/...` $\rightarrow$ **PASS**
  - Trusted Phone Test Suite (21 test scenarios) $\rightarrow$ **PASS (21/21)**
- **Live Render Status (Read-Only Probes):**
  - `GET https://nexa-relay.onrender.com/health` $\rightarrow$ `HTTP 200`  
    `{"status":"UP","database":"CONNECTED","redis":"CONNECTED"}`
  - `POST /api/v1/auth/google` $\rightarrow$ `HTTP 401` (`malformed JWT: expected 3 parts, got 1`), verifying the route is active and `GOOGLE_CLIENT_ID` is present.

---

## 7. Data Ownership Map

| Data Category | Primary Owner | Storage Location | Sensitivity | Sync Method |
|---|---|---|---|---|
| **User Identity (NX ID)** | Go Backend | PostgreSQL `accounts.nx_id` | Public | Resolved via `/api/v1/users/resolve` |
| **Phone Number (Plain)** | User Device | Not stored in DB (Zero-Knowledge) | Critical | Ephemeral verification only |
| **Phone Hash (Discovery)** | Go Backend | PostgreSQL `accounts.phone_hmac` | Confidential | Salted HMAC-SHA256 (`PHONE_HMAC_KEY`) |
| **Phone Encrypted (Recovery)**| Go Backend | PostgreSQL `accounts.phone_encrypted`| Restricted | AES-256-GCM (`PHONE_ENC_KEY`) |
| **Identity Public Key** | User Device | PostgreSQL `accounts.public_key_hex` | Public | Uploaded during registration |
| **Identity Private Key** | User Device | Android Keystore / SQLCipher | Top Secret | Never leaves client hardware |
| **Device Records** | Go Backend | PostgreSQL `devices` | Internal | Managed via `/api/v1/devices` |
| **Session Tokens** | Go Backend | Redis Cache + PostgreSQL `sessions` | Restricted | Stored as SHA-256 hash |
| **E2EE Prekeys (Signed/One-Time)**| Go Backend | PostgreSQL `signed_prekeys`, `one_time_prekeys` | Public | Uploaded by client, claimed by peers |
| **Message Payloads** | Sender/Recipient | In-transit Redis (24h TTL) | Confidential | End-to-End Encrypted (Signal Protocol) |
| **Connection Graph** | Go Backend | PostgreSQL `connections`, `connection_requests` | Internal | Mutual request/accept protocol |
| **User Blocklist** | Go Backend | PostgreSQL `user_blocks` | Internal | Filtered server-side before routing |
| **Device Audit Logs** | Go Backend | PostgreSQL `device_events` | Internal | Append-only event log |

---

## 8. Configuration Checklist & Environment State

| Configuration Item | Expected Location | Local Status | Production Status | Secret Exposure Guard |
|---|---|---|---|---|
| `DATABASE_URL` | Go Backend Env | Commented in `.env` | Active on Render | Masked (`postgresql://...`) |
| `REDIS_URL` | Go Backend Env | Commented in `.env` | Active on Render | Masked (`rediss://...`) |
| `GOOGLE_CLIENT_ID` | Go Backend Env | Commented in `.env` | Active on Render | Public Client Identifier |
| `google_web_client_id` | Android `strings.xml` | Configured | Configured | Public Client Identifier |
| `PHONE_HMAC_KEY` | Go Backend Env | Configured in `.env` | Verified Required | Value Masked |
| `PHONE_ENC_KEY` | Go Backend Env | Configured in `.env` | Verified Required | Value Masked |
| `OTP_SERVER_PEPPER` | Go Backend Env | Unset locally | Active on Render | Value Masked |
| `SUPABASE_JWT_SECRET` | Go Backend Env | Unset locally | Unset on Render | Value Masked |
| `RELEASE_KEY_ALIAS` | Android Keystore | Configured | Configured | Key name only |

---

## 9. Step-by-Step Manual Setup Guide

### Stage 1: Supabase Dashboard Setup
1. Log in to [Supabase Dashboard](https://supabase.com/dashboard) and navigate to your project.
2. Go to **Project Settings** $\rightarrow$ **Database** $\rightarrow$ **Connection parameters**.
3. Under **Connection string**, select **Session Pooler** (Port 5432).  
   *Format:* `postgresql://postgres.[YOUR-PROJECT-REF]:[YOUR-PASSWORD]@aws-0-[REGION].pooler.supabase.com:5432/postgres?sslmode=require`
4. In the **SQL Editor**, verify whether legacy tables exist:
   ```sql
   SELECT table_name FROM information_schema.tables 
   WHERE table_schema = 'public' 
     AND table_name IN ('profiles', 'devices', 'phone_verifications', 'phone_visibility_grants');
   ```
   *If legacy `profiles` or `devices` exist from previous migrations, rename them before boot:*
   ```sql
   ALTER TABLE IF EXISTS devices RENAME TO legacy_supabase_devices;
   ALTER TABLE IF EXISTS phone_verifications RENAME TO legacy_supabase_phone_verifications;
   ALTER TABLE IF EXISTS phone_visibility_grants RENAME TO legacy_supabase_phone_visibility_grants;
   ```

### Stage 2: Google Cloud Console Setup
1. Open [Google Cloud Console](https://console.cloud.google.com/) for your project.
2. Navigate to **APIs & Services** $\rightarrow$ **Credentials**.
3. Create **OAuth Client ID** $\rightarrow$ **Web application**:
   - Save the Web Client ID (matches `google_web_client_id` in `strings.xml`).
4. Create **OAuth Client ID** $\rightarrow$ **Android**:
   - Package name: `com.nexa.app`
   - Retrieve Debug SHA-1 fingerprint:
     ```powershell
     keytool -list -v -keystore $env:USERPROFILE\.android\debug.keystore -alias androiddebugkey -storepass android
     ```
   - Retrieve Release Keystore SHA-1 fingerprint:
     ```powershell
     keytool -list -v -keystore app/release.keystore -alias nexa-key
     ```
   - Register both SHA-1 fingerprints in Google Cloud Console.

### Stage 3: Render Deployment Configuration
In Render Dashboard $\rightarrow$ Web Service (`nexa-relay`) $\rightarrow$ **Environment**:
1. `APP_ENV` = `production`
2. `DATABASE_URL` = *(Your Supabase Session Pooler URL)*
3. `REDIS_URL` = *(Your Upstash / Redis TLS URL)*
4. `GOOGLE_CLIENT_ID` = `356056510017-grmokt7u1sch6hdk9dbah85k2jqe32v2.apps.googleusercontent.com`
5. `PHONE_HMAC_KEY` = *(32-byte cryptographically secure hex string)*
6. `PHONE_ENC_KEY` = *(32-byte cryptographically secure hex string)*
7. `OTP_SERVER_PEPPER` = *(Cryptographically secure random string)*

### Stage 4: Android App Verification
1. Open the project in Android Studio.
2. Confirm `app/src/main/res/values/strings.xml` contains the valid `google_web_client_id`.
3. Assemble the debug build:
   ```powershell
   .\gradlew.bat assembleDebug
   ```
4. Run the app on an emulator/device:
   - On fresh install, confirm the Splash Screen routes to Welcome/Auth.
   - Tap **Continue with Google** $\rightarrow$ Select Account $\rightarrow$ Verify backend receives token and returns `NEW_USER` or challenge.

---

### Audit Artifact Link
The complete audit document is saved at:  
[**`docs/audits/supabase-google-nxid-connectivity-audit.md`**](file:///H:/chat_app/docs/audits/supabase-google-nxid-connectivity-audit.md)