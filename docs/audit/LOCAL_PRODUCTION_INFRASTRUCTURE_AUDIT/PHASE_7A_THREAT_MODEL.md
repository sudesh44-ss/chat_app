# NEXA — Phase 7A: Comprehensive Threat Model & Security Boundary Analysis

**Document Version:** 1.0.0  
**Target Subsystem:** Cross-Device Backup, Key Portability, Hardware Keystore & Cloud Storage  
**Target Codebase:** `H:\chat_app` (Android Client Subsystem)  
**Security Classification:** Threat Modeling, Adversary Capability Matrix & Boundary Verification  
**Audit Standard:** STRIDE / NIST SP 800-30 / OWASP MASVS  
**Date:** November 2026  
**Status:** **DESIGN THREAT ANALYSIS — AWAITING EXPLICIT USER APPROVAL**

---

## 1. Threat Model Scope & Adversary Capabilities

This document establishes the threat boundaries for NEXA's cryptographic backup, restore, and key portability subsystem. It models adversaries with capabilities ranging from network eavesdroppers and cloud infrastructure attackers to physical device thieves and sophisticated state-level actors equipped with hardware analysis tools.

### Adversary Profiles:
- **ADV-1: Cloud Eavesdropper / Provider Insider:** Has full read/write access to Google Drive infrastructure and files in `appDataFolder`, or compromised Google Drive OAuth access tokens.
- **ADV-2: Compromised Backend Relay / Database Administrator:** Full access to Supabase PostgreSQL, Redis queues, and Go WebSocket relay servers.
- **ADV-3: Physical Device Thief (Locked State):** Possesses the physical Android device in a locked state (Screen off, Keyguard locked).
- **ADV-4: Physical Device Thief (Unlocked State / Coerced User):** Possesses the device in an unlocked state with access to the user interface.
- **ADV-5: Malware / Local App Sandbox Compromise:** Co-located malicious application running on the same device, or exploit executing within the app's UID context.
- **ADV-6: Cryptanalytic / Offline Attacker:** Possesses a copy of the encrypted `.vault` archive and has access to high-performance GPU clusters for offline brute-force attacks.

---

## 2. In-Depth Analysis of the 11 Threat Scenarios

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                                 NEXA THREAT MATRIX                                     │
├───────────────────────────────┬───────────────────────────────┬────────────────────────┤
│ 1. Stolen Unlocked Phone      │ 2. Stolen Locked Phone        │ 3. Compromised Process │
├───────────────────────────────┼───────────────────────────────┼────────────────────────┤
│ 4. Backup Replacement Attack  │ 5. Compromised Google Account │ 6. Compromised Supabase│
├───────────────────────────────┼───────────────────────────────┼────────────────────────┤
│ 7. Recovery Credential Theft  │ 8. Offline Brute-Force Attack │ 9. Rollback Attacks    │
├───────────────────────────────┼───────────────────────────────┼────────────────────────┤
│ 10. Malicious New Device      │ 11. Lost Original Device      │                        │
└───────────────────────────────┴───────────────────────────────┴────────────────────────┘
```

---

### Threat 1: Stolen Unlocked Phone
- **Attacker Profile:** ADV-4 (Possesses unlocked device).
- **Threat Vector:** Attacker opens the NEXA application, navigates to Settings -> Backup, or attempts to read SQLite databases from app internal storage.
- **Mitigation Controls in Proposed Design:**
  1. *App Biometric Gate:* Navigating to Settings or revealing the 12-word recovery mnemonic requires immediate biometric/PIN re-authentication.
  2. *Zero Mnemonic In-Memory Persistence:* The 12-word mnemonic is not held in continuous memory; it is sealed inside `EncryptedSharedPreferences`.
  3. *Remote Device Revocation:* If the user has a secondary authorized device, they can dispatch an authenticated device revocation (`POST /api/v1/devices/{device_id}/revoke`), which immediately terminates the stolen device's WebSocket session and revokes its session token.
- **Boundary Limit (What Design CANNOT Mitigate):**
  - If the attacker has an unlocked phone with full UI access and knows the device PIN, the attacker can view message plaintext currently displayed on the screen. No cryptographic architecture can prevent reading decrypted data when the OS session is fully compromised.

---

### Threat 2: Stolen Locked Phone
- **Attacker Profile:** ADV-3 (Possesses locked device; may attempt chip-off or cold-boot attacks).
- **Threat Vector:** Attacker powers down the device, mounts storage externally, or attempts to read `/data/data/com.nexa.app/databases/`.
- **Mitigation Controls in Proposed Design:**
  1. *Full Database Encryption at Rest:* `nexa_encrypted_vault.db` is encrypted with SQLCipher 4 using AES-256-CBC and HMAC-SHA512. Unkeyed storage reads return pseudo-random ciphertext.
  2. *Hardware-Backed KeyStore Isolation:* The database passphrase in `EncryptedSharedPreferences` is sealed under an AES-256-GCM MasterKey held exclusively inside the device's TEE/StrongBox. The key cannot be extracted through flash memory inspection.
  3. *Zero-Disk Staging:* In proposed Design C, backup extraction occurs in volatile RAM; no unencrypted temporary databases or key files reside in flash storage.
- **Residual Risk:** Negligible on modern devices with TEE hardware binding (Android 10+).

---

### Threat 3: Compromised Android Application Process (In-Memory Exploit)
- **Attacker Profile:** ADV-5 (Arbitrary code execution inside `com.nexa.app` UID).
- **Threat Vector:** Exploitation of native memory corruption (e.g. WebRTC or image parsing) to inspect app heap memory.
- **Mitigation Controls in Proposed Design:**
  1. *Immediate Volatile Memory Sanitization:* In `BackupCryptoEngine.kt` and `CryptoKeystoreManager.kt`, raw private keys and recovered database passphrases are immediately overwritten with `Arrays.fill(bytes, 0.toByte())` inside `finally` blocks.
  2. *Scoped Variable Lifetimes:* $K_{\text{vault}}$ and $K_{\text{env}}$ exist only for the duration of the coroutine execution and are never persisted as class fields.
- **Boundary Limit:**
  - An attacker with active code execution concurrent with an ongoing backup or restore operation could theoretically scrape RAM while the key is actively loaded in the cipher engine.

---

### Threat 4: Malicious Backup Replacement (Integrity & Tampering)
- **Attacker Profile:** ADV-1 (Google Drive API access or rogue MITM).
- **Threat Vector:** Attacker modifies `nx_backup.vault` in Google Drive, substitutes a corrupted SQLite database, or injects malicious payloads into the zip archive.
- **Mitigation Controls in Proposed Design:**
  1. *AES-256-GCM Authenticated Encryption:* Outer container is sealed with a 128-bit authentication tag. Any single-bit modification to ciphertext causes `AEADBadTagException` during decryption, aborting restore immediately.
  2. *Rigid 71-Byte Pre-Decryption Checksum:* The header contains `payloadSha256`. If the downloaded payload hash does not match, decryption is refused.
  3. *Manifest Snapshot SHA-256 Checksum:* After unpacking in memory, the extracted database snapshot must match `manifest.database_sha256`.
  4. *Zip-Slip Path Traversal Protection:* `VaultBundlePackager.unzipArchive` validates `newFile.canonicalPath.startsWith(targetDir.canonicalPath)` before writing any file, neutralizing Zip-Slip directory traversal exploits.
- **Result: 100% PROTECTED.** Tampered or replaced backups are mathematically impossible to decrypt or activate.

---

### Threat 5: Compromised Google Drive Account
- **Attacker Profile:** ADV-1 (Stolen Google OAuth password or token).
- **Threat Vector:** Attacker logs into the victim's Google Drive web interface or uses Google Drive REST API to download `appDataFolder/nx_backup_v2.vault`.
- **Mitigation Controls in Proposed Design:**
  1. *Zero-Knowledge Cloud Invariant:* The `.vault` container is fully client-side encrypted before upload. Google Drive stores only unreadable ciphertext.
  2. *Entropy Barrier:* The encryption key $K_{\text{vault}}$ is derived from the 12-word BIP-39 mnemonic (128 bits of cryptographic entropy) via PBKDF2-HMAC-SHA256 (65,536 rounds).
  3. *Zero Google Access to Keys:* Google does not possess the 12-word mnemonic, the salt, or the hardware Keystore keys.
- **Result: 100% PROTECTED.** Access to Google Drive yields zero user messages, zero media, and zero cryptographic keys.

---

### Threat 6: Compromised Supabase / Go Backend Account
- **Attacker Profile:** ADV-2 (Rogue database admin or server breach).
- **Threat Vector:** Attacker gains root access to Supabase PostgreSQL, Redis queues, or the Go relay server.
- **Mitigation Controls in Proposed Design:**
  1. *Physical Architecture Bifurcation:* Backups are completely isolated from Supabase and the Go backend. Backup data flows directly between the Android client and Google Drive.
  2. *Zero Backup Storage on Backend:* Neither Supabase nor Redis nor the Go server ever stores backup files, backup metadata, or backup keys.
  3. *Fail-Closed RLS:* Even within the server database, Migration 006 enforces strict fail-closed Row-Level Security on all tables.
- **Result: 100% PROTECTED.** A total server compromise has zero impact on backup confidentiality or integrity.

---

### Threat 7: Recovery Credential Theft (Compromised 12-Word Mnemonic)
- **Attacker Profile:** ADV-6 / Malicious Actor (Finds written-down 12 words or steals photo of mnemonic).
- **Threat Vector:** Attacker possesses the 12-word BIP-39 mnemonic.
- **Mitigation Controls & Analysis:**
  - *Dual-Factor Access Requirement:* Possessing the 12-word mnemonic alone is **INSUFFICIENT** to steal the backup. The attacker must ALSO obtain access to the victim's Google Account to download `nx_backup.vault` from Google Drive's hidden `appDataFolder`.
  - Conversely, possessing Google Account access alone is insufficient without the 12 words.
- **Boundary Limit (What Design CANNOT Mitigate):**
  - If an attacker obtains **BOTH** the victim's Google Account credentials **AND** the 12-word recovery mnemonic, the attacker can decrypt the backup vault. This is the intended behavior of a recovery credential.

---

### Threat 8: Offline Brute-Force Attacks on Vault Container
- **Attacker Profile:** ADV-6 (Possesses `.vault` file; attempts dictionary/rainbow table attacks).
- **Threat Vector:** Offline GPU-accelerated cracking of PBKDF2-HMAC-SHA256.
- **Mitigation Controls in Proposed Design:**
  1. *BIP-39 12-Word Key Space:* 12 words drawn from 2,048 candidates provide $2048^{12} \approx 5.44 \times 10^{39} \approx 2^{132}$ combinations (128 bits of entropy + 4 bits checksum).
  2. *Computational Cost (Work Factor):* 65,536 iterations of PBKDF2-HMAC-SHA256 per attempt.
  3. *Crack Time Calculations:*
     - At $10^9$ attempts/second (supercomputer cluster):
       $$\text{Time} = \frac{2^{128}}{10^9 \text{ hashes/sec}} \approx 1.07 \times 10^{22} \text{ years}$$
- **Result: 100% PROTECTED.** Brute-force attacks against the 12-word keyspace are computationally infeasible under the laws of physics.

---

### Threat 9: Rollback / Replay Attacks (Older Backup Substitution)
- **Attacker Profile:** ADV-1 (Replaces current backup with an older valid backup from 6 months ago).
- **Threat Vector:** Attacker causes the user to restore stale data, wiping out recent chat histories.
- **Mitigation Controls in Proposed Design:**
  1. *Split-Brain & Version Epoch Verification:* In `DriveBackupRepositoryImpl.kt:54–66`, the client inspects `appProperties["version_epoch"]`.
  2. *Monotonic Timestamp Guard:* If a remote backup has a timestamp older than the local database's recorded last-sync epoch, the client flags a split-brain conflict and warns the user before restoration.
  3. *Local Non-Destructive Rollback Journal:* The local database is preserved in `.rollback_bak` before applying any restore. If the user notices stale data, the local state can be recovered.

---

### Threat 10: Malicious or Unauthorized New Device
- **Attacker Profile:** Unauthorized user attempts to enroll a rogue device under victim's `NX-ID`.
- **Threat Vector:** Rogue device attempts to restore the victim's backup and intercept ongoing messages.
- **Mitigation Controls in Proposed Design:**
  1. *Decoupled E2EE Keys:* In NEXA's Signal-style architecture (`MULTI_DEVICE_SYNC_ARCHITECTURE.md`), E2EE identity keys are device-bound and NEVER restored from backup. A restored device generates its *own* fresh Ed25519 and X25519 keys.
  2. *Server-Side Device Registration Gate:* To register a new device under an existing account, the client must authenticate via Reverse SMS or Google OAuth.
  3. *Sibling Device Notification:* When a new device registers, existing devices receive a device sync packet and display an alert ("New device added to your account").

---

### Threat 11: Lost Original Device (Catastrophic Loss)
- **Attacker Profile:** Unavailability of Device A (Device dropped in ocean, destroyed, or stolen).
- **Threat Vector:** User needs to recover conversations on Device B without access to Device A.
- **Mitigation Controls in Proposed Design:**
  1. *Zero Dependency on Device A Hardware:* Under Design C, Device B recovers the database encryption key directly from the self-contained Key Envelope (`key_envelope.bin`) inside the encrypted vault using the 12-word mnemonic.
  2. *Zero Need for Local Keystore Migration:* Device B does not require Device A's Android Keystore keys; it re-wraps the recovered database key under Device B's own hardware Keystore.
- **Result: 100% PROTECTED.** Full recovery succeeds without Device A.

---

## 3. Summary of Threat Mitigation Capabilities

| Threat Scenario | Mitigation Status | Primary Protective Mechanism | Residual Vulnerability |
| :--- | :--- | :--- | :--- |
| **1. Stolen Unlocked Phone** | **PARTIALLY MITIGATED** | Biometric gates on Settings; remote session revocation | Screen viewing while unlocked and unlocked PIN known |
| **2. Stolen Locked Phone** | **FULLY MITIGATED** | SQLCipher AES-256-CBC + TEE KeyStore MasterKey isolation | None on secure Android 10+ hardware |
| **3. Compromised App Process** | **PARTIALLY MITIGATED** | Immediate RAM zeroing (`Arrays.fill`); short variable scopes | Live memory scraping during active encryption pass |
| **4. Malicious Backup Replace**| **FULLY MITIGATED** | AES-256-GCM 128-bit tag + 71B header SHA-256 + Zip-Slip check | None |
| **5. Compromised Google Drive** | **FULLY MITIGATED** | Client-side PBKDF2 (65,536) + AES-256-GCM Zero-Knowledge | None |
| **6. Compromised Backend** | **FULLY MITIGATED** | Physical architecture bifurcation; Zero backup server traffic | None |
| **7. Recovery Credential Theft**| **PARTIALLY MITIGATED** | Requires BOTH 12-word mnemonic AND Google Account access | Both factors compromised simultaneously |
| **8. Offline Brute-Force** | **FULLY MITIGATED** | 128-bit BIP-39 entropy ($2^{128}$ search space) | None |
| **9. Rollback Attacks** | **FULLY MITIGATED** | Version epoch verification + `.rollback_bak` journal | User overriding split-brain warning |
| **10. Rogue New Device** | **FULLY MITIGATED** | Device-bound E2EE keys + Reverse SMS / OAuth gate | Compromised primary telecom / Google session |
| **11. Lost Original Device** | **FULLY MITIGATED** | Self-contained Key Envelope in cloud vault + 12-word mnemonic | None (Intended recovery flow) |

**STATUS: THREAT MODEL COMPLETE. AWAITING USER APPROVAL.**
