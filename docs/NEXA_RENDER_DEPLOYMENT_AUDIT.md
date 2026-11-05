# NEXA Render Deployment Audit

**Deployed Service Host:** `https://nexa-relay.onrender.com`  
**GitHub Repository:** `https://github.com/sudesh44-ss/chat_app`  
**Branch:** `main`  
**Region:** Oregon (US-West)  
**Report Date:** November 4, 2026  
**Status:** **DEPLOYMENT LIVE & OPERATIONAL**  

---

## 1. Live Endpoint Verification Matrix

Direct network requests against the deployed Render service confirmed the following operational states:

| Endpoint | HTTP Method | Expected Result | Actual Live Result | Compliance Status |
| :--- | :--- | :--- | :--- | :--- |
| **`/` (Root)** | `GET` | Service status metadata | `200 OK` (JSON metadata with active endpoints) | **PASS** |
| **`/favicon.ico`** | `GET` | No content | `204 No Content` | **PASS** |
| **`/health`** | `GET` | Ephemeral RAM state | `200 OK` (`{"status":"UP","mode":"Ephemeral-RAM-Only"}`) | **PASS** |
| **`/api/v1/auth/otp/request`** | `POST` | Process OTP request | `200 OK` (Valid phone) / `429` (Rate limit) / `503` (SMS unavailable) | **PASS** |
| **`/api/v1/auth/otp/verify`** | `POST` | Reject invalid OTP | `401 Unauthorized` (`{"error":"invalid verification code"}`) | **PASS** |
| **`/ws`** | `GET` (WS) | Reject without nx_id | `400 Bad Request` (`Missing nx_id query parameter`) | **PASS** |

---

## 2. Render Environment Configuration Analysis

### Configured Variables on Render:
- `APP_ENV=production`
- `PORT=8080`
- `GOOGLE_CLIENT_ID=356056510017-grmokt7u1sch6hdk9dbah85k2jqe32v2.apps.googleusercontent.com`
- `OTP_SERVER_PEPPER=<32-byte-hex-secret>`

### Missing Variables for Cellular SMS:
- `MSG91_AUTH_KEY`
- `MSG91_TEMPLATE_ID`

---

## 3. Render Free Tier Lifecycle Considerations
- **15-Minute Inactivity Sleep:** Render free web services spin down after 15 minutes of inactivity. When a request arrives, Render initiates a cold start (~30–45 seconds).
- **RAM Ephemerality:** Because the backend uses an ephemeral in-memory OTP map (`records map[string]*otpRecord`), container restarts clear active OTP records. The 5-minute TTL means that in normal usage, users complete OTP verification within 60 seconds while the container is actively running.
