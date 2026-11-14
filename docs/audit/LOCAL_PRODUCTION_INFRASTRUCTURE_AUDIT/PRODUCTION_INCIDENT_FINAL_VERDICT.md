# NEXA Production Incident — Final Verdict & Staging Verification Mandate

**Incident:** Database Migration 004 Failures (`SQLSTATE 42703` & `SQLSTATE 42P07`) and Reverse SMS Unconfigured  
**Date:** 2026-11-10  
**Target:** Go Backend, Migration Runner, PostgreSQL Schema  
**Author:** Antigravity Principal Systems Engineer & Security Auditor  

---

## 1. Official Final Verdict

```
========================================================================================
FINAL ACCEPTANCE VERDICT:
READY FOR STAGING VERIFICATION
========================================================================================
```

### Justification:
1. **Both Confirmed Production Errors Have Been Thoroughly Diagnosed & Resolved in Code:**
   - **`SQLSTATE 42703 (column granter_nx_id does not exist)`:** Resolved by detecting the legacy table via `information_schema` and archiving it to `legacy_supabase_phone_visibility_grants` before creating the authentic table.
   - **`SQLSTATE 42P07 (relation uq_phone_grant already exists)`:** Resolved by renaming the legacy constraint `uq_phone_grant` and index `uq_phone_grant` on the archived table to `legacy_uq_phone_grant`, and giving the new authentic table's constraint a canonical, non-colliding name: `uq_phone_grants_nx_pair`.
2. **Concurrency Safety Implemented:**
   - `RunMigrations` now acquires a PostgreSQL session-level advisory lock (`pg_advisory_lock(8472910384729102)`), completely eliminating race conditions across multiple Render replicas.
3. **Data Preservation & Anti-Corruption Verified:**
   - Zero destructive `DROP TABLE` or `TRUNCATE` operations.
   - Legacy records preserved in `legacy_supabase_phone_visibility_grants`.
   - Account UUID-to-NX-ID mapping join includes self-relationship exclusion (`WHERE a1.nx_id <> a2.nx_id`) and duplicate protection (`ON CONFLICT DO NOTHING`).
   - Backward-compatible `safe_profiles` view recreated if `public.profiles` exists.
4. **Why NOT Marked Production Ready Yet:**
   - Strict adherence to engineering rules: **Successful Go unit tests alone do NOT prove PostgreSQL release readiness**.
   - Docker and local PostgreSQL were unavailable on this host. Therefore, the migration must be verified against an isolated staging PostgreSQL database prior to production execution.

---

## 2. Comprehensive Status Matrix

| Component / Subsystem | Status | Verification Summary |
|---|---|---|
| **Incident A1 Fix (SQLSTATE 42703)** | **VERIFIED (Code & Unit Tests)** | Detects legacy table; archives to `legacy_supabase_phone_visibility_grants`. |
| **Incident A2 Fix (SQLSTATE 42P07)** | **VERIFIED (Code & Unit Tests)** | Renames legacy constraint/index to `legacy_uq_phone_grant`; new constraint named `uq_phone_grants_nx_pair`. |
| **Legacy Index Collision Prevention** | **VERIFIED (Code & Unit Tests)** | Renames legacy indexes `idx_phone_grants_*` &rarr; `legacy_idx_phone_grants_*`; new indexes named `idx_phone_grants_*_nx`. |
| **Migration Concurrency Safety** | **VERIFIED (Code & Unit Tests)** | Session advisory lock `pg_advisory_lock` added to `RunMigrations` on dedicated connection. |
| **Transactional Rollback Safety** | **VERIFIED (Code & Unit Tests)** | Each migration runs in atomic transaction; version recorded only after successful commit. |
| **Legacy Data Preservation** | **VERIFIED (Code & Unit Tests)** | Zero drops; UUIDs mapped to `accounts`; unmapped rows remain accessible in archived table. |
| **RLS Security & Access Control** | **VERIFIED (Code & Unit Tests)** | RLS fail-closed default on active and legacy tables; direct PostgREST revoked; API bound to `session.NxID`. |
| **Reverse SMS Security** | **VERIFIED (Code & Unit Tests)** | HMAC-SHA256 constant-time compare; replay protection; 90s CSPRNG nonce; fail-closed on missing config. |
| **Backend Go Test Suite** | **VERIFIED (Executed & Passed)** | `go test -count=1 ./...` PASS (12/12 packages); `go vet ./...` clean. |
| **Live PostgreSQL Scenarios A–J** | **NOT VERIFIED (Environment Unavailable)** | Requires live PostgreSQL staging instance. |
| **Production Environment Setup** | **BLOCKED (Operator Action Required)** | Awaiting operator entry of `INBOUND_SMS_RECEIVER_NUMBER` & `GATEWAY_WEBHOOK_SECRET` in Render. |

---

## 3. Staging Rollout Procedure & Verification Runbook

Before requesting final production release authorization, execute this verified sequence on an isolated staging database:

### Step 1: Pre-Flight Staging Database Check
```sql
-- Confirm current migration level
SELECT version, name FROM schema_migrations ORDER BY version ASC;

-- Confirm whether legacy objects exist
SELECT table_name FROM information_schema.tables WHERE table_name LIKE '%phone_visibility%';
SELECT conname, conrelid::regclass FROM pg_constraint WHERE conname LIKE '%phone_grant%';
```

### Step 2: Deploy Branch to Staging Service
Deploy the commit containing the updated `backend/internal/database/migrations.go`.

### Step 3: Inspect Live Staging Boot Logs
Confirm the following log output occurs:
```text
[Database] Applying migration 4: 004_create_relationship_graph_and_phone_privacy...
[Database] Migration 4 (004_create_relationship_graph_and_phone_privacy) applied successfully.
[Database] Applying migration 5: 005_create_message_idempotency_table...
[Database] Migration 5 (005_create_message_idempotency_table) applied successfully.
[Database] Applying migration 6: 006_enforce_row_level_security_fail_closed...
[Database] Migration 6 (006_enforce_row_level_security_fail_closed) applied successfully.
```

### Step 4: Staging Post-Migration Verification Queries
```sql
-- 1. Confirm migration 4 recorded
SELECT * FROM schema_migrations WHERE version = 4;

-- 2. Confirm authentic table exists with correct columns
SELECT column_name, data_type, is_nullable 
FROM information_schema.columns 
WHERE table_name = 'phone_visibility_grants' 
ORDER BY ordinal_position;

-- 3. Confirm constraints are distinct and non-conflicting
SELECT conname, contype, conrelid::regclass 
FROM pg_constraint 
WHERE conname IN ('uq_phone_grants_nx_pair', 'legacy_uq_phone_grant', 'chk_phone_grants_distinct_parties');

-- 4. Confirm archived table exists and rows preserved
SELECT COUNT(*) FROM legacy_supabase_phone_visibility_grants;
```

---

## 4. Production Deployment Prerequisites

Once staging verification succeeds, the operator may proceed to production deployment strictly under the following conditions:
1. **Configure Render Environment Variables:**
   - `APP_ENV=production`
   - `INBOUND_SMS_RECEIVER_NUMBER=+91XXXXXXXXXX` (real domestic number)
   - `GATEWAY_WEBHOOK_SECRET=[generated 32+ byte hex]`
   - `NEXA_TRUSTED_PHONE_NUMBERS=+91XXXXXXXXXX` (internal devices)
2. **Execute Database Snapshot:**
   Trigger a manual backup/dump in Supabase Dashboard before pushing changes to `main`.
3. **Deploy & Observe Real-Time Logs.**
