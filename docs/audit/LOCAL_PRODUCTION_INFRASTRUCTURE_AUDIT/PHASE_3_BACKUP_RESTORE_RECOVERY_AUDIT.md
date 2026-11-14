# NEXA — Phase 3: Backup & Restore Interruption Recovery Comprehensive Audit Report

**Audit Type:** Independent Source-Level Interruption Recovery, Data Integrity, Encryption & Atomic Replacement Audit  
**Target Codebase:** `H:\chat_app` (Android Application & Database Subsystems)  
**Authority Hierarchy:** `1architecture.md` / `docs/*.md` / Actual Source Code  
**Audit Completion Date:** November 2026  
**Status:** **AUDIT COMPLETE — AWAITING IMPLEMENTATION APPROVAL**

---

## 1. Executive Summary

This independent audit assesses the complete source-level implementation of NEXA's Google Drive Zero-Knowledge Encrypted Vault Backup and Restore subsystem.

The audit specifically targeted failure resilience, network switching recovery, process termination survival, SQLite WAL snapshot consistency, SQLCipher encryption compatibility, and atomic database replacement safety.

### Summary of Audit Verdicts

| Subsystem / Dimension | Status | Key Technical Reality |
| :--- | :--- | :--- |
| **Backup Implementation** | **VERIFIED** | Implemented in `DriveBackupRepositoryImpl.kt`, `BackupCryptoEngine.kt`, and `VaultBundlePackager.kt`. Multi-pillar bundle packages database, preferences, media, and manifest. |
| **Backup Resumability** | **PARTIALLY VERIFIED** | In-process 256 KB chunk resumption (`DriveResumableUploader.kt`) and cross-process session persistence (`UploadSessionState.kt`, `SecureStorage.kt`) are implemented. However, manual backups run in UI coroutine scope without foreground service protection. |
| **Restore Implementation** | **PARTIALLY VERIFIED** | Implemented via `downloadVault()`, `decryptAndRestoreVault()`, and `unpackAndRestoreBundle()`. Pre-decryption SHA-256 and AES-GCM auth tags verified. |
| **Restore Resumability** | **VERIFIED** | HTTP Range requests (`bytes=X-`), `.part` file isolation, ETag validation, and HTTP 206 append logic implemented in `DriveAppDataService.kt`. |
| **Network Switching Recovery** | **VERIFIED** | Resumable upload session URIs and HTTP Range downloads are IP-agnostic. Switching from Wi-Fi to mobile data or changing VPNs retains sessions. |
| **Google Drive Auth Recovery** | **PARTIALLY VERIFIED** | HTTP 401 invalidates the token in Play Services cache, but in-flight requests abort rather than acquiring a refreshed token and retrying transparently. |
| **Database Snapshot Consistency** | **PARTIALLY VERIFIED** | `VACUUM INTO` creates an isolated snapshot. However, WAL checkpoints are not explicitly invoked prior to snapshotting. |
| **Atomic Database Replacement** | **FAILED (CRITICAL DATA LOSS RISK)** | Live database and WAL files are deleted *before* the decrypted snapshot is copied or validated. Any failure during file copy or database open permanently destroys user data without rollback. |
| **Multi-Device SQLCipher Keying** | **FAILED (CRITICAL BLOCKER)** | `VACUUM INTO` inherits Device A's local SQLCipher encryption key. Restoring on Device B (which has a newly generated Keystore key) fails with "file is not a database", making new-device restore impossible. |
| **Memory Scalability** | **FAILED (HIGH RISK)** | `BackupCryptoEngine.decryptAndRestoreVault` allocates monolithic byte arrays in RAM for the full ciphertext and plaintext, causing `OutOfMemoryError` on large vaults (>50 MB). |
| **Recovery Phrase & Crypto Security**| **VERIFIED** | 12-word BIP-39 mnemonic, PBKDF2-HMAC-SHA256 (65,536 iterations), AES-256-GCM authenticated encryption. Zero plaintext leaks to Google Drive. |
| **Automated Tests** | **PARTIALLY VERIFIED** | 13/13 unit tests passing in `GoogleDriveBackupUnitTests.kt`, 6/6 in `DriveUploadRecoveryUnitTests.kt`, 6/6 in `DriveRestoreDownloadResumeUnitTests.kt`. End-to-end `packBundle`/`unpackAndRestoreBundle` pipeline lacks automated test coverage. |

---

## 2. Actual Backup Architecture

NEXA's backup subsystem creates a multi-pillar container packaged as an encrypted vault in Google Drive's isolated `appDataFolder`:

```
+-------------------------------------------------------------------------------------------------+
|                                 NEXA BACKUP CREATION PIPELINE                                   |
+-------------------------------------------------------------------------------------------------+

  [ Live Room Database ] (nexa_encrypted_vault.db, SQLCipher Encrypted)
            │
            ▼
  [ 1. Zero-Lock Snapshot ] ──> SQLite 'VACUUM INTO' generates 'nexa_master_snapshot.db'
            │
            ├─► databases/nexa_master_snapshot.db
            ├─► preferences/app_user_settings.json & custom_chat_configs.json
            ├─► media_vault/ (Files named by Blake3 Hash)
            └─► manifest.json (Epoch, total messages, conversations, media count, db SHA-256)
            │
            ▼
  [ 2. Bundle Packaging ] ──> ZipOutputStream compresses directory into temporary zip archive
            │
            ▼
  [ 3. Key Derivation ] ────> 12-word BIP-39 Mnemonic + 16-byte random salt 
                              PBKDF2-HMAC-SHA256 (65,536 iterations) ──> 256-bit AES Master Key
            │
            ▼
  [ 4. Authenticated Encryption ] ──> AES-256-GCM with 96-bit random IV & 128-bit Tag
                                      Appends rigid 71-byte header (Magic 'NXVAULT', version, salt, IV, SHA-256)
            │
            ▼
  [ 5. Google Drive Resumable Upload ]
        - Split-brain check: queries existing remote backup epoch
        - Requests Google Drive resumable session URI (Location header)
        - Persists UploadSessionState in SecureStorage (URI, expected size, epoch, vault path)
        - Streams 256 KB binary slices via HTTP PUT
        - Updates confirmed byte offset after each chunk
        - Deletes local temporary vault only after verified completion
```

### Inspected Source Files
- `app/src/main/java/com/nexa/app/core/backup/BackupCryptoEngine.kt:36–122`
- `app/src/main/java/com/nexa/app/core/backup/VaultBundlePackager.kt:55–145`
- `app/src/main/java/com/nexa/app/core/backup/BackupVaultHeader.kt:1–75`
- `app/src/main/java/com/nexa/app/data/drive/DriveAppDataService.kt:88–141`
- `app/src/main/java/com/nexa/app/data/drive/DriveResumableUploader.kt:32–97`
- `app/src/main/java/com/nexa/app/data/repository/DriveBackupRepositoryImpl.kt:37–165`

---

## 3. Actual Restore Architecture

The restoration pipeline downloads the remote vault, verifies authentication tags, unpacks the multi-pillar bundle, and swaps the live database:

```
+-------------------------------------------------------------------------------------------------+
|                                 NEXA RESTORE EXECUTION PIPELINE                                 |
+-------------------------------------------------------------------------------------------------+

  [ Google Drive appDataFolder ]
            │
            ▼
  [ 1. HTTP Range Download ] ──> DriveAppDataService.downloadVault()
        - Queries remote metadata (file size, ETag)
        - Downloads to temporary staging file: 'nx_download_<id>.vault.part'
        - If interrupted: resumes via 'Range: bytes=<existingSize>-' and 'If-Match: <etag>'
        - Validates HTTP 206 Partial Content (or safe reset on HTTP 200 OK)
        - On 100% byte verification: atomically promotes .part to 'nx_download_<id>.vault'
            │
            ▼
  [ 2. Header & Checksum Verification ] ──> BackupCryptoEngine.decryptAndRestoreVault()
        - Parses 71-byte header: validates 'NXVAULT' magic and version
        - Computes SHA-256 of downloaded ciphertext
        - Rejects corrupted or truncated files BEFORE decryption
            │
            ▼
  [ 3. Authenticated AES-GCM Decryption ]
        - Derives AES-256 key from 12-word mnemonic + header salt (PBKDF2 65,536 iterations)
        - Decrypts ciphertext with AES-256-GCM
        - AEAD tag verification: throws immediately on wrong passphrase or tampered ciphertext
            │
            ▼
  [ 4. Bundle Extraction & Verification ] ──> VaultBundlePackager.unpackAndRestoreBundle()
        - Unzips archive to staging directory (with Zip Slip traversal guards)
        - Verifies manifest.json: validates database_sha256 checksum against extracted DB
        - Restores media_vault files to filesDir/media_vault
        - Restores user settings and custom chat configs SharedPreferences
            │
            ▼
  [ 5. Database Replacement & Activation ] (CURRENT IMPLEMENTATION - CRITICAL FLAW)
        - Calls NexaDatabase.closeAndResetInstance()
        - Deletes live DB (-wal, -shm, -journal, .db)
        - Copies snapshot to live DB path
        - Opens database with local Keystore passphrase
```

---

## 4. Backup Interruption Recovery Findings

### A. Network Interruption Behavior
1. **Transient Disconnection / Packet Loss:**
   - In `DriveResumableUploader.kt:102–162`, chunk upload implements an exponential backoff loop (up to 5 retries with delays: 1s, 2s, 4s, 8s, 16s).
   - If an upload PUT times out or drops, the uploader probes Google Drive using `queryUploadOffset()`. Drive responds with `308 Resume Incomplete` and `Range: bytes=0-X`, allowing the client to resume precisely at byte `X + 1`.
2. **Wi-Fi to Cellular / VPN Switching:**
   - Google Drive resumable session URIs (`https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&upload_id=...`) are completely decoupled from the client's public IP address.
   - When the network interface switches or VPN activates, existing HTTP connections drop, but subsequent chunk PUT requests to the session URI continue seamlessly once the new network route is established.

### B. Process Termination & Device Reboot
1. **P2-A Recovery Enhancement:**
   - Before uploading, `DriveBackupRepositoryImpl.kt:120` persists `UploadSessionState` to hardware-backed `SecureStorage`.
   - Persisted fields: `sessionUri`, `versionEpoch`, `vaultFilePath`, `expectedFileSize`, `lastConfirmedOffset`, `existingFileId`.
2. **Crash Recovery Flow:**
   - Upon next backup execution (`createAndUploadBackup()` or `performSilentBackup()`), the repository detects `secureStorage.getActiveUploadSession()`.
   - If `File(session.vaultFilePath)` exists and size matches `expectedFileSize`, it sends a `bytes */totalBytes` probe to Google Drive.
   - If valid, upload resumes without re-encrypting the database.
   - If Google Drive returns HTTP 404/410, `UploadSessionExpiredException` clears the stale session and falls back to a fresh snapshot.
3. **Identified Gap in Manual Backup UI:**
   - `BackupViewModel.triggerBackup()` runs directly inside `viewModelScope.launch`.
   - If the user leaves the `BackupSettingsScreen` or the app is placed in the background without a Foreground Service, Android may kill the process, halting the upload until the user manually returns and initiates a backup again.

---

## 5. Restore Interruption Recovery Findings

### A. Download Resumption (P2-B Verification)
1. **HTTP Range Protocol:**
   - `DriveAppDataService.downloadVault()` writes incoming bytes to `${destinationFile.name}.part`.
   - If the download drops at 80%, the next invocation inspects `partFile.length()` and attaches `Range: bytes=<length>-`.
   - Verified by unit tests in `DriveRestoreDownloadResumeUnitTests.kt`.
2. **Anti-Corruption Safeguards:**
   - **HTTP 206 Partial Content:** Appends bytes only if `Content-Range` start byte strictly equals `partFile.length()`. If mismatched, truncates file and restarts from byte 0.
   - **HTTP 200 OK:** If Google Drive ignores Range headers, truncates `.part` file immediately; never appends a full response to a partial file.
   - **ETag Validation:** If the remote file is modified during download, ETag mismatch triggers `.part` deletion and full restart.
   - **Promotion Gate:** The `.part` file is promoted to destination file ONLY when downloaded bytes match 100% of expected total size.

### B. Decryption and Validation Interruption
- Decryption and unpacking occur entirely in temporary cache files (`nx_download_<id>.vault`, `nx_restored_<ts>.db`, staging directory).
- If the app is killed or power is lost during decryption or unzipping, the active database is completely untouched.
- However, if the process terminates during the file replacement phase, severe data corruption occurs (see Section 8).

---

## 6. Network Switching & Environment Recovery Findings

| Network Event | In-Flight Backup | In-Flight Restore | Recovery Verdict |
| :--- | :--- | :--- | :--- |
| **Wi-Fi Disconnect (<30s)** | Retries chunk with backoff; resumes. | OkHttp read timeout fires; retry resumes via Range header. | **VERIFIED** |
| **Wi-Fi to Mobile Switching** | Socket resets; queryUploadOffset reconciles byte offset. | Socket resets; Range request resumes from partial file length. | **VERIFIED** |
| **VPN Connected Mid-Transfer**| IP changes; Google session URI valid; upload continues. | IP changes; Range request resumes via VPN route. | **VERIFIED** |
| **VPN Disconnected Mid-Transfer**| IP changes back; session URI valid; upload continues. | IP changes back; Range request resumes. | **VERIFIED** |
| **Public IP Address Change** | Google session URI unaffected; resumes cleanly. | Range request unaffected; resumes cleanly. | **VERIFIED** |
| **DNS Resolution Failure** | Exponential backoff retries up to 5 times. | Throws IOException; partial file retained on disk for next try. | **VERIFIED** |
| **Airplane Mode / No Network**| Upload loop fails; active session preserved in SecureStorage. | Download fails; `.part` file preserved in cacheDir. | **VERIFIED** |

---

## 7. Google Drive Integration Audit

1. **OAuth2 Authentication Scope:**
   - Scoped strictly to `https://www.googleapis.com/auth/drive.appdata` in `GoogleDriveAuthManager.kt:74`.
   - The application has zero access to user personal files in Google Drive, and user cannot view NEXA backup vaults in standard Drive UI.
2. **Authentication Token Refresh Gap:**
   - `DriveAppDataService` catches HTTP 401 and calls `authManager.invalidateToken(token)`.
   - However, it immediately throws `IOException("Drive authorization expired (HTTP 401)")`.
   - Neither `DriveResumableUploader` nor `DriveAppDataService` attempts to request a refreshed token from `GoogleAuthUtil` and retry the in-flight request. The user is presented with a failure and must manually retry.
3. **Split-Brain Cloud Conflict Protection:**
   - Before uploading, `createAndUploadBackup()` compares `remoteEpoch` with `localEpoch`.
   - If `remoteEpoch > localEpoch`, upload aborts immediately to prevent an older device from overwriting newer cloud history.
4. **Resumable Session Lifespan:**
   - Google Drive resumable upload sessions have a rolling 1-week expiration window.
   - Tested and verified: HTTP 404/410 cleanly throws `UploadSessionExpiredException`, clearing stale state.

---

## 8. Database Snapshot Consistency & SQLite WAL Handling

1. **Snapshot Isolation (`VACUUM INTO`):**
   - In `VaultBundlePackager.kt:73`, snapshotting executes:
     ```sql
     VACUUM INTO '.../databases/nexa_master_snapshot.db'
     ```
   - In SQLite 3.27+, `VACUUM INTO` creates a clean, unfragmented, standalone database file.
   - It acquires a shared read lock on the database and WAL file, ensuring snapshot isolation. Transactions committed after `VACUUM INTO` starts are not included in the snapshot.
2. **WAL Checkpoint Vulnerability:**
   - The backup engine **never executes an explicit WAL checkpoint** (`PRAGMA wal_checkpoint(TRUNCATE)` or `wal_checkpoint(PASSIVE)`) prior to snapshotting.
   - If the WAL file contains gigabytes of uncheckpointed transactions, `VACUUM INTO` must read and merge all WAL frames into the new database file, causing high CPU spikes and extended snapshot latency.
   - Active writing during snapshotting does not corrupt the snapshot, but new messages arriving during snapshot creation will not be included until the subsequent backup cycle.

---

## 9. Atomic Database Replacement — Critical Failure Analysis

**Current Implementation (`DriveBackupRepositoryImpl.kt:226–243` and `VaultBundlePackager.kt:233–246`):**
```kotlin
NexaDatabase.closeAndResetInstance()

val liveDbFile = context.getDatabasePath(DATABASE_NAME)
val walFile = File(liveDbFile.path + "-wal")
val shmFile = File(liveDbFile.path + "-shm")
val journalFile = File(liveDbFile.path + "-journal")

if (walFile.exists()) walFile.delete()
if (shmFile.exists()) shmFile.delete()
if (journalFile.exists()) journalFile.delete()

if (liveDbFile.exists()) liveDbFile.delete() // <-- LIVE DB UNCONDITIONALLY DELETED
tempRestoredDb.copyTo(liveDbFile, overwrite = true)

val dbPassphrase = secureStorage.getOrCreateDatabasePassphrase()
database = NexaDatabase.getInstance(context, dbPassphrase)
```

### Critical Flaws Identified:
1. **Destructive Delete-Before-Copy:**
   - `liveDbFile.delete()` is executed BEFORE copying the restored database.
   - If `tempRestoredDb.copyTo(liveDbFile)` fails due to out-of-disk space, I/O error, device power loss, or Android OS killing the process during copy, the original database is already gone!
2. **Zero Rollback Mechanism:**
   - There is no staging backup of the live database (`.bak`).
   - If `NexaDatabase.getInstance()` throws an exception (e.g., schema migration error, SQLCipher key rejection, corrupted file), the code enters the `finally` block:
     ```kotlin
     finally {
         if (tempVaultFile.exists()) tempVaultFile.delete()
         if (tempRestoredDb.exists()) tempRestoredDb.delete()
     }
     ```
   - `tempRestoredDb` is deleted! The original `liveDbFile` was already deleted! **Total unrecoverable permanent data loss occurs.**
3. **Duplicate Code Execution:**
   - `NexaDatabase.closeAndResetInstance()` and the database file deletion/copy logic are executed TWICE during restore: once inside `VaultBundlePackager.unpackAndRestoreBundle()`, and again inside `DriveBackupRepositoryImpl.downloadAndRestoreBackup()`.

---

## 10. Multi-Device SQLCipher Encryption Incompatibility (P0 Blocker)

### Root Cause Analysis:
1. `NexaDatabase` is encrypted with SQLCipher via `SupportOpenHelperFactory(passphrase)`:
   - `passphrase` is a random 32-byte key generated by `secureStorage.getOrCreateDatabasePassphrase()` and stored in device-local `EncryptedSharedPreferences`.
2. When Device A performs a backup:
   - `rawDatabase.execSQL("VACUUM INTO '...'")` is executed.
   - In SQLCipher 4, `VACUUM INTO` inherits the active database encryption key ($K_{\text{deviceA}}$).
   - Therefore, `nexa_master_snapshot.db` is encrypted with Device A's local SQLCipher key.
3. The snapshot is zipped and encrypted with PBKDF2(passphraseWords), then uploaded to Google Drive.
4. When a user restores on Device B (or after reinstalling on Device A):
   - Device B generates a NEW random 32-byte SQLCipher key ($K_{\text{deviceB}}$).
   - Device B downloads and decrypts the zip bundle using the 12-word mnemonic.
   - Device B extracts `nexa_master_snapshot.db` (which is encrypted with $K_{\text{deviceA}}$).
   - Device B copies `nexa_master_snapshot.db` into `liveDbFile`.
   - Device B attempts to open `liveDbFile` using $K_{\text{deviceB}}$!
   - **Result:** SQLCipher rejects the key with:  
     `net.zetetic.database.sqlcipher.database.SQLiteException: file is not a database`
   - **Impact:** **Restoring backups on a new phone or after app reinstallation is 100% broken.**

---

## 11. Memory Scalability & OOM Risks

In `BackupCryptoEngine.kt`:
```kotlin
// Line 144:
val ciphertext = ByteArray((vaultFile.length() - BackupVaultHeader.HEADER_SIZE).toInt())
// Line 164:
val computedHash = MessageDigest.getInstance("SHA-256").digest(ciphertext)
// Line 179:
val decryptedBytes = cipher.doFinal(ciphertext)
```
- If a user's backup vault is 100 MB (common when backing up media or years of chat history), `decryptAndRestoreVault` allocates:
  - 100 MB byte array for `ciphertext`
  - 100 MB byte array for `decryptedBytes` inside `cipher.doFinal()`
  - Intermediate decompression buffers
- On Android devices with standard 192 MB to 256 MB per-app heap limits, this causes an immediate `java.lang.OutOfMemoryError` (OOM) crash during restore.
- Note: `VaultStreamEncryptor.kt` was written with true 64 KB streaming buffers, but `BackupCryptoEngine.kt` does not use it for restore decryption.

---

## 12. Recovery State Machine Audit

### Existing State Model:
NEXA currently models backup and restore states using `DriveResource<T>` in `DriveResource.kt`:
- `DriveResource.Idle`
- `DriveResource.Progress(message: String, progressFraction: Float)`
- `DriveResource.Success(data: T)`
- `DriveResource.Error(message: String, exception: Throwable?)`

### Findings:
1. **No Persistent State:**
   - `DriveResource` states live strictly in memory inside `MutableStateFlow` in `BackupViewModel`.
   - If the activity recreation occurs or the process is killed, state resets to `Idle`.
2. **Missing Granular Transition States:**
   - There are no explicit states for `STAGING_DATABASE`, `PREPARING_REPLACEMENT`, `ACTIVATING`, or `ROLLED_BACK`.
   - UI cannot determine whether an interruption happened during download or during database activation.

---

## 13. Automated Test Verification Results

### Existing Test Suite Analysis:
1. **`GoogleDriveBackupUnitTests.kt` (13 Tests - All PASSING):**
   - Header serialization/deserialization (71-byte rigid header).
   - Magic bytes and truncation rejection.
   - BIP-39 mnemonic generation, dictionary check, checksum validation, and normalization.
   - GZIP compression roundtrip.
   - AES-GCM encryption/decryption roundtrip using synthetic test strings.
   - Wrong passphrase rejection (`AEADBadTagException`).
   - Ciphertext tampering detection.
   - Resumable upload chunk size constant (256 KB).
   - Split-brain epoch conflict comparison.
   - Streaming encryptor roundtrip and wrong passphrase rejection.
   - SHA-256 manifest calculation.
2. **`DriveUploadRecoveryUnitTests.kt` (6 Tests - All PASSING):**
   - Session URI validation (rejects HTTP, spoofed subdomains, attacker domains).
   - Session state validation.
   - Offset parsing from HTTP 308 `Range: bytes=0-X` header.
   - Process death recovery file validation.
   - `UploadSessionExpiredException` on HTTP 404/410.
3. **`DriveRestoreDownloadResumeUnitTests.kt` (6 Tests - All PASSING):**
   - HTTP Range header generation (`bytes=X-`).
   - Content-Range parsing and start byte validation.
   - Safe reset on HTTP 200 OK vs append on HTTP 206 Partial Content.
   - ETag change detection.
   - HTTP 416 Range Not Satisfiable reset.
   - Fail-closed `.part` staging promotion.
4. **Critical Test Coverage Gap:**
   - **Zero tests** exist for `packBundle()`, `unpackAndRestoreBundle()`, `createEncryptedVault()`, and `decryptAndRestoreVault()`.
   - **Zero tests** verify real SQLite `VACUUM INTO` execution against a live SQLCipher database.
   - **Zero failure-injection tests** verify rollback behavior when database activation fails.

---

## 14. Critical & High-Priority Blockers

| Priority | Component | Issue Description | Root Cause / File Location | Impact |
| :--- | :--- | :--- | :--- | :--- |
| **P0 (Critical)** | Restore / DB | Destructive delete-before-copy without backup or rollback. | `DriveBackupRepositoryImpl.kt:237`, `VaultBundlePackager.kt:243` | If process dies, disk fills, or copy fails, the active database is permanently lost. |
| **P0 (Critical)** | Restore / Crypto | SQLCipher key mismatch across devices. `VACUUM INTO` exports snapshot encrypted with Device A's local key. | `VaultBundlePackager.kt:73`, `SecureStorage.kt:50` | Restoring backup on a new device or fresh install fails with "file is not a database". |
| **P1 (High)** | Restore / RAM | Monolithic `ByteArray` allocations in heap for ciphertext and decrypted bytes. | `BackupCryptoEngine.kt:144, 179` | Vaults > 50 MB trigger `OutOfMemoryError` on 32-bit and low-RAM devices. |
| **P1 (High)** | Backup / WAL | No explicit WAL checkpoint before `VACUUM INTO`. | `VaultBundlePackager.kt:70` | Massive WAL files cause long snapshot pauses and high CPU overhead. |
| **P1 (High)** | Backup / UI | Manual backup runs on UI `viewModelScope` without Android Foreground Service. | `BackupViewModel.kt:71` | Backup terminates if app is backgrounded on aggressive OEM battery savers (Vivo/Xiaomi). |
| **P2 (Medium)** | Auth / Network | HTTP 401 invalidates token but aborts in-flight transfer instead of refreshing and retrying. | `DriveAppDataService.kt:48, 128, 190` | Requires user to restart backup on token expiry. |
| **P2 (Medium)** | Concurrency | No mutex locking between `ScheduledBackupWorker` and manual UI backup. | `ScheduledBackupWorker.kt:37`, `DriveBackupRepositoryImpl.kt:37` | Concurrent execution can trigger conflicting database locks. |
| **P2 (Medium)** | Testing | Zero end-to-end unit tests for `packBundle` and `unpackAndRestoreBundle`. | `app/src/test/java/` | Regressions in database packaging are not detected by CI. |

---

## 15. Audit Conclusion & Phase Status

- **Backup Implementation:** **VERIFIED**
- **Backup Resumability:** **PARTIALLY VERIFIED**
- **Restore Resumability:** **VERIFIED**
- **Atomic Database Replacement:** **FAILED (CRITICAL DATA LOSS RISK)**
- **Multi-Device SQLCipher Keying:** **FAILED (CRITICAL RESTORE BLOCKER)**
- **Phase Status:** **AUDIT COMPLETE — AWAITING IMPLEMENTATION APPROVAL**