# NEXA — Phase A Remaining Blockers & Future Phases Roadmap

**Date:** November 6, 2026  
**Status:** Phase A Complete; Awaiting Authorization for Subsequent Phases  

---

## 1. Scope Boundary Adherence

As strictly mandated:
- Phone verification was **not bypassed**.
- No dummy accounts were artificially marked as verified.
- Webhook cryptographic authentication was **strengthened and enforced**, not relaxed.
- The zero-permission Client-Initiated Reverse SMS architecture was preserved without introducing dangerous telephony permissions.
- Downstream domains (FCM, PostgreSQL, WebSocket state refactoring) were kept strictly out-of-scope for Phase A.

---

## 2. Documented Remaining Blockers

The following items are intentional architectural deferrals to be executed in subsequent phases:

### Blocker 1: Managed PostgreSQL Storage & Repository Migration (Phase C)
- **Current State:** Identity data is stored via `identity.Store` using JSON file persistence (`identity_store.json`).
- **Production Impact:** While functional for ephemeral and single-instance deployments, horizontal scaling of the Go relay across multiple Render instances requires a centralized relational database.
- **Remediation Plan (Phase C):** Implement PostgreSQL connection pooling using `pgxpool`, run automated schema migrations (`schema.sql`), and transition repository interfaces.

### Blocker 2: FCM Background Wake-Up Engine (Phase B)
- **Current State:** FCM runs in test/local mock mode because `FCM_PROJECT_ID` and `FCM_CREDENTIALS_JSON` are not yet configured on Render.
- **Production Impact:** Offline mobile clients cannot receive background push wake-ups when their WebSocket connection drops into Android Doze mode.
- **Remediation Plan (Phase B):** Configure Firebase Admin SDK with service account credentials and wire push tokens upon device registration.

### Blocker 3: WebSocket Identity & Session Challenge Hardening (Phase B)
- **Current State:** Real-time messaging uses `/ws` with Ed25519 cryptographic challenges.
- **Production Impact:** End-to-end multi-device routing and offline queue retrieval must be validated against production token lifetimes.
- **Remediation Plan (Phase B):** Harden connection lifecycle, blind-token registration, and heartbeat pong monitors.

### Blocker 4: Physical Telecom Carrier Inbound Loop (Operational Dependency)
- **Current State:** The code logic, webhook parsing, HMAC validation, and database state transitions are 100% verified via automated tests.
- **Action Required by User/Operations:**
  1. Procure or designate the inbound receiving number (e.g. Twilio number, GSM gateway SIM).
  2. Enter the live number in Render as `INBOUND_SMS_RECEIVER_NUMBER`.
  3. Enter the webhook secret in Render as `GATEWAY_WEBHOOK_SECRET`.
  4. Perform a real-world device test: open NEXA on a physical Android phone, trigger reverse SMS, send the pre-filled text from Google Messages, and verify the live handshake completes within 10 seconds.

---

## 3. Recommended Next Phase: Phase B

Upon user approval, Phase B will focus on:
1. **FCM Push Notification Engine:** Configuring Firebase credentials and validating background device wake-up payloads.
2. **WebSocket Session & Real-Time Sync:** Testing live bidirectional packet exchange, ephemeral message queuing, and offline delivery across multiple connected devices.
