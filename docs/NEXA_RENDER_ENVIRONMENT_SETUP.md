# NEXA Render Environment Setup Guide

**Target Web Service:** `nexa-relay` (or `chat_app`)  
**Dashboard URL:** `https://dashboard.render.com`  
**Status:** **READY FOR CONFIGURATION**  

---

## 1. Required Environment Variables

Configure these environment variables under **Render Dashboard $\rightarrow$ Web Service $\rightarrow$ Environment**:

| Variable Name | Required Value / Source | Description |
| :--- | :--- | :--- |
| `APP_ENV` | `production` | Enables production mode and disables mock fallbacks |
| `PORT` | `8080` | Internal listening port mapped by Render edge router |
| `GOOGLE_CLIENT_ID` | `356056510017-grmokt7u1sch6hdk9dbah85k2jqe32v2.apps.googleusercontent.com` | Google OAuth Web Client ID |
| `OTP_SERVER_PEPPER` | `<existing-32-byte-hex-secret>` | HMAC-SHA256 server secret pepper |
| `MSG91_AUTH_KEY` | `<your-msg91-auth-key>` | Production MSG91 authentication key |
| `MSG91_TEMPLATE_ID` | `<dlt-approved-template-id>` | DLT registered 6-digit OTP template ID |

---

## 2. Startup Verification Checklist

Once the variables are saved in Render:
1. Render will initiate an automatic redeploy.
2. Check the **Logs** tab in Render:
   - Expected startup line:
     ```text
     [SmsProvider] Initialized production MSG91 SMS Provider
     ```
   - If keys are missing, Render logs will display:
     ```text
     [SmsProvider] CRITICAL: Running in production with NO SMS provider configured! Mock fallback is DISABLED.
     ```
   - It will **never** log `Using MockSmsProvider` when `APP_ENV=production`.
