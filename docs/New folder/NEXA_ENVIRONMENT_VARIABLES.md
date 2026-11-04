# NEXA — Environment Variables Reference

**Security Classification:** Strict Backend Isolation  
**Rule:** NEVER put backend secret keys (`TWILIO_AUTH_TOKEN`, `SUPABASE_SERVICE_ROLE_KEY`, `SUPABASE_JWT_SECRET`) inside the Android application.

---

## 1. Environment Variable Registry

| Variable Name | Purpose | Mandatory? | Value Source / Where to Obtain | Placement Location |
| :--- | :--- | :---: | :--- | :--- |
| `PORT` | HTTP/WS listening port | **Automated** | Render sets this automatically (e.g. `10000`). Defaults to `8080`. | Render Environment Variables |
| `APP_ENV` | Application environment mode | Optional | Set to `production` or `staging`. | Render Environment Variables |
| `GOOGLE_CLIENT_ID` | OAuth 2.0 Web Client ID | **Yes** (for Google Auth) | Google Cloud Console Project # `356056510017`: `356056510017-grmokt7u1sch6hdk9dbah85k2jqe32v2.apps.googleusercontent.com` | Render Environment Variables |
| `OTP_SERVER_PEPPER` | Cryptographic HMAC salt pepper for OTP storage | Recommended | 32-byte random hex string (e.g. `openssl rand -hex 32`) | Render Environment Variables (Secret) |
| `IDENTITY_STORE_PATH` | Path for identity snapshot file | Optional | Default: `data/identity_store.json`. On Render: `/tmp/identity_store.json` or persistent disk path. | Render Environment Variables |
| `REDIS_ADDR` | Ephemeral Redis cache host:port | Optional | External Redis URL (e.g. Upstash or Render Redis). If omitted, falls back to RAM queue. | Render Environment Variables |
| `REDIS_PASSWORD` | Ephemeral Redis password | Optional | External Redis authentication password. | Render Environment Variables (Secret) |
| `SUPABASE_URL` | Supabase project REST URL | Optional | Supabase Dashboard (`https://<project-ref>.supabase.co`). | Render Environment Variables |
| `SUPABASE_JWT_SECRET` | Supabase HS256 JWT Secret | Optional | Supabase Dashboard -> Project Settings -> API -> JWT Secret. | Render Environment Variables (Secret) |
| `TWILIO_ACCOUNT_SID` | Twilio Account SID | Optional | Twilio Console Dashboard. | Render Environment Variables (Secret) |
| `TWILIO_AUTH_TOKEN` | Twilio Auth Token | Optional | Twilio Console Dashboard. | Render Environment Variables (Secret) |
| `TWILIO_FROM_NUMBER` | Twilio phone number | Optional | Twilio Phone Numbers (E.164 format, e.g. `+1234567890`). | Render Environment Variables |
| `MSG91_AUTH_KEY` | MSG91 API Auth Key | Optional | MSG91 Dashboard (India SMS Gateway). | Render Environment Variables (Secret) |
| `MSG91_TEMPLATE_ID` | MSG91 SMS Flow/Template ID | Optional | MSG91 DLT approved template identifier. | Render Environment Variables |
| `FCM_PROJECT_ID` | Firebase Project ID | Optional | Firebase Console -> Project Settings -> General -> Project ID. | Render Environment Variables |

---

## 2. Minimal Required Variables for Initial Render Testing

To launch the backend on Render Free Tier for initial Android testing with zero additional costs:

```env
PORT=10000
APP_ENV=production
GOOGLE_CLIENT_ID=356056510017-grmokt7u1sch6hdk9dbah85k2jqe32v2.apps.googleusercontent.com
OTP_SERVER_PEPPER=a8b3c9d2e1f40582736451a9b8c7d6e5f4a3b2c1d0e9f8a7b6c5d4e3f2a1b0c9
```

*(SMS OTP verification will use `MockSmsProvider` which outputs the OTP directly into Render's Live Deployment Logs for manual input).*
