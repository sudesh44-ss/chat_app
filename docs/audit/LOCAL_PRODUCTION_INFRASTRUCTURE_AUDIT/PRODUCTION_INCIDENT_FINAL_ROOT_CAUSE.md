# NEXA Production Incident — Final Root Cause Analysis (SQLSTATE 42703 & 42P07)

**Incident Classification:** Critical Database Migration Failure (Blocker)  
**Target:** Go Backend Migration Runner & PostgreSQL Schema  
**Date:** 2026-11-10  
**Status:** Root Cause Confirmed & Fully Diagnosed  

---

## 1. Incident Overview & Reported Errors

During production deployments on Render connected to Supabase PostgreSQL, the backend server failed to boot with two consecutive migration errors during Migration 4 (`004_create_relationship_graph_and_phone_privacy`):

### First Production Error:
```text
migration 4 (004_create_relationship_graph_and_phone_privacy) failed:
ERROR: column "granter_nx_id" does not exist
SQLSTATE 42703
```

### Second Production Error (After Initial Remediation Attempt):
```text
migration 4 (004_create_relationship_graph_and_phone_privacy) failed:
ERROR: relation "uq_phone_grant" already exists
SQLSTATE 42P07
```

---

## 2. Deep Technical Root Cause: First Error (SQLSTATE 42703)

### 2.1 Schema Namespace Collision
In legacy Supabase migration `database/migrations/016_create_phone_visibility_grants.sql`, a table named `public.phone_visibility_grants` had already been created in PostgreSQL with UUID columns referencing `public.profiles`:
```sql
CREATE TABLE IF NOT EXISTS public.phone_visibility_grants (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    granter_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    grantee_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    granted_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_phone_grant UNIQUE(granter_id, grantee_id),
    CONSTRAINT chk_different_parties CHECK (granter_id <> grantee_id)
);
```

In the Go backend Migration 4 (`004_create_relationship_graph_and_phone_privacy`), the DDL executed:
```sql
CREATE TABLE IF NOT EXISTS phone_visibility_grants (
    id VARCHAR(64) PRIMARY KEY,
    granter_nx_id VARCHAR(32) NOT NULL REFERENCES accounts(nx_id) ON DELETE CASCADE,
    grantee_nx_id VARCHAR(32) NOT NULL REFERENCES accounts(nx_id) ON DELETE CASCADE,
    ...
);
CREATE INDEX IF NOT EXISTS idx_phone_grants_granter ON phone_visibility_grants(granter_nx_id, is_active);
```

### 2.2 Why SQLSTATE 42703 Occurred
Because `public.phone_visibility_grants` already existed in PostgreSQL, `CREATE TABLE IF NOT EXISTS` silently skipped table creation. When the subsequent statement `CREATE INDEX ... ON phone_visibility_grants(granter_nx_id, is_active)` ran, PostgreSQL looked for column `granter_nx_id` on the pre-existing legacy table. Because the legacy table only contained `granter_id` (UUID) and not `granter_nx_id` (VARCHAR), PostgreSQL halted with `SQLSTATE 42703` (`column "granter_nx_id" does not exist`).

---

## 3. Deep Technical Root Cause: Second Error (SQLSTATE 42P07)

### 3.1 The Initial Remediation Attempt
In the first attempt to fix Incident A, Migration 4 was updated to detect the legacy table and rename it:
```sql
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_schema = 'public' 
          AND table_name = 'phone_visibility_grants' 
          AND column_name = 'granter_id'
    ) THEN
        DROP VIEW IF EXISTS public.safe_profiles CASCADE;
        DROP FUNCTION IF EXISTS public.toggle_phone_visibility() CASCADE;
        ALTER TABLE public.phone_visibility_grants RENAME TO legacy_supabase_phone_visibility_grants;
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS phone_visibility_grants (
    id VARCHAR(64) PRIMARY KEY DEFAULT gen_random_uuid()::text,
    granter_nx_id VARCHAR(32) NOT NULL REFERENCES accounts(nx_id) ON DELETE CASCADE,
    grantee_nx_id VARCHAR(32) NOT NULL REFERENCES accounts(nx_id) ON DELETE CASCADE,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    CONSTRAINT uq_phone_grant UNIQUE(granter_nx_id, grantee_nx_id),
    CONSTRAINT chk_different_parties CHECK (granter_nx_id <> grantee_nx_id)
);
```

### 3.2 PostgreSQL Internal Catalog Architecture & Why SQLSTATE 42P07 Occurred
In PostgreSQL:
1. When a table has a `CONSTRAINT ... UNIQUE(...)` clause, PostgreSQL creates an underlying unique index with the same name as the constraint.
2. In legacy migration 016, `CONSTRAINT uq_phone_grant UNIQUE(granter_id, grantee_id)` created an index named `uq_phone_grant` in schema `public` attached to `phone_visibility_grants`.
3. When `ALTER TABLE public.phone_visibility_grants RENAME TO legacy_supabase_phone_visibility_grants;` executed:
   - **PostgreSQL renames the table, but DOES NOT rename its constraints or underlying indexes!**
   - The unique constraint `uq_phone_grant` and the unique index `uq_phone_grant` remained named `uq_phone_grant` in `pg_constraint` and `pg_class`, attached to `legacy_supabase_phone_visibility_grants`!
4. In PostgreSQL, index names share a single flat relation namespace with tables, views, and sequences within a schema (`pg_class.relname`).
5. When the new table executed:
   ```sql
   CREATE TABLE IF NOT EXISTS phone_visibility_grants (
       ...
       CONSTRAINT uq_phone_grant UNIQUE(granter_nx_id, grantee_nx_id),
       ...
   );
   ```
   PostgreSQL attempted to create an index in schema `public` named `uq_phone_grant`.
6. Because an index/relation named `uq_phone_grant` already existed in `pg_class`, PostgreSQL immediately threw:
   ```text
   ERROR: relation "uq_phone_grant" already exists (SQLSTATE 42P07)
   ```
7. Furthermore, the legacy table also retained:
   - `idx_phone_grants_granter`
   - `idx_phone_grants_grantee`
   - `chk_different_parties`
   Any subsequent attempt to create indexes with those exact names would either be silently bypassed by `IF NOT EXISTS` (leaving the new table without indexes) or trigger relation collisions.

---

## 4. Root Cause Summary Matrix

| Error Code | Failed Operation | Triggering Object | Catalog Conflict |
|---|---|---|---|
| **SQLSTATE 42703** | `CREATE INDEX ... (granter_nx_id)` | Pre-existing table `phone_visibility_grants` | Table skipped creation due to name collision; column `granter_nx_id` was absent on legacy table. |
| **SQLSTATE 42P07** | `CREATE TABLE ... CONSTRAINT uq_phone_grant UNIQUE` | Renamed table `legacy_supabase_phone_visibility_grants` | Renaming table did not rename constraint or backing index `uq_phone_grant`; relation name collision in schema `public`. |

---

## 5. Architectural Remediation Strategy

To resolve both errors permanently without data loss:
1. **Archive Legacy Table:** Rename `phone_visibility_grants` to `legacy_supabase_phone_visibility_grants`.
2. **Rename Legacy Constraints and Indexes:** Atomically rename `uq_phone_grant` &rarr; `legacy_uq_phone_grant`, `chk_different_parties` &rarr; `legacy_chk_different_parties`, and indexes `idx_phone_grants_*` &rarr; `legacy_idx_phone_grants_*`.
3. **Canonical Naming on New Table:** Define new unique constraint as `uq_phone_grants_nx_pair` and new indexes as `idx_phone_grants_granter_nx` and `idx_phone_grants_grantee_nx`.
4. **Data Mapping with Self-Relationship Exclusion:** Join legacy UUIDs to accounts with `WHERE a1.nx_id <> a2.nx_id` and `ON CONFLICT (granter_nx_id, grantee_nx_id) DO NOTHING`.
5. **Session Advisory Locking:** Wrap `RunMigrations` in `pg_advisory_lock` to serialize execution across multi-instance Render deployments.
