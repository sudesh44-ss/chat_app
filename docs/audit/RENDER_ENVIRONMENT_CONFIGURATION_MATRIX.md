# NEXA — Render Environment Configuration Matrix

**Audit Mode:** STRICT READ-ONLY INSPECTION  
**Target:** Render Web Service Settings  
**Date:** November 2026  

---

## 1. Full Environment Configuration Matrix

| Variable Name | Required By | Scope | Current Render Status | Target Value / Specification | Action Required |
| :--- | :--- | :--- | :---: | :--- | :--- |
| `APP_ENV` | Backend Core | Production | **Configured** | `production` | Keep as-is. |
| `PORT` | Go HTTP Server | Production | **Auto-Assigned** | Render `$PORT` (e.g. `10000`) | Managed by Render. |
| `GOOGLE_CLIENT_ID` | Google Verifier | Production | **Verify** | `356056510017-grmokt7u1sch6hdk9dbah85k2jqe32v2.apps.googleusercontent.com` | Ensure matches Android `strings.xml`. |
| `REDIS_URL` | Redis Ephemeral Buffer | Production | **MISSING in Code** | `rediss://default:<pwd>@<host>:6379` | Update Go code to parse `REDIS_URL` and add to Render. |
| `INBOUND_SMS_RECEIVER_NUMBER` | Reverse SMS Service | Production | **MISSING** (Defaults to dummy) | E.164 phone string (e.g. `+9198XXXXXXXX`) | Add real virtual mobile number. |
| `GATEWAY_WEBHOOK_SECRET` | Inbound SMS Webhook | Production | **MISSING** | 64-char Hex string | Add secret to Render & Gateway. |
| `IDENTITY_STORE_PATH` | JSON Identity Store | Production | **MISSING** (Defaults to ephemeral) | `/var/data/identity_store.json` | Attach Render Disk & set path. |
| `FCM_PROJECT_ID` | Background Wake-Up | Production | **MISSING** | Firebase Project ID | Add to Render once OAuth2 token exchange is implemented. |
| `FIREBASE_SERVICE_ACCOUNT_JSON` | Firebase OAuth2 | Production | **MISSING** | Base64-encoded service account JSON | Add when FCM OAuth2 exchange is added. |
| `OTP_SERVER_PEPPER` | Legacy Outbound OTP | Production | **Configured** | 64-char Hex salt | Keep as-is. |
| `MSG91_AUTH_KEY` | Legacy Outbound OTP | Optional | **Unset** | MSG91 Secret Key | Add only if outbound OTP needed. |
| `MSG91_TEMPLATE_ID` | Legacy Outbound OTP | Optional | **Unset** | DLT Template ID | Add only if outbound OTP needed. |
| `TWILIO_ACCOUNT_SID` | Legacy Outbound OTP | Optional | **Unset** | Twilio SID (`AC...`) | Add only if Twilio fallback needed. |
| `TWILIO_AUTH_TOKEN` | Legacy Outbound OTP | Optional | **Unset** | Twilio Token | Add only if Twilio fallback needed. |
| `TWILIO_FROM_NUMBER` | Legacy Outbound OTP | Optional | **Unset** | Twilio Phone | Add only if Twilio fallback needed. |

---

## 2. Render Deployment Settings Checklist

- **Environment:** Docker
- **Root Directory:** `backend` (Ensures `go.mod` is found).
- **Dockerfile Path:** `Dockerfile` (or `./Dockerfile`).
- **Health Check Path:** `/health`
- **Auto-Deploy:** Yes (on git push to `main`).
- **Disk Mount:** `/var/data` (Minimum 1GB persistent disk recommended).
