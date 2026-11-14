# NEXA Phase C — PostgreSQL Implementation Report

**Author:** Antigravity Senior Security & Systems Architect  
**Date:** 2026-11-06  
**Status:** IMPLEMENTED & VERIFIED  
**Component:** Backend Persistence Engine (`nexa/wire/internal/database`)  

---

## 1. Executive Summary

As part of **Phase C**, NEXA's backend persistence architecture has been upgraded from a volatile single-file JSON model (`data/identity_store.json`) to an enterprise-grade PostgreSQL persistence engine powered by `pgx/v5` (`github.com/jackc/pgx/v5/pgxpool`).

The implementation provides:
- Decoupled, standalone relational schema independent of third-party BaaS dependencies (e.g. Supabase `auth.users`).
- Connection pooling with production-tuned limits (25 max connections, 5 idle connections, 1h max lifetime, 5s connection timeout).
- Automated, transactional, idempotent database migration engine (`schema_migrations`).
- Full abstraction via `identity.IdentityRepository` interface allowing seamless switching and testing between PostgreSQL and JSON stores.
- Strict constraint enforcement on cryptographic keys, NX-ID format, phone numbers, and foreign key cascades.
- Zero credential logging via automated connection string sanitization (`SanitizeDatabaseURL`).

---

## 2. PostgreSQL Connection Pooling (`db.go`)

### 2.1 Configuration Spec
Connection pooling is managed via `pgxpool.Pool` configured with production defaults in `database.DefaultConfig(databaseURL)`:

| Parameter | Default Value | Rationale |
| :--- | :--- | :--- |
| `MaxConns` | `25` | Prevents connection exhaustion on Render Standard/Starter PostgreSQL instances (typically capped at 50-100 max connections). |
| `MinConns` | `5` | Keeps warm connections ready to minimize handshake latency on incoming WebSocket and REST auth requests. |
| `MaxConnLifetime` | `1 hour` | Periodically recycles connections to prevent memory leaks and handle background TCP connection timeouts cleanly. |
| `MaxConnIdleTime` | `30 minutes` | Reclaims idle connection resources during off-peak hours. |
| `HealthCheckPeriod`| `1 minute` | Proactively identifies stale or severed TCP sockets before assigning them to active requests. |
| `ConnectTimeout` | `5 seconds` | Prevents Goroutine hangs if PostgreSQL is restarting or network partition occurs. |

### 2.2 Security & Credential Protection
The function `SanitizeDatabaseURL` scrubs database connection strings before logging:
- Standard URL schemes (`postgres://user:pass@host:port/db?sslmode=require`) are redacted to `postgres://user:xxxxx@host:port/db?sslmode=require` using Go's `url.Redacted()`.
- Non-standard key-value DSN formats (`user=... password=...`) are scrubbed using regular expression matching `(?i)(password=)[^\s]+` -> `${1}xxxxx`.
- Passwords, secret tokens, and credentials are never emitted to stdout, stderr, or log collectors.

---

## 3. Schema Architecture & Migrations (`migrations.go`)

### 3.1 Legacy Migration Incompatibility Resolution
Previous migrations in `database/migrations/` (such as `001_create_profiles.sql`) relied on `REFERENCES auth.users(id) ON DELETE CASCADE`. In standalone PostgreSQL environments (Render PostgreSQL, AWS RDS, GCP Cloud SQL), `auth.users` does not exist, causing migration failures.

Phase C introduces an embedded, self-contained schema runner (`database.RunMigrations`) backed by a dedicated `schema_migrations` tracking table.

### 3.2 Implemented DDL Specification

```sql
-- Migration 001: 001_create_identity_schema

CREATE TABLE IF NOT EXISTS accounts (
    id VARCHAR(64) PRIMARY KEY,
    nx_id VARCHAR(32) NOT NULL UNIQUE,
    public_key_hex VARCHAR(128) NOT NULL UNIQUE,
    dh_key_hex VARCHAR(128),
    phone_number VARCHAR(32),
    phone_verified BOOLEAN NOT NULL DEFAULT FALSE,
    is_discoverable_by_phone BOOLEAN NOT NULL DEFAULT TRUE,
    google_id VARCHAR(128),
    supabase_user_id VARCHAR(128),
    email VARCHAR(255),
    profile_name VARCHAR(128) NOT NULL DEFAULT '',
    avatar_url TEXT,
    bio VARCHAR(512),
    gender VARCHAR(32),
    age INT DEFAULT 0,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    CONSTRAINT chk_nx_id_format CHECK (nx_id ~ '^NX-[A-Z0-9]{8}$')
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_accounts_phone ON accounts(phone_number) 
    WHERE phone_number IS NOT NULL AND phone_number != '';
CREATE UNIQUE INDEX IF NOT EXISTS idx_accounts_google_id ON accounts(google_id) 
    WHERE google_id IS NOT NULL AND google_id != '';
CREATE UNIQUE INDEX IF NOT EXISTS idx_accounts_supabase_uid ON accounts(supabase_user_id) 
    WHERE supabase_user_id IS NOT NULL AND supabase_user_id != '';
CREATE INDEX IF NOT EXISTS idx_accounts_pubkey ON accounts(public_key_hex);

CREATE TABLE IF NOT EXISTS devices (
    device_id VARCHAR(128) PRIMARY KEY,
    account_id VARCHAR(64) NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    nx_id VARCHAR(32) NOT NULL REFERENCES accounts(nx_id) ON DELETE CASCADE,
    device_name VARCHAR(128) NOT NULL DEFAULT '',
    public_key_hex VARCHAR(128) NOT NULL,
    dh_key_hex VARCHAR(128),
    fcm_token TEXT,
    is_revoked BOOLEAN NOT NULL DEFAULT FALSE,
    created_at BIGINT NOT NULL,
    last_active_at BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_devices_nx_id ON devices(nx_id);
CREATE INDEX IF NOT EXISTS idx_devices_account_id ON devices(account_id);

CREATE TABLE IF NOT EXISTS sessions (
    token VARCHAR(128) PRIMARY KEY,
    account_id VARCHAR(64) NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    nx_id VARCHAR(32) NOT NULL REFERENCES accounts(nx_id) ON DELETE CASCADE,
    device_id VARCHAR(128) NOT NULL,
    expires_at BIGINT NOT NULL,
    is_revoked BOOLEAN NOT NULL DEFAULT FALSE,
    created_at BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_sessions_nx_id ON sessions(nx_id);
CREATE INDEX IF NOT EXISTS idx_sessions_token ON sessions(token);
CREATE INDEX IF NOT EXISTS idx_sessions_expires ON sessions(expires_at);

CREATE TABLE IF NOT EXISTS phone_verifications (
    id BIGSERIAL PRIMARY KEY,
    phone_number VARCHAR(32) NOT NULL,
    nonce VARCHAR(64) NOT NULL UNIQUE,
    session_id VARCHAR(128),
    verified BOOLEAN NOT NULL DEFAULT FALSE,
    expires_at BIGINT NOT NULL,
    created_at BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_phone_verifications_nonce ON phone_verifications(nonce);
```

---

## 4. Repository Implementation (`repository.go`)

`database.PostgresRepository` implements `identity.IdentityRepository` with 100% parameterization (`$1, $2, ...`), preventing SQL injection.

### Key Capabilities:
1. **Deterministic NX-ID Allocation:**
   - First checks if public key already has an assigned NX-ID.
   - If not, generates random NX-IDs adhering to `NX-XXXXXXXX` and checks for collisions with retry.
2. **Privacy-Preserving Contact Matching:**
   - `LookupVerifiedPhoneNumbers` queries via `phone_number = ANY($1)`.
   - Never stores or logs unmatched candidate phone numbers.
   - Only returns contacts that are explicitly `phone_verified = TRUE` or `is_discoverable_by_phone = TRUE`.
3. **Atomic Device & Session Revocation:**
   - When a device is revoked (`RevokeDevice`), a transaction atomically revokes the device in `devices` and all active sessions in `sessions` matching that `device_id`.
4. **Session Token Expiry:**
   - `GetSession` validates `is_revoked = FALSE` and `expires_at > time.Now().UnixMilli()`.

---

## 5. Fallback & Server Bootstrapping

In `backend/cmd/server/main.go`:
- If `DATABASE_URL` is configured: connects to PostgreSQL, applies migrations, runs one-time JSON import if source exists, and reports `"database": "CONNECTED"` in `/health`.
- If `DATABASE_URL` is empty: logs fallback message, initializes `identity.NewStore(identityStorePath)`, and reports `"database": "FALLBACK_JSON"` in `/health`.
- Both paths guarantee uninterrupted server operation across local dev and production.
