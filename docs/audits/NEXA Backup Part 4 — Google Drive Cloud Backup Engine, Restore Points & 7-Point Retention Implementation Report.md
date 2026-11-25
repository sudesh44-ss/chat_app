# NEXA Backup Part 4 — Google Drive Cloud Backup Engine, Restore Points & 7-Point Retention Implementation Report

**Document Status:** Complete & Verified  
**Date:** November 23, 2026  
**Implementation Stage:** Part 4 of 9 (Approved Backup & Restore Roadmap)  
**Target:** Google Drive AppDataFolder Cloud Backup Engine, Independent Restore Points & 7-Point Retention Policy  

---

## 1. Existing Cloud Backup System Findings (Part 1 Audit Baseline)

As identified in the Part 1 System Audit (`docs/audits/NEXA Backup & Restore — Existing System Audit & Gap Analysis.md`), the legacy Google Drive backup system suffered from foundational architectural flaws:
1. **Single-File Overwrite Model:**
   - Every backup operation unconditionally targeted a single remote file (`nx_secure_backup.vault`).
   - If an upload or process was interrupted midway or suffered corruption, the user's only recoverable backup was destroyed or left in an inconsistent remote state.
2. **Absence of Multiple Restore Points:**
   - Users had no timeline or history of backups. There was zero capability to roll back to yesterday's or last week's state if accidental chat deletions occurred.
3. **No Granular Metadata in the Cloud:**
   - Cloud backup metadata was strictly confined to a single epoch timestamp (`version_epoch`) stored in `appProperties`. No category configuration, size metrics, message counts, conversation counts, or checksums were stored remotely.
4. **Lack of Discovery & Self-Healing:**
   - If local application data or cache was wiped, the app had to download the entire vault file blindly just to discover whether it was usable.
5. **No Verification Gate Before Replacement:**
   - The previous system initiated overwrites before validating whether the incoming upload completed successfully or whether its cryptographic header was intact.

---

## 2. New Cloud Storage Architecture

Part 4 completely replaces the fragile single-overwrite model with an enterprise-grade multi-restore-point cloud backup engine operating within Google Drive's sandboxed, isolated `appDataFolder`:

```
Google Drive: appDataFolder (Isolated App-Specific Cloud Sandbox)
 ├── nexa_backup_bp_1732380000000_3a8f1b2c.vault  [Status: VERIFIED, Messages: 1542, Media: 87, SHA-256]
 ├── nexa_backup_bp_1732293600000_c4d5e6f7.vault  [Status: VERIFIED, Messages: 1490, Media: 82, SHA-256]
 ├── nexa_backup_bp_1732207200000_90ab12cd.vault  [Status: VERIFIED, Messages: 1420, Media: 79, SHA-256]
 ├── ... (Up to 7 verified restore points retained)
 └── nx_secure_backup.vault                        [Legacy Fallback Point, Auto-Detected & Restorable]
```

### Key Architectural Tenets:
- **Zero In-Place Overwrites:** Each backup operation writes to a fresh, independent remote file.
- **Self-Describing Remote Artifacts:** File metadata (manifest summary, message/media counts, category configurations, timestamps, integrity SHA-256) is recorded directly in Google Drive's native `appProperties` metadata dictionary.
- **Verification Gate Before Retention:** A new backup must pass remote existence, size matching, and cryptographic header verification before being marked `VERIFIED`. Retention pruning runs *only* after verification succeeds.
- **Deterministic 7-Point Retention:** Maintains at most 7 verified restore points chronologically (newest to oldest), safely trimming only verified candidates beyond index 7.
- **Zero PII Cloud Guarantee:** No plain message content, contact names, phone numbers, encryption keys, or private identifiers are stored in remote metadata.

---

## 3. Restore-Point Data Model

The domain model is defined in `com.nexa.app.domain.model.BackupRestorePoint.kt`:

```kotlin
enum class RestorePointStatus {
    PREPARING,
    UPLOADING,
    VERIFYING,
    VERIFIED,
    FAILED,
    INCOMPLETE
}

data class BackupRestorePoint(
    val backupId: String,
    val createdAt: Long,
    val formatVersion: Int = 2,
    val encryptionVersion: Int = 2,
    val fileName: String,
    val driveFileId: String,
    val sizeBytes: Long,
    val totalMessages: Int = 0,
    val totalConversations: Int = 0,
    val mediaFilesCount: Int = 0,
    val categoryConfig: BackupCategoryConfig = BackupCategoryConfig(),
    val status: RestorePointStatus = RestorePointStatus.VERIFIED,
    val isLegacy: Boolean = false,
    val sourceDevice: String = "Android Device",
    val label: String? = null,
    val sha256: String? = null
)
```

### Self-Describing Metadata Mapping (`appProperties`):
- `backup_id`: Unique identifier (`bp_<timestamp>_<random8>`).
- `created_at`: Creation epoch millisecond timestamp.
- `format_version`: Format schema version (`2` for Part 4 multi-point vaults).
- `encryption_version`: Crypto engine version (`2` for AES-256-GCM streaming vaults).
- `status`: Lifecycle phase (`PREPARING`, `UPLOADING`, `VERIFYING`, `VERIFIED`, `FAILED`).
- `total_messages`: Count of sanitized messages packaged in this point.
- `total_conversations`: Count of distinct conversation threads packaged.
- `media_files_count`: Total scoped external media files bundled.
- `category_config`: JSON-serialized `BackupCategoryConfig` indicating active categories.
- `source_device`: Device model identifier (e.g. `Pixel 8 Pro`).
- `is_legacy`: Boolean indicating legacy single-file format.
- `sha256`: SHA-256 hash of the final encrypted vault binary.

---

## 4. Unique ID Strategy

Backup IDs are generated using `BackupRestorePoint.generateBackupId(epoch: Long)`:
```
bp_<timestamp_epoch_ms>_<8_hex_random_entropy>
Example: bp_1732380000000_a1b2c3d4
```
- **Collision Safety:** Combines millisecond wall-clock timestamp with 8 hex characters (32 bits of cryptographically secure random entropy via UUID).
- **Privacy Preservation:** Contains zero user identifiers, NX-IDs, phone numbers, or account hashes.
- **Lexicographical & Temporal Ordering:** Naturally sorts chronologically by timestamp prefix.

---

## 5. Remote File Naming & Storage Structure

Remote vault files uploaded to `appDataFolder` adhere strictly to:
- **Prefix:** `nexa_backup_`
- **Body:** `<backupId>`
- **Suffix:** `.vault`
- **Final Pattern:** `nexa_backup_bp_<timestamp>_<random8>.vault`
- **Legacy Filename Preserved:** `nx_secure_backup.vault` (recognized and parsed without name mutation).

---

## 6. Upload Lifecycle

The safe upload lifecycle consists of 7 discrete, atomic phases:

```
[Phase 1: Session Recovery / Check]
              │
              ▼
[Phase 2: Local Packaging & Selective Sanitization]
              │
              ▼
[Phase 3: AES-256-GCM Streaming Encryption]
              │
              ▼
[Phase 4: Remote File Creation & Session Init (UPLOADING)]
              │
              ▼
[Phase 5: Resumable Chunked Upload (256 KB slices)]
              │
              ▼
[Phase 6: Upload Verification (VERIFYING -> VERIFIED)]
              │
              ▼
[Phase 7: 7-Point Retention Policy Enforcement]
```

1. **Session Recovery:** Inspects `SecureStorage` for active upload sessions. If an unexpired session exists for the same backup ID and file size, resumes upload from the confirmed offset.
2. **Local Packaging:** Uses Part 3's `VaultBundlePackager` with category selection, database snapshotting, and media bundling.
3. **Encryption:** `BackupCryptoEngine.createEncryptedVaultWithResult` outputs a 71-byte header (`NXVAULT` magic, salt, IV, checksum) followed by ciphertext. Returns manifest details and vault SHA-256.
4. **Session Init:** Invokes `DriveAppDataService.startResumableUploadSession` targeting `appDataFolder` with metadata `status = UPLOADING`.
5. **Resumable Upload:** Streams vault slices using `DriveResumableUploader`.
6. **Upload Verification:** Executes HTTP Range request for the first 71 bytes and queries remote metadata. Upon confirmation, patches `appProperties` to `status = VERIFIED`.
7. **Retention Enforcement:** Triggered *only* after `status = VERIFIED` is successfully committed.

---

## 7. Verification Method

Verification runs post-upload before the point is declared recoverable:
1. **Remote Existence Check:** Queries Google Drive REST API for file metadata by `driveFileId`.
2. **File Size Match Check:** Confirms `remoteSize == localVaultFile.length()`. Rejects if truncated.
3. **Minimum Size Check:** Confirms `remoteSize >= 71 bytes` (the exact length of `BackupVaultHeader`).
4. **Header Integrity Check via HTTP Range:**
   - Issues `DriveAppDataService.downloadVaultRange(fileId, dest, 0, 70)` requesting bytes `0-70`.
   - Parses bytes through `BackupVaultHeader.fromByteArray(bytes)`.
   - Validates `NXVAULT` 7-byte ASCII magic and format version `version in 1..2`.
5. **State Transition:** If all checks pass, updates `status = VERIFIED`. If any check fails, marks `status = FAILED` and aborts retention.

---

## 8. Seven-Point Retention Algorithm

Implemented in `DriveBackupRepositoryImpl.applyRetentionPolicy()`:
1. Fetch all backup files from `appDataFolder` matching `name contains 'nexa_backup_' or name == 'nx_secure_backup.vault'`.
2. Parse each into a `BackupRestorePoint`.
3. Filter strictly for `status == RestorePointStatus.VERIFIED`.
4. Sort descending by `createdAt` (newest first).
5. If `verifiedPoints.size > MAX_RETENTION_POINTS` (7):
   - Slice candidates for deletion: `toDelete = verifiedPoints.drop(7)`.
   - Protect active upload session: If `point.driveFileId == activeSessionFileId`, skip deletion.
   - For each candidate: invoke `driveService.deleteFile(point.driveFileId)`.
   - Return count of safely pruned restore points.

---

## 9. Retention Safety & Crash Recovery

The retention engine is designed with defensive fault-tolerance rules:
- **Never Delete Unverified Backups:** If an upload fails, crashes, or is marked `FAILED`/`INCOMPLETE`, it is excluded from `verifiedPoints`. Consequently, existing older verified points are *never* deleted when a new backup attempt fails.
- **Never Delete the Last Backup:** If only 1 verified backup exists, retention never deletes it.
- **Active Session Protection:** Active upload/download session file IDs stored in `SecureStorage` are checked. Even if an active file appears older due to clock anomalies, it is immune to retention deletion.
- **Crash Recovery & Idempotency:** The retention check is stateless; it computes candidates directly from Google Drive query results. If the app crashes midway through pruning, the next backup or manual retention call resumes trimming cleanly without corrupted pointers.

---

## 10. Legacy Backup Compatibility

- **File Recognition:** `DriveAppDataService.listBackupFiles()` explicitly includes `name = 'nx_secure_backup.vault'`.
- **Parsing:** `BackupRestorePoint.fromDriveFile()` detects `nx_secure_backup.vault` or `is_legacy == "true"`, sets `isLegacy = true`, `formatVersion = 1`, `encryptionVersion = 1`, and `status = VERIFIED` (provided file size >= 71 bytes).
- **Restoration:** `downloadAndRestoreBackup(passphraseWords, fileId)` accepts any valid `fileId`. When given a legacy file ID, it downloads the legacy vault, decrypts via legacy key derivation, and unpacks the database.
- **Backward-Compatible Facade:** `createAndUploadBackup()` remains functional and delegates directly to `createBackupRestorePoint()`.

---

## 11. Restore-Point Discovery & Self-Healing

When a user reinstalls NEXA on a new device or clears local app data:
- `DriveBackupRepository.discoverRestorePoints()` queries `appDataFolder`.
- It reconstructs the full list of `BackupRestorePoint` objects purely from `appProperties` without downloading full vault files.
- Restores all timestamps, total message counts, conversation counts, media file counts, and enabled category configurations in memory.
- Sorts the timeline chronologically from newest to oldest.

---

## 12. Concurrency Handling

- **`backupMutex = Mutex()`:** Protects local vault bundling, encryption, and resumable upload from concurrent backup triggers. Prevents racing uploads or clobbered temp files.
- **`retentionMutex = Mutex()`:** Serializes retention executions. If multiple background workers or user actions trigger retention simultaneously, the mutex ensures sequential execution and prevents double-deletion attempts.

---

## 13. Security Review

- **Zero Plaintext Data in Drive AppProperties:** Only aggregated numbers (message count, conversation count, file count, timestamp, format versions, category flags) are exposed.
- **Transport Security:** All Google Drive REST interactions enforce HTTPS with certificate pinning and authorization headers via Google Play Services OAuth token provider.
- **SSRF & Domain Verification:** `UploadSessionState` strictly enforces `https://www.googleapis.com` origin checks before issuing upload requests.
- **Cryptographic Independence:** Each restore point contains its own distinct AES-GCM 16-byte random salt and 12-byte IV in its 71-byte header.

---

## 14. Files Created / Modified

| File | Action | Purpose |
|------|--------|---------|
| `app/src/main/java/com/nexa/app/domain/model/BackupRestorePoint.kt` | Created | Domain model, status enum, `appProperties` serialization/deserialization, ID/filename generators, retention limit constant |
| `app/src/main/java/com/nexa/app/data/drive/DriveAppDataService.kt` | Modified | Multi-file query with `appProperties`, metadata fetching, `appProperties` patch, range download for header verification, new file upload session |
| `app/src/main/java/com/nexa/app/data/drive/UploadSessionState.kt` | Modified | Added `backupId` property to bind active sessions to specific restore points |
| `app/src/main/java/com/nexa/app/core/security/SecureStorage.kt` | Modified | Persists and restores `backupId` in session state |
| `app/src/main/java/com/nexa/app/core/backup/BackupCryptoEngine.kt` | Modified | Added `VaultCreationResult` returning manifest and SHA-256 alongside ciphertext |
| `app/src/main/java/com/nexa/app/domain/repository/DriveBackupRepository.kt` | Modified | Extended domain contract for restore point creation, discovery, verification, and retention |
| `app/src/main/java/com/nexa/app/data/repository/DriveBackupRepositoryImpl.kt` | Modified | Complete multi-restore-point engine, verification gate, 7-point retention, mutex isolation, legacy compatibility |
| `app/src/test/java/com/nexa/app/GoogleDriveMultiPointRetentionUnitTests.kt` | Created | Comprehensive unit test suite for Part 4 domain model, retention rules, discovery, and verification |

---

## 15. Tests Added

A dedicated unit test suite was implemented in `GoogleDriveMultiPointRetentionUnitTests.kt`:
1. `testRestorePointModelSerializationAndDeserialization`: Roundtrip serialization between `BackupRestorePoint` and Drive `appProperties`; validates zero PII leakage.
2. `testLegacyBackupFileDetection`: Validates automatic detection and classification of `nx_secure_backup.vault` as `isLegacy = true` and `status = VERIFIED`.
3. `testUniqueBackupIdAndFileNameGeneration`: Validates `bp_<epoch>_<hex8>` format and collision safety across 100 iterations.
4. `testSevenPointRetentionPolicy_retentionTrimsOldestWhenExceedingSeven`: Simulates 10 verified restore points and verifies that exactly the 3 oldest points are deleted and the 7 newest are preserved.
5. `testSevenPointRetentionPolicy_unverifiedOrFailedBackupsDoNotTriggerDeletion`: Validates that failed or incomplete backups never trigger deletion of valid backups.
6. `testSevenPointRetentionPolicy_activeUploadSessionFileNeverDeleted`: Validates that files belonging to active upload sessions are never deleted by retention.
7. `testRestorePointDiscovery_sortsNewestToOldest`: Validates timeline sorting across mixed modern and legacy points.
8. `testUploadVerification_detectsCorruptSizeOrHeader`: Validates rejection of files < 71 bytes, bad magic bytes, and unsupported versions.
9. `testConcurrencyMutex_preventsOverlappingRetentionJobs`: Validates coroutine mutex locking under concurrent load.
10. `testStatusParsingFallbackAndRobustness`: Validates graceful fallback for corrupted remote status strings.

---

## 16. Actual Test Execution Results

Executed the full backup suite via Gradle:
```powershell
.\gradlew testDebugUnitTest `
  --tests "com.nexa.app.GoogleDriveMultiPointRetentionUnitTests" `
  --tests "com.nexa.app.BackupSelectivePackagingAndPrivacyUnitTests" `
  --tests "com.nexa.app.BackupArchitectureAndFoundationUnitTests" `
  --tests "com.nexa.app.KeyEnvelopeAndSelectiveBackupUnitTests" `
  --tests "com.nexa.app.GoogleDriveBackupUnitTests" `
  --tests "com.nexa.app.DriveUploadRecoveryUnitTests" `
  --tests "com.nexa.app.DriveRestoreDownloadResumeUnitTests" `
  --tests "com.nexa.app.AtomicRestoreCrashRecoveryUnitTests"
```

**Result:**
```
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 37s
28 actionable tasks: 3 executed, 25 up-to-date
```
- **Tests Passed:** 100% (All 10 Part 4 tests + all Part 1, 2, 3 tests passed cleanly).
- **Failures:** 0
- **Errors:** 0

---

## 17. Debug Build Result

Executed full project compilation and packaging:
```powershell
.\gradlew assembleDebug
```

**Result:**
```
> Task :app:assembleDebug
BUILD SUCCESSFUL in 1m 8s
40 actionable tasks: 3 executed, 37 up-to-date
```
- Compilation succeeded with zero errors.
- Target APK generated cleanly at `app/build/outputs/apk/debug/app-debug.apk`.

---

## 18. Known Limitations

1. **Google Drive API Quota Limits:** Google Drive REST API enforces standard per-user rate limits (queries per minute). The engine mitigates this by fetching all file metadata in a single `listBackupFiles` call with `fields="files(id,name,size,modifiedTime,appProperties)"`.
2. **Drive AppDataFolder Visibility:** The `appDataFolder` is completely hidden from the user's regular Google Drive web UI and Google Drive mobile app. Users can view and manage their restore points exclusively within the NEXA app UI (to be delivered in Part 7).

---

## 19. Remaining Risks

1. **Network Interruption During Retention Pruning:** If network drops while Google Drive API is deleting the 8th point, the file may remain on Drive until the next backup or manual retention check executes. The idempotent design ensures this causes no state corruption.
2. **Partial Restores with Disconnected Media:** If a user restores an older point where some media files were purged from the cloud (due to category deselect in that specific point), the app handles missing media gracefully via fallback placeholders.

---

## 20. Roadmap Status Confirmation

- **Part 1 (Existing System Audit & Gap Analysis):** COMPLETED.
- **Part 2 (Backup Architecture & Foundation Refactoring):** COMPLETED.
- **Part 3 (Selective Data Packaging & External Media Backup):** COMPLETED.
- **Part 4 (Google Drive Cloud Backup Engine, Restore Points & 7-Point Retention):** COMPLETED & VERIFIED.
- **Parts 5–9:** PENDING (awaiting explicit authorization before starting Part 5).

---

## 21. Strict Scope Compliance Confirmation

- **NO UI screens or Composables were modified or created** (Restore-point UI is reserved for Part 7).
- **NO WorkManager scheduling or periodic sync workers were implemented** (Reserved for Part 6).
- **NO Merge restore or diffing logic was implemented** (Reserved for Part 5).
- **All changes strictly adhere to Part 4 cloud storage engine, restore-point data modeling, verification gates, and retention algorithms.**
