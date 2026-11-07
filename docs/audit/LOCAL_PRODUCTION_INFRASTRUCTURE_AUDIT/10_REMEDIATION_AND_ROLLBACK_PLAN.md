# NEXA Infrastructure Audit — 10: Remediation & Rollback Plan

**Execution Mode:** REMEDIATION SPECIFICATION (Execution requires operator authorization)  
**Target:** Supabase PostgreSQL & Render Web Service Environment  

---

## 1. Phase 1: Supabase Database Schema Remediation

### 1.1 Action
Execute the following non-destructive archival SQL script in the **Supabase Dashboard -> SQL Editor**. This renames legacy tables so they do not collide with the Go embedded migrations, while safely preserving any historical test records.

```sql
-- NEXA Database Remediation Script
-- Purpose: Resolve legacy schema drift and unblock Go embedded migrations 1, 2, and 3
BEGIN;

-- 1. Safely rename legacy tables to preserve all existing test data
ALTER TABLE IF EXISTS public.devices RENAME TO legacy_supabase_devices;
ALTER TABLE IF EXISTS public.phone_verifications RENAME TO legacy_supabase_phone_verifications;
ALTER TABLE IF EXISTS public.prekeys RENAME TO legacy_supabase_prekeys;

-- 2. Drop constraints on legacy tables that could cause name collisions
ALTER TABLE IF EXISTS public.legacy_supabase_devices 
    DROP CONSTRAINT IF EXISTS devices_user_id_fkey;

COMMIT;
```

### 1.2 Rollback Procedure (If needed)
```sql
BEGIN;
ALTER TABLE IF EXISTS public.legacy_supabase_devices RENAME TO devices;
ALTER TABLE IF EXISTS public.legacy_supabase_phone_verifications RENAME TO phone_verifications;
ALTER TABLE IF EXISTS public.legacy_supabase_prekeys RENAME TO prekeys;
COMMIT;
```

---

## 2. Phase 2: Render Environment Variable Configuration

### 2.1 Action
In the Render Web Service Dashboard -> Environment:

1. **Update `DATABASE_URL`:**
   Switch from the IPv6-only direct connection to the IPv4-compatible **Supabase Connection Pooler** (Session Mode on port 5432):
   ```env
   DATABASE_URL=postgres://postgres.kiktbhwmwptzqtkrdcvb:[SUPABASE_PASSWORD]@aws-0-ap-south-1.pooler.supabase.com:5432/postgres?sslmode=require
   ```
   *(Verify region in Supabase Dashboard -> Database -> Connection Pooling).*

2. **Configure `REDIS_URL`:**
   Provide the native Redis TLS URL:
   ```env
   REDIS_URL=rediss://default:[UPSTASH_PASSWORD]@quality-lioness-91050.upstash.io:6379
   ```

3. **Delete obsolete variables:**
   - Remove `UPSTASH_REDIS_REST_URL`
   - Remove `UPSTASH_REDIS_REST_TOKEN`
   - Remove `DATABASE_DIRECT_URL`

### 2.2 Rollback Procedure
If Render connectivity fails, restore previous environment variable values from Render's Environment history.

---

## 3. Phase 3: Local Environment Parity (Optional)

To enable local Redis testing without running in degraded in-memory mode:

1. Run local Docker container:
   ```powershell
   docker run -d --name nexa-redis -p 6379:6379 redis:7-alpine
   ```
2. Or configure `backend/.env` with Upstash `REDIS_URL`.
