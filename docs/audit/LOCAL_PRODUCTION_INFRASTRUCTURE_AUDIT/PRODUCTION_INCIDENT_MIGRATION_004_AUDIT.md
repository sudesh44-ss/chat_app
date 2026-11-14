# Production Incident Audit: Migration 004 Failure (`granter_nx_id`)

## 1. Incident Overview & Log Signature

```text
2026/11/10 10:32:57 [Server] CRITICAL:
Failed to apply database migrations:
migration 4 (004_create_relationship_graph_and_phone_privacy) failed:
ERROR: column "granter_nx_id" does not exist (SQLSTATE 42703)
```

- **Timestamp:** 2026-11-10 10:32:57 UTC
- **Severity:** **P0 (Total Production Outage Blocker)**
- **Subsystem:** PostgreSQL Database Persistence & Migration Engine (`backend/internal/database/migrations.go`)
- **Impact:** Server crashes immediately on boot via `log.Fatalf` in [`backend/cmd/server/main.go:101`](file:///H:/chat_app/backend/cmd/server/main.go#L101). Zero HTTP or WebSocket endpoints can serve traffic.

---

## 2. Forensic Investigation & Code Trace

### 2.1 The Failing Migration Definition
In [`backend/internal/database/migrations.go:224-243`](file:///H:/chat_app/backend/internal/database/migrations.go#L224-L243):
```sql
ALTER TABLE connections ADD COLUMN IF NOT EXISTS source VARCHAR(32) NOT NULL DEFAULT 'NEXA_ID';

CREATE TABLE IF NOT EXISTS phone_visibility_grants (
    id VARCHAR(64) PRIMARY KEY,
    granter_nx_id VARCHAR(32) NOT NULL REFERENCES accounts(nx_id) ON DELETE CASCADE,
    grantee_nx_id VARCHAR(32) NOT NULL REFERENCES accounts(nx_id) ON DELETE CASCADE,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    CONSTRAINT uq_phone_grant UNIQUE(granter_nx_id, grantee_nx_id),
    CONSTRAINT chk_different_parties CHECK (granter_nx_id <> grantee_nx_id)
);

CREATE INDEX IF NOT EXISTS idx_phone_grants_granter ON phone_visibility_grants(granter_nx_id, is_active);
CREATE INDEX IF NOT EXISTS idx_phone_grants_grantee ON phone_visibility_grants(grantee_nx_id, is_active);
```

### 2.2 The Pre-Existing Production Collision
In the legacy Supabase database, migration [`database/migrations/016_create_phone_visibility_grants.sql:9-19`](file:///H:/chat_app/database/migrations/016_create_phone_visibility_grants.sql#L9-L19) had previously created:
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

### 2.3 The Execution Sequence Triggering the Crash
1. When Migration 4 runs against a production database that was previously initialized with Supabase migrations, PostgreSQL encounters:
   `CREATE TABLE IF NOT EXISTS phone_visibility_grants (...)`
2. Because `public.phone_visibility_grants` already exists, PostgreSQL **skips** the `CREATE TABLE` statement without making any modifications.
3. The existing table contains columns: `id`, `granter_id`, `grantee_id`, `is_active`, `granted_at`, `updated_at`. **It does not contain `granter_nx_id` or `grantee_nx_id`**.
4. PostgreSQL then advances to the subsequent statement in the same migration block:
   `CREATE INDEX IF NOT EXISTS idx_phone_grants_granter ON phone_visibility_grants(granter_nx_id, is_active);`
5. PostgreSQL queries table metadata for column `granter_nx_id` on `phone_visibility_grants`.
6. Finding no such column, the query engine immediately raises:
   `ERROR: column "granter_nx_id" does not exist (SQLSTATE 42703)`.

---

## 3. Detailed Answers to Audit Questions

| Audit Question | Independent Finding |
|---|---|
| **1. Which table is expected to contain this column?** | `public.phone_visibility_grants`. |
| **2. Which migration should create it?** | Go embedded Migration 4 (`004_create_relationship_graph_and_phone_privacy`) in `migrations.go:224`. |
| **3. Does that migration actually create it?** | **Only on an empty database.** On an existing database containing the legacy 016 table, `CREATE TABLE IF NOT EXISTS` is a no-op, skipping column creation. |
| **4. Is the column name consistent across Go code?** | Yes. [`backend/internal/connections/postgres_repository.go:121`](file:///H:/chat_app/backend/internal/connections/postgres_repository.go#L121) and [`backend/internal/connections/model.go:22`](file:///H:/chat_app/backend/internal/connections/model.go#L22) consistently use `granter_nx_id` and `grantee_nx_id`. The collision is strictly against legacy Supabase 016 (`granter_id`). |
| **5. Does migration 004 reference the correct table?** | Yes, but it collides with an unarchived legacy table sharing the identical name. |
| **6. Is there a foreign-key dependency?** | Yes. In the legacy table: `granter_id REFERENCES public.profiles(id)`. In the new Go schema: `granter_nx_id REFERENCES public.accounts(nx_id)`. Also, legacy view `public.safe_profiles` references the legacy table. |
| **7. Is migration ordering correct?** | Yes, it is ordered as Version 4, following Version 1 (`accounts`), Version 2 (`connections`), and Version 3 (`prekeys`). |
| **8. Does the statement reference a column before creating it?** | Yes, because `CREATE TABLE IF NOT EXISTS` failed to add the columns to the pre-existing table before `CREATE INDEX` attempted to index them. |
| **9. Does the migration assume a schema that does not exist?** | Yes, it assumed a greenfield database where `phone_visibility_grants` had never been defined. |
| **10. Could an earlier migration have failed or been skipped?** | Migrations 1, 2, and 3 succeeded. However, the pre-migration reconciliation plan to rename legacy Supabase tables (`ALTER TABLE phone_visibility_grants RENAME TO legacy_supabase_phone_visibility_grants`) was not executed before adding Migration 4. |

---

## 4. Transactional Safety & Database State

- **Transaction Wrapping:** In [`backend/internal/database/migrations.go:322-330`](file:///H:/chat_app/backend/internal/database/migrations.go#L322-L330):
  ```go
  tx, err := pool.Begin(ctx)
  if err != nil { return ... }
  if _, err := tx.Exec(ctx, m.SQL); err != nil {
      _ = tx.Rollback(ctx)
      return fmt.Errorf("migration %d (%s) failed: %w", m.Version, m.Name, err)
  }
  ```
- **Integrity Guarantee:**
  Because `tx.Rollback(ctx)` executes upon error:
  1. Migration 4 is **NOT** recorded in `schema_migrations`.
  2. The `ALTER TABLE connections ADD COLUMN source` statement was rolled back.
  3. The production database is cleanly sitting at Version 3.
  4. There are **zero partial schema artifacts or corrupted rows** left behind.
  5. Subsequent retries are completely safe once the table collision is resolved.

---

## 5. Functionality and Production Impact

- **Server Availability:** **OFFLINE / CRASH LOOP**. `main.go:101` calls `log.Fatalf`, crashing the container upon startup.
- **Relationship Graph & Connections:** Unavailable.
- **Phone Privacy Grants:** Unavailable.
- **Data Loss Risk:** **ZERO**. No production data was corrupted or dropped.
