# NEXA — Remaining Blockers & Next Actions Ledger

## 1. Blocker Status Overview

All **Critical (P0)** and **High (P1)** architectural, stability, and compatibility blockers have been **RESOLVED**.

There are currently **ZERO P0 blockers** preventing application startup, cryptographic messaging, local database encryption, or contact discovery.

---

## 2. Low-Priority Operational & Non-Functional Items (P2 / P3)

| Item ID | Description | Priority | Recommended Action |
| :--- | :--- | :--- | :--- |
| **OP-001** | Production Supabase Instance Provisioning | **P2** | Apply migrations `001_` to `014_` on the live hosted Supabase project and bind production database URL. |
| **OP-002** | Google Cloud Console OAuth Client ID | **P2** | Populate release SHA-256 fingerprint in Google Cloud Console for production Google Sign-In. |
| **OP-003** | TURN Server Credential Refresh Integration | **P3** | Configure Coturn ephemeral credential generation API for WebRTC P2P direct fallback behind symmetric NATs. |
| **OP-004** | SMS Gateway Provider API Key Configuration | **P3** | Populate Twilio / Fast2SMS credentials in server environment for production international OTP SMS delivery. |

---

## 3. Post-Release Monitoring Recommendations
1. Monitor SQLCipher database keying telemetry across Android OEM distributions (Samsung One UI, Xiaomi HyperOS, vivo Funtouch OS).
2. Track WebRTC DataChannel connection success rates across cellular LTE/5G and dual-SIM configurations.
3. Validate WorkManager periodic background sync battery consumption using Android Battery Historian.
