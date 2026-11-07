# NEXA Phase C — Remaining Risks & Operational Recommendations

**Author:** Antigravity Senior Security & Systems Architect  
**Date:** 2026-11-06  
**Status:** AUDITED  
**Scope:** Production Deployment, Environment Variables, and Next Phase Dependencies  

---

## 1. Executive Summary

Phase C has established PostgreSQL as the authoritative identity repository and Redis as the distributed state and queue buffer. This document details remaining operational risks, infrastructure requirements on Render, and guidance for Phase D.

---

## 2. Risk Matrix

| Risk ID | Category | Description | Severity | Mitigation Implemented | Action Required for Production |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **RISK-C1** | Configuration | `DATABASE_URL` unset in Render dashboard causes server to fall back to volatile JSON store. | **HIGH** | Server automatically operates in `FALLBACK_JSON` mode and logs explicit status in `/health`. | Configure `DATABASE_URL` with internal Render PostgreSQL connection string. |
| **RISK-C2** | Performance | PostgreSQL connection pool saturation during sudden spikes of concurrent mobile clients. | **MEDIUM** | `MaxConns: 25` prevents pool exhaustion; `ConnectTimeout: 5s` prevents Goroutine hangs. | Monitor database connections via Render metrics; upgrade to Render Standard DB if active users exceed 5,000. |
| **RISK-C3** | Network | TLS handshake latency on remote Redis or PostgreSQL connections outside Render internal network. | **LOW** | Internal DNS names (`dpg-...` for Render Postgres, `red-...` for Render Redis) run over Render private network with sub-millisecond RTT. | Use internal service URLs rather than external public URLs for both Redis and PostgreSQL. |
| **RISK-C4** | Reliability | SMS provider webhook drops or timeouts due to mobile carrier network lag. | **MEDIUM** | Distributed Redis caching retains nonces for 95 seconds and allows multi-instance resolution. | Ensure carrier webhook endpoint has high availability and SSL certificates are auto-renewed. |

---

## 3. Required Render Environment Variables for Phase C

Before deploying to Render, the following environment variables must be configured in the Render Dashboard (never commit to Git):

```ini
# Production Environment Flag
APP_ENV=production
PORT=10000

# PostgreSQL Authoritative Identity Store
# Example: postgresql://nexa_user:<SECURE_PASSWORD>@dpg-xxxxxxxx-a:5432/nexa_db?sslmode=require
DATABASE_URL=postgresql://<user>:<password>@<internal_pg_host>:5432/<dbname>?sslmode=require

# Ephemeral Redis Buffer & Challenge Store
# Example: redis://red-xxxxxxxx:6379 or rediss://...
REDIS_URL=redis://<internal_redis_host>:6379

# Inbound Virtual Receiver Number (Real production domestic number)
INBOUND_SMS_RECEIVER_NUMBER=+91XXXXXXXXXX

# Inbound Webhook Signing Secret (HMAC-SHA256)
INBOUND_WEBHOOK_SECRET=<64_CHAR_HEX_SECRET>

# Optional Path for JSON Backup Retention
IDENTITY_STORE_PATH=data/identity_store.json
```

---

## 4. Phase D Scope Boundary

Per mission instructions:
- **Phase C is complete and verified.**
- **Do NOT begin Phase D** (FCM Push Notification Service, background wake-up triggers, battery optimization remediation) until explicit user authorization is provided.
