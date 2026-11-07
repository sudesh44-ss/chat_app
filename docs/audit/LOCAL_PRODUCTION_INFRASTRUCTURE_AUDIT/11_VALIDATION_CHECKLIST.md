# NEXA Infrastructure Audit — 11: Validation Checklist

**Subsystem:** Post-Remediation Verification & Quality Assurance  
**Environments:** Local Development & Render Production  

---

## 1. Local Development Validation Checklist

Execute each verification step in sequence:

- [ ] **Step 1: Verify Go Database Package Unit Tests**
  ```powershell
  Set-Location H:\chat_app\backend
  go test -v ./internal/database/...
  ```
  *Expected Output:* `PASS: TestSanitizeDatabaseURL`, `PASS: TestDefaultConfig`, `PASS: TestMigrationSchemaIntegrity`.

- [ ] **Step 2: Start Backend Server against Supabase**
  ```powershell
  Set-Location H:\chat_app\backend
  go run ./cmd/server
  ```
  *Expected Output:*
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

- [ ] **Step 3: Verify Health Endpoint**
  ```powershell
  Invoke-RestMethod -Uri "http://localhost:8080/health"
  ```
  *Expected Output:*
  ```json
  {
    "status": "healthy",
    "database": "CONNECTED",
    "production": false
  }
  ```

---

## 2. PostgreSQL Schema Verification Queries

Execute in **Supabase SQL Editor**:

- [ ] **Query 1: Verify Schema Migrations Table**
  ```sql
  SELECT version, name, applied_at FROM schema_migrations ORDER BY version ASC;
  ```
  *Expected Rows:*
  - Version 1: `001_create_identity_schema`
  - Version 2: `002_create_connections_schema`
  - Version 3: `003_create_prekeys_schema`

- [ ] **Query 2: Verify `devices` Table Structure**
  ```sql
  SELECT column_name, data_type, is_nullable 
  FROM information_schema.columns 
  WHERE table_name = 'devices'
  ORDER BY ordinal_position;
  ```
  *Expected Columns:* `device_id`, `account_id`, `nx_id`, `device_name`, `public_key_hex`, `dh_key_hex`, `fcm_token`, `is_revoked`, `created_at`, `last_active_at`.

- [ ] **Query 3: Verify `prekey_claims` and OPK Tables**
  ```sql
  SELECT table_name FROM information_schema.tables 
  WHERE table_schema = 'public' 
  AND table_name IN ('identity_key_bindings', 'signed_prekeys', 'one_time_prekeys', 'prekey_claims');
  ```
  *Expected Count:* 4 tables.

---

## 3. Render Production Validation Checklist

- [ ] **Step 1: Check Render Deployment Logs**
  Confirm absence of:
  - `Neither REDIS_URL nor a remote REDIS_ADDR is configured in production!`
  - `database ping failed: ... connect: network is unreachable`
  - `CRITICAL: Failed to apply database migrations`
  Confirm presence of:
  - `[Database] Connected to PostgreSQL at postgres://postgres.kikt...:xxxxx@aws-0-...pooler.supabase.com:5432`
  - `[Queue] Ephemeral Redis Queue Buffer initialized successfully.`
  - `[Server] NEXA Wire server listening on port 10000`

- [ ] **Step 2: External Health Check Probe**
  ```powershell
  Invoke-RestMethod -Uri "https://chat-app-backend-xxxx.onrender.com/health"
  ```
  *Expected Output:*
  ```json
  {
    "status": "healthy",
    "database": "CONNECTED",
    "buffer": true,
    "production": true
  }
  ```
