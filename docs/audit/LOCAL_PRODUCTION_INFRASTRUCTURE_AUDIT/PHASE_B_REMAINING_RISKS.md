# NEXA — Phase B Remaining Risks & Phase C Roadmap

**Date:** November 6, 2026  
**Status:** Phase B Complete; Awaiting Authorization for Subsequent Phases  

---

## 1. Scope Boundary Adherence

As strictly mandated:
- JSON storage was **not migrated to PostgreSQL** in this phase.
- FCM background wake-up engine was **not modified**.
- Reverse SMS architecture from Phase A was **preserved intact**.
- Google OAuth was **not changed**.
- E2EE Double Ratchet encryption was **preserved without modifying X25519 DH keys**.
- No existing user identities were deleted.
- No deployments were pushed to Render without approval.
- No production secrets were modified or exposed.

---

## 2. Identified Remaining Architectural Risks

### Risk 1: Ephemeral Identity Storage across Horizontal Scaling (Phase C Dependency)
- **Current Architecture:** Identity, devices, and sessions persist in `identity_store.json`.
- **Risk:** If the Go relay container on Render is restarted without persistent disk mounting, or scaled across multiple worker instances, local file changes do not replicate across nodes.
- **Mitigation (Phase C):** Migrate `identity.Store` to managed PostgreSQL (`pgxpool`) using the prepared `schema.sql` database schema and automated migrations.

### Risk 2: Device Wake-Up during Android Doze Mode (Phase D / Push Engine)
- **Current Architecture:** Real-time messaging depends on live WebSocket connection (`wss://nexa-relay.onrender.com/ws`).
- **Risk:** When an Android device enters deep Doze mode with the screen off for an extended period, the OS restricts background TCP connections.
- **Mitigation:** Configure Firebase Cloud Messaging credentials (`FCM_PROJECT_ID`, `FCM_CREDENTIALS_JSON`) so the server dispatches a high-priority push notification waking the client to reconnect its socket.

### Risk 3: Physical Telecom End-to-End Carrier Loop
- **Current Architecture:** All webhook parsing, HMAC validation, and database updates pass automated testing.
- **Risk:** Real-world cellular carriers may introduce delivery latency, SMS filtering, or shortcode alterations depending on domestic telecom DLT regulations.
- **Mitigation:** Operational end-to-end test with a physical SIM card transmitting `NEXA-VERIFY:<nonce>:<sessionId>` to the live designated inbound number.

---

## 3. Recommended Next Phase: Phase C

Upon user review and authorization, Phase C will address:
1. **PostgreSQL Repository Migration:** Transitioning identity, device, session, and audit logs from `identity_store.json` to PostgreSQL.
2. **Schema Migrations:** Executing idempotent DDL scripts and connection pool tuning (`pgxpool`).
3. **Multi-Instance Concurrency:** Ensuring distributed lock coordination and session consistency across horizontally scaled relay instances.
