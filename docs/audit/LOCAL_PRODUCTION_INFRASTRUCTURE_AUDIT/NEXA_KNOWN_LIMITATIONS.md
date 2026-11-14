# NEXA — Known Limitations & Accepted Constraints

**Document Version:** 1.0.0  
**Target Codebase:** `H:\chat_app`  
**Evaluation Date:** November 2026  
**Status:** **AUTHORITATIVE RELEASE REFERENCE**

---

## 1. Overview

This document formalizes all intentional architectural constraints, platform limitations, deactivated transports, and deferred features in the NEXA v2.0 Controlled Beta release. Every item is documented with its technical justification, user impact, security impact, available mitigation, and planned follow-up roadmap.

---

## 2. Limitation Registry

### LIM-01: Cellular Binary Data SMS Port 8901 Deactivated in Production

- **Subsystem:** SMS / Cellular Transport (`NexaDataSmsSender.kt`, `AndroidManifest.xml`)
- **Reason:**  
  1. Google Play Store policies strictly enforce anti-fraud restrictions on `SEND_SMS` and `RECEIVE_SMS` permissions. Apps without explicit SMS-handler default status are rejected during Google Play review.
  2. Android 15 introduces Enhanced Confirmation Mode (ECM), which blocks background binary SMS dispatch without explicit runtime modal confirmation for each SMS packet.
  3. Indian consumer cellular operators (Airtel, Jio, Vi) filter port-addressed binary SMS (WAP-push PDUs) at the carrier SMSC level, causing silent delivery failure.
- **User Impact:**  
  Users cannot send encrypted messages over cellular binary SMS. The UI transport selector marks this option as `Data SMS (Disabled)`.
- **Security Impact:**  
  Positive. Omitting SMS permissions guarantees the app cannot trigger unauthorized carrier billing, premium SMS fraud, or malicious interception of bank OTPs.
- **Workaround:**  
  Internet WebSocket messaging and Bluetooth LE Nearby Mesh are active. For onboarding identity verification, NEXA uses Client-Initiated Reverse SMS (`Intent.ACTION_SENDTO`), which requires zero Android telephony permissions.
- **Planned Follow-up:**  
  Publish a standalone F-Droid / GitHub APK variant with SMS permissions enabled for users in extreme connectivity-deprived environments.

---

### LIM-02: Direct WebRTC Cellular P2P NAT Traversal (STUN Only)

- **Subsystem:** Peer-to-Peer File Transfer (`WebRtcClient.kt`)
- **Reason:**  
  `WebRtcClient` currently configures only public Google STUN servers (`stun.l.google.com:19302`). Dedicated TURN (Traversal Using Relays around NAT) relay servers require paid bandwidth infrastructure.
- **User Impact:**  
  When both sender and recipient are on cellular networks behind restrictive Symmetric Carrier-Grade NAT (CGNAT), direct WebRTC SCTP DataChannels cannot establish a direct hole-punched peer connection (~15–25% of mobile network pairs).
- **Security Impact:**  
  None. WebRTC data channels and cloud fallback both utilize authenticated encryption.
- **Workaround:**  
  The 4.5-second ICE connection circuit breaker in `MediaRoutingCoordinator.kt` automatically falls back to client-encrypted Cloudflare R2 vaults (`BlindMediaVaultEngine.kt`). Files are transferred reliably without user intervention.
- **Planned Follow-up:**  
  Deploy a co-located Coturn relay server cluster with ephemeral time-limited HMAC credentials issued via Go backend `/api/v1/webrtc/turn-credentials`.

---

### LIM-03: Google Play Services Dependency for Nearby Mesh

- **Subsystem:** Delay-Tolerant Mesh (`NearbyMeshManager.kt`)
- **Reason:**  
  The Nearby Mesh engine utilizes Google Nearby Connections API (`com.google.android.gms.nearby.connection`).
- **User Impact:**  
  De-Googled devices (e.g., GrapheneOS, CalyxOS, microG without Nearby Connections module) cannot participate in the BLE mesh.
- **Security Impact:**  
  None. Payloads carried over Nearby Connections are encrypted end-to-end with ChaCha20-Poly1305 and routed using rotating Blake3 blind tokens.
- **Workaround:**  
  De-Googled devices communicate seamlessly over TLS WebSocket and Cloudflare R2.
- **Planned Follow-up:**  
  Implement a raw Bluetooth LE L2CAP connection-oriented channel driver as a pure AOSP fallback when Google Play Services is absent.

---

### LIM-04: Multi-Hop Mesh Physical RF Fleet Verification

- **Subsystem:** Delay-Tolerant Mesh Routing (`ThreeDeviceMeshSimulationTests.kt`)
- **Reason:**  
  Automated CI and local developer workstations operate without a dedicated three-device physical RF-isolated test lab.
- **User Impact:**  
  Multi-hop store-and-forward routing has been verified mathematically and via 7-scenario algorithmic simulation, but real-world radio attenuation, Bluetooth signal fading, and pedestrian mobility handoffs remain subject to real-world RF variability.
- **Security Impact:**  
  None. Hop count limits (max 5 hops) and Blake3 envelope signatures prevent relay loops and unauthorized envelope tampering.
- **Workaround:**  
  Internet WebSocket relay serves as the primary messaging backbone with DTN mesh opportunistic offload.
- **Planned Follow-up:**  
  Execute physical three-device fleet tests during the closed beta testbed phase before general public rollout.

---

### LIM-05: Non-Destructive Multi-Device Database Key Inheritance on Restore

- **Subsystem:** Google Drive Backup Restore (`DriveBackupRepositoryImpl.kt`, `VaultBundlePackager.kt`)
- **Reason:**  
  SQLCipher databases exported via `VACUUM INTO` preserve the original database schema and encryption page format. When restoring to a fresh install on a new physical device, the new device initializes a new SQLCipher passphrase in Android Keystore.
- **User Impact:**  
  Restoring a backup on the *same physical device* (e.g. after app reinstall) succeeds seamlessly. Restoring on a *new physical device* requires entering the 12-word recovery phrase, from which the vault bundle is decrypted.
- **Security Impact:**  
  Positive. Backups stored in Google Drive are protected with AES-256-GCM derived exclusively from the user's 12-word BIP-39 mnemonic, completely inaccessible to Google.
- **Workaround:**  
  The new atomic staging swap with `.rollback_bak` journal ensures that if any keying mismatch occurs during restore, the existing local database is never destroyed or corrupted.
- **Planned Follow-up:**  
  Enhance `VaultBundlePackager` to export an encrypted SQLCipher schema dump decrypted into the new device's local passphrase during initial vault unpacking.
