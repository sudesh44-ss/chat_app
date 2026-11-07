# PHASE D — FINAL SECURITY & PRODUCTION VERIFICATION REPORT

**Execution Mode:** STRICT READ-ONLY AUDIT  
**Audit Scope:** NEXA Phase D (Connection & Contact Management, PostgreSQL Persistence, Lifecycle State Machine, Blocklist, Multi-Instance Events, Distributed Rate Limiting, Android Client Integration)  
**Classification Standard:** `VERIFIED`, `VERIFIED WITH LIMITATIONS`, `NOT VERIFIED`, `FAILED`  

---

## 1. Repository and Production Persistence

### 1.1 Production PostgreSQL Usage vs. Memory Fallback
- **Classification:** `FAILED`
- **Code Inspection:** `backend/cmd/server/main.go:75-84, 147-152`
- **Details:** In `main.go`:
```go
var connRepo connections.ConnectionRepository
if pgPool != nil {
    connRepo = connections.NewPostgresRepository(pgPool)
} else {
    connRepo = connections.NewMemoryRepository(identityRepo)
}
```
If `DATABASE_URL` is unconfigured or if PostgreSQL fails to connect during startup, `pgPool` is `nil`. Instead of failing closed, the server logs an error and silently initializes `connections.NewMemoryRepository`. In a production environment, this silent fallback causes relationship records and blocks to be lost upon container restart.

### 1.2 Database Outage Behavior (Fail-Closed)
- **Classification:** `VERIFIED WITH LIMITATIONS`
- **Details:** When PostgreSQL is connected (`pgPool != nil`), runtime database errors (e.g. lost connection pool) cause repository queries to return errors, resulting in HTTP 500 responses (fail-closed). However, at server startup, initialization does not fail closed.

### 1.3 Transaction Rollbacks
- **Classification:** `VERIFIED`
- **Code Inspection:** `backend/internal/connections/postgres_repository.go:103, 509`
- **Details:** Both `SendRequest` and `BlockUser` initiate transactions via `r.pool.Begin(ctx)` and register deferred rollbacks: `defer func() { _ = tx.Rollback(ctx) }()`. If any intermediate operation fails, rollback occurs automatically.

### 1.4 Duplicate Connection Constraints Under Concurrency
- **Classification:** `VERIFIED`
- **Code Inspection:** `backend/internal/database/migrations.go:88-100` & `postgres_repository.go:148-155`
- **Details:** Enforced by PostgreSQL constraints:
  - `CONSTRAINT chk_canonical_users CHECK (user_a_nx_id < user_b_nx_id)`
  - `CONSTRAINT uq_connection_pair UNIQUE (user_a_nx_id, user_b_nx_id)`
  - `ON CONFLICT (user_a_nx_id, user_b_nx_id) DO UPDATE SET status = 'ACTIVE'`
  Concurrent mutual acceptances or simultaneous requests resolve to the identical canonical row without duplicates.

---

## 2. Connection Request Lifecycle

### 2.1 Send, Accept, Reject, and Cancel
- **Classification:** `VERIFIED`
- **Code Inspection:** `postgres_repository.go:85-206, 310-435`
- **Details:** 
  - `SendRequest`: Creates a `PENDING` request with 7-day TTL (`expires_at`).
  - `AcceptRequest`: Enforces that only `req.RecipientNxID == callerNxID` can accept. Inserts or activates canonical connection record.
  - `RejectRequest`: Enforces that only recipient can reject; sets status to `REJECTED`.
  - `CancelRequest`: Enforces that only `req.SenderNxID == callerNxID` can cancel; sets status to `CANCELLED`.

### 2.2 Reciprocal Cross-Requests (Simultaneous Requests)
- **Classification:** `VERIFIED`
- **Code Inspection:** `postgres_repository.go:132-169`
- **Details:** When User A sends a request to User B, the query checks with `SELECT ... FOR UPDATE` whether User B already has an active pending request to User A. If found, it auto-accepts the reciprocal request and activates the connection atomically.

### 2.3 Duplicate Request Prevention
- **Classification:** `VERIFIED`
- **Code Inspection:** `postgres_repository.go:171-179` & `migrations.go:78`
- **Details:** Backed by database partial unique index:
  `CREATE UNIQUE INDEX idx_unique_pending_request ON connection_requests (sender_nx_id, recipient_nx_id) WHERE status = 'PENDING'`.

### 2.4 Request Expiration Execution
- **Classification:** `VERIFIED WITH LIMITATIONS`
- **Code Inspection:** `postgres_repository.go:218-220`
- **Details:** In `GetIncomingRequests`, lazy expiration is executed:
  `UPDATE connection_requests SET status = 'EXPIRED' WHERE recipient_nx_id = $1 AND status = 'PENDING' AND expires_at <= now`.
  Furthermore, discovery and query checks filter on `expires_at > now`.
- **Limitation:** There is no standing background cron job purging expired requests if users never query their incoming lists; expiration is enforced strictly at read and action time.

---

## 3. Block and Unblock Enforcement

### 3.1 Block Ownership & Authorization
- **Classification:** `VERIFIED`
- **Code Inspection:** `postgres_repository.go:499-539`
- **Details:** Only authenticated callers can block another user. Self-block is prevented via `blockerNxID == blockedNxID` check and DB constraint `chk_no_self_block`.

### 3.2 Duplicate Block Prevention
- **Classification:** `VERIFIED`
- **Code Inspection:** `postgres_repository.go:512-516`
- **Details:** SQL uses `INSERT INTO user_blocks ... ON CONFLICT (blocker_nx_id, blocked_nx_id) DO NOTHING`.

### 3.3 Effect on Existing Connections and Pending Requests
- **Classification:** `VERIFIED`
- **Code Inspection:** `postgres_repository.go:521-530`
- **Details:** Blocking immediately sets any active connection to `'REMOVED'` and transitions all pending requests between the two users to `'REJECTED'`.

### 3.4 Wire Messaging Authorization vs. Future Requirements
- **Classification:** `VERIFIED WITH LIMITATIONS`
- **Analysis:**
  - **Implemented in Phase D:** REST discovery returns 404 (anti-enumeration); connection requests return 403; real-time WebSocket event `USER_BLOCKED` is broadcast to peer.
  - **Future Requirement (Phase E):** `Hub.RouteDecodedPacket` does NOT currently verify `user_blocks` or `connections` before relaying chat message packets. Wire-level blocking of E2EE messages is explicitly deferred to Phase E.

---

## 4. Multi-Instance WebSocket Events

### 4.1 Same-Instance Notification
- **Classification:** `VERIFIED`
- **Code Inspection:** `hub.go:377-409`
- **Details:** Iterates over local `h.sessions[nxId]` and enqueues events to all active authenticated client channels.

### 4.2 Cross-Instance Notification
- **Classification:** `FAILED (NOT IMPLEMENTED)`
- **Code Inspection:** `hub.go:377-409`
- **Details:** `BroadcastEventToNxID` queries only local in-process memory. Redis Pub/Sub is not integrated. If Alice is connected to Backend Instance 1 and Bob triggers an action on Backend Instance 2, Alice receives zero real-time WebSocket notification.

### 4.3 Disconnected Recipient Behavior
- **Classification:** `VERIFIED WITH LIMITATIONS`
- **Details:** If the recipient has no active WebSocket session, events are discarded in RAM. They are not queued to the Redis offline buffer. However, the client synchronizes authoritative state via REST upon app resumption.

---

## 5. Distributed Rate Limiting

### 5.1 Storage & Scope
- **Classification:** `FAILED`
- **Code Inspection:** `backend/internal/connections/handler.go:25-39, 88-109`
- **Details:** Rate limiters (`discoveryRate` and `requestRate`) are backed by local in-memory Go maps protected by `sync.Mutex`. They are **NOT** Redis-backed.
- **Cross-Instance Consistency:** Across multiple instances, counters are completely disjoint, allowing users to exceed configured limits by distributing requests across instances.

### 5.2 Configured Limits
- **Classification:** `VERIFIED`
- **Details:** Discovery limit: 30 requests/minute per `session.NxID + IP`. Request limit: 10 requests/minute per `session.NxID`.

---

## 6. Android Client Integration

### 6.1 Authentication Token Handling
- **Classification:** `VERIFIED`
- **Code Inspection:** `ContactRepositoryImpl.kt:403-405`
- **Details:** Reads session token via `secureStorage.getSessionToken()` and passes `Bearer <token>` on all requests.

### 6.2 No Unintended Local-Only Success State
- **Classification:** `VERIFIED`
- **Code Inspection:** `ContactRepositoryImpl.kt:466-473, 530-537, 544-551`
- **Details:** Room DB updates (`contactDao.updateStatus`, `deleteContact`) execute **strictly inside `if (res.isSuccess)` blocks**. If the network call fails, local state remains unmodified.

### 6.3 UI Dialogs & Action Confirmations
- **Classification:** `VERIFIED`
- **Details:** `AddContactDialog` enforces regex `^NX-[A-Z0-9]{8}$`; `PendingRequestsScreen` provides Incoming and Outgoing tabs; `PeerProfileDialog` presents confirmation dialogs before removing or blocking contacts.
