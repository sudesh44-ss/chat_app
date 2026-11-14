# NEXA — Minimal PostgreSQL Schema Reconciliation Plan

**Document:** `docs/audit/MINIMAL_SCHEMA_RECONCILIATION_PLAN.md`  
**Classification:** STRICT READ-ONLY MINIMAL RECONCILIATION SPECIFICATION  
**Objective:** Resolve ONLY the two confirmed table-name collisions (`devices` and `phone_verifications`) while preserving all other 7 legacy tables, views, triggers, and constraints intact.  
**Database Target:** Supabase PostgreSQL (`db.kiktbhwmwptzqtkrdcvb.supabase.co:5432`)  
**Date:** November 2026  

---

## 1. Executive Summary & Verdict

```
========================================================================================
FINAL AUDIT VERDICT:                   READY FOR REVIEW
========================================================================================
SCOPE OF RECONCILIATION:
- TABLES RENAMED:                      ONLY 2 (public.devices & public.phone_verifications)
- TABLES PRESERVED UNTOUCHED:          7 (profiles, contacts, device_sessions, 
                                          identity_keys, nx_identities, 
                                          phone_visibility_grants, prekeys)
- VIEWS PRESERVED UNTOUCHED:           public.safe_profiles (100% intact)
- TRIGGERS & FUNCTIONS PRESERVED:      trg_limit_devices, check_device_count(), 
                                       set_updated_at(), lookup_nexa_contacts(),
                                       toggle_phone_visibility(), generate_random_nx_id()
- SUPABASE AUTH WORKFLOWS:             100% intact (profiles is NOT renamed)
- DATA LOSS RISK:                      ZERO (all existing tables contain 0 rows)
- ROLLBACK RISK:                       ZERO (atomic 2-line transactional rename)
========================================================================================
```

---

## 2. Dependency & Impact Analysis for the Two Target Tables

### 2.1 Table 1: `public.phone_verifications`
- **Outbound Foreign Keys:** None.
- **Inbound Foreign Keys:** None.
- **Views & Triggers:** None.
- **Runtime Impact of Rename:**
  - Renaming `phone_verifications` to `legacy_supabase_phone_verifications` immediately frees the relation name.
  - When Go Migration 1 executes, it creates the production `phone_verifications` table (`id BIGSERIAL PRIMARY KEY`, `phone_number VARCHAR(32)`, `nonce VARCHAR(64) UNIQUE`, `session_id VARCHAR(128)`, `verified BOOLEAN`, `expires_at BIGINT`, `created_at BIGINT`).
  - Index `idx_phone_verifications_nonce` evaluates the new table and builds without errors.
  - Zero application code breaks (active OTP services in Go use memory buffers; this table provides future persistent nonce tracking).

### 2.2 Table 2: `public.devices`
- **Outbound Foreign Key:** `user_id UUID REFERENCES public.profiles(id) ON DELETE CASCADE`.
  - When `devices` is renamed to `legacy_supabase_devices`, PostgreSQL automatically preserves this constraint on the renamed table.
- **Inbound Foreign Keys:**
  - `prekeys.device_id -> devices(device_id)`
  - `identity_keys.device_id -> devices(device_id)`
  - `device_sessions.device_id -> devices(device_id)`
  - Under PostgreSQL catalog mechanics (`pg_constraint.confrelid`), foreign keys are bound by internal Object Identifier (OID), not by string name. When `devices` is renamed, all three child foreign keys automatically redirect to `legacy_supabase_devices`. They **do not break**.
- **Triggers:** `trg_limit_devices` (calls `check_device_count()`).
  - The trigger is attached to the table relation. When the table is renamed, `trg_limit_devices` travels with `legacy_supabase_devices`.
  - The new `devices` table created by Go Migration 1 will not have this trigger, ensuring that backend inserts via `repository.go` execute cleanly without looking for the obsolete `user_id` column.
- **Runtime Impact of Rename:**
  - Renaming `devices` to `legacy_supabase_devices` frees the relation name `devices`.
  - When Go Migration 1 executes, it creates the true production `devices` table expected by [`backend/internal/database/repository.go`](file:///H:/chat_app/backend/internal/database/repository.go#L278) (`device_id VARCHAR(128)`, `account_id VARCHAR(64)`, `nx_id VARCHAR(32)`, `BIGINT` timestamps).
  - Index `idx_devices_nx_id` and index `idx_devices_account_id` build without errors.

---

## 3. Why the Other Seven Legacy Tables Require Zero Changes

Because Go's embedded migrations use distinct table names for all other subsystems, the remaining 7 legacy tables do **not** cause collisions:

```
+-----------------------------------+-----------------------------------+--------------------+
| EXISTING SUPABASE LEGACY TABLE    | GO EMBEDDED PRODUCTION TABLE      | COLLISION STATUS   |
+-----------------------------------+-----------------------------------+--------------------+
| public.profiles                   | public.accounts                   | NO COLLISION       |
| public.contacts                   | public.connections, requests, blk | NO COLLISION       |
| public.device_sessions            | public.sessions                   | NO COLLISION       |
| public.identity_keys              | public.identity_key_bindings      | NO COLLISION       |
| public.nx_identities              | Handled natively in accounts      | NO COLLISION       |
| public.phone_visibility_grants    | Handled natively in accounts      | NO COLLISION       |
| public.prekeys                    | public.signed_prekeys, opk        | NO COLLISION       |
+-----------------------------------+-----------------------------------+--------------------+
```

### Critical Preservation Benefits:
1. **`public.profiles` Remains Untouched:** Any existing Supabase Auth triggers or functions on `auth.users` that reference `public.profiles` continue functioning without disruption.
2. **`public.safe_profiles` View Remains Untouched:** Does not need to be dropped or modified.
3. **No Trigger Drops:** `check_device_count()`, `set_updated_at()`, and all other functions remain in the catalog.

---

## 4. Verification of Go Migration Runner & `schema_migrations`

### 4.1 Tracking Table Alignment
In [`backend/internal/database/migrations.go:228-248`](file:///H:/chat_app/backend/internal/database/migrations.go#L228-L248):
- Runner checks `schema_migrations (version INT PRIMARY KEY, name VARCHAR(255), applied_at TIMESTAMPTZ)`.
- The table already exists in Supabase (created during the initial local backend startup attempt).
- Current row count: **0 rows** (because Migration 1 rolled back cleanly).
- Expected state after minimal reconciliation:
  - Version 1 (`001_create_identity_schema`): Creates `accounts`, `devices`, `sessions`, `phone_verifications`.
  - Version 2 (`002_create_connections_schema`): Creates `connection_requests`, `connections`, `user_blocks`.
  - Version 3 (`003_create_prekeys_schema`): Creates `identity_key_bindings`, `signed_prekeys`, `one_time_prekeys`, `prekey_claims`.

---

## 5. Operational SQL Execution Specification

---

### Step 1: Pre-Execution Verification Queries (Read-Only)
Run in the **Supabase Dashboard -> SQL Editor** before executing any changes:

```sql
-- 1. Confirm that target archive names do NOT already exist (Must return 0 rows)
SELECT tablename 
FROM pg_tables 
WHERE schemaname = 'public' 
  AND tablename IN ('legacy_supabase_devices', 'legacy_supabase_phone_verifications');

-- 2. Confirm existing row counts on devices and phone_verifications (Must be 0)
SELECT relname AS table_name, n_live_tup AS estimated_rows 
FROM pg_stat_user_tables 
WHERE schemaname = 'public' 
  AND relname IN ('devices', 'phone_verifications');

-- 3. Confirm foreign keys pointing to public.devices that will follow the rename
SELECT 
    conrelid::regclass AS dependent_table,
    conname AS constraint_name
FROM pg_constraint 
WHERE confrelid = 'public.devices'::regclass;
-- Expected Result: prekeys, identity_keys, device_sessions.

-- 4. Confirm schema_migrations state (Must be 0 rows)
SELECT COUNT(*) AS applied_migrations FROM public.schema_migrations;
```

---

### Step 2: Minimal Transactional Rename SQL
Run in the **Supabase Dashboard -> SQL Editor**:

```sql
-- ============================================================================
-- NEXA: Minimal Schema Reconciliation
-- Renames ONLY the two colliding tables inside an atomic transaction
-- ============================================================================
BEGIN;

-- 1. Atomically rename legacy devices table
ALTER TABLE public.devices RENAME TO legacy_supabase_devices;

-- 2. Atomically rename legacy phone_verifications table
ALTER TABLE public.phone_verifications RENAME TO legacy_supabase_phone_verifications;

COMMIT;
```

---

### Step 3: Post-Rename Verification SQL (Read-Only)
Run in the **Supabase Dashboard -> SQL Editor** immediately after Step 2:

```sql
-- 1. Verify that 'devices' and 'phone_verifications' no longer exist under old names
SELECT tablename 
FROM pg_tables 
WHERE schemaname = 'public' 
  AND tablename IN ('devices', 'phone_verifications');
-- Expected Result: 0 rows.

-- 2. Verify that 'legacy_supabase_devices' and 'legacy_supabase_phone_verifications' exist
SELECT tablename 
FROM pg_tables 
WHERE schemaname = 'public' 
  AND tablename IN ('legacy_supabase_devices', 'legacy_supabase_phone_verifications');
-- Expected Result: 2 rows.

-- 3. Verify that foreign keys were automatically updated to point to legacy_supabase_devices
SELECT 
    conrelid::regclass AS dependent_table,
    conname AS constraint_name,
    confrelid::regclass AS referenced_table
FROM pg_constraint 
WHERE confrelid = 'public.legacy_supabase_devices'::regclass;
-- Expected Result: prekeys, identity_keys, device_sessions pointing to legacy_supabase_devices.

-- 4. Verify all other 7 legacy tables remain intact and accessible
SELECT tablename 
FROM pg_tables 
WHERE schemaname = 'public' 
  AND tablename IN (
    'profiles', 'contacts', 'device_sessions', 
    'identity_keys', 'nx_identities', 'phone_visibility_grants', 'prekeys'
  );
-- Expected Result: 7 rows.
```

---

### Step 4: Trigger Go Backend Migrations
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

### Step 5: Post-Migration Schema Verification
Run in the **Supabase Dashboard -> SQL Editor**:

```sql
-- 1. Confirm that Go's new devices and phone_verifications tables now exist
SELECT tablename 
FROM pg_tables 
WHERE schemaname = 'public' 
  AND tablename IN ('devices', 'phone_verifications', 'accounts', 'sessions');
-- Expected Result: 4 rows.

-- 2. Confirm columns on the new devices table
SELECT column_name, data_type 
FROM information_schema.columns 
WHERE table_name = 'devices' AND table_schema = 'public'
ORDER BY ordinal_position;
-- Expected Result includes: device_id, account_id, nx_id, public_key_hex, dh_key_hex,
--                           is_revoked, created_at (bigint), last_active_at (bigint).

-- 3. Confirm all 3 migrations are recorded in schema_migrations
SELECT version, name, applied_at FROM public.schema_migrations ORDER BY version ASC;
-- Expected Result: Versions 1, 2, and 3.
```

---

## 6. Rollback Procedure (Emergency Reversal)

If the operator decides to revert the changes before or after migrations:

```sql
-- ============================================================================
-- NEXA: Minimal Reconciliation Rollback Script
-- ============================================================================
BEGIN;

-- 1. If Go migrations were applied, drop the new production tables
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

-- 2. Clear migration tracking history
TRUNCATE TABLE public.schema_migrations;

-- 3. Restore original names for the two legacy tables
ALTER TABLE public.legacy_supabase_devices RENAME TO devices;
ALTER TABLE public.legacy_supabase_phone_verifications RENAME TO phone_verifications;

COMMIT;
```

---

## 7. Safety & Risk Evaluation

```
+-----------------------------------+-----------------------------------+-----------------------------------------+
| POTENTIAL RISK                    | EVALUATION                        | MITIGATION / SAFEGUARD                  |
+-----------------------------------+-----------------------------------+-----------------------------------------+
| Data Loss                         | ZERO                              | Both tables have 0 rows.                |
| Foreign Key Orphanage             | ZERO                              | PG updates OID references automatically.|
| Supabase Auth Disruption          | ZERO                              | profiles is NOT renamed.                |
| Safe Profiles View Invalidation   | ZERO                              | safe_profiles does NOT reference devices|
| Application Service Disruption    | ZERO                              | Backend is currently blocked anyway.    |
| Rollback Complexity               | MINIMAL                           | Reversible in a single transaction.     |
+-----------------------------------+-----------------------------------+-----------------------------------------+
```

**Final Recommendation:** Proceed with Step 1 and Step 2 as specified.
