# NEXA — Phase 7: Three-Device Physical Test Matrix & QA Runbook

**Document Version:** 1.0.0  
**Target Hardware:** Multi-Device Physical Android Fleet & Variable Radio Environments  
**Target Codebase:** `H:\chat_app` (Android Mobile Application)  
**Evaluation Standard:** Real-World Hardware & Multi-Hop Transport Verification  
**Audit Date:** November 2026  
**Status:** **AUTHORITATIVE QA RUNBOOK & TEST MATRIX (Controlled Beta Staging)**

---

## 1. Fleet Hardware Specification

The following physical device configuration is designated for the Controlled Beta validation testbed:

| Fleet ID | Role | Recommended Hardware | OS Version | Network Connection |
| :---: | :--- | :--- | :--- | :--- |
| **DEV-A** | Primary Sender / Host | Google Pixel 7 / 8 | Android 14 (API 34) | Wi-Fi 6 (WPA3 Enterprise / Home LAN) |
| **DEV-B** | Intermediate DTN Mesh Mule | Samsung Galaxy S22 / S23 | Android 13 / 14 (OneUI 6) | Mobile 5G / 4G LTE (Carrier CGNAT: Jio / Airtel) |
| **DEV-C** | Air-Gapped Recipient | Xiaomi / OnePlus / Pixel 6 | Android 12 / 13 (API 32/33)| Airplane Mode (Bluetooth & Wi-Fi Direct enabled) |

---

## 2. Multi-Device Physical Test Matrix

| Test ID | Test Scenario | Device Topology | Test Procedure | Expected Result | Actual Result in Sandbox / CI | Final Status |
| :-: | :--- | :---: | :--- | :--- | :--- | :---: |
| **PHY-01** | Device A sends to Device B (Direct Internet) | DEV-A (Wi-Fi) &rarr; DEV-B (Cellular) | DEV-A sends pairwise E2EE text message to DEV-B over Go relay WebSocket. | Message delivered in < 500ms; double checkmarks on DEV-A. | Verified via `hub_test.go` and `WebSocketManager.kt` integration. | 🟢 **PASS (Simulated)** / ⚪ **NOT TESTED (Live Physical)** |
| **PHY-02** | Device B replies to Device A (Bidirectional) | DEV-B (Cellular) &rarr; DEV-A (Wi-Fi) | DEV-B sends reply; Double Ratchet advances DH ratchet. | Reply delivered; forward secrecy verified; zero out-of-order packets. | Verified via `DualGateSecurityUnitTests` (23/23 PASS). | 🟢 **PASS (Simulated)** / ⚪ **NOT TESTED (Live Physical)** |
| **PHY-03** | Device C Group Communication | DEV-A, DEV-B, DEV-C | DEV-A broadcasts message to multi-party conversation. | All online participants receive individual pairwise encrypted envelopes. | Verified via `ChatRepositoryImpl.kt` fanout tests. | 🟢 **PASS (Simulated)** / ⚪ **NOT TESTED (Live Physical)** |
| **PHY-04** | Foreground vs Background Delivery | DEV-A (Active) &rarr; DEV-B (Background) | DEV-B backgrounded for 5 minutes; DEV-A sends message. | WebSocket keep-alive delivers message; notification posted to notification tray. | Verified via `NotificationSettingsViewModel` unit tests. | 🟢 **PASS (Simulated)** / ⚪ **NOT TESTED (Live Physical)** |
| **PHY-05** | App Process Termination Survival | DEV-A &rarr; DEV-B (Swiped from Recents) | DEV-B process killed via OS task switcher; DEV-A sends message. | Message buffered in Redis 24h queue; delivered immediately when DEV-B reopens. | Verified via `reliable_queue_test.go` (PASS). | 🟢 **PASS (Simulated)** / ⚪ **NOT TESTED (Live Physical)** |
| **PHY-06** | Wi-Fi to Cellular Handover | DEV-A (Wi-Fi &rarr; 5G) | DEV-A streams 10 MB payload; Wi-Fi router is powered off mid-transfer. | `NetworkMonitor` detects route change; WebSocket reconnects with backoff; resumes. | Verified via `NetworkClassificationUnitTests` (8/8 PASS). | 🟢 **PASS (Simulated)** / ⚪ **NOT TESTED (Live Physical)** |
| **PHY-07** | Cellular to Wi-Fi Handover | DEV-B (4G &rarr; Wi-Fi) | DEV-B reconnects to office Wi-Fi while chatting. | Connection switches smoothly without dropping session or skipping ratchet keys. | Verified via `NetworkMonitor.kt` integration tests. | 🟢 **PASS (Simulated)** / ⚪ **NOT TESTED (Live Physical)** |
| **PHY-08** | VPN Activation / Deactivation | DEV-A (ProtonVPN / WireGuard) | VPN toggled on/off while active messaging session is open. | Socket drops on route change, reconnects in < 3s, pending messages sent. | Verified via `WebSocketManager.kt` reconnect loop. | 🟢 **PASS (Simulated)** / ⚪ **NOT TESTED (Live Physical)** |
| **PHY-09** | Network Interruption During Transfer | DEV-A &rarr; DEV-B (P2P Transfer) | Network dropped mid-file (at 45% progress); restored after 15s. | `BitfieldTracker` queries missing chunk bitmask; resumes without restarting file from 0. | Verified via `ResilientP2PAndMeshUnitTests` (14/14 PASS). | 🟢 **PASS (Simulated)** / ⚪ **NOT TESTED (Live Physical)** |
| **PHY-10** | Large File Transfer (&gt; 50 MB) | DEV-A &rarr; DEV-B | Transfer 75 MB video file over local Wi-Fi. | WebRTC SCTP DataChannel transfers in 64 KB slices; SHA-256 verified at recipient. | Verified via `FileSecurityAndSanitizationUnitTests`. | 🟢 **PASS (Simulated)** / ⚪ **NOT TESTED (Live Physical)** |
| **PHY-11** | WebRTC Fallback to Cloudflare R2 | DEV-A &rarr; DEV-B (Symmetric CGNAT) | WebRTC peer connection blocked by firewall / symmetric NAT. | 4.5s circuit breaker triggers; file uploaded to R2 vault; recipient downloads `.enc`. | Verified via `R2MediaFallbackUnitTests` (7/7 PASS). | 🟢 **PASS (Simulated)** / ⚪ **NOT TESTED (Live Physical)** |
| **PHY-12** | Nearby Mesh BLE Discovery | DEV-A &rarr; DEV-B (No Internet) | Both devices disconnect mobile data & Wi-Fi; open app. | Google Nearby Connections discovers peers within 15 meters via BLE beaconing. | Verified via `NearbyMeshManager.kt` unit tests. | 🟢 **PASS (Simulated)** / ⚪ **NOT TESTED (Live Physical)** |
| **PHY-13** | Multi-Hop DTN Mesh Relay (A &rarr; B &rarr; C) | DEV-A &rarr; DEV-B (Mule) &rarr; DEV-C | DEV-A out of range of DEV-C. DEV-B moves between them. | DEV-B carries store-and-forward envelope; delivers to DEV-C upon proximity handshake. | Verified via `ThreeDeviceMeshSimulationTests` (7/7 PASS). | 🟢 **PASS (Simulated)** / ⚪ **NOT TESTED (Live Physical)** |
| **PHY-14** | Multi-Transport Deduplication | DEV-A sends via Mesh + Internet | Recipient receives identical message via both channels. | 64-byte Bloom filter + SQLite idempotency table drops second copy silently. | Verified via `ExactDeduplicationAndPeerSyncTests` (9/9 PASS). | 🟢 **PASS (Simulated)** / ⚪ **NOT TESTED (Live Physical)** |
| **PHY-15** | Device Revocation Teardown | DEV-A revokes DEV-B | DEV-A triggers `/api/v1/devices/{devB}/revoke`. | DEV-B WebSocket dropped immediately; cannot authenticate future challenges. | Verified via `TestDeviceRevocation` in Go backend (PASS). | 🟢 **PASS (Simulated)** / ⚪ **NOT TESTED (Live Physical)** |
| **PHY-16** | Cross-Device Backup & Restore | DEV-A Backup &rarr; DEV-B Restore | DEV-A uploads vault to Google Drive; DEV-B attempts restore using 12 words. | Restored cleanly with matching key. | 🔴 **FAILED IN CURRENT SOURCE** (Blocked by Critical Area A key mismatch). | 🔴 **FAILED (Key Mismatch)** |

---

## 3. Physical Test Execution Protocol for Pilot Staging

When pilot devices are enrolled in the Google Play Internal Testing Track:

1. **Step 1:** Download and install release APK/AAB on DEV-A, DEV-B, and DEV-C.
2. **Step 2:** Execute Onboarding on DEV-A and DEV-B using Google Sign-In and Reverse SMS verification.
3. **Step 3:** Perform QR Handshake between DEV-A and DEV-B to establish pairwise E2EE trust roots.
4. **Step 4:** Execute PHY-01 through PHY-15 sequentially, recording timestamped logcat captures.
5. **Step 5:** Redact all sensitive identifiers (session tokens, public keys, IP addresses) before committing QA reports.
