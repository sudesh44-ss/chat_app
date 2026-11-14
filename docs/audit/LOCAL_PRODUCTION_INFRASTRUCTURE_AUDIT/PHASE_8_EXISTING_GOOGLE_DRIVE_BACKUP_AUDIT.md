# NEXA — Phase 8: Existing Google Drive Backup & Restore Audit

**Document Version:** 1.0.0  
**Target Subsystem:** Google Drive AppData REST API v3, Resumable Upload/Download, E2EE Backup Engine  
**Target Codebase:** `H:\chat_app` (Android Client Subsystem)  
**Audit Standard:** Zero-Knowledge Cloud Storage, Cryptographic Portability, Resilience & UX Verification  
**Date:** November 2026  
**Status:** **AUDIT COMPLETE**

---

## 1. Executive Summary

This audit assesses the actual production source code implementing Google Drive backup and restore in NEXA across twelve core source files. The system contains a sophisticated, privacy-first foundational infrastructure—including 256 KB chunked resumable uploads, HTTP Range download recovery, and client-side AES-256-GCM encryption within Google Drive's hidden `appDataFolder`.

However, the audit identified five architectural and UX gaps required for a complete, production-grade user experience:
1. **Cross-Device Key Portability Defect:** Restoring on a new device terminates with SQLCipher error 26 (now approved for remediation under **Design C Key Envelope**).
2. **Missing Granular Category Selection:** Current packaging is all-or-nothing; users cannot selectively back up or restore specific categories (Chats, Media, Preferences, etc.).
3. **Limited Account Management:** No UI exists to switch Google accounts, view account avatars, or disconnect without clearing app data.
4. **No Backup Management:** Users cannot view backup history, inspect remote file metadata, verify backup integrity on demand, or delete cloud backups.
5. **No Selective Restore or Restore Modes:** Restoration is an unconfigurable atomic overwrite; users cannot preview contents or choose between merge and replacement.

---

## 2. Answers to the 13 Mandatory Audit Questions

### 1. Is Google Drive authentication already implemented?
**YES.**  
Implemented in [`GoogleDriveAuthManager.kt:18–76`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/drive/GoogleDriveAuthManager.kt#L18-L76). It uses `GoogleSignInClient` with `GoogleSignInOptions.DEFAULT_SIGN_IN` and requests `Scope("https://www.googleapis.com/auth/drive.appdata")`. Bearer tokens are acquired on background threads via `GoogleAuthUtil.getToken(context, androidAccount, "oauth2:https://www.googleapis.com/auth/drive.appdata")`.

### 2. Can users select their Google account?
**PARTIALLY.**  
`googleSignInClient.signInIntent` invokes the Google Play Services account picker on initial sign-in. However, once an account is authorized, `GoogleSignIn.getLastSignedInAccount(context)` caches that account permanently. There is currently no UI or method to trigger account switching or present an account selection sheet without clearing app storage.

### 3. Is the selected Gmail account stored and restored correctly?
**YES.**  
In [`BackupPrefs.kt:39`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/local/preferences/BackupPrefs.kt#L39), `googleAccountEmail` is persisted in `nexa_backup_preferences`. In [`BackupViewModel.kt:62`](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/backup/BackupViewModel.kt#L62), `refreshAuthState()` updates the preference whenever `account?.email` is retrieved.

### 4. Which Google Drive API scope is currently used?
**`https://www.googleapis.com/auth/drive.appdata` (Drive AppData Scope).**  
Defined in [`GoogleDriveAuthManager.kt:74`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/drive/GoogleDriveAuthManager.kt#L74). This restricted scope strictly isolates NEXA backups from the user's personal Google Drive files and prohibits NEXA from accessing any documents outside its own application folder.

### 5. Where are backup files stored in Google Drive?
**In Google Drive's hidden Application Data folder (`appDataFolder`).**  
The file is named `nx_secure_backup.vault`.

### 6. Does the system use `appDataFolder` or regular Drive storage?
**Strictly `appDataFolder`.**  
In [`DriveAppDataService.kt:38`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/drive/DriveAppDataService.kt#L38) and [`DriveAppDataService.kt:106`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/drive/DriveAppDataService.kt#L106), queries use `spaces=appDataFolder` and uploads specify `parents = ["appDataFolder"]`. The backup file is completely invisible in the standard Google Drive web UI and mobile app, preventing accidental user deletion or tampering.

### 7. Can users see and manage their backups?
**NO.**  
[`BackupSettingsScreen.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/backup/BackupSettingsScreen.kt) only renders the last backup timestamp, total size, and a manual "Back Up Now" button. Users cannot see backup version details, inspect included categories, verify backup integrity, or delete the cloud backup.

### 8. Is resumable upload functional?
**YES.**  
Implemented in [`DriveResumableUploader.kt:32–97`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/drive/DriveResumableUploader.kt#L32-L97). It uses Google Drive's 256 KB chunked protocol (`uploadType=resumable`), tracks byte offsets via HTTP `308 Resume Incomplete`, persists active upload session state in `SecureStorage`, and applies exponential backoff for transient network dropouts.

### 9. Is interrupted download recovery functional?
**YES.**  
Implemented in [`DriveAppDataService.kt:149–299`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/drive/DriveAppDataService.kt#L149-L299). It downloads to a temporary `.part` file using HTTP `Range: bytes=X-` requests (HTTP 206 Partial Content), verifies remote ETags to prevent corrupting partial downloads if the remote file changes, and safely falls back to a fresh HTTP 200 download on 416 Range Not Satisfiable.

### 10. Is backup encryption actually end-to-end?
**YES.**  
In [`BackupCryptoEngine.kt:60–120`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/backup/BackupCryptoEngine.kt#L60-L120), all databases, preferences, and media files are bundled into a zip archive and encrypted client-side using streaming AES-256-GCM. The key ($K_{\text{vault}}$) is derived from the user's 12-word BIP-39 mnemonic via PBKDF2-HMAC-SHA256 (65,536 iterations). Neither Google Drive, Supabase, nor the Go relay receives plaintext data or cryptographic keys.

### 11. Can a backup created on Device A be restored on Device B?
**CURRENTLY FAILED (Fixing via Approved Design C).**  
Because `nexa_master_snapshot.db` inherits Device A's local SQLCipher passphrase, Device B generates a new random passphrase upon clean install and fails authentication with SQLite error 26. Under the approved **Design C (Dual-Layer Key Envelope)**, Device A packages a sealed `key_envelope.bin` that Device B decrypts in RAM using the 12-word mnemonic.

### 12. Are chats, attachments and device preferences currently included?
**YES, but all-or-nothing without category controls.**  
[`VaultBundlePackager.kt:65–116`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/backup/VaultBundlePackager.kt#L65-L116) packs:
- `databases/nexa_master_snapshot.db` (Messages, chats, contacts, transfers)
- `preferences/app_user_settings.json` and `custom_chat_configs.json`
- `media_vault/` (Locally stored attachments named by Blake3 hash)
However, there is no selective backup logic; everything is bundled unconditionally.

### 13. Are backup metadata and encryption keys handled securely?
**YES in crypto engine, but needs Design C RAM isolation.**  
Outer container has rigid 71-byte header with SHA-256 payload checksum and 128-bit AES-GCM tag. Implementing Design C guarantees that $K_{\text{db}}$ is wrapped in a dedicated binary envelope and never exposed in plaintext JSON or written to flash storage.

---

## 3. Existing Architecture Map

```
┌────────────────────────────────────────────────────────────────────────┐
│                        PRESENTATION LAYER                              │
│  - BackupSettingsScreen.kt  (Settings -> Chat Backup)                  │
│  - RestoreBackupScreen.kt   (Restore flow with 12-word input grid)     │
│  - BackupViewModel.kt       (StateFlow, jobs, auth refresh)            │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
┌───────────────────────────────────▼────────────────────────────────────┐
│                          DOMAIN LAYER                                  │
│  - DriveBackupRepository.kt (Interface: backup, restore, silent)       │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
┌───────────────────────────────────▼────────────────────────────────────┐
│                           DATA LAYER                                   │
│  - DriveBackupRepositoryImpl.kt (Flow orchestration & atomic swap)     │
│  - DriveAppDataService.kt       (Direct Drive v3 REST: query, dl, del) │
│  - DriveResumableUploader.kt    (256 KB chunked HTTP 308 uploader)     │
│  - GoogleDriveAuthManager.kt    (OAuth 2.0 appdata scope token source) │
│  - BackupPrefs.kt               (SharedPreferences for backup settings)│
│  - ScheduledBackupWorker.kt     (WorkManager 24h periodic task)        │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
┌───────────────────────────────────▼────────────────────────────────────┐
│                        CRYPTOGRAPHIC CORE                              │
│  - BackupCryptoEngine.kt        (PBKDF2 65,536 + AES-256-GCM stream)   │
│  - BackupVaultHeader.kt         (71-byte binary magic header)          │
│  - VaultBundlePackager.kt       (Multi-pillar zip packager & unpacker) │
│  - Bip39PassphraseHelper.kt     (128-bit entropy 12-word generator)    │
│  - SecureStorage.kt             (Android KeyStore MasterKey wrapper)   │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 4. Remediation Plan (Phases 2 through 8)

1. **Phase 2 — Backup Settings UI:**
   - Add Google Account section (avatar, email, change account, disconnect).
   - Add 12 granular backup category switches (Communication Data, Media & Files, App Data).
   - Add backup schedule controls (Manual, Auto on/off, Frequency, Wi-Fi only, Charging only).
2. **Phase 3 — Backup Execution Engine:**
   - Implement category-filtering in `VaultBundlePackager.packBundle`.
   - Update `DriveBackupRepositoryImpl` with accurate category-based sizing.
3. **Phase 4 — End-to-End Encryption (Design C):**
   - Author `key_envelope.bin` generation and zero-disk in-memory extraction in `BackupCryptoEngine.kt`, `BackupVaultHeader.kt`, and `SecureStorage.kt`.
4. **Phase 5 — Complete Restore UI:**
   - Enhance `RestoreBackupScreen` with backup discovery, category preview, selective restore toggles, and merge vs replace modes.
5. **Phase 6 — Backup Management:**
   - Implement cloud backup deletion, integrity verification, and detailed metadata inspection.
6. **Phase 7 & 8 — Integration & Automated Testing:**
   - Unit tests for Key Envelope roundtrip, category filtering, and regression testing.

**STATUS: AUDIT COMPLETE. PROCEEDING TO IMPLEMENTATION.**
