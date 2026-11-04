# NEXA — Deployment & Environment Configuration Audit

**Audit Date:** November 4, 2026  
**Auditor:** DevOps Engineer & Security Auditor  
**Scope:** Backend Services, Cloud Hosting, Environment Variables & Secret Protection  

---

## 1. Environment Variable Audit Checklist

| Subsystem | Variable Name | Required Location | Audit Finding |
|---|---|---|---|
| **Supabase** | `SUPABASE_URL` | Go Backend & Android Client | Missing from active environment; must be configured. |
| **Supabase** | `SUPABASE_ANON_KEY` | Android Client | Missing from active environment; public client key. |
| **Supabase** | `SUPABASE_SERVICE_ROLE_KEY` | Go Backend ONLY | Missing from active environment; NEVER put in Android APK. |
| **Supabase** | `SUPABASE_JWT_SECRET` | Go Backend ONLY | Used for offline HS256 JWT validation on relay server. |
| **Supabase** | `DATABASE_URL` | Go Backend & Migration Tool | PostgreSQL connection string (`postgresql://postgres:...`). |
| **Google Auth** | `GOOGLE_SERVER_CLIENT_ID` | Android & Supabase Provider | Web Client ID for Credential Manager token verification. |
| **Phone OTP** | `TWILIO_ACCOUNT_SID` | Go Backend | Twilio SMS API Account SID. |
| **Phone OTP** | `TWILIO_AUTH_TOKEN` | Go Backend | Twilio SMS API Auth Token. |
| **Phone OTP** | `TWILIO_FROM_NUMBER` | Go Backend | Twilio Phone Number (E.164). |
| **Phone OTP** | `MSG91_AUTH_KEY` | Go Backend | MSG91 SMS API Key (India delivery). |
| **Phone OTP** | `MSG91_TEMPLATE_ID` | Go Backend | MSG91 DLT Flow/Template ID. |
| **Relay Backend** | `PORT` | Go Backend | Port for WebSocket / HTTP (Default: `8080`). |
| **Relay Backend** | `REDIS_ADDR` | Go Backend | Redis host:port for ephemeral queue (Default: `localhost:6379`). |
| **FCM** | `FCM_SERVER_KEY` | Go Backend | Firebase Cloud Messaging server key for background wake-ups. |

---

## 2. Deployment Architecture & Hosting Status

### A. Docker Services (`backend/docker-compose.yml`)
The backend is containerized into microservices:
1. `nexa-redis-ephemeral`: In-memory Redis 7 with disk persistence disabled (`--save "" --appendonly no`).
2. `nexa-relay-server`: Core WebSocket relay, Ed25519 authentication, and REST APIs.
3. `nexa-signaling-hub`: Ephemeral RAM-only WebRTC P2P signaling hub (port 8083).
4. `nexa-swarm-coordinator`: P2P chunk swarm coordinator (port 8081).
5. `nexa-blind-relay`: Ciphertext blind postman forwarder (port 8082).

### B. Deployment Reality Check
- **Local Containerization**: Ready and configured via `docker-compose.yml`.
- **Cloud Deployment**: **NOT DEPLOYED**. No live public HTTPS/WSS URL is currently accessible. The server runs locally on `ws://0.0.0.0:8080/ws` during testing.
- **Production Status**: Marked **PARTIAL / LOCAL ONLY** until cloud hosting (Railway, Fly.io, or VPS) with TLS certificates is provisioned.

---

## 3. Secret Protection Audit
- **Android APK Inspection**:
  - ProGuard rules keep sensitive classes obscured.
  - Inspected `strings.xml`, `BuildConfig`, and Java/Kotlin constants:
    **ZERO** service-role keys, private identity keys, or database passwords are hardcoded or exposed.
- **Reporting Security**:
  - No secret values, authentication tokens, or personal credentials are exposed in documentation.
