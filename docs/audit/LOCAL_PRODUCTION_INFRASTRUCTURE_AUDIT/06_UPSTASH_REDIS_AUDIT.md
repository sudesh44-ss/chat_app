# NEXA Infrastructure Audit — 06: Upstash Redis Architecture & Integration Audit

**Subsystem:** Ephemeral Message Queue, Pub/Sub, and Challenge Store  
**Service Provider:** Upstash Managed Redis  
**Instance Host:** `quality-lioness-91050.upstash.io:6379`  
**Client Library:** `github.com/redis/go-redis/v9`  
**Protocols:** Native Redis Serialization Protocol (RESP) over TLS vs Upstash HTTP REST  

---

## 1. Upstash Redis Credential Types

Upstash provides two completely different mechanisms to connect to managed Redis instances:

```
+-------------------------------------------------------------------------------------------------+
|                                 UPSTASH CREDENTIAL COMPARISON                                   |
+------------------------------+----------------------------------+-------------------------------+
| ATTRIBUTE                    | NATIVE REDIS PROTOCOL (RESP)     | UPSTASH HTTP REST API         |
+------------------------------+----------------------------------+-------------------------------+
| Protocol                     | RESP over TLS (TCP Port 6379)    | HTTPS over Port 443           |
| URI Scheme                   | rediss://...                     | https://...                   |
| Render Environment Variables | REDIS_URL                        | UPSTASH_REDIS_REST_URL/TOKEN  |
| Client Library               | github.com/redis/go-redis/v9     | Custom HTTP client / SDK      |
| Pub/Sub Support              | FULL (Persistent streaming TCP)  | NONE / Long polling only      |
| Pipeline & Transactions      | FULL (MULTI/EXEC, Pipelines)     | Batched HTTP requests         |
| NEXA Backend Support         | NATIVE & FULLY INTEGRATED        | NOT SUPPORTED ANYWHERE IN CODE|
+------------------------------+----------------------------------+-------------------------------+
```

---

## 2. Codebase Verification: Zero REST API Support

An exhaustive search of all `.go` files across `backend/` confirmed:
- Zero references to `UPSTASH_REDIS_REST_URL`.
- Zero references to `UPSTASH_REDIS_REST_TOKEN`.
- Zero HTTP-based Redis drivers.

All Redis interactions in NEXA rely on `github.com/redis/go-redis/v9`:
1. `backend/internal/queue/redis_buffer.go`: Implements offline FIFO queue buffering via `RPUSH`, `LPOP`, `LRANGE`.
2. `backend/internal/connection/pubsub.go`: Implements cross-instance message routing via Redis Pub/Sub channels (`PUBLISH`, `SUBSCRIBE`).
3. `backend/internal/auth/challenge_store.go`: Implements atomic challenge nonce expiration via `SETEX`, `GETDEL`.

Because `redis.PubSub` requires a persistent, bidirectional TCP connection, an HTTP REST API cannot fulfill the architectural requirements of NEXA's real-time messaging pipeline.

---

## 3. Render Production Startup Failure Analysis

In Render deployment logs:
```text
[Server] ERROR: Neither REDIS_URL nor a remote REDIS_ADDR is configured in production! Aborting startup.
```

### 3.1 Trace of the Failure
In `backend/cmd/server/main.go:42-70`:
```go
redisURL := strings.TrimSpace(os.Getenv("REDIS_URL"))
redisAddr := strings.TrimSpace(os.Getenv("REDIS_ADDR"))
redisPassword := os.Getenv("REDIS_PASSWORD")

if isProduction && redisURL == "" && (redisAddr == "" || isLoopbackAddr(redisAddr)) {
    log.Fatalf("[Server] ERROR: Neither REDIS_URL nor a remote REDIS_ADDR is configured in production! Aborting startup.")
}
```

When Render was configured with:
```env
UPSTASH_REDIS_REST_URL=https://quality-lioness-91050.upstash.io
UPSTASH_REDIS_REST_TOKEN=AX...
```
`REDIS_URL` remained empty. Because `isProduction == true`, the startup guard aborted execution before any socket was opened.

---

## 4. The Correct Production Redis Configuration

To connect the NEXA backend to Upstash Redis in Render:

1. Obtain the **Native Redis Connection String** from the Upstash Console:
   - Go to Upstash Console -> Databases -> `quality-lioness-91050`.
   - Scroll to **Connect your database** -> Select **redis-cli** or **Go**.
   - Copy the `rediss://...` connection URL.

2. Configure in Render Environment Settings:
   ```env
   REDIS_URL=rediss://default:<UPSTASH_PASSWORD>@quality-lioness-91050.upstash.io:6379
   ```

### 4.1 Verification of TLS Handling
In `backend/internal/queue/redis_buffer.go:34-45`:
When `REDIS_URL` begins with `rediss://`, `redis.ParseURL` automatically enables `tls.Config{ServerName: "quality-lioness-91050.upstash.io"}`. TLS handshake completes cleanly over port 6379.
