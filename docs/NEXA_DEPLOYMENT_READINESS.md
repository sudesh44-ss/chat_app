# NEXA — Deployment Readiness Assessment

**Date:** November 4, 2026  
**Auditor:** Senior DevOps & Cloud Infrastructure Architect  

---

## 1. Readiness Classification Summary

| Component | Status | Readiness Level | Blocker / Next Step |
| :--- | :---: | :---: | :--- |
| **Go Codebase Quality** | **PASS** | 100% | No compile errors; Linux static cross-compilation verified. |
| **Local Backend Execution** | **PASS** | 100% | Verified: Responds to HTTP/REST & WebSocket on port 8080. |
| **Render Web Service Compatibility** | **PASS** | 100% | Compatible with `$PORT`, `0.0.0.0` binding, WSS, and `/health`. |
| **Free-Tier Viability** | **PASS** | 90% | Zero-disk design fits Render Free (with cold-start caveats). |
| **Git Repository on Host** | **BLOCKED** | 0% | `H:\chat_app` does not have a `.git` root. Must push to GitHub. |
| **Production SMS Credentials** | **PARTIAL** | 50% | Mock provider works; Twilio/MSG91 keys required for live SMS. |
| **Production Google Client ID** | **READY** | 100% | Web Client ID `356056510017-grmokt7u1sch6hdk9dbah85k2jqe32v2.apps.googleusercontent.com`. |
| **Supabase Cloud Integration** | **PARTIAL** | 50% | Verifier implemented; Supabase URL & JWT secret needed in env vars. |

---

## 2. Render Free Tier vs. Paid Strategy

| Dimension | Render Free Tier ($0/mo) | Render Paid Starter ($7/mo) |
| :--- | :--- | :--- |
| **Instance Type** | Shared CPU, 512 MB RAM | Dedicated CPU slice, 512 MB - 1 GB RAM |
| **Sleep Behavior** | Spins down after 15 min of inactivity | **Always On** (No sleep) |
| **Cold Start Delay** | ~30 to 50 seconds on first request | **0 seconds** |
| **WebSocket Stability** | Allowed; resets during redeploy/sleep | Persistent long-lived connections |
| **Disk Storage** | Ephemeral (resets on deploy/restart) | Optional Persistent Disk ($1/mo for 10 GB) |
| **Bandwidth** | 100 GB outbound/month (Generous for NEXA) | 100 GB+ included |
| **Recommendation** | **Ideal for Stage 1 Testing & Physical Device Verification** | **Required for Production Multi-User Reliability** |

> [!TIP]
> Because NEXA routes large file transfers **directly via P2P WebRTC DataChannels** (zero cloud storage cost), the backend only handles lightweight signaling packets and cryptographic handshakes. The 512 MB memory footprint and 100 GB bandwidth on Render Free Tier are more than sufficient for testing.

---

## 3. Deployment Blocker Matrix

1. **Git / GitHub Repository**:
   - Render deploys from Git (GitHub or GitLab).
   - `H:\chat_app` currently has no git repository initialized.
   - **Action Required:** Initialize git in `H:\chat_app` (or `H:\chat_app\backend`), create a private GitHub repository, and push the code.
2. **Render Account**:
   - Create a free account at [render.com](https://render.com).
   - Link GitHub account to Render.
3. **Environment Variables**:
   - Provide `GOOGLE_CLIENT_ID`, `APP_ENV=production`.
   - (Optional) Provide `SUPABASE_URL`, `SUPABASE_JWT_SECRET` if Supabase auth linking is active.
   - (Optional) Provide `TWILIO_*` or `MSG91_*` for real SMS OTP; otherwise, `MockSmsProvider` prints test OTPs in the Render log viewer.
