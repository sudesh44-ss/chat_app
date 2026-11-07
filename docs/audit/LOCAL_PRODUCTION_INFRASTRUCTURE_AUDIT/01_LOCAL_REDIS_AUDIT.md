# NEXA Infrastructure Audit — 01: Local Redis Audit

**Subsystem:** Ephemeral Message Queue & In-Memory State Buffer  
**Target:** Local Development Environment (Windows 11)  
**Package:** `backend/internal/queue/redis_buffer.go` & `backend/internal/connection/pubsub.go`  

---

## 1. Local Redis Connection Status

### 1.1 Socket & Port Verification
Execution of socket diagnostic on the local Windows host:
```powershell
Test-NetConnection -ComputerName localhost -Port 6379
```
**Result:**
- `TcpTestSucceeded: False`
- `PingSucceeded: False`
- Diagnostic shows no process listening on `127.0.0.1:6379` or `[::1]:6379`.

### 1.2 Observed Backend Log at Local Startup
```text
2026/11/07 15:30:11 [Queue] Redis connection failed at localhost:6379 (dial tcp [::1]:6379: connect: connection refused). Falling back to in-memory RAM queue.
2026/11/07 15:30:11 [Server] WARNING: Buffer is running in DEGRADED in-memory RAM fallback mode.
```

---

## 2. Code Analysis: `redis_buffer.go` Fallback Mechanics

The Redis buffer initialization is implemented in `backend/internal/queue/redis_buffer.go`:

```go
func NewRedisQueueBuffer(addr, password string, db int) *RedisQueueBuffer {
    rdb := redis.NewClient(&redis.Options{
        Addr:         addr,
        Password:     password,
        DB:           db,
        DialTimeout:  3 * time.Second,
        ReadTimeout:  2 * time.Second,
        WriteTimeout: 2 * time.Second,
    })

    ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
    defer cancel()

    if err := rdb.Ping(ctx).Err(); err != nil {
        log.Printf("[Queue] Redis connection failed at %s (%v). Falling back to in-memory RAM queue.", addr, err)
        return &RedisQueueBuffer{
            client:     rdb,
            isDegraded: true,
            fallback:   make(map[string][][]byte),
        }
    }

    return &RedisQueueBuffer{
        client:     rdb,
        isDegraded: false,
        fallback:   make(map[string][][]byte),
    }
}
```

### 2.1 Environmental Gating (`cmd/server/main.go:73-80`)
```go
if buffer.IsDegraded() {
    if isProduction {
        log.Fatalf("[Server] CRITICAL: Ephemeral Redis Queue Buffer failed in production! Operating in degraded mode is rejected. Aborting startup.")
    }
    log.Println("[Server] WARNING: Buffer is running in DEGRADED in-memory RAM fallback mode.")
} else {
    log.Println("[Server] Ephemeral Redis Queue Buffer initialized successfully.")
}
```

---

## 3. Ramifications of Degraded Mode in Local Development

When `isDegraded == true`, the backend switches all queue operations to a local in-memory Go map protected by a mutex (`sync.RWMutex`):

| Operation | Standard Redis Mode | Degraded In-Memory Mode | Development Impact |
| :--- | :--- | :--- | :--- |
| **Offline Message Queue** | Redis `RPUSH` with configurable TTL | Local `map[string][][]byte` in RAM | Works during single run; wiped immediately on server stop/restart |
| **Auth Challenge Store** | Redis `SETEX` with atomic TTL | `NewMemoryChallengeStore()` | Works during single run; not shared across processes |
| **Pub/Sub Cluster** | Redis `PUBLISH` / `SUBSCRIBE` channel | No-op / In-process dispatch only | Multi-instance testing impossible; single client testing OK |
| **Memory Consumption** | External process memory | Go heap memory | Acceptable for local testing; volatile |

### Verdict on Degraded Mode
- **For single-instance local testing:** ACCEPTABLE. The backend is designed explicitly to allow local development to proceed without requiring Redis installed.
- **For E2E multi-device sync or cross-process testing:** INSUFFICIENT. Requires a real Redis instance.

---

## 4. Local Redis Options for Full Parity

To bring local development to full parity with production:

### Option A: Run Redis via Docker (Recommended)
```powershell
docker run -d --name nexa-redis -p 6379:6379 redis:7-alpine
```

### Option B: Point Local Backend to Upstash Redis via `.env`
In `backend/.env`:
```env
REDIS_URL=rediss://default:<UPSTASH_PASSWORD>@quality-lioness-91050.upstash.io:6379
```
Because the Go backend parses `REDIS_URL` with TLS support (`rediss://`), local development can directly leverage the Upstash Redis instance over secure TLS without running any local daemon.
