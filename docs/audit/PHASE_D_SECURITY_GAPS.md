# PHASE D — SECURITY GAPS & MULTI-INSTANCE LIMITATIONS

## 1. Silent In-Memory Fallback for Connection Repository
- **Severity:** HIGH
- **Component:** `backend/cmd/server/main.go:147-152`
- **Impact:** Permanent loss of contact relationships and blocks upon server restart if PostgreSQL is disconnected.
- **Description:** If `DATABASE_URL` is missing or fails to connect, `main.go` falls back to `connections.NewMemoryRepository`. In production, this causes relationships to be stored in ephemeral RAM without persistence.
- **Remediation:** Enforce `if isProduction && pgPool == nil { log.Fatalf(...) }` to ensure the server fails closed in production.

---

## 2. In-Memory Local Rate Limiting
- **Severity:** MEDIUM
- **Component:** `backend/internal/connections/handler.go:25-39, 88-109`
- **Impact:** Rate limit evasion across clustered backend instances.
- **Description:** Discovery and request rate limits are tracked in in-memory Go maps. When running multiple backend instances behind a load balancer, users can distribute requests across instances, multiplying their allowed rate limits.
- **Remediation:** Migrate sliding window rate-limiting to Redis using `INCR` and `EXPIRE` or a Redis sorted set (`ZADD`/`ZREMRANGEBYSCORE`).

---

## 3. Absence of Wire-Level Message Blocking in Current Hub
- **Severity:** MEDIUM (Architectural Boundary)
- **Component:** `backend/internal/connection/hub.go:214-230`
- **Impact:** Blocked users could still send packets if they know the recipient's NX-ID and initiate a direct message packet over WebSocket.
- **Description:** Phase D implemented block enforcement across discovery, connection requests, and REST APIs. However, `Hub.RouteDecodedPacket` relays packets based solely on recipient online status without querying `user_blocks`.
- **Remediation:** Scheduled for Phase E: Integrate an active blocklist check inside `Hub.RouteDecodedPacket` to drop messages from blocked peers before routing.
