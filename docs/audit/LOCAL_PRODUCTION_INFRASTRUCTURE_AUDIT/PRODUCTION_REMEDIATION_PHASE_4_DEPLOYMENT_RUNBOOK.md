# Production Incident Remediation — Phase 4: Staged Deployment & Rollback Runbook

**Status:** AWAITING EXPLICIT AUTHORIZATION (NO PRODUCTION ACTIONS EXECUTED)  
**Date:** 2026-11-10  
**Target:** Render Production Environment & Supabase Database  
**Classification:** Phase 4 Deployment Runbook  

---

> [!CAUTION]
> **STRICT SAFETY NOTICE:**
> In accordance with project safety guidelines, this runbook details the verified step-by-step procedure for rolling out the remediation to production. **NO production migrations, environment updates, or code deployments have been performed.** Execution of this runbook must only take place after explicit user approval.

---

## 1. Pre-Deployment Preparation Checklist

Before initiating production deployment, confirm that each of the following pre-flight requirements is satisfied:

- [ ] **Git Working Tree:** Clean status on `main` branch with all tests passing locally.
- [ ] **Database Backup:** Create a full snapshot/dump of the Supabase PostgreSQL database:
  ```bash
  # Supabase CLI backup command
  supabase db dump -f pre_remediation_backup_$(date +%Y%m%d_%H%M%S).sql
  ```
  *(Or trigger a manual backup via Supabase Dashboard &rarr; Database &rarr; Backups).*
- [ ] **Telecom Provider Readiness:** Ensure the inbound GSM SIM / virtual receiver number has been provisioned and its webhook destination points to:
  `https://<your-render-url>/api/v1/auth/inbound-sms-webhook`

---

## 2. Render Environment Configuration Sequence

To resolve **Incident B (Reverse SMS Configuration Failure)**:

1. Log in to the **Render Dashboard** (`dashboard.render.com`).
2. Select the **NEXA Relay Backend** Web Service.
3. In the left navigation, click **Environment**.
4. Click **Add Environment Variable** and enter the following keys:

| Key | Required Value / Format | Purpose |
|---|---|---|
| `INBOUND_SMS_RECEIVER_NUMBER` | `+91XXXXXXXXXX` (E.164 format) | Real domestic Indian GSM SIM or virtual receiver number |
| `GATEWAY_WEBHOOK_SECRET` | `[Generated 32+ Byte Cryptographic Secret]` | Shared secret for HMAC-SHA256 inbound webhook validation |

5. Click **Save Changes**.
   *(Note: Saving environment variables in Render initiates an automated rolling restart/redeploy).*

---

## 3. Code Deployment Sequence

To resolve **Incident A (Migration 004 Schema Reconciliation)**:

### Step 3.1: Commit Reconciled Changes
Commit the updated migration logic, tests, and documentation:
```bash
git add backend/internal/database/migrations.go
git add backend/internal/database/database_test.go
git add docs/audit/PRODUCTION_REMEDIATION_PHASE_*.md
git commit -m "fix(database): reconcile migration 004 legacy schema collision and preserve legacy phone grants"
```

### Step 3.2: Push to Origin Repository
```bash
git push origin main
```

### Step 3.3: Trigger or Verify Render Deployment
- Render detects the push on `main` and automatically begins building and deploying the Go service.
- If manual deploy is configured: In Render Dashboard, click **Manual Deploy** &rarr; **Deploy latest commit**.

---

## 4. Live Startup Log Verification

During deployment boot, monitor Render **Logs** in real-time. Confirm the following log events occur in order:

```text
1. [Database] Connecting to database...
2. [Database] Database connection pool initialized successfully.
3. [Database] Applying migration 4: 004_create_relationship_graph_and_phone_privacy...
4. [Database] Migration 4 (004_create_relationship_graph_and_phone_privacy) applied successfully.
5. [Database] Applying migration 5: 005_create_message_idempotency_table...
6. [Database] Migration 5 (005_create_message_idempotency_table) applied successfully.
7. [Database] Applying migration 6: 006_enforce_row_level_security_fail_closed...
8. [Database] Migration 6 (006_enforce_row_level_security_fail_closed) applied successfully.
9. [ReverseSMS] Distributed Redis nonce caching and rate limiting enabled.
10. [Server] HTTP and Signaling server listening on :8080
```

> [!IMPORTANT]
> **CRITICAL LOG ASSERTION:**
> Ensure that `[ReverseSMS] CRITICAL: INBOUND_SMS_RECEIVER_NUMBER is not configured in production!` is **ABSENT** from the logs.

---

## 5. Post-Deployment Smoke Tests

Execute the following 4 automated smoke tests against the production URL:

### Smoke Test 1: Service Health Check
```bash
curl -i https://<render-backend-url>/health
```
**Expected Response:** `HTTP/1.1 200 OK`

### Smoke Test 2: Database Migration Status
Connect via `psql` to Supabase:
```sql
SELECT version, name, applied_at FROM schema_migrations ORDER BY version ASC;
```
**Expected Result:**
```
 version |                         name                          |          applied_at           
---------+-------------------------------------------------------+-------------------------------
       1 | 001_create_identity_schema                           | 2026-11-07 16:32:00
       2 | 002_create_connections_schema                        | 2026-11-07 16:32:01
       3 | 003_create_prekeys_schema                            | 2026-11-07 16:32:02
       4 | 004_create_relationship_graph_and_phone_privacy       | <current_timestamp>
       5 | 005_create_message_idempotency_table                 | <current_timestamp>
       6 | 006_enforce_row_level_security_fail_closed           | <current_timestamp>
(6 rows)
```

### Smoke Test 3: Table Schema Verification
```sql
SELECT column_name, data_type, is_nullable 
FROM information_schema.columns 
WHERE table_name = 'phone_visibility_grants' 
ORDER BY ordinal_position;
```
**Expected Result:**
Columns `id` (character varying), `granter_nx_id` (character varying), `grantee_nx_id` (character varying), `is_active` (boolean), `created_at` (bigint), `updated_at` (bigint).

Confirm archived table exists:
```sql
SELECT COUNT(*) FROM legacy_supabase_phone_visibility_grants;
```

### Smoke Test 4: Reverse SMS Initiation Endpoint
```bash
curl -i -X POST https://<render-backend-url>/api/v1/auth/initiate-reverse-verify \
  -H "Content-Type: application/json" \
  -d '{
    "phone_number": "+919876543210",
    "name": "Smoke Test User",
    "public_key_hex": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
  }'
```
**Expected Response:** `HTTP/1.1 200 OK`
```json
{
  "status": "PENDING",
  "nonce": "NX-XXXXX",
  "inbound_receiver_number": "+91XXXXXXXXXX",
  "sms_payload": "NEXA-VERIFY:NX-XXXXX:...",
  "expires_in_seconds": 90
}
```

---

## 6. Rollback Runbooks

In the unlikely event of an issue, follow the appropriate rollback plan:

### Plan A: Database Rollback
- `RunMigrations` executes every migration inside an atomic PostgreSQL transaction (`pool.Begin` &rarr; `tx.Commit`).
- If any DDL fails during execution, PostgreSQL automatically rolls back the transaction. Version 4 is not recorded, and the database remains stable at Version 3.
- If a manual rollback is needed post-migration:
  ```sql
  -- Manual rollback script (if needed)
  ALTER TABLE public.phone_visibility_grants RENAME TO failed_reconciliation_grants;
  ALTER TABLE public.legacy_supabase_phone_visibility_grants RENAME TO phone_visibility_grants;
  DELETE FROM schema_migrations WHERE version >= 4;
  ```

### Plan B: Render Web Service Rollback
1. Go to **Render Dashboard** &rarr; **NEXA Relay Backend** &rarr; **Events**.
2. Locate the previous successful deploy.
3. Click the three dots menu &rarr; **Rollback to this deploy**.

### Plan C: Reverse SMS Fallback Mode
If telecom carrier SMS delivery experiences an external outage:
- Developers and internal beta testers can continue onboarding without delay via `TRUSTED_PHONE_NUMBERS`.
- Non-trusted users receive a standard `reverse verification unavailable` message without data corruption or partial accounts.

---

## 7. Approval Gate

> [!WARNING]
> **STOP — DO NOT PROCEED TO PRODUCTION WITHOUT EXPLICIT USER APPROVAL.**  
> The next action must come from the user. Provide the Final Verdict report and wait for user confirmation before executing any production command.
