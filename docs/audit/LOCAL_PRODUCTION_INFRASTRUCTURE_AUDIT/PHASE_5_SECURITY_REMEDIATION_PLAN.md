# NEXA — Phase 5: Security Hardening Remediation Plan

**Plan Type:** Step-by-Step Security Hardening, Vulnerability Remediation & Pre-Release Hardening Plan  
**Target Codebase:** `H:\chat_app` (Android Mobile Application & Go Backend Relay)  
**Authority Hierarchy:** `1architecture.md` / `docs/*.md` / `PHASE_5_COMPLETE_SECURITY_AUDIT.md`  
**Status:** **PROPOSED — AWAITING EXPLICIT USER APPROVAL TO COMMENCE IMPLEMENTATION**

---

## 1. Executive Summary & Objective

This document defines the exact remediation procedures, patch specifications, rollback plans, and retest criteria for all vulnerabilities identified in `PHASE_5_COMPLETE_SECURITY_AUDIT.md`.

### Remediation Priorities:
1. **[CRITICAL / P0] VULN-01:** Authenticate WebRTC Signaling WebSocket (`/ws/signaling`) to prevent session hijacking and signaling eavesdropping.
2. **[CRITICAL / P0] VULN-02:** Enforce Row-Level Security (RLS) across all PostgreSQL tables on Supabase to prevent unauthenticated PostgREST extraction via the anonymous key.
3. **[HIGH / P1] VULN-03:** Remove cleartext LAN IP `10.155.150.84` from production `network_security_config.xml`.
4. **[HIGH / P1] VULN-04:** Enable R8 minification and ProGuard obfuscation (`isMinifyEnabled = true`) for release builds in `app/build.gradle.kts`.
5. **[HIGH / P1] VULN-05:** Guard `NexaPushWakeUpReceiver` by setting `android:exported="false"` in `AndroidManifest.xml`.
6. **[MEDIUM / P2] VULN-06:** Configure TURN relay credentials for WebRTC mobile NAT traversal.
7. **[MEDIUM / P2] VULN-07:** Implement Google OAuth2 Service Account JWT token exchange in `backend/internal/fcm/fcm_service.go`.

---

## 2. Detailed Vulnerability Remediation Specifications

### 2.1 VULN-01: Unauthenticated WebRTC Signaling WebSocket (P0)

- **Finding ID:** `VULN-01`
- **Severity:** **CRITICAL (CVSS: 9.1)**
- **Affected Files:**  
  - `backend/cmd/server/main.go` (lines 234–252)  
  - `backend/internal/signaling/hub.go`  
  - `app/src/main/java/com/nexa/app/core/webrtc/WebRtcSignalingClient.kt`
- **Root Cause:**  
  The endpoint `/ws/signaling` upgraded connections and registered sessions taking `nx_id` directly from the URL query parameter without validating Bearer session tokens or requiring cryptographic challenge-response authentication.
- **Recommended Remediation:**  
  1. In `backend/cmd/server/main.go`, validate the caller's session token from query param or `Sec-WebSocket-Protocol` header:
     ```go
     http.HandleFunc("/ws/signaling", func(w http.ResponseWriter, r *http.Request) {
         token := r.URL.Query().Get("token")
         if token == "" {
             http.Error(w, "Unauthorized: session token required", http.StatusUnauthorized)
             return
         }
         sess, ok := identityRepo.GetSession(token)
         if !ok || sess == nil || sess.IsRevoked || (sess.ExpiresAt > 0 && time.Now().UnixMilli() > sess.ExpiresAt) {
             http.Error(w, "Unauthorized: invalid or revoked session", http.StatusUnauthorized)
             return
         }
         if sess.DeviceID != "" {
             if dev, found := identityRepo.GetDevice(sess.DeviceID); found && (dev.IsRevoked || dev.NxID != sess.NxID) {
                 http.Error(w, "Unauthorized: device revoked", http.StatusUnauthorized)
                 return
             }
         }
         
         conn, err := upgrader.Upgrade(w, r, nil)
         if err != nil { return }
         
         session := signaling.NewSignalingSession(signalingHub, conn, sess.NxID)
         signalingHub.Register(session)
         go session.WritePump()
         go session.ReadPump()
     })
     ```
  2. In `WebRtcSignalingClient.kt`, attach the active session token to the WebSocket connection URL: `ws://host/ws/signaling?token=${activeSessionToken}`.
- **Dependencies:** `IdentityRepository`.
- **Potential Breaking Changes:** Clients attempting to connect to signaling without an active session token will receive HTTP 401. (Intended security behavior).
- **Data Migration Impact:** None.
- **Required Tests:** Author `backend/internal/signaling/auth_test.go` verifying that unauthenticated or revoked session connections are rejected with 401 Unauthorized.
- **Retest Criteria:** Connections with invalid tokens receive 401; valid sessions register successfully.
- **Rollback Plan:** Revert `main.go` and `hub.go` changes.

---

### 2.2 VULN-02: Missing Row-Level Security on Supabase PostgreSQL (P0)

- **Finding ID:** `VULN-02`
- **Severity:** **CRITICAL (CVSS: 9.8)**
- **Affected Files:** `backend/internal/database/migrations.go`
- **Root Cause:**  
  Tables created in PostgreSQL do not have Row-Level Security enabled. In Supabase deployments, PostgREST exposes all public schema tables by default to requests using the public anonymous API key.
- **Recommended Remediation:**  
  Add Migration 006 to `backend/internal/database/migrations.go`:
  ```go
  {
      Version: 6,
      Name:    "006_enforce_row_level_security_fail_closed",
      SQL: `
      -- 1. Revoke public API access to public schema tables from anon and authenticated roles
      REVOKE ALL ON ALL TABLES IN SCHEMA public FROM anon, authenticated;
      REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM anon, authenticated;
      REVOKE ALL ON ALL ROUTINES IN SCHEMA public FROM anon, authenticated;

      -- 2. Explicitly enable Row-Level Security on all tables (Fail-Closed Default)
      ALTER TABLE accounts ENABLE ROW LEVEL SECURITY;
      ALTER TABLE devices ENABLE ROW LEVEL SECURITY;
      ALTER TABLE sessions ENABLE ROW LEVEL SECURITY;
      ALTER TABLE phone_verifications ENABLE ROW LEVEL SECURITY;
      ALTER TABLE connection_requests ENABLE ROW LEVEL SECURITY;
      ALTER TABLE connections ENABLE ROW LEVEL SECURITY;
      ALTER TABLE user_blocks ENABLE ROW LEVEL SECURITY;
      ALTER TABLE identity_key_bindings ENABLE ROW LEVEL SECURITY;
      ALTER TABLE signed_prekeys ENABLE ROW LEVEL SECURITY;
      ALTER TABLE one_time_prekeys ENABLE ROW LEVEL SECURITY;
      ALTER TABLE prekey_claims ENABLE ROW LEVEL SECURITY;
      ALTER TABLE phone_visibility_grants ENABLE ROW LEVEL SECURITY;
      ALTER TABLE message_idempotency ENABLE ROW LEVEL SECURITY;

      -- 3. The Go backend connects via direct connection string as user 'postgres' (which bypasses RLS).
      -- Public REST requests via PostgREST now receive 0 rows or HTTP 403 Forbidden.
      `,
  }
  ```
- **Dependencies:** PostgreSQL / Supabase migration engine.
- **Potential Breaking Changes:** None for the Go backend (the Go backend connects as the superuser/table owner via pooler). Third-party PostgREST direct queries are completely blocked.
- **Data Migration Impact:** Zero. Pure permission and policy hardening.
- **Required Tests:** Execute Supabase API queries using the `anon` key against `/rest/v1/accounts` and `/rest/v1/sessions`; verify HTTP 403 or empty array returned.
- **Retest Criteria:** Migration applies idempotently on fresh and existing databases.
- **Rollback Plan:** Revert migration or execute `ALTER TABLE ... DISABLE ROW LEVEL SECURITY`.

---

### 2.3 VULN-03: Cleartext Private LAN IP Exception in Production (P1)

- **Finding ID:** `VULN-03`
- **Severity:** **HIGH (CVSS: 7.4)**
- **Affected Files:**  
  - `app/src/main/res/xml/network_security_config.xml`  
  - Create: `app/src/debug/res/xml/network_security_config.xml`
- **Root Cause:**  
  The hardcoded development IP `10.155.150.84` was placed in the production `src/main` resource directory, allowing cleartext HTTP traffic in release builds.
- **Recommended Remediation:**  
  1. Remove `10.155.150.84` from `app/src/main/res/xml/network_security_config.xml`.
  2. Create a debug-only network security config at `app/src/debug/res/xml/network_security_config.xml` that permits local LAN IP exceptions exclusively during development:
     ```xml
     <!-- app/src/main/res/xml/network_security_config.xml (Strict Production) -->
     <network-security-config>
         <base-config cleartextTrafficPermitted="false">
             <trust-anchors>
                 <certificates src="system" />
             </trust-anchors>
         </base-config>
     </network-security-config>
     ```
- **Dependencies:** Android Gradle build variants.
- **Potential Breaking Changes:** Release builds connecting to `http://10.155.150.84` will be blocked by the Android OS network security manager. (Intended behavior).
- **Required Tests:** Build release APK; verify cleartext traffic is rejected on release variants.
- **Rollback Plan:** Revert XML file changes.

---

### 2.4 VULN-04: Release Build Minification Disabled (P1)

- **Finding ID:** `VULN-04`
- **Severity:** **HIGH (CVSS: 6.8)**
- **Affected Files:**  
  - `app/build.gradle.kts` (line 47)  
  - `app/proguard-rules.pro`
- **Root Cause:**  
  `isMinifyEnabled = false` in `buildTypes.release` leaves release APK bytecode unminified and unobfuscated.
- **Recommended Remediation:**  
  1. In `app/build.gradle.kts`:
     ```kotlin
     buildTypes {
         release {
             isMinifyEnabled = true
             isShrinkResources = true
             signingConfig = signingConfigs.getByName("release")
             proguardFiles(
                 getDefaultProguardFile("proguard-android-optimize.txt"),
                 "proguard-rules.pro"
             )
         }
     }
     ```
  2. In `app/proguard-rules.pro`, ensure keep rules protect:
     - SQLCipher native methods and JNI bindings (`net.zetetic.database.sqlcipher.**`).
     - Protobuf wire packet models.
     - WebRTC native peer connection classes (`io.getstream.webrtc.**`).
     - Room database DAOs and entity models.
- **Dependencies:** Android R8 compiler.
- **Potential Breaking Changes:** If R8 rules miss a reflective or native JNI call, runtime `ClassNotFoundException` can occur. Must be verified with clean test build.
- **Required Tests:** Run `./gradlew assembleRelease` and execute full unit test suite.
- **Rollback Plan:** Toggle `isMinifyEnabled = false`.

---

### 2.5 VULN-05: Unprotected Exported Push Wake-Up Broadcast Receiver (P1)

- **Finding ID:** `VULN-05`
- **Severity:** **HIGH (CVSS: 7.1)**
- **Affected Files:** `app/src/main/AndroidManifest.xml` (line 86)
- **Root Cause:**  
  `NexaPushWakeUpReceiver` is declared with `android:exported="true"` without permission checks, allowing rogue third-party apps to trigger battery-draining wake-up loops.
- **Recommended Remediation:**  
  Change `android:exported="false"` in `app/src/main/AndroidManifest.xml`:
  ```xml
  <receiver
      android:name=".data.remote.push.NexaPushWakeUpReceiver"
      android:exported="false">
      <intent-filter>
          <action android:name="com.nexa.app.PUSH_WAKE_UP" />
      </intent-filter>
  </receiver>
  ```
  Internal local broadcasts and Firebase background messaging services running within the application process can dispatch `PUSH_WAKE_UP` intents freely, while third-party apps are blocked by the OS.
- **Dependencies:** Android manifest.
- **Potential Breaking Changes:** None. External apps have no legitimate reason to trigger internal push wake-ups.
- **Required Tests:** Verify internal wake-up intent triggers WebSocket connection while external intent fails.
- **Rollback Plan:** Revert manifest change.

---

### 2.6 VULN-06: WebRTC TURN Relay Integration (P2)

- **Finding ID:** `VULN-06`
- **Severity:** **MEDIUM (CVSS: 5.3)**
- **Affected Files:** `backend/cmd/server/main.go`, `app/src/main/java/com/nexa/app/core/webrtc/WebRtcClient.kt`
- **Root Cause:** STUN-only configuration fails on mobile carrier Symmetric NATs.
- **Recommended Remediation:** Implement ephemeral TURN credential issuance endpoint and inject TURN servers into `WebRtcClient`. (Details specified in Phase 4 Implementation Plan).

---

### 2.7 VULN-07: FCM Google OAuth2 Token Exchange (P2)

- **Finding ID:** `VULN-07`
- **Severity:** **MEDIUM (CVSS: 5.3)**
- **Affected Files:** `backend/internal/fcm/fcm_service.go`
- **Root Cause:** `getAccessToken()` returns `""`; lacks RS256 JWT assertion generation and OAuth2 exchange with `https://oauth2.googleapis.com/token`.
- **Recommended Remediation:** Implement `GoogleOAuthTokenSource` with 5-minute pre-expiry caching. (Details specified in Phase 2 Implementation Plan).

---

## 3. Step-by-Step Remediation Sequence

```
┌────────────────────────────────────────────────────────┐
│ Phase 5 Security Remediation Sequence                  │
└────────────────────────────────────────────────────────┘
                           │
                           ▼
 [Gate 1: User Approval of Security Audit & Remediation Plan]
                           │
                           ▼
 [Step 1: Remediate Critical P0 Vulnerabilities]
  - VULN-01: Add session token validation to /ws/signaling in main.go
  - VULN-02: Add Migration 006 (REVOKE public access & ENABLE RLS on all tables)
  - Run: go test -v ./internal/database/... ./internal/signaling/...
                           │
                           ▼
 [Step 2: Remediate High P1 Vulnerabilities]
  - VULN-03: Remove cleartext LAN IP from network_security_config.xml
  - VULN-04: Enable R8 minification (isMinifyEnabled = true) in build.gradle.kts
  - VULN-05: Set android:exported="false" on NexaPushWakeUpReceiver in manifest
  - Run: ./gradlew assembleRelease
                           │
                           ▼
 [Step 3: Remediate P2 Resiliency Issues]
  - VULN-06: Inject TURN credentials
  - VULN-07: Implement FCM OAuth2 token exchange
                           │
                           ▼
 [Gate 2: Pre-Release Security Checklist Verification]
```

---

**End of Remediation Plan.**  
*Awaiting explicit user approval before touching source files.*
