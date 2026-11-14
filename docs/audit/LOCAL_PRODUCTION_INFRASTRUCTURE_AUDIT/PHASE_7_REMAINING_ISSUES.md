# NEXA — Phase 7: Remaining Issues, Risks & Technical Debt Register

**Document Version:** 1.0.0  
**Target Codebase:** `H:\chat_app`  
**Evaluation Standard:** Pre-Release Risk & Defect Inventory  
**Audit Date:** November 2026  
**Status:** **AUTHORITATIVE DEFECT REGISTER**

---

## 1. Executive Summary

This register provides a complete and unvarnished account of all unresolved defects, architecture gaps, resilience limitations, and accepted constraints remaining in the NEXA ecosystem following the Phase 7 audit.

Every issue is classified by severity, source file location, user impact, security impact, available mitigation, and the explicit action required to resolve it.

---

## 2. Prioritized Defect Register

### Issue 1: Cross-Device SQLCipher Key Mismatch on Restore (P0 — Critical Release Blocker)
- **Severity:** 🔴 **CRITICAL (P0)**
- **Subsystem:** Google Drive Backup Restore (`VaultBundlePackager.kt`, `SecureStorage.kt`)
- **Source Location:** [`VaultBundlePackager.kt:73`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/backup/VaultBundlePackager.kt#L73) and [`SecureStorage.kt:50–61`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/security/SecureStorage.kt#L50-L61).
- **The Defect:**  
  `VACUUM INTO` clones the source database's exact encryption key ($K_{\text{Device A}}$). When a user installs the app on Device B and restores using their 12-word recovery phrase, the vault container decrypts successfully, but Device B attempts to open the database with a newly generated local key ($K_{\text{Device B}}$). SQLCipher throws `file is not a database`, breaking cross-device restore 100% of the time.
- **User Impact:** Users who switch to a new phone or reinstall the app lose access to historical messages.
- **Security Impact:** Data remains encrypted; zero plaintext leaks. However, availability and data recoverability are broken.
- **Mitigation:** The atomic restore rollback journal (`.rollback_bak`) prevents corruption of any existing database on Device B.
- **Required Action:** Approve **Option A** (In-Vault Symmetric Key Transfer) detailed in [`PHASE_7_CROSS_DEVICE_BACKUP_RESTORE_AUDIT.md`](file:///H:/chat_app/docs/audit/PHASE_7_CROSS_DEVICE_BACKUP_RESTORE_AUDIT.md) to package $K_{\text{Device A}}$ securely inside the AES-GCM encrypted vault bundle.

---

### Issue 2: FCM Push Notifications Function in Mock Mode (P1 — High Priority Gap)
- **Severity:** 🔴 **HIGH (P1)**
- **Subsystem:** Push Notifications (`backend/internal/fcm/fcm_service.go`, `app/build.gradle.kts`)
- **Source Location:** [`fcm_service.go:111`](file:///H:/chat_app/backend/internal/fcm/fcm_service.go#L111) and [`AndroidManifest.xml:86`](file:///H:/chat_app/app/src/main/AndroidManifest.xml#L86).
- **The Defect:**  
  The Go backend's `getAccessToken()` returns an empty string `""` (lacks Google Service Account OAuth2 exchange). The Android client lacks the Firebase Messaging library and `google-services.json`.
- **User Impact:** When the app process is terminated or in deep OS battery sleep, new incoming messages are held in the Redis 24-hour TTL queue and are only retrieved when the user re-opens the app.
- **Security Impact:** None. Payloads are generic wake-up signals with zero plaintext.
- **Mitigation:** Active foreground/background WebSocket connections deliver messages in real time while the app process is alive.
- **Required Action:** Follow the FCM Implementation Roadmap in [`PHASE_7_FCM_END_TO_END_AUDIT.md`](file:///H:/chat_app/docs/audit/PHASE_7_FCM_END_TO_END_AUDIT.md) before General Public Production.

---

### Issue 3: Missing WebRTC TURN Relay for Mobile Symmetric CGNAT (P2 — Medium Priority)
- **Severity:** 🟡 **MEDIUM (P2)**
- **Subsystem:** P2P File Transfer (`WebRtcClient.kt`)
- **Source Location:** [`WebRtcClient.kt:95`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/webrtc/WebRtcClient.kt#L95).
- **The Defect:** WebRTC peer connection configures only Google STUN servers. Zero TURN relay servers are configured.
- **User Impact:** Mobile carriers enforcing Symmetric CGNAT (Jio, Airtel) fail direct WebRTC hole-punching ~15–25% of the time.
- **Security Impact:** None.
- **Mitigation:** The 4.5-second ICE circuit breaker automatically falls back to client-encrypted Cloudflare R2 vaults. Zero file loss occurs.
- **Required Action:** Deploy a Coturn relay cluster with ephemeral time-limited HMAC credentials.

---

### Issue 4: Physical Three-Device Mesh Fleet Testing Unexecuted (P2 — Medium Priority)
- **Severity:** 🟡 **MEDIUM (P2)**
- **Subsystem:** Delay-Tolerant Mesh Routing (`ThreeDeviceMeshSimulationTests.kt`)
- **The Defect:** Validated via 7-scenario algorithmic simulation in unit tests, but physical RF fleet testing across 3 real phones under real radio attenuation has not been conducted.
- **User Impact:** Real-world BLE packet loss, device sleep throttling, and radio range variations remain subject to field verification.
- **Security Impact:** None. Hop count limits and Blake3 signatures prevent replay loops.
- **Mitigation:** Internet WebSocket relay is the primary transport channel.
- **Required Action:** Execute the physical QA runbook in [`PHASE_7_PHYSICAL_DEVICE_TEST_MATRIX.md`](file:///H:/chat_app/docs/audit/PHASE_7_PHYSICAL_DEVICE_TEST_MATRIX.md) during pilot staging.

---

### Issue 5: Cellular Binary Data SMS Port 8901 Deactivated (P3 — Accepted Limitation)
- **Severity:** ⚪ **LOW / ACCEPTED LIMITATION (P3)**
- **Subsystem:** Telephony Transport (`NexaDataSmsSender.kt`, `AndroidManifest.xml`)
- **The Defect:** `SEND_SMS` and `RECEIVE_SMS` permissions are omitted to comply with Google Play anti-fraud policies and Android 15 ECM.
- **User Impact:** Raw cellular binary SMS is unavailable. The UI cleanly marks the option as `Data SMS (Disabled)`.
- **Security Impact:** Positive. Eliminates premium SMS fraud risks and Play Protect rejections.
- **Mitigation:** Internet and Bluetooth DTN Mesh are the active messaging transports.
- **Required Action:** Documented in [`NEXA_KNOWN_LIMITATIONS.md`](file:///H:/chat_app/docs/audit/NEXA_KNOWN_LIMITATIONS.md).

---

### Issue 6: Informational Settings Dialogs vs Full Sub-Screens (P3 — Minor Technical Debt)
- **Severity:** ⚪ **LOW (P3)**
- **Subsystem:** Settings UI (`SettingsScreen.kt`)
- **The Defect:** Language, Archived Chats, Network, Storage, Help, and About provide user-facing feedback via Liquid Glass alert dialogs rather than dedicated multi-tab settings screens.
- **User Impact:** Users receive informative explanations of current system state rather than dedicated configuration screens.
- **Security Impact:** None.
- **Mitigation:** Core security, account, backup, and notification settings have dedicated screens.
- **Required Action:** Implement dedicated sub-screens in upcoming UI polish milestone.

---

## 3. Summary Scorecard

| Severity Level | Active Issues | Blocking Release? | Action Required |
| :--- | :---: | :---: | :--- |
| 🔴 **Critical (P0)** | 1 | **YES (for New Device Restore)** | Approve & implement Option A key transfer in `VaultBundlePackager.kt`. |
| 🔴 **High (P1)** | 1 | **NO (Acceptable for Pilot)** | Implement FCM OAuth2 token exchange before General Public Release. |
| 🟡 **Medium (P2)** | 2 | **NO (Mitigated)** | Handled via Cloudflare R2 fallback & unit simulations. |
| ⚪ **Low (P3)** | 2 | **NO (Documented)** | Accepted limitations. |
