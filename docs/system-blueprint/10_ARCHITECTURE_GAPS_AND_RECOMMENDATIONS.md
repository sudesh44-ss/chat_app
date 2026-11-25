# NEXA — Master System Architecture Blueprint
## 10. Architectural Gaps, Technical Debt & Engineering Roadmap

---

### 1. Document Objective

This document provides a candid, rigorous technical analysis of the architectural gaps, technical debt, un-wired code paths, and scaling bottlenecks identified during the architectural investigation of NEXA. Each gap is paired with an actionable, prioritized engineering remediation strategy.

---

### 2. High-Priority Architectural Gaps & Technical Debt

#### Gap 1: In-Memory Client Auth Simulation (`AuthApiServiceImpl.kt`)
- **Current State:** In the Android client, `AuthApiServiceImpl.kt` operates with an in-memory simulation for certain registration endpoints. It accepts any 6-digit OTP code in local mock mode and derives random mock NX-IDs when server connection is unconfigured.
- **Risk / Architectural Drift:** If a developer tests onboarding without configuring the real Go backend URL, the app defaults to simulated state without hitting the actual PostgreSQL identity registry.
- **Remediation Plan:**
  1. Deprecate mock fallbacks in release builds via `#if !DEBUG` / `BuildConfig.DEBUG` guards.
  2. Enforce strict HTTP REST network binding in `AuthRepositoryImpl` pointing directly to the Go backend `/api/v1/auth/` routes.
  3. Ensure that an un-reachable backend triggers an explicit, user-friendly offline retry banner rather than silently falling back to mock registration.

#### Gap 2: Production Google OAuth & SMS Gateway Credentials Provisioning
- **Current State:** `app/build.gradle.kts` defines `GOOGLE_WEB_CLIENT_ID` with a development credential (`356056510017-grmokt7u1sch6hdk9dbah85k2jqe32v2.apps.googleusercontent.com`). The backend `internal/auth/msg91.go` contains placeholders for production MSG91 SMS authentication keys (`MSG91_AUTH_KEY`).
- **Risk / Architectural Drift:** Attempting to run a public production deployment without provisioning verified Google Console OAuth credentials and funded SMS gateway accounts will break onboarding for external users.
- **Remediation Plan:**
  1. Provision production Google OAuth 2.0 Web Client credentials linked to the production release keystore SHA-1 fingerprint.
  2. Store `MSG91_AUTH_KEY`, `FIREBASE_SERVICE_ACCOUNT_JSON`, and `TRUECALLER_PARTNER_KEY` inside secure environment variables managed via HashiCorp Vault or Docker secrets.

#### Gap 3: Complete Wiring of Multi-Device State Sync Engine
- **Current State:** The database migrations (`009_create_device_sessions.sql`), Room entities (`DeviceSyncEntity`, `PeerSyncCursorEntity`), and backend sync hub (`internal/sync/hub.go`) are fully implemented. However, background delta synchronization between multiple active physical devices owned by the same NX-ID is currently triggered on-demand rather than on a real-time reactive pipeline.
- **Risk / Architectural Drift:** A user active on both an Android phone and an Android tablet might experience a slight delay in chat read status reflection across devices until a manual pull occurs.
- **Remediation Plan:**
  1. Wire `PACKET_DEVICE_SYNC` wire frames directly into `WebSocketManager` as a real-time reactive event.
  2. Implement an automated Room trigger: whenever a message is marked `READ` locally on Device A, dispatch a lightweight `PACKET_DEVICE_SYNC` broadcast to Device B via the Go relay hub.

#### Gap 4: Production FCM Credentials & google-services.json Packaging
- **Current State:** The backend Go service (`fcm.FcmService`) is fully implemented using the modern Firebase HTTP v1 API. The Android client contains `NexaPushManager` and the necessary notification channels. However, `google-services.json` must be officially generated from the production Firebase console and placed in `app/`.
- **Risk / Architectural Drift:** Without `google-services.json`, background wake-up pings cannot reach sleeping Android devices whose network sockets have been terminated by Android OS Doze mode.
- **Remediation Plan:**
  1. Generate production `google-services.json` from the Firebase Developer Console.
  2. Bind `NexaFirebaseMessagingService` directly to `NexaUniversalTransportIngest` to wake the app and drain pending packets from the Redis queue.

---

### 3. Medium & Low Priority Technical Debt

| Priority | Component | Issue Description | Proposed Architecture Resolution |
|---|---|---|---|
| **MEDIUM** | DTN Mesh Routing | BLE L2CAP CoC packet transmission is capped at 64 KB bundles. Larger media transfers over mesh require Wi-Fi Aware socket negotiation. | Implement automatic transport promotion: if DTN bundle > 64 KB, automatically trigger Wi-Fi Aware NAN discovery and establish high-speed direct TCP stream. |
| **MEDIUM** | YARA Native Engine | `YaraNativeBridge.kt` relies on a compiled native C++ shared library (`.so`). OTA differential rule updates are currently cached in memory. | Implement on-disk encrypted rule caching in app sandbox; verify rule signature against backend developer key before loading into native memory. |
| **LOW** | Room Migrations | `NexaDatabase.kt` defines 28 explicit sequential migrations, causing verbose code overhead (~800 lines). | Group legacy migrations v1 to v20 into an automated consolidated baseline schema; retain explicit migrations for versions 21 through 28+. |
| **LOW** | Sound Settings | Custom sound player directly binds Android `MediaPlayer`. | Refactor audio effects to `SoundPool` for lower latency audio playback during rapid typing and message send micro-interactions. |

---

### 4. Phased Engineering Remediation Roadmap

```
+---------------------------------------------------------------------------------------------------+
|                               PHASED ENGINEERING REMEDIATION ROADMAP                              |
|                                                                                                   |
|  PHASE 1: PRODUCTION ONBOARDING HARDENING (Weeks 1 - 2) [HIGH PRIORITY]                           |
|    - Remove all mock fallbacks in AuthApiServiceImpl for release builds.                          |
|    - Configure production Google OAuth Web Client ID and release keystore SHA-1.                  |
|    - Secure environment injection for MSG91, Firebase Admin, and Truecaller secrets.              |
|                                                                                                   |
|  PHASE 2: REAL-TIME MULTI-DEVICE REACTIVITY (Weeks 3 - 4) [HIGH PRIORITY]                         |
|    - Bind PACKET_DEVICE_SYNC directly to WebSocket write pumps for instant multi-device read sync.|
|    - Deploy google-services.json to Android app/ directory and test Doze mode wake-up pings.      |
|    - Verify atomic OPK replenishment threshold: alert client when OPK pool drops below 20 keys.   |
|                                                                                                   |
|  PHASE 3: MESH AUTOMATION & HARDWARE OPTIMIZATION (Weeks 5 - 6) [MEDIUM PRIORITY]                 |
|    - Automate BLE L2CAP to Wi-Fi Aware bandwidth handoff for media files > 64 KB.                 |
|    - Implement encrypted on-disk storage for OTA differential YARA malware rules.                 |
|    - Refactor UI audio feedback to Android SoundPool.                                             |
|                                                                                                   |
|  PHASE 4: LOAD TESTING & SECURITY PENETRATION AUDIT (Weeks 7 - 8) [FINAL CERTIFICATION]           |
|    - Execute 100,000 concurrent WebSocket session stress test on Go relay cluster.                |
|    - Conduct independent third-party cryptographic audit of Double Ratchet & X3DH implementations.|
|    - Perform Play Protect compliance check and Google Play Store pre-launch report verification.  |
+---------------------------------------------------------------------------------------------------+
```

---

### 5. Production Deployment & Operational Checklist

Before issuing a public release build of NEXA, verify the following checklist:

- [ ] **1. Compilation & Test Verification:**
  - `gradlew testDebugUnitTest` passes 100% of unit tests (minimum 111 tests).
  - `gradlew assembleRelease` completes with ProGuard / R8 minification enabled.
  - Native `.so` libraries confirmed 16 KB page-size compliant (`16KB_PAGE_SIZE_COMPATIBILITY_REPORT.md`).
- [ ] **2. Secrets & Key Management:**
  - `PHONE_HMAC_KEY` (32 bytes) and `PHONE_ENC_KEY` (32 bytes) configured in backend environment.
  - `OTP_HMAC_PEPPER` injected; development default pepper rejected in production mode.
  - Keystore release alias and password secured via CI/CD environment variables.
- [ ] **3. Backend Infrastructure:**
  - PostgreSQL 15+ database provisioned with migrations 001 through 018 executed.
  - Row-Level Security (RLS) active and verified on all public tables.
  - Redis 7.x running with 24-hour key expiration policies and password authentication.
- [ ] **4. Cloud Storage & Relay Policies:**
  - Cloudflare R2 bucket configured with 7-day automatic object expiration lifecycle rule.
  - Ephemeral media upload ticket rate limiters active (max 100 MB per file).
- [ ] **5. Client Privacy Verification:**
  - Confirm zero plaintext message logs written to Android Logcat in release builds (`android.util.Log.d` disabled).
  - Confirm EXIF stripping active on all image attachments before network dispatch.
