# NEXA — Render Environment Variables Guide

**Dashboard Location:** Render Dashboard → Your Web Service → **Environment** tab → **Add Environment Variable**

---

## 1. Mandatory Variables to Add in Render

Enter these exact Key-Value pairs in the Render Environment Variables section:

| Variable Name | Exact Value | Purpose |
| :--- | :--- | :--- |
| `APP_ENV` | `production` | Switches backend into production mode. |
| `GOOGLE_CLIENT_ID` | `356056510017-grmokt7u1sch6hdk9dbah85k2jqe32v2.apps.googleusercontent.com` | Validates Google Sign-In ID tokens from your Google Cloud Project # `356056510017`. |
| `OTP_SERVER_PEPPER` | `a8b3c9d2e1f40582736451a9b8c7d6e5f4a3b2c1d0e9f8a7b6c5d4e3f2a1b0c9` | Secret salt pepper for cryptographic HMAC OTP verification (`internal/auth/otp_service.go:83`). |

---

## 2. Variables Automatically Provided by Render (Do NOT Add Manually)

| Variable | Description |
| :--- | :--- |
| `PORT` | Render automatically assigns a port (e.g. `10000`) and passes it into `$PORT`. The Go server reads `os.Getenv("PORT")` and binds automatically. |
| `RENDER` | Render sets `RENDER=true` automatically. |

---

## 3. Optional Variables (Add Only if Configured)

| Variable Name | Required? | Default / Fallback Behavior |
| :--- | :---: | :--- |
| `IDENTITY_STORE_PATH` | No | Defaults to `data/identity_store.json`. Set to `/tmp/identity_store.json` if using ephemeral storage. |
| `TWILIO_ACCOUNT_SID` | Optional | If omitted, backend uses `MockSmsProvider` (prints OTPs directly into Render Live Logs). |
| `TWILIO_AUTH_TOKEN` | Optional | Twilio API Auth Token (Secret). |
| `TWILIO_FROM_NUMBER` | Optional | Twilio Phone Number (e.g. `+1234567890`). |
| `MSG91_AUTH_KEY` | Optional | MSG91 SMS gateway key for India SMS delivery. |
| `MSG91_TEMPLATE_ID` | Optional | MSG91 DLT approved SMS template ID. |
| `REDIS_ADDR` | Optional | If omitted or unreachable, backend automatically falls back to internal zero-disk RAM queue (`internal/queue/redis_buffer.go:55`). |
| `SUPABASE_URL` | Optional | Supabase Project URL (`https://<project>.supabase.co`). |
| `SUPABASE_JWT_SECRET` | Optional | Supabase HS256 JWT Secret for token verification. |
| `FCM_PROJECT_ID` | Optional | Firebase Cloud Messaging project ID. |

> [!NOTE]
> For initial deployment and Android testing, the 3 variables in **Section 1** are 100% sufficient. You can test phone registration immediately using Render's live log viewer to see the generated OTP!
