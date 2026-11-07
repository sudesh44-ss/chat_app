# NEXA Infrastructure Audit — 02: Local PostgreSQL Audit

**Subsystem:** Relational Database Persistence & Connection Pool  
**Target:** Local Development Environment (Windows 11)  
**Host:** Supabase PostgreSQL (`db.kiktbhwmwptzqtkrdcvb.supabase.co:5432`)  
**Package:** `backend/internal/database/db.go` & `backend/internal/database/repository.go`  

---

## 1. Local PostgreSQL Connectivity Status

### 1.1 Connection Verification
During local backend startup, `database.NewPool` successfully establishes a TCP connection and completes the initial database ping to Supabase PostgreSQL:

```text
2026/11/07 15:30:12 [Database] Connected to PostgreSQL at postgres://postgres:xxxxx@db.kiktbhwmwptzqtkrdcvb.supabase.co:5432/postgres?sslmode=require (MaxConns: 25, MinConns: 5)
```

### 1.2 Why Local Succeeds where Render Fails
The local Windows host has a network stack with IPv4/IPv6 dual-stack capability and public DNS resolvers that properly negotiate the Supabase direct connection. The TCP handshake completes, TLS negotiation succeeds with `sslmode=require`, and the `pgxpool.Pool` is initialized.

---

## 2. Pool Configuration & Tuning (`internal/database/db.go`)

The PostgreSQL connection pool is configured via `DefaultConfig`:

```go
func DefaultConfig(databaseURL string) Config {
    return Config{
        DatabaseURL:       databaseURL,
        MaxConns:          25,
        MinConns:          5,
        MaxConnLifetime:   1 * time.Hour,
        MaxConnIdleTime:   30 * time.Minute,
        HealthCheckPeriod: 1 * time.Minute,
        ConnectTimeout:    5 * time.Second,
    }
}
```

### 2.1 Pool Behavior Analysis
- **`MaxConns: 25`**: Well within Supabase free-tier limits (typically 60-90 direct connections). However, when multiple developers or server instances connect directly, connection exhaustion can occur unless Supabase Connection Pooling is used.
- **`MinConns: 5`**: Maintains 5 pre-warmed connections to eliminate cold-start connection latency on identity lookups and handshake prekey queries.
- **`ConnectTimeout: 5s`**: Bounded timeout prevents indefinite hangs when network latency spikes.
- **`HealthCheckPeriod: 1m`**: Automatically prunes dead connections caused by cloud firewall resets or NAT idle drops.

---

## 3. Migration Runner Invocation (`cmd/server/main.go:98-103`)

Once the pool is established, `main.go` invokes the migration runner:

```go
// cmd/server/main.go:98-103
migCtx, migCancel := context.WithTimeout(context.Background(), 30*time.Second)
if err := database.RunMigrations(migCtx, pool); err != nil {
    log.Fatalf("[Server] CRITICAL: Failed to apply database migrations: %v", err)
}
migCancel()
```

### 3.1 Failure Point
At this exact invocation, `database.RunMigrations` attempts to apply Migration 1 (`001_create_identity_schema`), which encounters the schema drift on table `devices` and throws `SQLSTATE 42703 (column "nx_id" does not exist)`.

Because `log.Fatalf` is called:
1. The critical error is logged.
2. `os.Exit(1)` is executed immediately.
3. The remaining backend initialization (identity repository, auth handlers, WebSocket server) is aborted.

---

## 4. Current State of Database Schema on Supabase

Inspection of the Supabase PostgreSQL database indicates:
1. `schema_migrations` table exists (created by `RunMigrations:229-234`).
2. `schema_migrations` has **0 records** because Migration 1 was rolled back on error.
3. Legacy tables created during early project phases exist in `public`:
   - `profiles`
   - `devices` (legacy structure without `nx_id` or `account_id`)
   - `phone_verifications` (legacy structure without `nonce`)
   - `nx_identities`
   - `contacts`
   - `prekeys` (legacy structure)
4. Tables from `backend/internal/database/migrations.go` (`accounts`, `sessions`, `connection_requests`, `connections`, `user_blocks`, `identity_key_bindings`, `signed_prekeys`, `one_time_prekeys`, `prekey_claims`) **do not exist** yet.

---

## 5. Conclusion & Verdict
- PostgreSQL network connectivity and pool initialization on the local host are **FULLY FUNCTIONAL**.
- The local backend startup is **BLOCKED** exclusively by the migration schema collision.
