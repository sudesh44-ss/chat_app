# NEXA — Demo, Dummy, Mock & Placeholder Audit

**Audit Scope:** Repository-Wide Audit of All Mock Implementations, Seeded Data, Dummy Endpoints, Development Bypasses, Hardcoded Values, and Stubs  
**Repository:** `H:\chat_app`  
**Date:** November 10, 2026  
**Auditor Mode:** Strict Read-Only Source Code Inspection  

---

## 1. Executive Summary

This audit conducted an exhaustive search across the entire NEXA codebase for development stubs, simulated networks, demo seeders, hardcoded constants, and mock providers.

### Key Takeaways:
- **Zero Accidental Leakage in Production:** All seeded demo data and development bypasses are strictly fenced behind `BuildConfig.DEBUG && DEV_BYPASS_AUTH_TO_HOME_SCREEN` or `APP_ENV != "production"`.
- **Failsafe Production Rejection:** The Go backend has explicit checks that abort startup or reject calls if mock SMS providers or in-memory volatile databases are invoked in production.
- **Unconnected Stubs Identified:** The primary placeholders are empty lambda handlers in Android `SettingsScreen` (e.g. Language, Network, Storage) and an unimplemented Google OAuth2 token exchange in `fcm_service.go`.

---

## 2. Complete Findings Inventory

| Finding / Item | File & Line Reference | Feature / Subsystem | Classification | Production Risk Level | Audit Analysis & Verification |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **`DemoDataSeeder`** | `app/.../core/demo/DemoDataSeeder.kt:77` | Seeded demo contacts & conversations | **Debug-Only / Safe** | **Low (Zero Risk)** | Fenced by `if (!BuildConfig.DEBUG && !isBypassAuthToHomeScreenEnabled) return`. Never executes in release builds. Completely offline; zero network sync. |
| **`DEV_BYPASS_AUTH_TO_HOME_SCREEN`** | `app/.../core/config/AppLaunchConfig.kt:27` | Launch directly to home screen | **Debug-Only / Safe** | **Low (Zero Risk)** | Evaluated strictly via `val isBypassAuthToHomeScreenEnabled: Boolean get() = BuildConfig.DEBUG && DEV_BYPASS_AUTH_TO_HOME_SCREEN`. In release builds (`DEBUG=false`), guaranteed `false`. |
| **`MockSmsProvider`** | `backend/internal/auth/sms_provider.go:182` | In-memory mock SMS dispatcher | **Test / Dev-Only** | **Low (Zero Risk)** | In production (`APP_ENV=production`), `GetDefaultSmsProvider()` strictly rejects mock fallback and returns `UnavailableSmsProvider`. `SendOtp()` logs alert and returns error. |
| **`isTestMode` in AuthApiService** | `app/.../data/remote/api/AuthApiService.kt:51` | Mock REST responses in Android | **Test-Only** | **Low (Zero Risk)** | Parameter `isTestMode: Boolean = false` defaults to `false`. Only enabled in unit test fixtures (`testDebugUnitTest`). |
| **Hardcoded Inbound Shortcode** | `backend/internal/auth/reverse_sms.go:161` | Inbound receiver phone default | **Hardcoded Fallback** | **Medium** | If `INBOUND_SMS_RECEIVER_NUMBER` environment variable is unset, defaults to `+919800000000`. Production must set valid carrier inbound number. |
| **Unimplemented FCM Token Exchange** | `backend/internal/fcm/fcm_service.go:111` | Google OAuth2 token for FCM v1 | **Stub / Placeholder** | **Medium** | `getAccessToken()` returns empty string. If `FCM_PROJECT_ID` is set without token exchange, Google FCM API returns HTTP 401. Operates in mock mode when project ID is unset. |
| **Empty Settings Lambdas** | `app/.../presentation/settings/SettingsScreen.kt` | Language, Network, Storage, Backup | **Placeholder UI** | **Low (UX Gap)** | Destination items exist in UI list, but their click actions execute `{}` empty lambdas. Backup screens exist in codebase but are unrouted. |
| **Unrouted Pending Invitations** | `app/.../presentation/navigation/AuthNavGraph.kt:622` | Pending invitations screen | **Orphaned Screen** | **Low (UX Gap)** | `PendingRequestsScreen.kt` is fully implemented in Compose, but navigation callback in `AuthNavGraph` is unhandled. |
| **Unused Cloud Fallback Endpoints** | `backend/cmd/server/main.go:347, 420` | R2 Media ticket & purge ACK | **Unused Backend API** | **Low** | Implemented on Go backend (`/api/v1/media/upload-ticket`, `/api/v1/media/purge-ack`), but Android client transfers media purely via P2P WebRTC. |
| **Local IP in Network Security** | `app/src/main/res/xml/network_security_config.xml:18` | Cleartext HTTP domain config | **Hardcoded Development IP**| **Medium** | Permits cleartext HTTP to `10.155.150.84`. Must be moved to debug source set before release. |
| **Demo Message Intercept** | `app/.../data/repository/ChatRepositoryImpl.kt:111` | Intercept messages to `NX-DEMO-*` | **Development-Only** | **Low (Zero Risk)** | Messages sent to demo contact `NX-DEMO-...` are marked DELIVERED locally without cloud transmission. Does not affect real user NX-IDs. |
| **In-Memory Connection Repository**| `backend/cmd/server/main.go:177` | Fallback memory repo | **Dev-Only Fallback** | **Low (Zero Risk)** | Fenced by `if isProduction { log.Fatalf("PostgreSQL connections repository is unavailable") }`. Rejects in-memory repo in production. |
| **In-Memory Prekey Repository** | `backend/cmd/server/main.go:191` | Fallback memory prekey repo | **Dev-Only Fallback** | **Low (Zero Risk)** | Fenced by `if isProduction { log.Fatalf("PostgreSQL prekey repository is unavailable") }`. Rejects in-memory repo in production. |
| **In-Memory Redis Degraded Buffer** | `backend/cmd/server/main.go:74` | Volatile RAM queue fallback | **Dev-Only Fallback** | **Low (Zero Risk)** | Fenced by `if isProduction { log.Fatalf("Buffer failed in production! Operating in degraded mode is rejected") }`. |

---

## 3. Remediation Checklist for Controlled Beta

1. **Set `INBOUND_SMS_RECEIVER_NUMBER`** in Render production environment to the actual live shortcode/longcode provided by the telecom provider.
2. **Move LAN IP from Base Network Security Config** into `app/src/debug/res/xml/network_security_config.xml`.
3. **Connect Orphaned Screens** (`BackupSettingsScreen.kt` and `PendingRequestsScreen.kt`) in `AuthNavGraph.kt`.
4. **Implement FCM Service Account OAuth2 exchange** in `fcm_service.go` before enabling high-volume background push notifications.
