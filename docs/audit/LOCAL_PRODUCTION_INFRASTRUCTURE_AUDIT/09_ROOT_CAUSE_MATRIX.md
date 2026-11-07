# NEXA Infrastructure Audit — 09: Root Cause Matrix

**Audit Dossier:** `docs/audit/LOCAL_PRODUCTION_INFRASTRUCTURE_AUDIT/`  
**Classification:** FORENSIC CAUSE-AND-EFFECT MAPPING  

---

## Comprehensive Problem-to-Root-Cause Matrix

```
+-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------+
|                                                                        ROOT CAUSE MATRIX                                                                                    |
+-----+------------------------------------------+-------------+---------------------+---------------------------------------+---------------------+--------------------------+
| ID  | SYMPTOM OBSERVED                         | SUBSYSTEM   | ENVIRONMENT         | EXACT ROOT CAUSE                      | IMPACT              | REMEDIATION STRATEGY     |
+-----+------------------------------------------+-------------+---------------------+---------------------------------------+---------------------+--------------------------+
| RC1 | ERROR: column "nx_id" does not exist     | PostgreSQL  | Local Development   | Schema drift: Table "devices" was     | Migration 1 aborts; | Rename/archive legacy    |
|     | (SQLSTATE 42703)                         | Migrations  |                     | created by legacy 002_create_devices  | backend exits with  | tables or execute        |
|     |                                          |             |                     | without nx_id. CREATE TABLE IF NOT    | code 1.             | non-destructive ALTER    |
|     |                                          |             |                     | EXISTS skipped table, and CREATE      |                     | TABLE scripts.           |
|     |                                          |             |                     | INDEX idx_devices_nx_id failed.       |                     |                          |
+-----+------------------------------------------+-------------+---------------------+---------------------------------------+---------------------+--------------------------+
| RC2 | dial tcp [2406:...]:5432: connect:       | PostgreSQL  | Render Production   | Supabase direct hostname              | Backend fails to    | Change DATABASE_URL to   |
|     | network is unreachable                   | Networking  |                     | (db.<ref>.supabase.co) resolves only  | connect to DB;      | Supabase Connection      |
|     |                                          |             |                     | to IPv6 AAAA. Render containers lack  | fail-closed guard   | Pooler (Session Mode on  |
|     |                                          |             |                     | outbound IPv6 routes.                 | aborts startup.     | port 5432) via IPv4.     |
+-----+------------------------------------------+-------------+---------------------+---------------------------------------+---------------------+--------------------------+
| RC3 | Neither REDIS_URL nor a remote           | Redis /     | Render Production   | Render environment had                | Backend startup     | Provide native Redis     |
|     | REDIS_ADDR is configured in production!  | Config      |                     | UPSTASH_REDIS_REST_URL configured.    | fails validation    | URL in REDIS_URL         |
|     |                                          |             |                     | go-redis/v9 requires native RESP      | before attempting   | (rediss://default:xxx@   |
|     |                                          |             |                     | REDIS_URL (rediss://...).             | connections.        | ...upstash.io:6379).     |
+-----+------------------------------------------+-------------+---------------------+---------------------------------------+---------------------+--------------------------+
| RC4 | Redis connection failed at               | Redis       | Local Development   | No local Redis daemon listening on    | Queue runs in       | Normal behavior for dev. |
|     | localhost:6379 (connection refused).     | Buffer      |                     | port 6379.                            | degraded in-memory  | For full parity, run     |
|     | Falling back to in-memory RAM queue.     |             |                     |                                       | RAM fallback mode.  | Docker redis:7-alpine.   |
+-----+------------------------------------------+-------------+---------------------+---------------------------------------+---------------------+--------------------------+
| RC5 | Potential future failure on              | PostgreSQL  | Local / Production  | Table "phone_verifications" from      | Subsequent index    | Add missing nonce and    |
|     | "nonce" does not exist (SQLSTATE 42703)  | Migrations  |                     | legacy 004 lacks nonce column         | creation will fail  | session_id columns or    |
|     |                                          |             |                     | expected by migrations.go.            | after fixing dev.   | rename legacy table.     |
+-----+------------------------------------------+-------------+---------------------+---------------------------------------+---------------------+--------------------------+
```
