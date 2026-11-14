# NEXA — PostgreSQL Migration 001 Failure Investigation Report

**Audit Mode:** STRICT READ-ONLY FORENSIC INSPECTION  
**Subsystem:** PostgreSQL Database Persistence & Migration Runner  
**Target Migration:** `001_create_identity_schema` (`backend/internal/database/migrations.go`)  
**Database Host:** Supabase PostgreSQL (`db.kiktbhwmwptzqtkrdcvb.supabase.co`)  
**Date:** November 2026  

---

## 1. Executive Summary & Incident Overview

During local backend startup connecting to Supabase PostgreSQL, the application aborts immediately with a critical migration failure:

```text
2026/11/07 15:30:12 [Database] Connected to PostgreSQL at postgres://postgres:xxxxx@db.kiktbhwmwptzqtkrdcvb.supabase.co:5432/postgres?sslmode=require
2026/11/07 15:30:12 [Database] Applying migration 1: 001_create_identity_schema...
2026/11/07 15:30:13 [Server] CRITICAL: Failed to apply database migrations: migration 1 (001_create_identity_schema) failed: ERROR: column "nx_id" does not exist (SQLSTATE 42703)
exit status 1
```

### Forensic Verdict
- **Root Cause:** **Dual-Schema Collision & Schema Drift**. The target Supabase database was previously initialized using the legacy declarative SQL migrations located in `database/migrations/` (specifically `002_create_devices.sql`), where table `public.devices` was defined with Supabase auth column `user_id UUID REFERENCES public.profiles(id)` without `nx_id` or `account_id`.
- When Go's embedded migration `001_create_identity_schema` (`backend/internal/database/migrations.go`) executed `CREATE TABLE IF NOT EXISTS devices (...)`, PostgreSQL skipped creation because relation `devices` already existed. The immediately following statement `CREATE INDEX IF NOT EXISTS idx_devices_nx_id ON devices(nx_id)` evaluated the existing `devices` table, which lacks `nx_id`, triggering PostgreSQL error `SQLSTATE 42703` (`undefined_column`).
- **Current Database State:** **CLEAN TRANSACTIONAL ROLLBACK**. Because Go's migration runner wraps each migration in `tx, err := pool.Begin(ctx)` and calls `tx.Rollback(ctx)` on error, zero partial tables or inconsistent states from Migration 1 were committed. The database `schema_migrations` table contains no record for Version 1.
- **Remediation Classification:** **REQUIRES NON-DESTRUCTIVE SCHEMA FIX / LEGACY TABLE RESOLUTION**.

---

## 2. Code Inspection & Architecture Comparison

### 2.1 The Two Divergent Migration Systems in NEXA

The codebase contains two parallel migration mechanisms created at different stages of project development:

```
H:\chat_app\
├── database\migrations\                     <-- Legacy Supabase Declarative Migrations (Created Nov 4-5)
│   ├── 001_create_profiles.sql             (Tables: public.profiles linked to auth.users)
│   ├── 002_create_devices.sql              (Tables: public.devices linked to public.profiles)
│   ├── 003_create_nx_identity.sql          (Tables: public.nx_identities)
│   ├── 004_create_phone_verifications.sql  (Tables: public.phone_verifications with hashed_otp, salt)
│   └── 007_create_prekeys.sql              (Tables: public.prekeys)
│
└── backend\internal\database\migrations.go  <-- Go Embedded Production Migrations (Created Nov 6-7)
    ├── Version 1: 001_create_identity_schema (accounts, devices, sessions, phone_verifications)
    ├── Version 2: 002_create_connections_schema (connection_requests, connections, user_blocks)
    └── Version 3: 003_create_prekeys_schema (identity_key_bindings, signed_prekeys, one_time_prekeys, prekey_claims)
```

### 2.2 Comparison of Table `devices` Definitions

| Feature | Legacy Supabase SQL (`database/migrations/002_create_devices.sql`) | Go Embedded Migration (`backend/internal/database/migrations.go`) |
| :--- | :--- | :--- |
| **Primary Key** | `device_id VARCHAR(64)` | `device_id VARCHAR(128)` |
| **Account Reference** | `user_id UUID REFERENCES public.profiles(id)` | `account_id VARCHAR(64) REFERENCES accounts(id)` |
| **NX-ID Column** | **DOES NOT EXIST** | `nx_id VARCHAR(32) NOT NULL REFERENCES accounts(nx_id)` |
| **Public Key Column** | `public_key_hex VARCHAR(64)` | `public_key_hex VARCHAR(128)` |
| **DH Key Column** | `dh_public_key_hex VARCHAR(64)` | `dh_key_hex VARCHAR(128)` |
| **Timestamp Format** | `TIMESTAMPTZ` | `BIGINT` (epoch millis/seconds) |
| **Associated Index** | None on `nx_id` | `idx_devices_nx_id ON devices(nx_id)` |

---

## 3. Step-by-Step Failure Anatomy

The Go backend runner in `backend/internal/database/migrations.go` executes the following sequence:

```go
// backend/internal/database/migrations.go:252-260
tx, err := pool.Begin(ctx)
if err != nil {
    return fmt.Errorf("failed to begin transaction for migration %d: %w", m.Version, err)
}

if _, err := tx.Exec(ctx, m.SQL); err != nil {
    _ = tx.Rollback(ctx)
    return fmt.Errorf("migration %d (%s) failed: %w", m.Version, m.Name, err)
}
```

The string `m.SQL` for Migration 1 contains multiple DDL statements separated by semicolons:

```sql
-- Step 1: Create accounts table
CREATE TABLE IF NOT EXISTS accounts (
    id VARCHAR(64) PRIMARY KEY,
    nx_id VARCHAR(32) NOT NULL UNIQUE,
    ...
);
--> Succeeded (or skipped if existed).

-- Step 2: Create indexes on accounts
CREATE UNIQUE INDEX IF NOT EXISTS idx_accounts_phone ON accounts(phone_number)...;
CREATE UNIQUE INDEX IF NOT EXISTS idx_accounts_google_id ON accounts(google_id)...;
CREATE UNIQUE INDEX IF NOT EXISTS idx_accounts_supabase_uid ON accounts(supabase_user_id)...;
CREATE INDEX IF NOT EXISTS idx_accounts_pubkey ON accounts(public_key_hex);
--> Succeeded.

-- Step 3: Create devices table
CREATE TABLE IF NOT EXISTS devices (
    device_id VARCHAR(128) PRIMARY KEY,
    account_id VARCHAR(64) NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    nx_id VARCHAR(32) NOT NULL REFERENCES accounts(nx_id) ON DELETE CASCADE,
    device_name VARCHAR(128) NOT NULL DEFAULT '',
    public_key_hex VARCHAR(128) NOT NULL,
    dh_key_hex VARCHAR(128),
    fcm_token TEXT,
    is_revoked BOOLEAN NOT NULL DEFAULT FALSE,
    created_at BIGINT NOT NULL,
    last_active_at BIGINT NOT NULL
);
--> PostgreSQL checks table catalog. Relation "devices" ALREADY EXISTS (created by 002_create_devices.sql).
--> PostgreSQL issues NOTICE: relation "devices" already exists, skipping.
--> CRITICAL: The columns account_id and nx_id ARE NOT ADDED.

-- Step 4: Create index on devices(nx_id)
CREATE INDEX IF NOT EXISTS idx_devices_nx_id ON devices(nx_id);
--> PostgreSQL attempts to build index on column "nx_id" of relation "devices".
--> Column "nx_id" does not exist in the pre-existing "devices" table!
--> PostgreSQL aborts execution with:
--> ERROR: column "nx_id" does not exist (SQLSTATE 42703)
```

Because `tx.Exec` failed, `_ = tx.Rollback(ctx)` was immediately triggered.

---

## 4. Secondary Pre-Existing Collisions Awaiting Resolution

If only `devices` is altered without addressing the other tables in `database/migrations/`, Migration 1 will encounter further schema collisions:

### 4.1 Collision on `phone_verifications`
- In `database/migrations/004_create_phone_verifications.sql`:
  `phone_verifications` has columns: `id`, `phone_number`, `hashed_otp`, `salt`, `attempts`, `max_attempts`, `last_sent_at`, `expires_at`, `is_consumed`, `created_at`.
- In `backend/internal/database/migrations.go:82-92`:
  `phone_verifications` requires: `id BIGSERIAL PRIMARY KEY`, `phone_number VARCHAR(32)`, `nonce VARCHAR(64) NOT NULL UNIQUE`, `session_id VARCHAR(128)`, `verified BOOLEAN`, `expires_at BIGINT`, `created_at BIGINT`.
- Statement at line 92: `CREATE INDEX IF NOT EXISTS idx_phone_verifications_nonce ON phone_verifications(nonce);`
- **Result:** Will fail with `ERROR: column "nonce" does not exist (SQLSTATE 42703)`.

---

## 5. Non-Destructive Remediation Strategy

To preserve data while allowing Go's embedded migrations to succeed, execute the following SQL in the **Supabase Dashboard SQL Editor** before restarting the backend.

### Strategy Choice: Legacy Table Migration vs Clean Schema
- **If the database is purely development/staging with no production users:**
  The cleanest and safest path is to archive or rename the legacy Supabase tables:

```sql
-- Safe Archival / Rename Script
BEGIN;

-- 1. Rename conflicting legacy tables to preserve backup data
ALTER TABLE IF EXISTS public.devices RENAME TO legacy_supabase_devices;
ALTER TABLE IF EXISTS public.phone_verifications RENAME TO legacy_supabase_phone_verifications;
ALTER TABLE IF EXISTS public.prekeys RENAME TO legacy_supabase_prekeys;

-- 2. Drop legacy foreign key constraints that might block clean creation
ALTER TABLE IF EXISTS public.legacy_supabase_devices 
    DROP CONSTRAINT IF EXISTS devices_user_id_fkey;

COMMIT;
```

- **If existing device records must be retained in `public.devices`:**
  Apply non-destructive column additions and type harmonizations:

```sql
BEGIN;

-- Add missing columns expected by Go PostgresRepository
ALTER TABLE public.devices 
    ADD COLUMN IF NOT EXISTS account_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS nx_id VARCHAR(32),
    ADD COLUMN IF NOT EXISTS dh_key_hex VARCHAR(128);

-- Add missing columns to phone_verifications
ALTER TABLE public.phone_verifications 
    ADD COLUMN IF NOT EXISTS nonce VARCHAR(64),
    ADD COLUMN IF NOT EXISTS session_id VARCHAR(128),
    ADD COLUMN IF NOT EXISTS verified BOOLEAN NOT NULL DEFAULT FALSE;

-- Ensure foreign key constraint can be created once accounts table is populated
-- (Can be added after Migration 1 creates accounts)

COMMIT;
```

---

## 6. Verification and Validation Procedure

After applying the remediation script in Supabase:

1. **Verify table status via Supabase SQL Editor:**
   ```sql
   SELECT table_name, column_name, data_type 
   FROM information_schema.columns 
   WHERE table_name IN ('accounts', 'devices', 'phone_verifications', 'schema_migrations')
   ORDER BY table_name, ordinal_position;
   ```

2. **Run local backend migration test:**
   ```powershell
   Set-Location H:\chat_app\backend
   go test -v ./internal/database/...
   ```

3. **Start local backend server with `DATABASE_URL`:**
   Observe logs to confirm successful migration execution:
   ```text
   [Database] Connected to PostgreSQL at postgres://postgres:xxxxx@...
   [Database] Applying migration 1: 001_create_identity_schema...
   [Database] Migration 1 (001_create_identity_schema) applied successfully.
   [Database] Applying migration 2: 002_create_connections_schema...
   [Database] Migration 2 (002_create_connections_schema) applied successfully.
   [Database] Applying migration 3: 003_create_prekeys_schema...
   [Database] Migration 3 (003_create_prekeys_schema) applied successfully.
   ```

4. **Verify `schema_migrations` records:**
   ```sql
   SELECT * FROM schema_migrations ORDER BY version ASC;
   ```
   Expected output:
   - Version 1: `001_create_identity_schema`
   - Version 2: `002_create_connections_schema`
   - Version 3: `003_create_prekeys_schema`
