# NEXA Infrastructure Audit — 07: Environment Configuration Comparison

**Audit Scope:** Environment Variable Alignment & Security Matrix  
**Target Environments:** Local Development vs Render Production  
**Reference Code:** `backend/cmd/server/main.go:25-70`  

---

## 1. Environment Variable Specification & Cross-Environment Matrix

```
+-----------------------------------------------------------------------------------------------------------------------------------------+
|                                                  NEXA CONFIGURATION MATRIX                                                              |
+------------------------------+----------+------------------------------------------------------+----------------------------------------+
| VARIABLE                     | REQUIRED | LOCAL DEVELOPMENT VALUE                              | RENDER PRODUCTION VALUE                |
+------------------------------+----------+------------------------------------------------------+----------------------------------------+
| APP_ENV                      | YES      | development                                          | production                             |
| PORT                         | YES      | 8080                                                 | 10000 (Set automatically by Render)    |
| DATABASE_URL                 | YES      | postgres://postgres:xxx@db...co:5432/postgres?sslmode=require | postgres://postgres.kikt...:xxx@aws...pooler...:5432/postgres?sslmode=require |
| REDIS_URL                    | COND.    | Optional (or rediss://... for Upstash parity)       | rediss://default:xxx@quality-lioness...upstash.io:6379 |
| REDIS_ADDR                   | COND.    | localhost:6379 (fallback if REDIS_URL empty)        | (Do not use; use REDIS_URL)            |
| REDIS_PASSWORD               | NO       | (Empty if local Redis has no auth)                   | (Included in REDIS_URL)                |
| IDENTITY_STORE_PATH          | NO       | data/identity_store.json (Local dev fallback only)   | (PROHIBITED; must use PostgreSQL)      |
| GOOGLE_CLIENT_ID             | YES      | 528373724838-xxxx.apps.googleusercontent.com         | 528373724838-xxxx.apps.googleusercontent.com |
| OTP_SERVER_PEPPER            | YES      | 64-character hex pepper string                       | 64-character hex pepper string         |
| INBOUND_SMS_RECEIVER_NUMBER  | YES      | +12055550199                                         | +12055550199                           |
| GATEWAY_WEBHOOK_SECRET       | YES      | 32-character webhook secret                          | 32-character webhook secret            |
+------------------------------+----------+------------------------------------------------------+----------------------------------------+
```

---

## 2. Environment Loading Mechanics

### 2.1 Local Environment
- In `backend/cmd/server/main.go`, the backend utilizes `godotenv.Load()` (if `.env` exists in the working directory).
- If `backend/.env` is absent, the backend reads directly from the OS process environment (`os.Getenv`).
- If neither is provided, defaults apply:
  - `APP_ENV` defaults to `development`.
  - `PORT` defaults to `8080`.
  - `REDIS_ADDR` defaults to `localhost:6379`.
  - `DATABASE_URL` empty -> falls back to file-backed `data/identity_store.json`.

### 2.2 Production Environment (Render)
- Render injects environment variables directly into the Linux container process environment.
- No `.env` file exists or is checked in Render.
- `APP_ENV=production` activates strict fail-closed guards:
  - Missing or loopback `REDIS_ADDR` without `REDIS_URL` -> Fatal startup abort.
  - Redis connection failure or degraded mode -> Fatal startup abort.
  - PostgreSQL connection failure or missing `DATABASE_URL` -> Fatal startup abort.
  - Falling back to `data/identity_store.json` -> Fatal startup abort.

---

## 3. Disallowed & Deprecated Variables

The following variables were observed in previous configurations or attempted deployments and must be purged:

1. `UPSTASH_REDIS_REST_URL`: **UNSUPPORTED**. Not read by `go-redis/v9`.
2. `UPSTASH_REDIS_REST_TOKEN`: **UNSUPPORTED**. Not read by `go-redis/v9`.
3. `DATABASE_DIRECT_URL`: Not read by the backend. The backend reads exclusively from `DATABASE_URL`.
