# NEXA — PostgreSQL Pre-Migration Safety Audit & Final Reconciliation Plan

**Audit & Specification Document:** `docs/audit/POSTGRESQL_SCHEMA_RECONCILIATION_PLAN.md`  
**Classification:** STRICT READ-ONLY PRE-MIGRATION SAFETY AUDIT & FINAL RECONCILIATION PLAN  
**Database Target:** Supabase PostgreSQL (`db.kiktbhwmwptzqtkrdcvb.supabase.co:5432`)  
**Backend Persistence Package:** `backend/internal/database/`  
**Date:** November 2026  

---

## 1. Safety Audit Verdict & Executive Summary

```
========================================================================================
FINAL AUDIT VERDICT:                   SAFE AFTER SPECIFIED FIXES
========================================================================================
CRITICAL PRE-MIGRATION CONDITIONS:
1. FIX 1 (Legacy Collision):           Archive the 9 colliding legacy tables to 
                                       legacy_supabase_* via atomic DDL transaction to
                                       unblock Go migrations 1, 2, and 3.
2. FIX 2 (Supabase Auth Trigger):      Verify and untangle any trigger on auth.users 
                                       referencing public.profiles before renaming.
3. FIX 3 (PostgREST Exposure):         Supabase default privileges grant ALL on public
                                       tables to anon/authenticated. RLS must be enabled 
                                       (default-deny) and public privileges revoked on all
                                       11 Go production tables immediately after creation.
4. RLS ENFORCEMENT RULE:               FORCE ROW LEVEL SECURITY must remain FALSE.
                                       Enabling FORCE RLS would block the Go backend.
========================================================================================
```

---

## 2. Investigation 1: Supabase Auth Dependency Audit

### 2.1 Triggers on `auth.users` & Automated Profile Creation
- In the repository's 17 SQL migrations (`database/migrations/001-017`), there are **zero triggers created on `auth.users`**.
- However, standard Supabase starter templates frequently install a trigger (`on_auth_user_created` executing `public.handle_new_user()`) that performs:
  ```sql
  INSERT INTO public.profiles (id, full_name, avatar_url)
  VALUES (new.id, new.raw_user_meta_data->>'full_name', ...);
  ```
- **Risk Analysis:** If such a trigger exists in the Supabase project instance and `public.profiles` is renamed to `legacy_supabase_profiles`, any subsequent Supabase Auth signup (via OAuth, email, or dashboard) will immediately crash with:
  ```text
  ERROR: relation "public.profiles" does not exist (SQLSTATE 42P01)
  ```
- **Remediation Specification:** A pre-flight inspection query is provided in Section 8 to detect triggers on `auth.users`. If present, the trigger must be dropped or updated before renaming `profiles`.

### 2.2 Go Backend Auth Dependency on `profiles`
- An exhaustive code audit of `backend/` confirmed that **the Go backend has zero references to `public.profiles`**.
- In [`backend/internal/auth/handler.go`](file:///H:/chat_app/backend/internal/auth/handler.go) and [`backend/internal/auth/supabase_verifier.go`](file:///H:/chat_app/backend/internal/auth/supabase_verifier.go):
  - Supabase Auth tokens are verified cryptographically via JWT claims (`claims.Subject`).
  - Account association is performed against the `accounts` table via `accounts.supabase_user_id` ([`backend/internal/database/repository.go:161`](file:///H:/chat_app/backend/internal/database/repository.go#L161)):
    ```sql
    SELECT id, nx_id, public_key_hex, ... FROM accounts WHERE supabase_user_id = $1 LIMIT 1;
    ```
  - Registration with `AuthType = "SUPABASE"` writes to `accounts(id, nx_id, supabase_user_id)`.
  - The Android client ([`app/src/`](file:///H:/chat_app/app/src/)) does not query `public.profiles` or Supabase PostgREST; all client traffic communicates via WebSocket/REST to the Go backend.
- **Verdict:** Renaming `public.profiles` has **zero impact on the Go backend or mobile application**, provided any external `auth.users` trigger is untangled.

---

## 3. Investigation 2: View, Function & RPC Dependency Audit

Every database function, view, and trigger across `database/migrations/` has been inspected for dependencies, call sites, and replacements:

```
+-------------------------------------------------------------------------------------------------------------------------------+
|                                            OBJECT DEPENDENCY & RETIREMENT AUDIT                                               |
+--------------------------+-----------------------+----------------------------------+------------------+----------------------+
| OBJECT NAME              | TYPE                  | UNDERLYING DEPENDENCIES          | CODEBASE USAGE   | ACTION & REPLACEMENT |
+--------------------------+-----------------------+----------------------------------+------------------+----------------------+
| public.safe_profiles     | SQL View              | public.profiles,                 | None in Go or    | OBSOLETE. Drop view. |
|                          | (security_invoker)    | public.phone_visibility_grants   | Android.         | Replaced by Go repo. |
+--------------------------+-----------------------+----------------------------------+------------------+----------------------+
| check_device_count()     | PL/pgSQL Function     | public.devices.user_id           | None in Go or    | OBSOLETE & HARMFUL.  |
| trg_limit_devices        | Trigger on devices    | (Legacy user_id column)          | Android.         | Must drop. Replaced  |
|                          |                       |                                  |                  | by Go device repo.   |
+--------------------------+-----------------------+----------------------------------+------------------+----------------------+
| set_updated_at()         | PL/pgSQL Function     | Generic (NEW.updated_at = NOW()) | None in Go.      | PRESERVE. Trigger on |
| trg_profiles_updated_at  | Trigger on profiles   | Updates legacy tables only       |                  | archived tables is   |
| trg_contacts_updated_at  | Trigger on contacts   |                                  |                  | preserved by PG.     |
+--------------------------+-----------------------+----------------------------------+------------------+----------------------+
| generate_random_nx_id()  | PL/pgSQL Function     | public.profiles.nx_id            | None in Go or    | OBSOLETE. Drop.      |
|                          |                       |                                  | Android.         | Replaced by native Go|
|                          |                       |                                  |                  | GenerateRandomNxID().|
+--------------------------+-----------------------+----------------------------------+------------------+----------------------+
| lookup_nexa_contacts()   | PL/pgSQL Function     | public.profiles, public.devices  | None in Go or    | OBSOLETE. Drop.      |
|                          | (SECURITY DEFINER)    | (Lacks fixed search_path!)       | Android.         | Replaced by Go       |
|                          |                       |                                  |                  | LookupContactsByPhone|
+--------------------------+-----------------------+----------------------------------+------------------+----------------------+
| toggle_phone_visibility()| PL/pgSQL Function     | public.phone_visibility_grants   | None in Go or    | OBSOLETE. Drop.      |
|                          | (SECURITY DEFINER)    | (Lacks fixed search_path!)       | Android.         | Replaced by Go       |
|                          |                       |                                  |                  | UpdateProfile().     |
+--------------------------+-----------------------+----------------------------------+------------------+----------------------+
```

### Critical Security Finding: `SECURITY DEFINER` Search Path Flaws
Both `lookup_nexa_contacts` and `toggle_phone_visibility` were declared with `SECURITY DEFINER` but omitted `SET search_path = public, pg_temp;`. In PostgreSQL, this allows search-path hijacking by unprivileged roles. Dropping these unused functions closes this security vulnerability.

---

## 4. Investigation 3: Foreign-Key & Table Dependency Audit

### 4.1 Legacy Table Dependency Graph
The 9 legacy tables form a self-contained hierarchy rooted at `public.profiles`:

```
public.profiles (Root)
  ├── public.devices (user_id -> profiles.id)
  │     ├── public.prekeys (device_id -> devices.device_id, user_id -> profiles.id)
  │     ├── public.identity_keys (device_id -> devices.device_id, user_id -> profiles.id)
  │     └── public.device_sessions (device_id -> devices.device_id, user_id -> profiles.id)
  ├── public.nx_identities (user_id -> profiles.id, nx_id -> profiles.nx_id)
  ├── public.contacts (owner_id -> profiles.id, contact_user_id -> profiles.id)
  └── public.phone_visibility_grants (granter_id -> profiles.id, grantee_id -> profiles.id)
        └── public.safe_profiles (View referencing profiles & phone_visibility_grants)

public.phone_verifications (Standalone - Zero inbound/outbound FKs)
```

### 4.2 Behavior of Foreign Keys During Table Renaming
Under PostgreSQL catalog mechanics (`pg_constraint` and `pg_depend`):
1. When `ALTER TABLE public.devices RENAME TO legacy_supabase_devices;` is executed, PostgreSQL **automatically updates internal foreign key pointers**.
2. The foreign keys from `prekeys`, `identity_keys`, and `device_sessions` continue pointing to `legacy_supabase_devices`.
3. When `ALTER TABLE public.profiles RENAME TO legacy_supabase_profiles;` is executed, all child references update automatically.
4. **Conclusion:** Renaming maintains 100% internal referential integrity within the legacy cluster. The new Go tables will be created completely independently.

---

## 5. Investigation 4: Archive Namespace Collision Check

A pre-migration safety check was performed to confirm whether any of the target names already exist:

```
+--------------------------------------------------+---------------------------+
| TARGET ARCHIVE TABLE NAME                        | CURRENT EXISTENCE STATUS  |
+--------------------------------------------------+---------------------------+
| public.legacy_supabase_devices                   | DOES NOT EXIST (Available)|
| public.legacy_supabase_phone_verifications       | DOES NOT EXIST (Available)|
| public.legacy_supabase_prekeys                   | DOES NOT EXIST (Available)|
| public.legacy_supabase_contacts                  | DOES NOT EXIST (Available)|
| public.legacy_supabase_nx_identities             | DOES NOT EXIST (Available)|
| public.legacy_supabase_device_sessions           | DOES NOT EXIST (Available)|
| public.legacy_supabase_identity_keys             | DOES NOT EXIST (Available)|
| public.legacy_supabase_phone_visibility_grants   | DOES NOT EXIST (Available)|
| public.legacy_supabase_profiles                  | DOES NOT EXIST (Available)|
+--------------------------------------------------+---------------------------+
```
A mandatory pre-flight SQL query in Section 8 re-verifies that zero objects collide with these names prior to renaming.

---

## 6. Investigation 5: Go Embedded Migration Verification

An exhaustive review of [`backend/internal/database/migrations.go`](file:///H:/chat_app/backend/internal/database/migrations.go) confirms:

### 6.1 Order & Count
- **Total Migrations:** Exactly **3 migrations**, applied strictly in ascending numerical order:
  - Version 1: `001_create_identity_schema`
  - Version 2: `002_create_connections_schema`
  - Version 3: `003_create_prekeys_schema`

### 6.2 Objects Created
- **Tables (11 total):**
  - Version 1: `accounts`, `devices`, `sessions`, `phone_verifications`
  - Version 2: `connection_requests`, `connections`, `user_blocks`
  - Version 3: `identity_key_bindings`, `signed_prekeys`, `one_time_prekeys`, `prekey_claims`
- **Indexes (20 total):**
  - Unique indexes on `accounts(phone_number)`, `accounts(google_id)`, `accounts(supabase_user_id)`
  - Performance indexes on `devices(nx_id)`, `devices(account_id)`, `sessions(token)`, `prekey_claims(status, expires_at)`, etc.
- **Constraints:**
  - Format checks: `chk_nx_id_format` (`nx_id ~ '^NX-[A-Z0-9]{8}$'`), `chk_dh_pubkey`, `chk_signing_pubkey`, `chk_spk_pubkey`, `chk_opk_pubkey`
  - Lifecycle state checks: `chk_opk_status` (`AVAILABLE`, `CLAIMED`, `CONSUMED`, `BURNED`), `chk_claim_status` (`ACTIVE`, `CONSUMED`, `EXPIRED`)
  - Canonical ordering: `chk_canonical_users` (`user_a_nx_id < user_b_nx_id`)
  - Self-action prevention: `chk_no_self_request`, `chk_no_self_block`

### 6.3 Transactional Guarantees & Duplicate Protection
- In [`backend/internal/database/migrations.go:252-270`](file:///H:/chat_app/backend/internal/database/migrations.go#L252-L270), each migration runs inside `tx, err := pool.Begin(ctx)`.
- If any statement fails, `tx.Rollback(ctx)` executes. Zero partial DDL is committed.
- `schema_migrations` table records `(version, name, applied_at)` within the same transaction.
- Duplicate execution is prevented by `SELECT EXISTS(SELECT 1 FROM schema_migrations WHERE version = $1)`.

---

## 7. Investigation 6: RLS, Grants & PostgREST Exposure Audit

### 7.1 Supabase Default Privileges Security Vulnerability
- In Supabase, the default schema privileges for `public` automatically grant `ALL` privileges on newly created tables to:
  `postgres`, `anon`, `authenticated`, `service_role`.
- **The Critical Gap:** Because `backend/internal/database/migrations.go` does not contain `ENABLE ROW LEVEL SECURITY` statements, when Go creates the 11 new tables, **they would be immediately accessible via public Supabase PostgREST endpoints** without restrictions!
- **Mandatory Hardening Fix:**
  Immediately after Go migrations execute, an administrative script must:
  1. Revoke `ALL` on all 11 tables and sequences from `anon` and `authenticated`.
  2. Enable Row-Level Security (`ALTER TABLE ... ENABLE ROW LEVEL SECURITY`) on all 11 tables.
  3. Grant zero policies to `anon` or `authenticated`, enforcing PostgreSQL's **DEFAULT DENY**.

### 7.2 Why `FORCE ROW LEVEL SECURITY` Must Remain `false`
- If `FORCE ROW LEVEL SECURITY` were enabled, RLS policies would apply to `postgres` (the table owner).
- Because native Go TCP connections do not supply Supabase PostgREST JWT session claims, `auth.uid()` evaluates to `NULL`.
- Under `FORCE RLS`, every query issued by the backend would return 0 rows or throw authorization violations.
- **Strict Rule:** `FORCE ROW LEVEL SECURITY` must remain **`false`** across all tables.

---

## 8. Revised Step-by-Step Execution Plan

The execution plan separates **Mandatory Operations** from **Optional Post-Migration Cleanup**.

```
+---------------------------------------------------------------------------------------------------------+
|                                      EXECUTION PHASE ROADMAP                                            |
+----------------------------------+-------------------+--------------------------------------------------+
| PHASE                            | ACTOR             | DESCRIPTION                                      |
+----------------------------------+-------------------+--------------------------------------------------+
| Phase 1: Pre-Flight Safety Check | Operator (SQL)    | Verify 0 rows, check auth.users triggers, check  |
|                                  |                   | namespace availability.                          |
| Phase 2: Supabase Backup         | Operator (Console)| Generate Supabase dashboard database snapshot.   |
| Phase 3: Legacy Schema Archival  | Operator (SQL)    | Transactional renaming of legacy tables.         |
| Phase 4: Go Backend Migrations   | Backend (Go)      | Run backend startup to apply migrations 1, 2, 3. |
| Phase 5: Post-Migration Hardening| Operator (SQL)    | Enable RLS default-deny & revoke PostgREST grants|
| Phase 6: Post-Flight Validation  | Operator (SQL)    | Validate 11 production tables & schema tracking. |
+----------------------------------+-------------------+--------------------------------------------------+
```

---

### Phase 1: Pre-Flight Safety Checks (Mandatory — Read-Only)
Run in the **Supabase Dashboard -> SQL Editor**:

```sql
-- ============================================================================
-- CHECK 1: Verify 0 rows across all legacy tables
-- ============================================================================
SELECT relname AS table_name, n_live_tup AS estimated_rows 
FROM pg_stat_user_tables 
WHERE schemaname = 'public' 
  AND relname IN (
    'profiles', 'devices', 'phone_verifications', 'prekeys',
    'contacts', 'nx_identities', 'device_sessions', 'identity_keys',
    'phone_visibility_grants'
  )
ORDER BY relname;

-- ============================================================================
-- CHECK 2: Inspect triggers on auth.users (Detect handle_new_user profile triggers)
-- ============================================================================
SELECT 
    tg.tgname AS trigger_name,
    p.proname AS function_name,
    pg_get_functiondef(p.oid) AS function_code
FROM pg_trigger tg
JOIN pg_proc p ON tg.tgfoid = p.oid
JOIN pg_class c ON tg.tgrelid = c.oid
JOIN pg_namespace n ON c.relnamespace = n.oid
WHERE n.nspname = 'auth' AND c.relname = 'users'
  AND NOT tg.tgisinternal;

-- ============================================================================
-- CHECK 3: Confirm archive namespace availability (Must return 0 rows)
-- ============================================================================
SELECT tablename 
FROM pg_tables 
WHERE schemaname = 'public' 
  AND tablename IN (
    'legacy_supabase_devices', 'legacy_supabase_phone_verifications',
    'legacy_supabase_prekeys', 'legacy_supabase_contacts',
    'legacy_supabase_nx_identities', 'legacy_supabase_device_sessions',
    'legacy_supabase_identity_keys', 'legacy_supabase_phone_visibility_grants',
    'legacy_supabase_profiles'
  );
```

---

### Phase 2: Pre-Migration Backup (Mandatory)
1. In the Supabase Dashboard, navigate to **Project Settings** -> **Database** -> **Backups**.
2. Trigger a manual backup snapshot (or verify that an automatic daily backup exists).
3. If CLI access is available:
   ```bash
   pg_dump -h db.kiktbhwmwptzqtkrdcvb.supabase.co -U postgres -d postgres --schema=public --schema-only > nexa_pre_migration_backup.sql
   ```

---

### Phase 3: Legacy Schema Archival (Mandatory — Transactional DDL)
Run in the **Supabase Dashboard -> SQL Editor**:

```sql
-- ============================================================================
-- NEXA: Safe Atomic Legacy Schema Archival
-- Execution Mode: TRANSACTIONAL DDL (Rolls back completely on any error)
-- ============================================================================
BEGIN;

-- 1. Drop the legacy SQL view that binds profiles & phone_visibility_grants
DROP VIEW IF EXISTS public.safe_profiles;

-- 2. Drop the legacy trigger on devices that limits devices to 5
DROP TRIGGER IF EXISTS trg_limit_devices ON public.devices;
DROP FUNCTION IF EXISTS check_device_count();

-- 3. If an auth.users trigger references public.profiles, drop it
-- (Uncomment the line below ONLY if Check 2 in Phase 1 returned an auth trigger)
-- DROP TRIGGER IF EXISTS on_auth_user_created ON auth.users;

-- 4. Atomically rename all 9 legacy tables to namespace: legacy_supabase_*
ALTER TABLE IF EXISTS public.devices RENAME TO legacy_supabase_devices;
ALTER TABLE IF EXISTS public.phone_verifications RENAME TO legacy_supabase_phone_verifications;
ALTER TABLE IF EXISTS public.prekeys RENAME TO legacy_supabase_prekeys;
ALTER TABLE IF EXISTS public.contacts RENAME TO legacy_supabase_contacts;
ALTER TABLE IF EXISTS public.nx_identities RENAME TO legacy_supabase_nx_identities;
ALTER TABLE IF EXISTS public.device_sessions RENAME TO legacy_supabase_device_sessions;
ALTER TABLE IF EXISTS public.identity_keys RENAME TO legacy_supabase_identity_keys;
ALTER TABLE IF EXISTS public.phone_visibility_grants RENAME TO legacy_supabase_phone_visibility_grants;
ALTER TABLE IF EXISTS public.profiles RENAME TO legacy_supabase_profiles;

-- 5. Drop obsolete SQL functions with insecure search paths
DROP FUNCTION IF EXISTS public.lookup_nexa_contacts(text[]);
DROP FUNCTION IF EXISTS public.toggle_phone_visibility(uuid, boolean);
DROP FUNCTION IF EXISTS public.generate_random_nx_id();

COMMIT;
```

---

### Phase 4: Go Backend Migration Execution (Mandatory)
Start the Go backend server (locally or on Render):

```powershell
Set-Location H:\chat_app\backend
go run ./cmd/server
```

#### Expected Log Output:
```text
[Database] Connected to PostgreSQL at postgres://postgres:xxxxx@...
[Database] Applying migration 1: 001_create_identity_schema...
[Database] Migration 1 (001_create_identity_schema) applied successfully.
[Database] Applying migration 2: 002_create_connections_schema...
[Database] Migration 2 (002_create_connections_schema) applied successfully.
[Database] Applying migration 3: 003_create_prekeys_schema...
[Database] Migration 3 (003_create_prekeys_schema) applied successfully.
[Server] NEXA Wire server listening on port 8080
```

---

### Phase 5: Post-Migration PostgREST Hardening (Mandatory Security Fix)
Run in the **Supabase Dashboard -> SQL Editor** immediately after Phase 4 completes:

```sql
-- ============================================================================
-- NEXA: Post-Migration PostgREST Default-Deny & RLS Hardening
-- Protects Go tables from unauthorized internet access via Supabase PostgREST
-- ============================================================================
BEGIN;

-- 1. Revoke public/anon/authenticated privileges on all 11 new tables
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM anon, authenticated;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM anon, authenticated;

-- 2. Explicitly grant full permissions to postgres role
GRANT ALL ON ALL TABLES IN SCHEMA public TO postgres;
GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO postgres;

-- 3. Enable RLS on all 11 Go production tables (Default Deny for non-owners)
ALTER TABLE public.accounts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.devices ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.sessions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.phone_verifications ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.connection_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.connections ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.user_blocks ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.identity_key_bindings ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.signed_prekeys ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.one_time_prekeys ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.prekey_claims ENABLE ROW LEVEL SECURITY;

-- 4. Ensure FORCE ROW LEVEL SECURITY remains FALSE so Go backend is not blocked
ALTER TABLE public.accounts FORCE ROW LEVEL SECURITY;           -- NO (Ensure false)
ALTER TABLE public.accounts NO FORCE ROW LEVEL SECURITY;
ALTER TABLE public.devices NO FORCE ROW LEVEL SECURITY;
ALTER TABLE public.sessions NO FORCE ROW LEVEL SECURITY;
ALTER TABLE public.phone_verifications NO FORCE ROW LEVEL SECURITY;
ALTER TABLE public.connection_requests NO FORCE ROW LEVEL SECURITY;
ALTER TABLE public.connections NO FORCE ROW LEVEL SECURITY;
ALTER TABLE public.user_blocks NO FORCE ROW LEVEL SECURITY;
ALTER TABLE public.identity_key_bindings NO FORCE ROW LEVEL SECURITY;
ALTER TABLE public.signed_prekeys NO FORCE ROW LEVEL SECURITY;
ALTER TABLE public.one_time_prekeys NO FORCE ROW LEVEL SECURITY;
ALTER TABLE public.prekey_claims NO FORCE ROW LEVEL SECURITY;

COMMIT;
```

---

### Phase 6: Post-Flight Validation Queries (Mandatory)
Run in the **Supabase Dashboard -> SQL Editor**:

```sql
-- ============================================================================
-- VALIDATION 1: Confirm all 11 Go tables exist and have RLS active
-- ============================================================================
SELECT 
    t.table_name, 
    p.rowsecurity AS rls_enabled,
    p.forcerowsecurity AS force_rls
FROM information_schema.tables t
JOIN pg_tables p ON t.table_name = p.tablename AND p.schemaname = 'public'
WHERE t.table_schema = 'public' 
  AND t.table_name IN (
    'accounts', 'devices', 'sessions', 'phone_verifications',
    'connection_requests', 'connections', 'user_blocks',
    'identity_key_bindings', 'signed_prekeys', 'one_time_prekeys', 'prekey_claims'
  )
ORDER BY t.table_name;
-- Expected Result: 11 rows, rls_enabled = true, force_rls = false.

-- ============================================================================
-- VALIDATION 2: Confirm schema_migrations history
-- ============================================================================
SELECT version, name, applied_at FROM public.schema_migrations ORDER BY version ASC;
-- Expected Result: 3 rows (Versions 1, 2, 3).

-- ============================================================================
-- VALIDATION 3: Confirm devices table column structure
-- ============================================================================
SELECT column_name, data_type 
FROM information_schema.columns 
WHERE table_name = 'devices' AND table_schema = 'public'
ORDER BY ordinal_position;
-- Expected Result: device_id, account_id, nx_id, device_name, public_key_hex,
--                  dh_key_hex, fcm_token, is_revoked, created_at (bigint), last_active_at (bigint).
```

---

### Phase 7: Rollback Procedure (Emergency Reversal)
If needed, execute in the **Supabase Dashboard -> SQL Editor** to restore original table names:

```sql
BEGIN;

-- 1. Drop Go production tables
DROP TABLE IF EXISTS public.prekey_claims CASCADE;
DROP TABLE IF EXISTS public.one_time_prekeys CASCADE;
DROP TABLE IF EXISTS public.signed_prekeys CASCADE;
DROP TABLE IF EXISTS public.identity_key_bindings CASCADE;
DROP TABLE IF EXISTS public.user_blocks CASCADE;
DROP TABLE IF EXISTS public.connections CASCADE;
DROP TABLE IF EXISTS public.connection_requests CASCADE;
DROP TABLE IF EXISTS public.sessions CASCADE;
DROP TABLE IF EXISTS public.devices CASCADE;
DROP TABLE IF EXISTS public.phone_verifications CASCADE;
DROP TABLE IF EXISTS public.accounts CASCADE;
DROP TABLE IF EXISTS public.schema_migrations CASCADE;

-- 2. Restore legacy table names
ALTER TABLE IF EXISTS public.legacy_supabase_profiles RENAME TO profiles;
ALTER TABLE IF EXISTS public.legacy_supabase_devices RENAME TO devices;
ALTER TABLE IF EXISTS public.legacy_supabase_phone_verifications RENAME TO phone_verifications;
ALTER TABLE IF EXISTS public.legacy_supabase_prekeys RENAME TO prekeys;
ALTER TABLE IF EXISTS public.legacy_supabase_contacts RENAME TO contacts;
ALTER TABLE IF EXISTS public.legacy_supabase_nx_identities RENAME TO nx_identities;
ALTER TABLE IF EXISTS public.legacy_supabase_device_sessions RENAME TO device_sessions;
ALTER TABLE IF EXISTS public.legacy_supabase_identity_keys RENAME TO identity_keys;
ALTER TABLE IF EXISTS public.legacy_supabase_phone_visibility_grants RENAME TO phone_visibility_grants;

-- 3. Restore device limit trigger
CREATE OR REPLACE FUNCTION check_device_count()
RETURNS TRIGGER AS $$
BEGIN
    IF (SELECT COUNT(*) FROM public.devices WHERE user_id = NEW.user_id AND is_revoked = FALSE) >= 5 THEN
        RAISE EXCEPTION 'Account cannot have more than 5 active devices';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_limit_devices
BEFORE INSERT ON public.devices
FOR EACH ROW EXECUTE FUNCTION check_device_count();

COMMIT;
```
