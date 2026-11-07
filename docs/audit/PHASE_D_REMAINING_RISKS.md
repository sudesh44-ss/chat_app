# PHASE D — REMAINING RISKS & OPERATIONAL CONSIDERATIONS

## 1. Operational Risks & Mitigations

### 1.1 Render PostgreSQL Migration Deployment
- **Risk:** When deploying to Render, Migration 2 (`002_create_connections_schema`) must execute successfully against the production database without locking existing operations.
- **Mitigation:** The migration runs automatically on server boot inside a transaction, uses `CREATE TABLE IF NOT EXISTS` and `CREATE INDEX IF NOT EXISTS`, and references the existing `accounts(nx_id)` foreign key. Tested with zero downtime in staging tests.

### 1.2 In-Memory Fallback vs. Redis Rate Limiting
- **Risk:** Currently, the discovery and connection request rate limiters in `backend/internal/connections/handler.go` use an in-memory sliding window cache. If multiple Render instances run behind a load balancer, rate limits are enforced per-instance rather than globally.
- **Mitigation:** For single-instance Render standard deployments, in-memory rate limiting is completely effective. In Phase E or production scaling, rate limiting counters can be backed by Redis `INCR` with `EXPIRE`.

### 1.3 Offline Connection Notifications
- **Risk:** If Bob is offline when Alice sends a connection request, Bob will receive the request via REST when he opens the app, but he will not receive a real-time WebSocket notification while offline.
- **Mitigation:** REST endpoints (`/api/v1/connections/requests/incoming`) remain the authoritative source of truth that Android synchronizes on launch and screen navigation. Push notifications (FCM) are scheduled as a dedicated future phase.

---

## 2. Scope Boundary & Transition to Phase E

Per the Controlled Implementation specification:
- **Phase D Scope:** Connection and contact management, user discovery, bilateral relationship requests, connection removal, blocking/unblocking, and real-time WebSocket connection state notifications.
- **Phase D Boundaries:** Did NOT implement message routing, message delivery receipts, FCM push notifications, or Bluetooth/Mesh transport.
- **Phase E Readiness:** With bilateral connection relationships now authoritatively persisted in PostgreSQL and synchronized to Android, NEXA is fully prepared for Phase E (End-to-End Encrypted Message Routing & Ratchet Session Initialization between verified connections).
