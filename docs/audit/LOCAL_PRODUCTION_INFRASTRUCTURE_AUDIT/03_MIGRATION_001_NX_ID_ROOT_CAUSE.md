# NEXA Infrastructure Audit — 03: Migration 001 `nx_id` Root Cause Analysis

**Subsystem:** Database Migration Engine & DDL Execution  
**Migration Version:** 1 (`001_create_identity_schema`)  
**Error Log:** `ERROR: column "nx_id" does not exist (SQLSTATE 42703)`  
**Source Code:** `backend/internal/database/migrations.go:18-94`  

---

## 1. Forensic Anatomy of the Failure

### 1.1 The Complete Migration 1 SQL Payload
The SQL string defined in `migrations.go` executes as a single transactional batch via `tx.Exec(ctx, m.SQL)`:

```sql
CREATE TABLE IF NOT EXISTS accounts (
    id VARCHAR(64) PRIMARY KEY,
    nx_id VARCHAR(32) NOT NULL UNIQUE,
    public_key_hex VARCHAR(128) NOT NULL UNIQUE,
    dh_key_hex VARCHAR(128),
    phone_number VARCHAR(32),
    phone_verified BOOLEAN NOT NULL DEFAULT FALSE,
    is_discoverable_by_phone BOOLEAN NOT NULL DEFAULT TRUE,
    google_id VARCHAR(128),
    supabase_user_id VARCHAR(128),
    email VARCHAR(255),
    profile_name VARCHAR(128) NOT NULL DEFAULT '',
    avatar_url TEXT,
    bio VARCHAR(512),
    gender VARCHAR(32),
    age INT DEFAULT 0,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    CONSTRAINT chk_nx_id_format CHECK (nx_id ~ '^NX-[A-Z0-9]{8}$')
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_accounts_phone ON accounts(phone_number) 
    WHERE phone_number IS NOT NULL AND phone_number != '';
CREATE UNIQUE INDEX IF NOT EXISTS idx_accounts_google_id ON accounts(google_id) 
    WHERE google_id IS NOT NULL AND google_id != '';
CREATE UNIQUE INDEX IF NOT EXISTS idx_accounts_supabase_uid ON accounts(supabase_user_id) 
    WHERE supabase_user_id IS NOT NULL AND supabase_user_id != '';
CREATE INDEX IF NOT EXISTS idx_accounts_pubkey ON accounts(public_key_hex);

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

CREATE INDEX IF NOT EXISTS idx_devices_nx_id ON devices(nx_id);
CREATE INDEX IF NOT EXISTS idx_devices_account_id ON devices(account_id);
-- ... sessions and phone_verifications ...
```

---

## 2. PostgreSQL DDL Parser Behavior: Why `IF NOT EXISTS` Masks the Conflict

When PostgreSQL executes `CREATE TABLE IF NOT EXISTS <table_name> (<columns>)`:
1. It queries the system catalog (`pg_class` and `pg_namespace`).
2. If a table named `<table_name>` already exists in the search path:
   - PostgreSQL logs a `NOTICE: relation "<table_name>" already exists, skipping`.
   - **PostgreSQL completely ignores the column definitions inside the parentheses.**
   - It does **NOT** alter the table.
   - It does **NOT** add any missing columns.
   - It does **NOT** modify any column data types.
3. PostgreSQL then immediately proceeds to the next statement in the batch.

### 2.1 The Execution Trace on Supabase
```
[Statement 1] CREATE TABLE IF NOT EXISTS accounts (...) 
              -> Relation "accounts" does not exist. Created successfully.
[Statement 2] CREATE UNIQUE INDEX idx_accounts_phone ... -> Created.
[Statement 3] CREATE UNIQUE INDEX idx_accounts_google_id ... -> Created.
[Statement 4] CREATE UNIQUE INDEX idx_accounts_supabase_uid ... -> Created.
[Statement 5] CREATE INDEX idx_accounts_pubkey ... -> Created.
[Statement 6] CREATE TABLE IF NOT EXISTS devices (...)
              -> Relation "devices" ALREADY EXISTS (from 002_create_devices.sql).
              -> NOTICE: relation "devices" already exists, skipping.
              -> Columns 'account_id' and 'nx_id' are NOT added to 'devices'.
[Statement 7] CREATE INDEX IF NOT EXISTS idx_devices_nx_id ON devices(nx_id);
              -> PostgreSQL inspects catalog for column 'nx_id' on table 'devices'.
              -> Column 'nx_id' does NOT exist!
              -> ERROR: column "nx_id" does not exist (SQLSTATE 42703).
              -> BATCH TERMINATED. TRANSACTION ABORTED.
```

---

## 3. Transaction Isolation & Rollback Verification

In `backend/internal/database/migrations.go:252-270`:

```go
tx, err := pool.Begin(ctx)
if err != nil {
    return fmt.Errorf("failed to begin transaction for migration %d: %w", m.Version, err)
}

if _, err := tx.Exec(ctx, m.SQL); err != nil {
    _ = tx.Rollback(ctx)
    return fmt.Errorf("migration %d (%s) failed: %w", m.Version, m.Name, err)
}

recordSQL := `INSERT INTO schema_migrations (version, name) VALUES ($1, $2)`
if _, err := tx.Exec(ctx, recordSQL, m.Version, m.Name); err != nil {
    _ = tx.Rollback(ctx)
    return fmt.Errorf("failed to record migration %d: %w", m.Version, err)
}

if err := tx.Commit(ctx); err != nil {
    return fmt.Errorf("failed to commit migration %d: %w", m.Version, err)
}
```

### 3.1 Verification Findings
1. PostgreSQL fully supports **Transactional DDL** (`CREATE TABLE`, `CREATE INDEX`, `ALTER TABLE` inside `BEGIN ... COMMIT/ROLLBACK`).
2. When Statement 7 failed, `tx.Rollback(ctx)` was executed.
3. Therefore:
   - The newly created `accounts` table and its indexes were **completely rolled back**.
   - No row was inserted into `schema_migrations`.
   - The database state returned exactly to what it was before Migration 1 was attempted.
4. **Safety Confirmation:** The database is in a consistent, non-corrupted state. There are no half-applied migrations.

---

## 4. Impact on Subsequent Migrations

Until Migration 1 completes successfully:
- **Migration 2 (`002_create_connections_schema`)** is blocked. It requires foreign keys pointing to `accounts(nx_id)`.
- **Migration 3 (`003_create_prekeys_schema`)** is blocked. It creates `identity_key_bindings`, `signed_prekeys`, `one_time_prekeys`, and `prekey_claims`, which reference both `accounts(nx_id)` and `devices(device_id)`.
- Backend startup is blocked because `main.go` treats migration errors as fatal.
