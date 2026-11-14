# NEXA — Phase 8.1: Unresolved Issues & Known Limitations Audit

## 1. Separation of Concerns

This audit maintains strict separation between:
1. **Google Drive Backup & Restore Readiness** (Evaluated in Phase 8/8.1)
2. **Overall NEXA Ecosystem Production Readiness** (Evaluated across Phases 1–7)

Passing unit tests and cryptographic proofs for the backup engine do **not** automatically resolve systemic transport or infrastructure limitations across the broader NEXA application.

---

## 2. Status Tracking of the 10 Known NEXA Ecosystem Issues

| No. | Issue Name | Architectural Scope | Current Implementation State | Verified Status |
|---|---|---|---|---|
| 1 | **FCM Token Exchange** | Push Notifications | Backend endpoint implemented; Android token exchange service registered in Manifest. | **PARTIALLY VERIFIED** (Mocked in unit tests; real APNs/FCM push delivery requires live Firebase project). |
| 2 | **Actual Android Firebase Messaging Integration** | App Background Wakeup | `firebase-analytics` was removed to fix Kotlin metadata crash. `FirebaseMessagingService` class exists, but `google-services.json` contains dummy project IDs. | **UNRESOLVED / BLOCKED IN PRODUCTION** |
| 3 | **WebRTC Symmetric CGNAT** | Direct P2P File Transfer | STUN NAT traversal fails across double-NAT / carrier-grade NAT. Requires TURN relay. | **ARCHITECTURAL LIMITATION** |
| 4 | **TURN Relay Infrastructure** | P2P Fallback | `TurnServerConfig` and dynamic configuration models added in `WebRtcClient.kt`, but production TURN servers (`coturn`) are not deployed or configured in environment. | **PARTIALLY IMPLEMENTED / UNCONFIGURED IN PROD** |
| 5 | **R2 Encrypted Media Fallback** | Asynchronous Media Delivery | Tested in unit tests with mock S3 credentials. Real Cloudflare R2 bucket integration requires production API tokens. | **UNIT-TEST VERIFIED / PENDING PRODUCTION BUCKET** |
| 6 | **Physical Nearby Mesh Testing** | Offline Mesh Transport | `NearbyMeshManager` unit tests pass via mocked BLE/Wi-Fi Direct callbacks. Real physical RF mesh testing on 3+ Android devices not conducted. | **NOT VERIFIED ON PHYSICAL HARDWARE** |
| 7 | **Real-World Data SMS Limitations** | Port 8901 SMS | Code chunking and Port 8901 routing verified in code. Carrier SMS filtering, MMS auto-conversion, and roaming binary PDU restrictions remain real-world constraints. | **CARRIER-DEPENDENT LIMITATION** |
| 8 | **Three-Device Mesh Communication** | Multi-Hop DTN | Unit tests pass in `ThreeDeviceMeshSimulationTests.kt`. Multi-hop mesh hops (A &rarr; B &rarr; C) verified in JVM memory, not over physical BLE. | **UNIT-TEST VERIFIED ONLY** |
| 9 | **Cross-Device SQLCipher Portability** | Backup & Restore | **RESOLVED VIA DESIGN C.** 66-byte `key_envelope.bin` successfully bridges the database passphrase across devices in RAM. | **LOGICALLY RESOLVED / PHYSICAL DEVICE PENDING** |
| 10 | **Google Drive Restore After Clean Reinstall** | Fresh Device Setup | Tested in unit test harnesses. Real-device clean wipe & restore from Google Drive AppData requires live Google test account. | **LOGICALLY VERIFIED / LIVE GOOGLE AUTH PENDING** |

---

## 3. Specific Limitations in the Backup & Restore Subsystem (Phase 8.1 Findings)

1. **Monolithic SQLite Restore (P0):**
   Selective restore checkboxes in the UI are functional only for media files and SharedPreferences. The SQLite database is replaced wholesale. Unchecking "Chats" during restore does not prevent the local database from being overwritten.
2. **Monolithic SQLite Backup (P0):**
   Unchecking "Chats" in Backup Settings does not exclude the messages table from the SQLite snapshot created by `VACUUM INTO`.
3. **Missing Zip-Bomb Protections (P1):**
   Archive extraction does not cap total uncompressed bytes or enforce a compression ratio threshold, leaving devices susceptible to storage exhaustion DoS.
4. **Envelope AAD Discrepancy (P1):**
   Envelope AAD binds only 6 bytes (`"NXKE" || 0x0002`). Vault `salt` is used in HKDF key derivation but omitted from the GCM AAD buffer.
5. **Plaintext Clipboard Copying of Recovery Phrase (P2):**
   The 12-word mnemonic is copied directly to the Android system clipboard without masking or timeout expiration.
6. **No Incremental / Delta Backups (P3):**
   Each backup creates a fresh, consolidated point-in-time snapshot. Over time, large media vaults will consume significant Google Drive upload bandwidth.
