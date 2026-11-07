# NEXA Local & Production Infrastructure Audit — Executive Summary

**Audit Dossier:** `docs/audit/LOCAL_PRODUCTION_INFRASTRUCTURE_AUDIT/`  
**Classification:** STRICT READ-ONLY COMPREHENSIVE INFRASTRUCTURE AUDIT  
**Date:** November 2026  
**Target Environments:** Local Development (Windows 11) & Production (Render Cloud Services)  
**Target Services:** Supabase PostgreSQL (`kiktbhwmwptzqtkrdcvb`) & Upstash Redis (`quality-lioness-91050`)  

---

## 1. High-Level Audit Overview

This audit provides an exhaustive, root-cause investigation of the startup and runtime blockers affecting both Local Development and Render Production deployments of the NEXA backend.

```
+----------------------------------------------------------------------------------------------------+
|                                      INFRASTRUCTURE ARCHITECTURE                                    |
+------------------------------------+-------------------------------+-------------------------------+
| SUBSYSTEM                          | LOCAL DEVELOPMENT             | RENDER PRODUCTION             |
+------------------------------------+-------------------------------+-------------------------------+
| Go Runtime (cmd/server/main.go)    | PASS (Go 1.24/Windows)        | PASS (Render Docker container)|
| Redis Queue & Challenge Store      | DEGRADED (In-memory RAM)      | READY (Upstash rediss:// TLS) |
| PostgreSQL Connectivity (Supabase) | CONNECTED (IPv4 direct DNS)   | BLOCKED (IPv6 unreachable)    |
| Database Migrations                | BLOCKED (SQLSTATE 42703 nx_id)| BLOCKED (Due to conn failure) |
+------------------------------------+-------------------------------+-------------------------------+
```

---

## 2. Formal Verdicts

```
========================================================================================
1. LOCAL RUNTIME VERDICT:              BLOCKED
   * Reason: While PostgreSQL connects, Migration 001 fails on schema drift (nx_id).
   * Redis operates in degraded in-memory RAM fallback mode.

2. PRODUCTION (RENDER) VERDICT:        BLOCKED
   * Reason: Supabase direct DB hostname resolves to IPv6-only AAAA record.
   * Render containers lack outbound IPv6 routing -> dial tcp [2406:...]:5432 unreachable.
   * Server fail-closed check triggers immediately on missing DB persistence.

3. DATABASE MIGRATION VERDICT:         REQUIRES NON-DESTRUCTIVE SCHEMA FIX
   * Reason: Existing "devices" and "phone_verifications" tables from legacy Supabase
     migrations lack columns expected by Go PostgresRepository and fail on index creation.
   * Transactional rollback preserved clean state; zero partial schema was recorded.

4. REDIS INFRASTRUCTURE VERDICT:       VERIFIED WORKING (CONFIGURATION PREREQUISITE)
   * Render production with Upstash Redis is fully validated when REDIS_URL uses rediss://.
   * REST API credentials (UPSTASH_REDIS_REST_URL/TOKEN) are unsupported by go-redis/v9.
   * Local development requires Docker Redis or Upstash TLS config in .env.
========================================================================================
```

---

## 3. Key Findings Summary

### Finding 1: The Local Migration 001 Failure (`SQLSTATE 42703`)
- **Root Cause:** Schema collision between legacy Supabase migrations in `database/migrations/` (specifically `002_create_devices.sql` which lacked `nx_id`) and the Go embedded migration in `backend/internal/database/migrations.go:21-94`.
- `CREATE TABLE IF NOT EXISTS devices` skipped creation because table `devices` already existed. The following statement `CREATE INDEX idx_devices_nx_id ON devices(nx_id)` threw `ERROR: column "nx_id" does not exist (SQLSTATE 42703)`.
- **Integrity Guarantee:** Because `RunMigrations` executes each version inside a transaction with `tx.Rollback(ctx)` on error, the transaction rolled back cleanly. No partial migration state was recorded in `schema_migrations`.

### Finding 2: The Render IPv6 Network Unreachable Blocker
- **Root Cause:** Render's standard container environment operates on an IPv4-only network stack. The direct Supabase database endpoint `db.kiktbhwmwptzqtkrdcvb.supabase.co:5432` on free/standard Supabase tiers resolves exclusively to IPv6 addresses (`2406:da1a:314:7101:98e3:f6f5:5d7a:1157`).
- Attempting to dial this IPv6 address results in `connect: network is unreachable`.
- Because `APP_ENV=production` enforces strict persistence gating (`if identityRepo == nil { log.Fatalf(...) }`), the server aborts startup immediately.
- **Solution:** Render must connect via the **Supabase Connection Pooler** (Supavisor) in **Session Mode (port 5432)**, which provides full dual-stack IPv4/IPv6 support (`aws-0-<region>.pooler.supabase.com:5432` with username `postgres.kiktbhwmwptzqtkrdcvb`).

### Finding 3: Redis Architecture & Degraded Fallback
- In local development without a local Redis instance on port 6379, the server gracefully degrades to an in-memory RAM queue.
- In production, degraded mode is strictly rejected: `[Server] CRITICAL: Ephemeral Redis Queue Buffer failed in production! Operating in degraded mode is rejected. Aborting startup.`
- Upstash Redis requires native Redis RESP protocol with TLS (`rediss://...`). HTTP REST credentials (`UPSTASH_REDIS_REST_URL`) are ignored by `go-redis/v9`.

---

## 4. Remediation Priority Action Matrix

| Priority | Action | Target Environment | Difficulty | Expected Impact |
| :--- | :--- | :--- | :--- | :--- |
| **P0** | Resolve legacy table collision in Supabase | Database (Supabase SQL) | Low (SQL Script) | Unblocks Migration 001, 002, and 003 locally and on Render |
| **P0** | Switch Render `DATABASE_URL` to Supabase Pooler IPv4 | Render Environment | Low (Dashboard) | Unblocks Render network reachability, resolves IPv6 fatal abort |
| **P1** | Set `REDIS_URL` in Render Environment | Render Environment | Low (Dashboard) | Ensures Upstash native TLS connection; prevents degraded mode exit |
| **P2** | Configure Local `.env` for Redis (Optional) | Local Environment | Low (File edit) | Enables full Redis testing locally without degraded mode warnings |

---

## 5. Dossier Structure

- **[01_LOCAL_REDIS_AUDIT.md](file:///H:/chat_app/docs/audit/LOCAL_PRODUCTION_INFRASTRUCTURE_AUDIT/01_LOCAL_REDIS_AUDIT.md)**: Local Redis socket test, `redis_buffer.go` fallback behavior, and degraded mode implications.
- **[02_LOCAL_POSTGRESQL_AUDIT.md](file:///H:/chat_app/docs/audit/LOCAL_PRODUCTION_INFRASTRUCTURE_AUDIT/02_LOCAL_POSTGRESQL_AUDIT.md)**: Local connectivity to Supabase, connection pool tuning, and ping mechanics.
- **[03_MIGRATION_001_NX_ID_ROOT_CAUSE.md](file:///H:/chat_app/docs/audit/LOCAL_PRODUCTION_INFRASTRUCTURE_AUDIT/03_MIGRATION_001_NX_ID_ROOT_CAUSE.md)**: Detailed forensic breakdown of SQLSTATE 42703, DDL rollback verification, and table conflicts.
- **[04_PRODUCTION_RENDER_AUDIT.md](file:///H:/chat_app/docs/audit/LOCAL_PRODUCTION_INFRASTRUCTURE_AUDIT/04_PRODUCTION_RENDER_AUDIT.md)**: Render container architecture, ephemeral filesystem risks, and startup gating.
- **[05_PRODUCTION_SUPABASE_CONNECTIVITY.md](file:///H:/chat_app/docs/audit/LOCAL_PRODUCTION_INFRASTRUCTURE_AUDIT/05_PRODUCTION_SUPABASE_CONNECTIVITY.md)**: Deep dive into Supabase IPv6 direct DNS vs Supavisor IPv4 connection pooling.
- **[06_UPSTASH_REDIS_AUDIT.md](file:///H:/chat_app/docs/audit/LOCAL_PRODUCTION_INFRASTRUCTURE_AUDIT/06_UPSTASH_REDIS_AUDIT.md)**: Upstash Redis RESP vs REST protocol analysis and `go-redis/v9` integration.
- **[07_ENVIRONMENT_CONFIGURATION_COMPARISON.md](file:///H:/chat_app/docs/audit/LOCAL_PRODUCTION_INFRASTRUCTURE_AUDIT/07_ENVIRONMENT_CONFIGURATION_COMPARISON.md)**: Complete variable-by-variable comparison matrix across environments.
- **[08_SECURITY_AND_DATA_INTEGRITY.md](file:///H:/chat_app/docs/audit/LOCAL_PRODUCTION_INFRASTRUCTURE_AUDIT/08_SECURITY_AND_DATA_INTEGRITY.md)**: Secrets sanitization, credential protection, and cryptographic key safety.
- **[09_ROOT_CAUSE_MATRIX.md](file:///H:/chat_app/docs/audit/LOCAL_PRODUCTION_INFRASTRUCTURE_AUDIT/09_ROOT_CAUSE_MATRIX.md)**: Comprehensive tabular mapping of symptoms, causes, impacts, and solutions.
- **[10_REMEDIATION_AND_ROLLBACK_PLAN.md](file:///H:/chat_app/docs/audit/LOCAL_PRODUCTION_INFRASTRUCTURE_AUDIT/10_REMEDIATION_AND_ROLLBACK_PLAN.md)**: Safe, step-by-step remediation scripts and rollback plans.
- **[11_VALIDATION_CHECKLIST.md](file:///H:/chat_app/docs/audit/LOCAL_PRODUCTION_INFRASTRUCTURE_AUDIT/11_VALIDATION_CHECKLIST.md)**: Post-remediation verification commands and SQL validation queries.
- **[README.md](file:///H:/chat_app/docs/audit/LOCAL_PRODUCTION_INFRASTRUCTURE_AUDIT/README.md)**: Master navigation guide.
