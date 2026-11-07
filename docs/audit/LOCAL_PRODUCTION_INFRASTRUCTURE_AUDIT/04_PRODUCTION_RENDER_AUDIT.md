# NEXA Infrastructure Audit — 04: Production Render Deployment Audit

**Subsystem:** Render Cloud Container Runtime & Deployment Lifecycle  
**Target:** Render Web Service Environment  
**Repository:** `https://github.com/sudesh44-ss/chat_app` (Branch `main`)  
**Deployment Tooling:** Docker container via Render Web Service  

---

## 1. Render Runtime Environment Characteristics

Render Web Services run Linux containers in isolated network namespaces. Understanding the container characteristics is essential for analyzing startup failures:

### 1.1 Ephemeral Container Filesystem
- Render containers are completely stateless.
- Every git deploy, manual restart, or automatic container recovery provisions a brand new container from the Docker image.
- Any file written to the local filesystem (such as `data/identity_store.json`) is destroyed upon restart.
- **Production Requirement:** The backend **MUST** use persistent PostgreSQL storage. Running in JSON file fallback mode is prohibited in production.

### 1.2 IPv4-Only Outbound Network Namespace
- Render standard Web Services run on AWS/GCP underlying infrastructure without default IPv6 egress routing.
- The default route `0.0.0.0/0` directs all IPv4 traffic through the Render NAT gateway.
- Outbound traffic to IPv6 destinations (`::/0`) has no gateway route, returning `ENETUNREACH` (`network is unreachable`).

---

## 2. Startup Sequence & Fail-Closed Production Architecture

The entrypoint in `backend/cmd/server/main.go` enforces strict fail-closed guards when `APP_ENV=production`:

```go
isProduction := os.Getenv("APP_ENV") == "production"

// Guard 1: Ephemeral Redis Queue Buffer
if buffer.IsDegraded() {
    if isProduction {
        log.Fatalf("[Server] CRITICAL: Ephemeral Redis Queue Buffer failed in production! Operating in degraded mode is rejected. Aborting startup.")
    }
}

// Guard 2: PostgreSQL Persistence
if identityRepo == nil {
    if isProduction {
        log.Fatalf("[Server] CRITICAL: PostgreSQL identity repository connection failed or unconfigured in production! Aborting startup.")
    }
}
```

### 2.1 The Two Render Deployment Crashes Observed

#### Crash 1: Redis Configuration Missing
```text
2026/11/07 14:10:02 [Server] ERROR: Neither REDIS_URL nor a remote REDIS_ADDR is configured in production! Aborting startup.
```
- **Cause:** Render environment had `UPSTASH_REDIS_REST_URL` instead of native `REDIS_URL` (`rediss://...`).
- **Result:** Pre-initialization validation aborted before attempting to dial.

#### Crash 2: PostgreSQL IPv6 Network Unreachability
```text
2026/11/07 14:45:10 [Server] ERROR: Failed to connect to PostgreSQL: database ping failed: [2406:da1a:314:7101:98e3:f6f5:5d7a:1157]:5432 (db.kiktbhwmwptzqtkrdcvb.supabase.co): dial error: dial tcp [2406:da1a:314:7101:98e3:f6f5:5d7a:1157]:5432: connect: network is unreachable
2026/11/07 14:45:10 [Server] CRITICAL: PostgreSQL identity repository connection failed or unconfigured in production! Aborting startup.
```
- **Cause:** Render attempted to connect to the Supabase direct hostname `db.kiktbhwmwptzqtkrdcvb.supabase.co`, which resolved to an IPv6 address.
- **Result:** Connection failed, `identityRepo` remained nil, Guard 2 triggered `log.Fatalf`, container exited with code 1, and Render entered crash backoff.

---

## 3. Render Production Health Check Architecture

In `cmd/server/main.go:376-398`, the server exposes `/health`:

```go
mux.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
    status := map[string]interface{}{
        "status":     "healthy",
        "timestamp":  time.Now().Unix(),
        "database":   dbStatus,
        "buffer":     !buffer.IsDegraded(),
        "production": isProduction,
    }
    // Returns 200 OK
})
```

Render uses this endpoint to confirm service readiness. Until the database and Redis connect cleanly, the HTTP server cannot start listening on `$PORT`, and Render marks the build as failed.
