# NEXA Phase C — Backup & Recovery Report

**Author:** Antigravity Senior Security & Systems Architect  
**Date:** 2026-11-06  
**Status:** COMPLETE & DOCUMENTED  
**Scope:** PostgreSQL Disaster Recovery, JSON Snapshot Preservation, and Failover Protocols  

---

## 1. Executive Summary

This report establishes operational procedures for data protection, disaster recovery, and automated failover for the NEXA backend persistence layer following the PostgreSQL and Redis migration.

---

## 2. Source Data Retention Policy

During migration from file-based storage to PostgreSQL:
1. **Source Preservation:** The original `data/identity_store.json` file is **NEVER** modified, truncated, or deleted by the migration runner.
2. **Immutable Backup Copy:** A timestamped backup copy (`data/identity_store.json.bak.YYYYMMDD_HHMMSS`) is written to disk with restricted permissions (`0600`) prior to parsing.
3. **Recovery Guarantee:** In the event of a catastrophic database hardware failure during migration, the server can instantly revert to file-based mode by unsetting `DATABASE_URL`.

---

## 3. PostgreSQL Backup Strategy

For production deployments on Render PostgreSQL (or equivalent managed cloud DB):

### 3.1 Automated Managed Snapshots
- Render PostgreSQL provides automated daily snapshots with continuous write-ahead logging (WAL).
- Point-in-time recovery (PITR) is supported up to 7 days on standard plans.

### 3.2 Automated Logical Dumps (pg_dump)
For off-site disaster recovery backups, execute the following script on a scheduled cron runner:

```bash
#!/usr/bin/env bash
set -euo pipefail

BACKUP_DIR="/var/backups/nexa"
TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
BACKUP_FILE="${BACKUP_DIR}/nexa_pg_${TIMESTAMP}.sql.gz"

mkdir -p "${BACKUP_DIR}"

# Execute logical pg_dump with compression
pg_dump --clean --if-exists --no-owner --no-privileges "${DATABASE_URL}" | gzip -9 > "${BACKUP_FILE}"

# Retain 30 days of backups; prune older
find "${BACKUP_DIR}" -name "nexa_pg_*.sql.gz" -mtime +30 -delete

echo "PostgreSQL backup successfully written to ${BACKUP_FILE}"
```

---

## 4. Disaster Recovery & Rollback Runbook

### Scenario A: PostgreSQL Outage / Connection Drop
1. **Behavior:** If PostgreSQL becomes unreachable, server logs connection failure.
2. **Action:** The server automatically operates in file-backed fallback mode if `DATABASE_URL` is omitted, or re-attempts pool health checks every minute (`HealthCheckPeriod: 1 * time.Minute`).
3. **Recovery:** Once PostgreSQL connectivity is restored, the connection pool automatically re-establishes active connections without requiring server process restart.

### Scenario B: Database Restoration from Logical Backup
To restore database state from a backup file:

```bash
# 1. Terminate active backend connections
# 2. Decompress and restore via psql
gunzip -c /var/backups/nexa/nexa_pg_YYYYMMDD_HHMMSS.sql.gz | psql "${DATABASE_URL}"

# 3. Verify record counts
psql "${DATABASE_URL}" -c "SELECT count(*) FROM accounts; SELECT count(*) FROM devices;"
```

### Scenario C: Safe Emergency Rollback to JSON Store
If a database corruption occurs and immediate service continuity is required:
1. Stop backend service.
2. Unset or clear the `DATABASE_URL` environment variable.
3. Verify that `data/identity_store.json` (or the latest `.bak` file) is present.
4. Restart backend service.
5. Service will bootstrap in `"database": "FALLBACK_JSON"` mode with zero downtime for existing identities.
