# NEXA — P1 Configuration Report

**Date:** 2026-11-07  
**Branch:** `main`  
**Scope:** Production Deployment Configuration, Environment Variables, & Infrastructure Sizing

---

## 1. Environment Variables Specification

Following Phase P1 remediations, the backend uses the following environment variables. In production, security-critical variables fail closed if unconfigured or pointing to invalid dummy placeholders.

| Variable Name | Required in Prod | Valid Format / Example | Default / Dev Fallback | Purpose & Behavior |
|---|---|---|---|---|
| `APP_ENV` / `ENV` | **YES** | `production` or `development` | `development` | Dictates fail-closed enforcement for DB, Redis, and Rate Limiter. |
| `DATABASE_URL` | **YES** | `postgres://user:pass@host:5432/nexa?sslmode=require` | In-memory / JSON fallback in dev | Authoritative identity, device, prekey, and connection store. |
| `REDIS_URL` | **YES** | `rediss://default:token@host:6379` | `localhost:6379` (RAM in dev) | Cluster Pub/Sub channel (`nexa:events:cluster`), rate limiter sorted sets, and 24h offline buffer. |
| `INBOUND_SMS_RECEIVER_NUMBER` | **YES** | `+91XXXXXXXXXX` (E.164, valid domestic mobile/virtual) | `+919800000000` (Dev only) | GSM receiver number displayed on Android client for Reverse SMS verification. Dummy rejected in prod. |
| `GATEWAY_WEBHOOK_SECRET` | **YES** | Minimum 32-char high-entropy secret hex/token | None (rejects webhooks if missing) | HMAC-SHA256 secret for telecom carrier inbound webhook authentication. |
| `TWILIO_AUTH_TOKEN` | Optional | Twilio 32-char Auth Token | None | Secret token used when validating Twilio-formatted webhook callbacks (`ValidateTwilioSignature`). |
| `FCM_PROJECT_ID` | Optional | Google Cloud Project ID | Local test mode | Background silent FCM wake-up pings for offline recipients. |
| `FCM_CREDENTIALS_JSON` | Optional | Google Service Account JSON | Local test mode | Credentials for Google Firebase Cloud Messaging v1 HTTP API. |

---

## 2. Distributed Rate Limiter Sizing & Allocation

The distributed rate limiter uses Redis Sorted Sets (`ZSET`) via Lua script.

### 2.1 Storage Key Schemas & Retention

| Key Pattern | Purpose | Limits Enforced | Key TTL | Estimated Size per User |
|---|---|---|---|---|
| `ratelimit:discover:<nxId>:<ip>` | User discovery rate limit | 30 requests / 60 seconds | 60s (auto-expire) | ~2.5 KB (30 score entries) |
| `ratelimit:conn_req:<nxId>` | Connection request rate limit | 10 requests / 60 seconds | 60s (auto-expire) | ~1.0 KB (10 score entries) |
| `ratelimit:reversesms:ip:<ip>` | Reverse SMS initiation per IP | 5 requests / 15 minutes | 900s (auto-expire) | ~0.5 KB (5 score entries) |
| `ratelimit:reversesms:user:<uid>` | Reverse SMS initiation per user | 3 requests / 24 hours | 86400s (auto-expire) | ~0.3 KB (3 score entries) |

### 2.2 Redis Infrastructure Sizing

For an active user base of **100,000 daily active users (DAU)**:
- **Rate Limit Keys:** Maximum 20,000 concurrent active sliding window keys at peak hour.
- **Rate Limit RAM:** `20,000 keys * ~1.5 KB = ~30 MB RAM`.
- **Ephemeral Queue RAM:** `100,000 packets/day * 4 KB average = ~400 MB RAM`.
- **Pub/Sub Memory Footprint:** Zero disk storage; ephemeral in-memory delivery buffer (< 5 MB).
- **Recommended Minimum Redis Instance:** 1 GB to 2 GB Managed Redis (e.g., Render Starter/Standard Redis or AWS ElastiCache t4g.small).

---

## 3. Multi-Instance Cluster Configuration Matrix

When scaling beyond a single instance on Render, Kubernetes, or Docker Swarm:

```
+-----------------------------------------------------------------------------+
|                           CLUSTER CONFIGURATION                             |
+--------------------------+-----------------------+--------------------------+
| Parameter                | Instance 1            | Instance 2               |
+--------------------------+-----------------------+--------------------------+
| APP_ENV                  | production            | production               |
| REDIS_URL                | shared rediss://...   | shared rediss://...      |
| DATABASE_URL             | shared postgres://... | shared postgres://...    |
| INBOUND_SMS_RECEIVER_NUM | +9198XXXXXXXX         | +9198XXXXXXXX            |
| GATEWAY_WEBHOOK_SECRET   | <shared-secret>       | <shared-secret>          |
| Instance ID (Auto)       | inst_<time>_<rand1>   | inst_<time>_<rand2>      |
| Pub/Sub Channel          | nexa:events:cluster   | nexa:events:cluster      |
+--------------------------+-----------------------+--------------------------+
```

### 3.1 Verification Health Check
To verify cluster health and Redis connectivity on any instance:
- `GET /health` returns HTTP 200 with Redis buffer and DB statuses.
- If Redis is disconnected, `buffer.IsDegraded()` returns `true` and the health endpoint reflects degraded state.
- In production, server aborts during boot if Redis is unreachable, guaranteeing no unmonitored degraded instances receive production traffic.
