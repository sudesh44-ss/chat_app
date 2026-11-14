# NEXA — Phase 8: Known Limitations & Production Constraints

## 1. Executive Summary

This document provides a transparent accounting of known operational boundaries, device hardware constraints, platform limitations, and external service quotas affecting the NEXA Google Drive Backup & Restore system as of Phase 8.

---

## 2. Platform & Hardware Constraints

### 2.1 Android Keystore Hardware Isolation Boundary
- **Limitation:** As designed for Zero-Knowledge security, hardware-bound private keys (e.g., ECDSA identity keys, Double Ratchet state keys generated inside Android Keystore / StrongBox) cannot be exported into cloud backups.
- **Impact on Restore:** When restoring to a new physical device, the user will retain their complete message history, verified contacts, group memberships, and media, but the application must re-establish active Double Ratchet sessions with communication peers. Any queued unread messages sent during the migration window must be delivered via the Go relay's offline queue.

### 2.2 Android WorkManager Execution Windows & Battery Optimization
- **Limitation:** Background backups scheduled via Android WorkManager are subject to Doze Mode, OEM aggressive app-killing policies (e.g., Xiaomi MIUI, Huawei EMUI, Samsung battery optimization), and network metering constraints.
- **Mitigation:**
  - `ScheduledBackupWorker` specifies `NetworkType.UNMETERED` (or `CONNECTED` if configured) and `requiresCharging(true)`.
  - Backups default to a 24-hour periodic interval.
  - If a device is kept off a charger or in extreme power-saving mode, automated backups will be deferred by Android until the device is plugged in and connected to Wi-Fi.

### 2.3 Free Google Drive Storage Quota
- **Limitation:** NEXA stores backups in the user's private Google Drive `appDataFolder`. While this folder is hidden, it still consumes storage against the user's overall Google account quota (typically 15 GB free tier).
- **Mitigation:**
  - The Backup Settings UI provides live size estimation.
  - Granular category filtering allows users to exclude high-capacity media (e.g., Videos and Documents) to keep backup vaults under 100 MB.
  - When Google Drive returns HTTP `403 QuotaExceeded`, NEXA displays a clear error informing the user that their Google Drive is full.

---

## 3. Network & Upload Boundaries

### 3.1 Google Drive Resumable Upload Session Lifespan
- **Limitation:** Google Drive Resumable Upload session URIs expire after approximately 7 days.
- **Handling:** If an upload is paused or disconnected for longer than 7 days, the resumable session token expires. `DriveResumableUploader` detects HTTP `404 Not Found` or `410 Gone` on resume queries, clears the stale session ID, and initiates a clean new upload from byte 0.

### 3.2 Mobile Data Metering
- **Limitation:** By default, "Backup over Wi-Fi only" is enabled. Users on unlimited 5G cellular plans must explicitly toggle this off if they wish to back up over cellular data.

---

## 4. Cryptographic & Recovery Invariants

### 4.1 Lost BIP-39 Recovery Phrase Unrecoverability
- **Limitation:** True Zero-Knowledge encryption means there is no "Forgot Recovery Phrase" backdoor. Neither NEXA, Google, nor Supabase can recover a lost 12-word mnemonic phrase.
- **Mitigation:**
  - The Backup Settings screen prominently features a "Reveal Recovery Phrase" card with a warning requiring the user to physically write down the words.
  - The restore flow rejects any mnemonic that does not pass the BIP-39 4-bit SHA-256 checksum, preventing typos from attempting doomed decryptions.

---

## 5. Summary of Open Items for Future Releases

1. **Multi-Cloud Provider Fallback:** Support for Nextcloud / WebDAV / Proton Drive in addition to Google Drive.
2. **Differential / Incremental Backups:** Currently, backups create a fresh consolidated point-in-time snapshot. Future iterations may explore rsync-style binary diffing for multi-gigabyte media vaults.
3. **Biometric Guard for Backup Deletion:** Prompting for local biometric authentication before executing a permanent cloud backup deletion.
