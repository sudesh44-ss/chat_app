# NEXA — Production Incident Root Cause Analysis (RCA)

## 1. Executive Summary & Incident Distinction

On November 10, 2026, two distinct production backend incidents were recorded in the NEXA relay backend logs. This Root Cause Analysis establishes that the two incidents arise from **two completely independent root causes across different layers of the system**:

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                               INCIDENT DISTINCTION SUMMARY                             │
├─────────────────────┬─────────────────────────────────┬────────────────────────────────┤
│ Incident Dimension  │ Incident A (Migration 004)      │ Incident B (Reverse SMS Config)│
├─────────────────────┼─────────────────────────────────┼────────────────────────────────┤
│ Nature of Failure   │ Code DDL / Schema Collision     │ Operational Environment Defect │
│ Affected Layer      │ PostgreSQL Database Schema      │ Render Cloud Environment Vars  │
│ Manifestation Point │ Server boot (main.go:101)       │ Auth init (reverse_sms.go:170) │
│ Failure Mechanism   │ Fatal process crash (log.Fatalf)│ HTTP 500 on client request     │
│ Operational State   │ Outage / Crash Loop             │ Degraded (Auth disabled)       │
│ Severity            │ **P0 (Outage Blocker)**         │ **P1 (Critical Feature Down)** │
└─────────────────────┴─────────────────────────────────┴────────────────────────────────┘
```

---

## 2. Root Cause Analysis: Incident A (Migration 004 Failure)

### 2.1 Confirmed Root Cause: Table Collision with Legacy Supabase Schema
The confirmed root cause of Incident A is a **table namespace collision** between Go embedded migration 4 and an unarchived legacy Supabase table:

1. **The Legacy Origin:**
   The production PostgreSQL database previously ran legacy Supabase migration [`016_create_phone_visibility_grants.sql`](file:///H:/chat_app/database/migrations/016_create_phone_visibility_grants.sql), which created `public.phone_visibility_grants` with columns `id UUID`, `granter_id UUID`, `grantee_id UUID`, `is_active BOOLEAN`.
2. **The Go Migration Addition:**
   In commit `d8e7d99141b6f86df1ee6f2f763467e5a3227dea` (Nov 10, 2026), migration 4 (`004_create_relationship_graph_and_phone_privacy`) was added to `backend/internal/database/migrations.go`. It used the statement:
   `CREATE TABLE IF NOT EXISTS phone_visibility_grants (...)`
   expecting columns `granter_nx_id VARCHAR(32)` and `grantee_nx_id VARCHAR(32)`.
3. **The DDL Trap (`IF NOT EXISTS`):**
   Because the table already existed, PostgreSQL skipped the `CREATE TABLE` block.
   When PostgreSQL executed the immediate next statement:
   `CREATE INDEX IF NOT EXISTS idx_phone_grants_granter ON phone_visibility_grants(granter_nx_id, is_active);`
   PostgreSQL threw `SQLSTATE 42703 (undefined_column)` because the legacy table has `granter_id`, NOT `granter_nx_id`.
4. **The Process Crash:**
   [`backend/cmd/server/main.go:101`](file:///H:/chat_app/backend/cmd/server/main.go#L101) treats migration failure as fatal (`log.Fatalf`), terminating the backend container and preventing boot.

---

## 3. Root Cause Analysis: Incident B (Reverse SMS Configuration Failure)

### 3.1 Confirmed Root Cause: Missing Render Environment Variable
The confirmed root cause of Incident B is an **operational configuration omission** during the deployment of the NEXA Web Service on Render:

1. **The Configuration Requirement:**
   NEXA's Client-Initiated Reverse SMS architecture requires a physical or virtual GSM receiver telephone number in domestic Indian E.164 format (`+91XXXXXXXXXX`). This number is loaded via `os.Getenv("INBOUND_SMS_RECEIVER_NUMBER")`.
2. **The Environment Omission:**
   While documented in `REQUIRED_ENVIRONMENT_VARIABLES.md` and `RENDER_ENVIRONMENT_CONFIGURATION_MATRIX.md`, the environment variable `INBOUND_SMS_RECEIVER_NUMBER` was not populated in the Render Dashboard environment settings for the service.
3. **The Security Guardrail (Fail-Closed Enforcement):**
   In development mode (`APP_ENV=development`), the backend falls back to a simulated number (`+919800000000`). However, in production mode (`APP_ENV=production`), [`backend/internal/auth/reverse_sms.go:306`](file:///H:/chat_app/backend/internal/auth/reverse_sms.go#L306) strictly forbids running with empty or dummy numbers.
4. **The User Experience Impact:**
   When an Android client requests verification initiation via `/api/v1/auth/initiate-reverse-verify`, the fail-closed check rejects the request with an error, resulting in an HTTP 500 response and blocking new user phone registrations.

---

## 4. Why Local and Automated Tests Did Not Catch These Incidents

1. **Migration Test Blind Spot:**
   [`backend/internal/database/database_test.go:70-112`](file:///H:/chat_app/backend/internal/database/database_test.go#L70-L112) contains `TestMigrationSchemaIntegrity`, which performs simple substring checks on migration SQL strings. It does **not** run migrations against a live or containerized PostgreSQL instance, and thus never tested upgrading an existing Supabase database.
2. **Local Environment Difference:**
   In local development, developers either run with a clean local database (where Migration 4 creates the table fresh with `granter_nx_id`) or run with `APP_ENV=development` where `INBOUND_SMS_RECEIVER_NUMBER` silently defaults to `+919800000000`. Neither condition reproduced the production collision.

---

## 5. Security & Risk Assessment

- **Database Integrity:** **PRESERVED.** The PostgreSQL transaction rollback prevented any partial schema changes. Production database remains safe at Version 3.
- **Data Exposure:** **ZERO LEAKAGE.** No user phone numbers, database credentials, or auth tokens were exposed in logs or over the wire.
- **Service Availability:** **HIGH RISK / P0 OUTAGE.** Backend cannot boot until Migration 4 is resolved, and public users cannot register until the receiver number is configured.
