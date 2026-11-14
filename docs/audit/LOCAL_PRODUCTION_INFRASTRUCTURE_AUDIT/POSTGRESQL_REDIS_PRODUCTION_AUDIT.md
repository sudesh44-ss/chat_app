# NEXA — PostgreSQL & Redis Production Infrastructure Audit

**Audit Mode:** STRICT READ-ONLY INSPECTION  
**Target:** Render Production Environment & Backend Storage Layer  
**Date:** November 2026  

---

## 1. PostgreSQL Database Status: The Non-Existent Integration

### Finding: Zero PostgreSQL Code in Backend
An exhaustive search of `backend/` confirmed that the running Go backend has **zero database integration code**:
- No `database/sql` driver import.
- No `pgx`, `gorm`, or `lib/pq` in `backend/go.mod`.
- No database connection string loader (`DATABASE_URL` is never read in `cmd/server/main.go`).

### The SQL Migrations Paradox
- 16 SQL migration files reside in `database/migrations/` (e.g., `001_create_profiles.sql`, `002_create_devices.sql`, `004_create_phone_verifications.sql`).
- These were created for a Supabase database design.
- The running Go backend **does not execute, connect to, or use these migrations**.

### What the Backend Actually Uses
- The backend relies entirely on **`internal/identity/store.go`**, which reads and writes to a local JSON file (`data/identity_store.json`).
- In Render Web Services, the filesystem is **stateless and ephemeral**.
- When Render redeploys, restarts, or wakes the web service, `/root/data/identity_store.json` is wiped clean.
- **Production Impact:** Users registered before a deployment or restart will lose their accounts and sessions on the server.

---

## 2. Redis & Queue Audit: The `localhost:6379` Failure

### Exact Startup Log
```
[Queue] Redis connection failed at localhost:6379 (dial tcp [::1]:6379: connect: connection refused). Falling back to in-memory RAM queue.
```

### Analysis of the Failure Chain
1. **Config Loader:** In `backend/cmd/server/main.go:41-45`:
   ```go
   redisAddr := os.Getenv("REDIS_ADDR")
   if redisAddr == "" {
       redisAddr = "localhost:6379"
   }
   redisPassword := os.Getenv("REDIS_PASSWORD")
   ```
2. **Environment Mismatch:** Render supplies Redis instances with `REDIS_URL` (e.g. `rediss://default:pwd@red-xxx.render.com:6379`). The Go backend does not check `REDIS_URL`.
3. **Dialing Localhost:** Because `REDIS_ADDR` is not provided, the server attempts to connect to `localhost:6379`. Inside a Render web service container, no Redis service is running locally.
4. **Fallback to Volatile RAM:**
   - In `backend/internal/queue/redis_buffer.go:54`, the failure triggers fallback to an in-memory map:
     ```go
     fallback: make(map[string][][]byte)
     ```
   - In `backend/internal/auth/challenge_store.go:217`, `NewChallengeStore(buffer.GetClient())` falls back to `NewMemoryChallengeStore()`.

### Production Consequences
- **Offline Message Loss:** If Recipient B is offline when Sender A sends an encrypted message, the packet is stored in RAM. When Render restarts, all buffered offline messages are permanently lost.
- **Horizontal Scaling Prohibited:** Multiple Render service instances cannot share offline message queues or authentication challenges.
- **Replay Protection Weakness:** In-memory challenge store lacks Redis atomic single-use extraction (`GETDEL` or Lua script), increasing susceptibility to race conditions.
