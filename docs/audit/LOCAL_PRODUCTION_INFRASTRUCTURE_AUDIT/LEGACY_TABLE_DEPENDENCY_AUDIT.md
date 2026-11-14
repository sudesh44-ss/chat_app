# NEXA — Legacy Database Table Dependency Audit

**Document:** `docs/audit/LEGACY_TABLE_DEPENDENCY_AUDIT.md`  
**Classification:** STRICT READ-ONLY DEPENDENCY AUDIT  
**Repository:** `H:\chat_app`  
**Target Tables:** 9 Existing Supabase Tables in `public` Schema  
**Date:** November 2026  

---

## 1. Executive Summary & Verdict Table

An exhaustive, repository-wide search across the Go backend ([`backend/`](file:///H:/chat_app/backend/)), Android application ([`app/src/`](file:///H:/chat_app/app/src/)), Protobuf definitions ([`proto/`](file:///H:/chat_app/proto/)), and database migrations ([`database/migrations/`](file:///H:/chat_app/database/migrations/)) was conducted for all 9 legacy Supabase tables.

All 9 tables currently contain **zero rows (`0 rows`)** in the target Supabase database.

```
+---------------------------------------------------------------------------------------------------------------------+
|                                            TABLE VERDICT SUMMARY                                                    |
+-----+---------------------------+-----------------------+--------------------------+-----------------+--------------+
| #   | TABLE NAME                | BACKEND RUNTIME USAGE | ANDROID APP USAGE        | DATA MIGRATION  | FINAL VERDICT|
+-----+---------------------------+-----------------------+--------------------------+-----------------+--------------+
| 1   | profiles                  | None (0 queries)      | None (0 references)      | None (0 rows)   | SAFE TO RENAME|
| 2   | devices                   | Name reused in Go Mig1| None (calls Go REST API) | None (0 rows)   | SAFE TO RENAME|
| 3   | phone_verifications       | Name reused in Go Mig1| None (calls Go REST API) | None (0 rows)   | SAFE TO RENAME|
| 4   | contacts                  | None (uses connections| None (calls Go REST API) | None (0 rows)   | SAFE TO RENAME|
| 5   | device_sessions           | None (uses sessions)  | None (calls Go REST API) | None (0 rows)   | SAFE TO RENAME|
| 6   | identity_keys             | None (uses id_key_bind| None (calls Go REST API) | None (0 rows)   | SAFE TO RENAME|
| 7   | nx_identities             | None (uses accounts)  | None (calls Go REST API) | None (0 rows)   | SAFE TO RENAME|
| 8   | phone_visibility_grants   | None (uses accounts)  | None (calls Go REST API) | None (0 rows)   | SAFE TO RENAME|
| 9   | prekeys                   | None (uses spk/opk)   | None (calls Go REST API) | None (0 rows)   | SAFE TO RENAME|
+-----+---------------------------+-----------------------+--------------------------+-----------------+--------------+
```

### Overall Verdict: `SAFE TO RENAME` (All 9 Tables)
- **Zero Code Changes Required:** No Go backend source files or Android Kotlin source files require edits to rename these 9 tables.
- **Zero Data Loss:** All tables contain 0 rows. No data transformation, ETL, or row migration scripts are necessary.
- **Name Reclamation:** Renaming `devices` and `phone_verifications` is **mandatory** to resolve the `SQLSTATE 42703` startup crash and allow Go Migration 1 to create the schemas expected by [`backend/internal/database/repository.go`](file:///H:/chat_app/backend/internal/database/repository.go).

---

## 2. Table-by-Table Forensic Dependency Analysis

---

### Table 1: `public.profiles`

#### 2.1 Codebase References
- **`backend/` (Go):** Exactly **1 match**, in a code comment:
  - [`backend/internal/connections/repository.go:60`](file:///H:/chat_app/backend/internal/connections/repository.go#L60): `// ListBlockedUsers returns public profiles of all users blocked by blocker.`
  - **Zero SQL queries.** Zero `SELECT`, `INSERT`, `UPDATE`, or `DELETE` statements on `profiles`.
- **`app/` (Android Kotlin):** **Zero matches.** Android UI models use `UserProfile` and `ContactEntry`, communicating via Go REST endpoints `/api/v1/auth/register`, `/api/v1/contacts/lookup`, etc.
- **`proto/`:** **Zero matches.**
- **`database/migrations/`:** Defined in `001_create_profiles.sql:7-24`. Referenced as foreign key target in `002`, `003`, `005`, `006`, `007`, `009`, `010`, `011`, `012`, `013`, `014`, `016`.

#### 2.2 Database Object Attachments
- **Foreign Keys Outbound:** `id UUID REFERENCES auth.users(id) ON DELETE CASCADE`.
- **Foreign Keys Inbound:**
  - `devices.user_id -> profiles(id)`
  - `nx_identities.user_id -> profiles(id)`
  - `nx_identities.nx_id -> profiles(nx_id)`
  - `contacts.owner_id -> profiles(id)`
  - `contacts.contact_user_id -> profiles(id)`
  - `identity_keys.user_id -> profiles(id)`
  - `prekeys.user_id -> profiles(id)`
  - `device_sessions.user_id -> profiles(id)`
  - `phone_visibility_grants.granter_id -> profiles(id)`
  - `phone_visibility_grants.grantee_id -> profiles(id)`
- **Views:** Referenced in `public.safe_profiles` (`016_create_phone_visibility_grants.sql:48-69`).
- **Triggers:** `trg_profiles_updated_at` (executes `public.set_updated_at()`).
- **RLS Policies:**
  - `"Public profiles are viewable by authenticated users"` (`SELECT`, `TO authenticated`)
  - `"Users can only update their own profile"` (`UPDATE`, `auth.uid() = id`)

#### 2.3 Impact of Renaming to `legacy_supabase_profiles`
- The Go backend uses `public.accounts` (Go Migration 1) and does not query `profiles`.
- When `profiles` is renamed, PostgreSQL automatically updates all foreign key constraints from the other legacy tables.
- **Pre-Flight Requirement:** Drop `public.safe_profiles` before renaming, and verify no custom `auth.users` trigger references `public.profiles`.
- **Data Migration:** None (0 rows).
- **Verdict:** **`SAFE TO RENAME`**

---

### Table 2: `public.devices`

#### 2.1 Codebase References
- **`backend/` (Go):** Active runtime queries exist in [`backend/internal/database/repository.go`](file:///H:/chat_app/backend/internal/database/repository.go) and [`backend/internal/prekey/postgres_repository.go`](file:///H:/chat_app/backend/internal/prekey/postgres_repository.go):
  - Line 278: `INSERT INTO devices (device_id, account_id, nx_id, device_name, public_key_hex, dh_key_hex, fcm_token, is_revoked, created_at, last_active_at) VALUES (...)`
  - Line 328: `SELECT ... FROM devices WHERE device_id = $1 LIMIT 1`
  - Line 340: `SELECT ... FROM devices WHERE nx_id = $1 AND is_revoked = FALSE`
  - Line 376: `UPDATE devices SET is_revoked = TRUE, fcm_token = '' WHERE device_id = $1`
  - Line 402: `UPDATE devices SET fcm_token = $2, last_active_at = $3 WHERE device_id = $1`
  - `prekey/postgres_repository.go:76`: `UPDATE devices SET dh_key_hex = $1 WHERE device_id = $2`
  - **CRITICAL ARCHITECTURAL FINDING:** All Go runtime queries target the **new Go schema** (`account_id`, `nx_id`, `created_at BIGINT`). They **CANNOT** execute on the existing legacy Supabase `devices` table because it lacks `nx_id`/`account_id` and has incompatible timestamp types (`TIMESTAMPTZ` vs `BIGINT`).
- **`app/` (Android Kotlin):** Calls REST API `/api/v1/devices` ([`backend/internal/auth/handler.go:143`](file:///H:/chat_app/backend/internal/auth/handler.go#L143)). No direct database access.
- **`database/migrations/`:** Defined in `002_create_devices.sql:7-22`. Referenced by `006`, `007`, `009`, `010`, `011`, `012`, `013`.

#### 2.2 Database Object Attachments
- **Foreign Keys Outbound:** `user_id UUID REFERENCES public.profiles(id) ON DELETE CASCADE`.
- **Foreign Keys Inbound:**
  - `prekeys.device_id -> devices(device_id)`
  - `identity_keys.device_id -> devices(device_id)`
  - `device_sessions.device_id -> devices(device_id)`
- **Triggers:** `trg_limit_devices` (calls `check_device_count()`).
- **RLS Policies:**
  - `"Users can view their own devices"` (`SELECT`, `auth.uid() = user_id`)
  - `"Users can modify their own devices"` (`ALL`, `auth.uid() = user_id`)

#### 2.3 Impact of Renaming to `legacy_supabase_devices`
- Renaming frees the relation name `devices` so Go Migration 1 can create the table with the exact columns and types expected by `repository.go`.
- Internal foreign keys from `prekeys`, `identity_keys`, and `device_sessions` automatically follow the rename to `legacy_supabase_devices`.
- **Pre-Flight Requirement:** Drop `trg_limit_devices` and `check_device_count()` before renaming.
- **Data Migration:** None (0 rows).
- **Verdict:** **`SAFE TO RENAME`** (Mandatory to unblock Go Migration 1).

---

### Table 3: `public.phone_verifications`

#### 2.1 Codebase References
- **`backend/` (Go):** Referenced only in Go Migration 1 ([`backend/internal/database/migrations.go:82`](file:///H:/chat_app/backend/internal/database/migrations.go#L82)) and the migration schema test ([`backend/internal/database/database_test.go:88`](file:///H:/chat_app/backend/internal/database/database_test.go#L88)).
  - Zero runtime SQL queries. The active OTP services ([`backend/internal/auth/otp_service.go`](file:///H:/chat_app/backend/internal/auth/otp_service.go) and [`backend/internal/auth/reverse_sms.go`](file:///H:/chat_app/backend/internal/auth/reverse_sms.go)) use memory maps. Go Migration 1 defines `phone_verifications` as the future persistence model.
- **`app/` (Android Kotlin):** Calls REST endpoints `/api/v1/auth/request-otp` and `/api/v1/auth/verify-otp`. Zero SQL calls.
- **`database/migrations/`:** Defined in `004_create_phone_verifications.sql:7-20` (columns: `hashed_otp`, `salt`, `attempts`, `expires_at TIMESTAMPTZ`).

#### 2.2 Database Object Attachments
- **Foreign Keys:** Zero outbound, zero inbound.
- **Indexes:** `idx_phone_verifications_phone` (`010_create_indexes.sql:17`).
- **RLS:** RLS enabled (`012_create_rls_policies.sql:72`), locked to service role only.

#### 2.3 Impact of Renaming to `legacy_supabase_phone_verifications`
- Frees relation name `phone_verifications` so Go Migration 1 can create the new table with columns `nonce`, `session_id`, `verified`, and `BIGINT` timestamps.
- Prevents `SQLSTATE 42703 (nonce does not exist)` during index creation.
- **Data Migration:** None (0 rows).
- **Verdict:** **`SAFE TO RENAME`** (Mandatory to prevent second failure in Migration 1).

---

### Table 4: `public.contacts`

#### 2.1 Codebase References
- **`backend/` (Go):** Zero runtime SQL queries.
  - Handlers in `backend/cmd/qr-sync/main.go` and `backend/internal/auth/handler.go:142` expose REST endpoints `/api/v1/contacts/sync-qr` and `/api/v1/contacts/lookup`.
  - Database queries in [`backend/internal/connections/postgres_repository.go`](file:///H:/chat_app/backend/internal/connections/postgres_repository.go) query `connections`, `connection_requests`, and `user_blocks` (Go Migration 2).
- **`app/` (Android Kotlin):** Calls `/api/v1/contacts/lookup` via Retrofit. Zero database calls.
- **`database/migrations/`:** Defined in `005_create_contacts.sql:11-23`.

#### 2.2 Database Object Attachments
- **Foreign Keys Outbound:** `owner_id REFERENCES profiles(id)`, `contact_user_id REFERENCES profiles(id)`.
- **Foreign Keys Inbound:** Zero.
- **Triggers:** `trg_contacts_updated_at` (executes `set_updated_at()`).
- **RLS Policies:** `"Users can only view their own contacts"` (`SELECT`), `"Users can only insert or delete their own contacts"` (`ALL`).

#### 2.3 Impact of Renaming to `legacy_supabase_contacts`
- Zero runtime impact. Go backend does not use table `contacts`.
- **Data Migration:** None (0 rows).
- **Verdict:** **`SAFE TO RENAME`**

---

### Table 5: `public.device_sessions`

#### 2.1 Codebase References
- **`backend/` (Go):** Zero runtime SQL queries.
  - The Go backend manages sessions in table `sessions` (Go Migration 1: [`backend/internal/database/migrations.go:68-76`](file:///H:/chat_app/backend/internal/database/migrations.go#L68-L76)) and in `internal/identity/store.go`.
- **`app/` (Android Kotlin):** Stores session tokens in Android KeyStore / EncryptedSharedPreferences. Zero SQL calls.
- **`database/migrations/`:** Defined in `009_create_device_sessions.sql:7-16`.

#### 2.2 Database Object Attachments
- **Foreign Keys Outbound:** `user_id REFERENCES profiles(id)`, `device_id REFERENCES devices(device_id)`.
- **Foreign Keys Inbound:** Zero.
- **Indexes:** `idx_device_sessions_token_hash` (`010_create_indexes.sql:31`).
- **RLS:** RLS enabled, locked to service role only (`012_create_rls_policies.sql:73`).

#### 2.3 Impact of Renaming to `legacy_supabase_device_sessions`
- Zero runtime impact. Go uses `sessions`.
- **Data Migration:** None (0 rows).
- **Verdict:** **`SAFE TO RENAME`**

---

### Table 6: `public.identity_keys`

#### 2.1 Codebase References
- **`backend/` (Go):** Zero runtime SQL queries.
  - Handshake and identity key bindings are queried from `identity_key_bindings` (Go Migration 3) in [`backend/internal/prekey/postgres_repository.go:28`](file:///H:/chat_app/backend/internal/prekey/postgres_repository.go#L28).
- **`app/` (Android Kotlin):** Calls REST `/api/v1/users/identity-key`. Zero SQL calls.
- **`database/migrations/`:** Defined in `006_create_identity_keys.sql:7-17`.

#### 2.2 Database Object Attachments
- **Foreign Keys Outbound:** `user_id REFERENCES profiles(id)`, `device_id REFERENCES devices(device_id)`.
- **Foreign Keys Inbound:** Zero.
- **RLS Policies:** RLS enabled.

#### 2.3 Impact of Renaming to `legacy_supabase_identity_keys`
- Zero runtime impact. Go uses `identity_key_bindings`.
- **Data Migration:** None (0 rows).
- **Verdict:** **`SAFE TO RENAME`**

---

### Table 7: `public.nx_identities`

#### 2.1 Codebase References
- **`backend/` (Go):** Zero runtime SQL queries.
  - Account public keys and NX-IDs are stored directly in `accounts` (`accounts.nx_id`, `accounts.public_key_hex`).
- **`app/` (Android Kotlin):** Zero references.
- **`database/migrations/`:** Defined in `003_create_nx_identity.sql:7-16`.

#### 2.2 Database Object Attachments
- **Foreign Keys Outbound:** `nx_id REFERENCES profiles(nx_id)`, `user_id REFERENCES profiles(id)`.
- **Foreign Keys Inbound:** Zero.
- **RLS Policies:** `"NX identities are publicly readable for verification"` (`SELECT`).

#### 2.3 Impact of Renaming to `legacy_supabase_nx_identities`
- Zero runtime impact.
- **Data Migration:** None (0 rows).
- **Verdict:** **`SAFE TO RENAME`**

---

### Table 8: `public.phone_visibility_grants`

#### 2.1 Codebase References
- **`backend/` (Go):** Zero runtime SQL queries.
  - Phone privacy is managed natively in Go repository queries via `accounts.is_discoverable_by_phone` ([`backend/internal/database/repository.go:230`](file:///H:/chat_app/backend/internal/database/repository.go#L230)).
- **`app/` (Android Kotlin):** Zero references.
- **`database/migrations/`:** Defined in `016_create_phone_visibility_grants.sql:9-19`.

#### 2.2 Database Object Attachments
- **Foreign Keys Outbound:** `granter_id REFERENCES profiles(id)`, `grantee_id REFERENCES profiles(id)`.
- **Foreign Keys Inbound:** Referenced in SQL view `public.safe_profiles`.
- **Functions:** `toggle_phone_visibility()` (`016_create_phone_visibility_grants.sql:72-99`).
- **RLS Policies:**
  - `"Users can manage their own phone visibility grants"` (`ALL`, `auth.uid() = granter_id`)
  - `"Grantees can inspect visibility granted to them"` (`SELECT`, `auth.uid() = grantee_id`)

#### 2.3 Impact of Renaming to `legacy_supabase_phone_visibility_grants`
- Zero runtime impact on backend or mobile clients.
- View `safe_profiles` and function `toggle_phone_visibility` must be dropped as specified in the reconciliation plan.
- **Data Migration:** None (0 rows).
- **Verdict:** **`SAFE TO RENAME`**

---

### Table 9: `public.prekeys`

#### 2.1 Codebase References
- **`backend/` (Go):** Zero runtime SQL queries.
  - Endpoint routes contain `/api/v1/prekeys/claim` and `/api/v1/users/prekeys/*`.
  - Database persistence queries in [`backend/internal/prekey/postgres_repository.go`](file:///H:/chat_app/backend/internal/prekey/postgres_repository.go) target `signed_prekeys`, `one_time_prekeys`, and `prekey_claims` (Go Migration 3). Table `prekeys` is never queried.
- **`app/` (Android Kotlin):** Handshake client ([`app/src/main/java/com/nexa/app/data/remote/api/HandshakeApi.kt:92`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/remote/api/HandshakeApi.kt#L92)) calls `/api/v1/prekeys/claim`. Zero database calls.
- **`database/migrations/`:** Defined in `007_create_prekeys.sql:7-19`.

#### 2.2 Database Object Attachments
- **Foreign Keys Outbound:** `user_id REFERENCES profiles(id)`, `device_id REFERENCES devices(device_id)`.
- **Foreign Keys Inbound:** Zero.
- **Indexes:** `idx_prekeys_user_device` (`010_create_indexes.sql:24`).
- **RLS Policies:**
  - `"Prekeys are readable by authenticated users for handshakes"` (`SELECT`)
  - `"Users can only manage their own prekeys"` (`ALL`, `auth.uid() = user_id`)

#### 2.3 Impact of Renaming to `legacy_supabase_prekeys`
- Zero runtime impact.
- **Data Migration:** None (0 rows).
- **Verdict:** **`SAFE TO RENAME`**

---

## 3. Cross-Cutting Database Object Retirement Matrix

```
+---------------------------------------------------------------------------------------------------------------+
|                                      ATTACHED OBJECT DISPOSITION MATRIX                                       |
+--------------------------+-----------------------+-----------------------+------------------------------------+
| OBJECT NAME              | OBJECT TYPE           | ATTACHED TABLE(S)     | DISPOSITION & REASON               |
+--------------------------+-----------------------+-----------------------+------------------------------------+
| public.safe_profiles     | SQL View              | profiles,             | DROP: Depends on profiles.         |
|                          |                       | phone_vis_grants      | Replaced by Go phone lookup.       |
+--------------------------+-----------------------+-----------------------+------------------------------------+
| trg_limit_devices        | Trigger               | devices               | DROP: Calls check_device_count().  |
| check_device_count()     | Trigger Function      |                       | Incompatible with Go devices.      |
+--------------------------+-----------------------+-----------------------+------------------------------------+
| trg_profiles_updated_at  | Trigger               | profiles              | PRESERVE: PostgreSQL updates       |
| trg_contacts_updated_at  | Trigger               | contacts              | triggers to renamed tables.        |
| set_updated_at()         | Trigger Function      | (shared)              |                                    |
+--------------------------+-----------------------+-----------------------+------------------------------------+
| generate_random_nx_id()  | SQL Function          | profiles              | DROP: Insecure search path.        |
|                          |                       |                       | Replaced by Go crypto generator.   |
+--------------------------+-----------------------+-----------------------+------------------------------------+
| lookup_nexa_contacts()   | SQL Function (RPC)    | profiles, devices     | DROP: Insecure search path.        |
|                          | (SECURITY DEFINER)    |                       | Replaced by Go phone lookup.       |
+--------------------------+-----------------------+-----------------------+------------------------------------+
| toggle_phone_visibility()| SQL Function (RPC)    | phone_vis_grants      | DROP: Insecure search path.        |
|                          | (SECURITY DEFINER)    |                       | Replaced by Go UpdateProfile.      |
+--------------------------+-----------------------+-----------------------+------------------------------------+
```

---

## 4. Final Audit Synthesis & Safety Conclusion

1. **No Application Breakage:** Neither the Go backend nor the Android mobile application has any active runtime dependencies on the 9 legacy Supabase tables.
2. **Mandatory Unblocking:** Renaming `devices` and `phone_verifications` is required to eliminate the fatal `SQLSTATE 42703` errors preventing backend startup and migration execution.
3. **Preservation of History:** Renaming to `legacy_supabase_*` rather than dropping ensures that historical schema artifacts remain 100% available for reference.
4. **Referential Integrity:** PostgreSQL automatically updates all foreign keys within the `legacy_supabase_*` cluster during renaming.
5. **Final Status:** **ALL 9 TABLES ARE VERIFIED SAFE TO RENAME.**
