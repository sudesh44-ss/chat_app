# Production Incident: Safe & Ordered Remediation Plan

## 1. Safety Guidelines & Approval Gate

> [!IMPORTANT]
> **STRICT AUDIT-ONLY PHASE NOTICE:**
> In accordance with the Phase 8.1 / Production Incident audit restrictions, **NO changes have been applied to production or the repository**. The steps below constitute the proposed, evidence-based remediation plan requiring explicit separate authorization before execution.

---

## 2. Remediation Overview & Execution Order

Remediation must proceed in strict sequential order:

```
[Phase 1: Database Schema Reconciliation]
   │ (Resolves Incident A: Server Boot Crash)
   ├── Step 1.1: Pre-flight Verification of Legacy Table
   ├── Step 1.2: Author Idempotent Schema Migration in Go
   └── Step 1.3: Verify Migration Runner in Test Database
        │
        v
[Phase 2: Render Environment Variable Configuration]
   │ (Resolves Incident B: Reverse SMS HTTP 500)
   ├── Step 2.1: Populate INBOUND_SMS_RECEIVER_NUMBER in Render
   └── Step 2.2: Populate GATEWAY_WEBHOOK_SECRET in Render
        │
        v
[Phase 3: Deployment & Boot Verification]
   │ (Validates Server Boot & Endpoint Health)
   ├── Step 3.1: Trigger Redploy of Render Web Service
   ├── Step 3.2: Inspect Live Startup Logs (Confirm Migrations 4, 5, 6 Applied)
   └── Step 3.3: Verify Non-Trusted Onboarding via Reverse SMS
```

---

## 3. Detailed Remediation Steps

### Phase 1: Resolving Incident A (Migration 004 DDL Collision)

#### Step 1.1: Root Cause Fix in `backend/internal/database/migrations.go`
Update Migration 4 (`004_create_relationship_graph_and_phone_privacy`) to detect the legacy table sharing the same name and archive it transactionally before creating the new schema.

**Proposed Migration 4 SQL (Idempotent & Greenfield/Upgrade Compatible):**
```sql
-- 1. Extend connections table with source attribution
ALTER TABLE connections ADD COLUMN IF NOT EXISTS source VARCHAR(32) NOT NULL DEFAULT 'NEXA_ID';

-- 2. Detect and safely archive legacy Supabase table if it contains legacy 'granter_id' column
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_schema = 'public' 
          AND table_name = 'phone_visibility_grants' 
          AND column_name = 'granter_id'
    ) THEN
        -- Drop obsolete dependent view and function from legacy migration 016
        DROP VIEW IF EXISTS public.safe_profiles CASCADE;
        DROP FUNCTION IF EXISTS public.toggle_phone_visibility() CASCADE;
        
        -- Archive legacy table to avoid name collision
        ALTER TABLE public.phone_visibility_grants RENAME TO legacy_supabase_phone_visibility_grants;
    END IF;
END $$;

-- 3. Create the authentic Go relationship graph table
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

-- 4. Create performance indexes
CREATE INDEX IF NOT EXISTS idx_phone_grants_granter ON phone_visibility_grants(granter_nx_id, is_active);
CREATE INDEX IF NOT EXISTS idx_phone_grants_grantee ON phone_visibility_grants(grantee_nx_id, is_active);
```

#### Why This Fix is 100% Safe:
- **Zero Manual Database Access Required:** Runs automatically through `RunMigrations` inside a PostgreSQL transaction.
- **Zero Data Loss:** Preserves the 0-row legacy table as `legacy_supabase_phone_visibility_grants`.
- **Greenfield Compatible:** On fresh test databases where `granter_id` doesn't exist, the `IF EXISTS` block is silently bypassed.

---

### Phase 2: Resolving Incident B (Reverse SMS Configuration)

#### Step 2.1: Render Dashboard Environment Settings
1. Navigate to **Render Dashboard** &rarr; **NEXA Relay Backend Web Service** &rarr; **Environment**.
2. Add the missing environment variable:
   - **Key:** `INBOUND_SMS_RECEIVER_NUMBER`
   - **Value:** The dedicated domestic Indian telecom SIM / Virtual Number in E.164 format (e.g. `+91XXXXXXXXXX`).
3. Ensure the companion webhook secret is populated:
   - **Key:** `GATEWAY_WEBHOOK_SECRET`
   - **Value:** High-entropy secret key shared with the telecom inbound webhook provider.
4. Save Changes (Render will automatically queue a new deployment).

---

### Phase 3: Rollback Procedures

If any unanticipated failure occurs during execution:

1. **Database Rollback:**
   Because `RunMigrations` executes each migration inside `pool.Begin(ctx)` with deferred `tx.Rollback(ctx)`, any failure during execution automatically rolls back the transaction. The database remains intact at Version 3.
2. **Reverse SMS Rollback:**
   If the configured receiver number fails to receive inbound SMS carrier traffic, the server will continue operating in fail-closed mode for Reverse SMS without impacting WebSocket messaging, P2P file transfer, or direct connections.

---

## 4. Required Post-Remediation Verification Tests

1. **Automated Migration Test (`database_test.go`):**
   Add a migration test that simulates upgrading a database containing legacy table `phone_visibility_grants(granter_id)` to ensure all 6 migrations apply cleanly.
2. **Server Boot Log Inspection:**
   Confirm the server logs:
   ```text
   [Database] Applying migration 4: 004_create_relationship_graph_and_phone_privacy...
   [Database] Migration 4 (004_create_relationship_graph_and_phone_privacy) applied successfully.
   [Database] Applying migration 5: 005_create_message_idempotency_table...
   [Database] Migration 5 (005_create_message_idempotency_table) applied successfully.
   [Database] Applying migration 6: 006_enforce_row_level_security_fail_closed...
   [Database] Migration 6 (006_enforce_row_level_security_fail_closed) applied successfully.
   ```
3. **Onboarding Integration Test:**
   Execute a client request to `POST /api/v1/auth/initiate-reverse-verify`:
   - Assert HTTP response status is `200 OK`.
   - Assert `inbound_receiver_number` matches the configured production number.
   - Assert `sms_payload` contains a valid `NX-XXXXX` nonce.
