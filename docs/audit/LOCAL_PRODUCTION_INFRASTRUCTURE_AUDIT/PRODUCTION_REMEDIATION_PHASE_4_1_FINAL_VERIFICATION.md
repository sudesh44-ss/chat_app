# Phase 4.1: Independent Production Deployment Safety Audit

**Author:** NEXA Principal Security & Release Auditor  
**Date:** 2026-11-10  
**Target:** Staged Production Remediation (Migration 004 & Reverse SMS)  
**Workspace:** `H:\chat_app`  
**Evaluation Mode:** STRICT AUDIT-ONLY (No code, database, or environment modifications)  

---

## 1. Executive Summary & Verdict Declaration

```
========================================================================================
PRODUCTION READINESS VERDICT:
B — CONDITIONALLY READY, SPECIFIC VERIFICATION REQUIRED
========================================================================================
```

### Justification Summary
1. **Source Code Integrity:** The working tree contains exactly two modified files (`backend/internal/database/migrations.go` and `backend/internal/database/database_test.go`). No unrelated production files were modified. `go vet ./...` passes with zero warnings.
2. **Go Test Suite:** All 12 packages in `backend/` execute and pass cleanly (`-count=1`).
3. **Condition 1 (Live PostgreSQL Verification Gap):** Migration 004 DDL reconciliation was verified using Go in-memory string assertions and unit test checks. **A real PostgreSQL database engine was NOT executed against locally** (Docker/PostgreSQL not available on the local Windows host). Scenarios A through J remain **NOT VERIFIED against real PostgreSQL runtime**.
4. **Condition 2 (Concurrent Migration Advisory Lock Missing):** `RunMigrations` does not utilize `pg_advisory_lock` or `pg_advisory_xact_lock`. Multi-instance Render deploys must be avoided or guarded until single-instance execution completes.
5. **Condition 3 (Render Production Variables Required):** `INBOUND_SMS_RECEIVER_NUMBER` and `GATEWAY_WEBHOOK_SECRET` must be populated in the Render Dashboard prior to non-trusted user verification.

---

## 2. Complete Git Diff Audit

### 2.1 Git Status & Working Tree State
Command: `git status --short -u`
```text
 M backend/internal/database/database_test.go
 M backend/internal/database/migrations.go
```
- **Staged Changes (`git diff --cached`):** `EMPTY` (Zero staged changes).
- **Untracked Production Code:** `NONE` (Zero untracked source files; audit documents reside in `docs/audit/` which is ignored via `.gitignore`).
- **Unrelated Modifications:** `NONE`. Audit confirmed that 100% of changes are strictly confined to the database migration runner and its unit tests.

### 2.2 Exact Line-by-Line Changes
1. **[`backend/internal/database/migrations.go`](file:///H:/chat_app/backend/internal/database/migrations.go):**
   - **Migration 004:** Added `DO $$ BEGIN ... END $$;` block checking `information_schema.columns` for `granter_id` in `phone_visibility_grants`.
   - Safely executes `DROP VIEW IF EXISTS public.safe_profiles CASCADE` and `DROP FUNCTION IF EXISTS public.toggle_phone_visibility() CASCADE`.
   - Executes `ALTER TABLE public.phone_visibility_grants RENAME TO legacy_supabase_phone_visibility_grants;`.
   - Updated `phone_visibility_grants` table definition with `id VARCHAR(64) PRIMARY KEY DEFAULT gen_random_uuid()::text`.
   - Added legacy data migration block preserving existing grants via `INSERT INTO ... SELECT ... JOIN accounts`.
   - **Migration 006:** Added check to apply Row-Level Security (`ALTER TABLE ... ENABLE ROW LEVEL SECURITY`) to `legacy_supabase_phone_visibility_grants` if present.
2. **[`backend/internal/database/database_test.go`](file:///H:/chat_app/backend/internal/database/database_test.go):**
   - Expanded `TestMigrationSchemaIntegrity` from 2 to all 6 sequential migrations.
   - Added `TestMigration004_SchemaReconciliationSQL` verifying non-destructive operations (asserting absence of `DROP TABLE` and `TRUNCATE`, and asserting presence of safe drops and rename).

---

## 3. Migration 004 Real PostgreSQL Verification Audit

The 10 production database upgrade scenarios were evaluated against the implementation:

> [!CRITICAL]
> **EVIDENCE DISTINCTION:**  
> The Go test `TestMigration004_SchemaReconciliationSQL` verifies the SQL string contents. **It does NOT connect to or execute DDL against a real PostgreSQL engine.** Docker is not installed on this host and localhost:5432 is inactive. Therefore, all live engine test statuses are truthfully classified below.

| Scenario | Architectural / SQL Analysis | Real PostgreSQL Test Status |
|---|---|---|
| **A. Fresh Database** | Greenfield DB has no `phone_visibility_grants` table. `information_schema.columns` returns false; PL/pgSQL block is a no-op; `CREATE TABLE IF NOT EXISTS` creates the authentic table directly. | **NOT VERIFIED** (No live DB) |
| **B. Existing Legacy Database** | Table `phone_visibility_grants` exists with column `granter_id`. `information_schema.columns` returns true; dependent view and function are dropped; table is renamed to `legacy_supabase_phone_visibility_grants`. | **NOT VERIFIED** (No live DB) |
| **C. Active & Inactive Records** | Query maps `l.is_active` directly to the new `is_active` column. Inactive grants remain inactive; active grants remain active. | **NOT VERIFIED** (No live DB) |
| **D. Unmapped UUIDs** | Query uses `JOIN accounts a1 ON (a1.id = l.granter_id::text OR a1.supabase_user_id = l.granter_id::text)`. Any UUID with no matching account is omitted from the new table. **The original legacy record remains completely preserved in `legacy_supabase_phone_visibility_grants`.** | **NOT VERIFIED** (No live DB) |
| **E. Duplicate Relationships** | Query specifies `ON CONFLICT (granter_nx_id, grantee_nx_id) DO NOTHING`. Duplicates are skipped without crashing. | **NOT VERIFIED** (No live DB) |
| **F. Missing Account References** | Foreign key constraints `REFERENCES accounts(nx_id) ON DELETE CASCADE` are satisfied because inner joins only select mapped `nx_id` from existing accounts. | **NOT VERIFIED** (No live DB) |
| **G. Failure & Rollback** | `RunMigrations` wraps migration execution in `tx, err := pool.Begin(ctx)` and calls `tx.Rollback(ctx)` on error. PostgreSQL DDL is transactional. | **NOT VERIFIED** (No live DB) |
| **H. Retrying After Failure** | If transaction rolls back, Version 4 is not inserted into `schema_migrations`. Next boot retries Version 4 cleanly from the pre-failure state. | **NOT VERIFIED** (No live DB) |
| **I. Existing Views & Functions** | `DROP VIEW IF EXISTS public.safe_profiles CASCADE` and `DROP FUNCTION IF EXISTS public.toggle_phone_visibility() CASCADE` safely remove objects that depend on `granter_id`. | **NOT VERIFIED** (No live DB) |
| **J. Concurrent Migration Execution** | `RunMigrations` does NOT acquire a PostgreSQL advisory lock (`pg_advisory_lock`). If multiple backend replicas boot concurrently, race conditions on `schema_migrations` may occur. | **NOT VERIFIED** (No live DB) |

---

## 4. Legacy Data Preservation Audit

1. **Accounting for Legacy Records:**
   - The legacy table is **never dropped**. `ALTER TABLE public.phone_visibility_grants RENAME TO legacy_supabase_phone_visibility_grants` retains 100% of rows, columns, data types, and primary key values.
2. **UUID-to-Account Mapping:**
   - The mapping query joins on `a1.id = l.granter_id::text OR a1.supabase_user_id = l.granter_id::text`.
   - If an account exists in the Go backend, its `nx_id` is assigned.
   - If an account does not yet exist in the Go backend, the grant is **not** discarded; it remains accessible in `legacy_supabase_phone_visibility_grants`.
3. **Duplicate Prevention:**
   - The table enforces `CONSTRAINT uq_phone_grant UNIQUE(granter_nx_id, grantee_nx_id)`.
   - The data migration uses `ON CONFLICT (granter_nx_id, grantee_nx_id) DO NOTHING`.
4. **Record Counts from Isolated Test Database:**
   - **NOT AVAILABLE** (No live PostgreSQL engine available in the local execution sandbox).
   - In production Supabase, `public.phone_visibility_grants` previously contained 0 rows (documented in `docs/audit/LEGACY_TABLE_DEPENDENCY_AUDIT.md`).

---

## 5. Legacy Dependencies Audit

### 5.1 Object Analysis: `public.safe_profiles` & `public.toggle_phone_visibility()`
In legacy Supabase migration `016_create_phone_visibility_grants.sql`:
- `safe_profiles` was a PostgREST SQL view projecting profile columns and hiding phone numbers based on legacy UUID grants.
- `toggle_phone_visibility(target_grantee_id UUID, grant_active BOOLEAN)` was a PL/pgSQL stored procedure designed for Supabase RPC.

### 5.2 Impact of Dropping Legacy Objects
An exhaustive audit across the entire repository confirmed:
- **Go Backend (`backend/`):** 0 references to `safe_profiles`; 0 references to `toggle_phone_visibility()`. The Go backend handles phone visibility exclusively via REST endpoints (`/api/v1/users/phone-visibility`) calling Go repository methods (`SetPhoneVisibility`, `HasPhoneVisibility`, `ListPhoneVisibilityGrants`) which query `phone_visibility_grants` using `nx_id`.
- **Android App (`app/`):** 0 references to `safe_profiles`; 0 references to `toggle_phone_visibility()`. The mobile application interacts with the Go HTTP/WebSocket API, never directly with Supabase PostgREST or RPC.
- **Backward Compatibility:** No backward-compatible SQL replacement view or function is required because no client or server code depends on them.

---

## 6. Migration 006 RLS Security Verification

### 6.1 Row-Level Security Mechanics
In PostgreSQL:
- Executing `ALTER TABLE phone_visibility_grants ENABLE ROW LEVEL SECURITY` without policies causes non-owner/unprivileged roles to **fail closed** (all `SELECT`, `INSERT`, `UPDATE`, `DELETE` operations return 0 rows).
- The Go backend connects as the database owner (`postgres` or `nexa_user`), which bypasses RLS and continues normal operations without hindrance.
- Migration 006 also explicitly executes:
  ```sql
  REVOKE ALL ON ALL TABLES IN SCHEMA public FROM anon, authenticated;
  REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM anon, authenticated;
  REVOKE ALL ON ALL ROUTINES IN SCHEMA public FROM anon, authenticated;
  ```
- Migration 006 now additionally executes:
  ```sql
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'legacy_supabase_phone_visibility_grants') THEN
      EXECUTE 'ALTER TABLE legacy_supabase_phone_visibility_grants ENABLE ROW LEVEL SECURITY';
  END IF;
  ```

### 6.2 Security Findings
1. **Direct PostgREST Access:** Completely blocked. Supabase `anon` and `authenticated` roles have zero table permissions and fail closed.
2. **Backend API Authorization:** Enforced at the application layer in [`backend/internal/connections/handler.go`](file:///H:/chat_app/backend/internal/connections/handler.go#L719) by binding operations strictly to the authenticated `session.NxID`.
3. **Administrative Access:** Database administrators and database connection owners retain full access for disaster recovery and audits.

---

## 7. Reverse SMS Security Audit

A thorough inspection of [`backend/internal/auth/webhook_auth.go`](file:///H:/chat_app/backend/internal/auth/webhook_auth.go) and [`backend/internal/auth/reverse_sms.go`](file:///H:/chat_app/backend/internal/auth/reverse_sms.go) was conducted:

### 7.1 Authentication Methods Evaluation
1. **Static Token Authentication (`X-Webhook-Secret` or `Authorization: Bearer <secret>`):**
   - **Mechanism:** `subtle.ConstantTimeCompare([]byte(headerToken), []byte(secret)) == 1`.
   - **Audit Finding:** Constant-time verification is correctly used. However, static token authentication validates the secret only; it does not sign the payload body.
2. **HMAC-SHA256 Payload Signature (`X-Gateway-Signature`, `X-Signature`, `X-Hub-Signature-256`):**
   - **Mechanism:** Computes `hmac.New(sha256.New, []byte(secret))` over `rawBody` and compares with `hmac.Equal`.
   - **Audit Finding:** Cryptographically sound. Normalizes optional `sha256=` prefix; verifies raw byte slice before JSON or form parsing.
3. **`X-Twilio-Signature`:**
   - **Audit Finding:** In `webhook_auth.go:59`, `X-Twilio-Signature` is accepted as an alias for the gateway HMAC-SHA256 check. For native Twilio webhooks, NEXA provides a dedicated HMAC-SHA1 validator in [`backend/internal/auth/twilio_adapter.go`](file:///H:/chat_app/backend/internal/auth/twilio_adapter.go). Inbound SMS webhooks must use standard HMAC-SHA256 gateway signatures.

### 7.2 Replay Protection & Sender Binding
- **Timestamp Tolerance:** Headers `X-Gateway-Timestamp` and `X-Timestamp` are parsed (supporting seconds and milliseconds). Rejects requests older than 5 minutes or skewed in the future by > 60 seconds.
- **Single-Use Nonce Lifecycle:**
  - Nonces (`NX-XXXXX`) are 5-character CSPRNG alphanumeric strings with 90-second TTL.
  - Upon webhook verification, `record.Status` transitions to `VERIFIED`. Subsequent webhook submissions for the same nonce fail with: `replay attack rejected`.
  - When the client polls status via `GetVerificationStatus`, `record.Status` immediately transitions to `CONSUMED`. Subsequent status checks cannot retrieve the session token.
- **Sender Binding:** `record.PhoneNumber` must strictly match the incoming SMS sender MSISDN (`canonicalSender`). Mismatched senders are rejected with `ErrPhoneMismatch`.
- **Anti-Dummy Check:** In production (`APP_ENV=production`), `reverse_sms.go:306` strictly rejects empty strings and placeholders (`+919800000000`, `+910000000000`), forcing non-trusted verification to fail closed until a real number is configured.

---

## 8. Test Evidence Verification Matrix

Tests independently executed during this audit:

| Command / Scope | Status | Result / Findings |
|---|---|---|
| `go vet ./...` (backend) | **EXECUTED** | **PASS** (Zero warnings, zero lint errors) |
| `go test -count=1 ./...` (backend) | **EXECUTED** | **PASS** (All 12 packages passing, 0 failures) |
| `internal/database` unit tests | **EXECUTED** | **PASS** (6/6 tests passing, SQL assertion verified) |
| `internal/auth` unit tests | **EXECUTED** | **PASS** (All tests passing, 11/11 webhook tests pass) |
| In-memory repository tests | **MOCKED** | Memory repositories used for unit testing |
| Real PostgreSQL engine tests | **NOT AVAILABLE** | No PostgreSQL engine running locally |
| Scenarios A–J in real DB | **NOT VERIFIED** | Awaits staging / pre-prod database run |

---

## 9. Remaining Blockers & Production Prerequisites

Before production deployment authorization can be granted:

1. **Prerequisite 1 — Single-Instance Boot Restriction:**  
   Because `RunMigrations` does not utilize `pg_advisory_lock`, ensure that Render is configured with `numInstances: 1` during the deployment where Migration 4 is applied, preventing concurrent migration race conditions.
2. **Prerequisite 2 — Render Environment Variable Configuration:**  
   Populate the following in the Render Dashboard before rolling out:
   - `INBOUND_SMS_RECEIVER_NUMBER`: Dedicated Indian telecom SIM / Virtual Number in E.164 (`+91XXXXXXXXXX`).
   - `GATEWAY_WEBHOOK_SECRET`: High-entropy 32+ byte secret string.
3. **Prerequisite 3 — Database Snapshot:**  
   Execute a pre-migration backup snapshot of the Supabase PostgreSQL database.

---

## 10. Final Deployment Recommendation

The codebase changes in `backend/internal/database/migrations.go` and `backend/internal/database/database_test.go` are **well-designed, non-destructive, and mathematically sound**.

However, because real PostgreSQL engine verification could not be executed locally, deployment must proceed under **Controlled Staging Rollout** conditions:
- **Do NOT execute blind direct production deployment.**
- **Deploy first to a staging environment or run the pre-flight checks against a staging PostgreSQL instance.**
- **Observe startup logs carefully to verify Migrations 4, 5, and 6 apply cleanly in sequence.**
