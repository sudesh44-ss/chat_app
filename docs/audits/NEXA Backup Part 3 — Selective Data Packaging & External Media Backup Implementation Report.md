# NEXA Backup & Restore — Part 3: Selective Data Packaging & External Media Backup Implementation Report

**Application:** NEXA Android Secure Messenger  
**Architecture Authority:** `1architecture.md`  
**Execution Phase:** Part 3 of 9 (Selective Backup Data Engine & Privacy Leakage Elimination)  
**Date:** 2026-11-23  
**Status:** **COMPLETED & VERIFIED** (All unit tests passing, full debug APK assembled successfully)

---

## 1. Executive Summary

In Part 3 of the NEXA Backup & Restore engineering overhaul, we addressed and resolved the **Critical Privacy Leakage** identified during the Part 1 audit and established a **real, category-aware selective backup packaging engine**.

Previously, `VaultBundlePackager.kt` utilized SQLite's native `VACUUM INTO` command unconditionally, capturing the entire unencrypted live database image into the snapshot bundle regardless of whether the user had disabled `Chats & Conversations`. Furthermore, device-bound ephemeral security tables (such as Double Ratchet session states, skipped message keys, SMS provisioning key material, SMS sessions, and DTN/Mesh routing envelopes) were copied into backups, creating serious forward secrecy risks across devices.

Part 3 has completely eliminated these privacy and security flaws. The backup packaging engine now:
1. **Sanitizes SQLite Snapshots in Volatile Storage:** Uses SQLite's `ATTACH DATABASE` with `PRAGMA secure_delete = ON` to zero-overwrite and purge all device-bound security tables unconditionally and all chat, message, draft, reaction, attachment, and contact records when `Chats & Conversations` is disabled.
2. **Scrapes External NEXA Media:** Scrapes both user-accessible scoped storage (`/Android/media/<package>/NEXA/` $\rightarrow$ `Media/Images`, `Media/Videos`, `Media/Audio`, `Media/Documents`, `Downloads`, `Exports`) and internal `context.filesDir/media_vault`, filtering strictly by the 7 official categories.
3. **Guarantees Media Deduplication & Path Traversal Immunity:** Deduplicates files across internal and external storage trees while strictly validating canonical paths against directory traversal attacks (`../`).
4. **Calculates Real Category Sizes:** Replaced hardcoded dummy sizing with real disk and table measurements across all 7 categories.
5. **Sanitizes Portable Preferences:** Exports non-sensitive user settings while strictly scrubbing all auth tokens, API secrets, Google Drive credentials, PIN hashes, passwords, and session keys.

---

## 2. Critical Privacy Fix: Database Snapshot Sanitization

### Problem Identified in Part 1 Audit
`VaultBundlePackager.packBundle` executed:
```kotlin
rawDatabase.execSQL("VACUUM INTO '${snapshotDb.absolutePath}'")
```
This produced a 1:1 exact replica of the live database. When the user unchecked "Chats & Conversations", the full database was still zipped and encrypted into the cloud backup, violating user consent and privacy guarantees.

### Implemented Architecture: Atomic Secure Purge
In `VaultBundlePackager.sanitizeDatabaseSnapshot()`:
```
┌───────────────────────────────────────────────────────────────────────────┐
│                     Active Live SQLite Connection                         │
│                    (SQLCipher or Standard SQLite)                         │
└─────────────────────────────────────┬─────────────────────────────────────┘
                                      │
                         VACUUM INTO  │ (Zero-lock snapshot)
                                      ▼
┌───────────────────────────────────────────────────────────────────────────┐
│              Frozen Staging Snapshot (nexa_master_snapshot.db)            │
└─────────────────────────────────────┬─────────────────────────────────────┘
                                      │
                       ATTACH DATABASE│ AS backup_staging
                                      ▼
┌───────────────────────────────────────────────────────────────────────────┐
│              PRAGMA backup_staging.secure_delete = ON                     │
│    (Overwrites all deleted B-tree pages with zeroes to prevent forensics) │
├───────────────────────────────────────────────────────────────────────────┤
│ Unconditional Security Purge:                                             │
│  - DELETE FROM backup_staging.ratchet_sessions / ratchet_state            │
│  - DELETE FROM backup_staging.skipped_message_keys / skipped_keys         │
│  - DELETE FROM backup_staging.sms_key_material                            │
│  - DELETE FROM backup_staging.sms_sessions                                │
│  - DELETE FROM backup_staging.sms_packets                                 │
│  - DELETE FROM backup_staging.sms_inbound_parts                           │
│  - DELETE FROM backup_staging.dtn_envelopes                               │
│  - DELETE FROM backup_staging.mesh_outbox / mesh_envelopes                │
│  - DELETE FROM backup_staging.peer_sync_cursors                           │
│  - DELETE FROM backup_staging.device_sync_log / device_sync               │
│  - DELETE FROM backup_staging.quarantine_logs / quarantine                │
│  - DELETE FROM backup_staging.file_transfers / transfer_sessions          │
│  - DELETE FROM backup_staging.status_views                                │
├───────────────────────────────────────────────────────────────────────────┤
│ Selective Chat Purge (When Chats & Conversations is OFF):                 │
│  - DELETE FROM backup_staging.messages                                    │
│  - DELETE FROM backup_staging.chats                                       │
│  - DELETE FROM backup_staging.chat_members                                │
│  - DELETE FROM backup_staging.chat_drafts                                 │
│  - DELETE FROM backup_staging.message_reactions                           │
│  - DELETE FROM backup_staging.message_attachments                         │
│  - DELETE FROM backup_staging.scheduled_messages                          │
│  - DELETE FROM backup_staging.message_dedup_index / message_dedup         │
│  - DELETE FROM backup_staging.message_transport_observations              │
│  - DELETE FROM backup_staging.contacts                                    │
│  - DELETE FROM backup_staging.status_updates                              │
│  - DELETE FROM backup_staging.file_envelopes                              │
├───────────────────────────────────────────────────────────────────────────┤
│ Integrity Verification:                                                   │
│  - PRAGMA backup_staging.integrity_check (Ensures B-Tree validity)        │
│  - VACUUM backup_staging (Best-effort page reclaim)                       │
│  - DETACH DATABASE backup_staging                                         │
└───────────────────────────────────────────────────────────────────────────┘
```

### Deterministic Zero-Leakage Manifest
The packager now computes `total_messages` and `total_conversations` directly from the sanitized staging database rather than querying the live database. When `includeChats = false`, the manifest writes:
```json
{
  "version_epoch": 1732378000000,
  "format_version": 2,
  "total_conversations": 0,
  "total_messages": 0,
  "media_files_count": 0,
  "database_sha256": "...",
  "category_config": { ... }
}
```
And `database_sha256` is strictly computed **after** all sanitization steps and detaching are complete.

---

## 3. Real Category-Aware Selective Packaging

### 7 Official Categories
Every file, database record, and preference is classified into one of the 7 official categories:

| Category ID | Category Name | Data Sources Included | Exclusions / Sanitization |
| :--- | :--- | :--- | :--- |
| `chats` | Chats & Conversations | `messages`, `chats`, `chat_members`, `chat_drafts`, `message_reactions`, `message_attachments`, `scheduled_messages`, `contacts`, `status_updates` | Unconditional purge of ratchet states, SMS keys, and mesh envelopes. |
| `images` | Images & Photos | `Media/Images` in external NEXA tree and internal `media_vault` (`.jpg`, `.jpeg`, `.png`, `.webp`, `.gif`, `.bmp`, `.heic`, `.heif`) | Non-image files excluded; `.nomedia` and empty files skipped. |
| `videos` | Videos | `Media/Videos` in external NEXA tree and internal `media_vault` (`.mp4`, `.mkv`, `.webm`, `.avi`, `.mov`, `.3gp`, `.flv`) | Non-video files excluded. |
| `audio` | Audio & Voice Notes | `Media/Audio` in external NEXA tree and internal `media_vault` (`.opus`, `.mp3`, `.m4a`, `.aac`, `.wav`, `.ogg`, `.flac`, `.amr`) | Non-audio files excluded. |
| `documents` | Documents | `Media/Documents` in external NEXA tree and internal `media_vault` (`.pdf`, `.doc`, `.docx`, `.xls`, `.xlsx`, `.ppt`, `.pptx`, `.txt`, `.csv`, `.rtf`, `.odt`) | Non-document files excluded. |
| `other_files` | Other Files | `Downloads`, `Exports`, and internal `media_vault` (`.zip`, `.tar`, `.gz`, `.7z`, `.apk`, and generic attachments) | Filtered strictly if `includeOtherFiles = false`. |
| `settings` | Settings & App Data | `nexa_settings`, `nexa_chat_configs`, `nexa_appearance_prefs`, `nexa_chats_preferences`, `nexa_sound_preferences`, `nexa_notification_preferences` | Strict sanitization: any key containing `token`, `secret`, `auth`, `pin`, `pass`, `key`, `credential`, `session` is scrubbed. If disabled, `preferences/` is omitted entirely. |

---

## 4. External Media Storage Integration & Deduplication

### Directory Discovery
`VaultBundlePackager.collectMediaFiles` integrates with `NexaStorageManager`:
- **Primary Scoped Storage:** `/Android/media/<packageName>/NEXA/`
  - `Media/Images`
  - `Media/Videos`
  - `Media/Audio`
  - `Media/Documents`
  - `Downloads`
  - `Exports`
- **Internal Private Media Vault:** `context.filesDir/media_vault`

### Deduplication Algorithm
To avoid storing the same media file twice (e.g. if an image was saved to both `Media/Images` and internal `media_vault`), a deterministic deduplication key is maintained during collection:
```kotlin
val deduplicationKey = "${cleanName}_${file.length()}"
if (seenFileKeys.contains(deduplicationKey) || seenFileKeys.contains(cleanName)) {
    return // Skip duplicate
}
```

### Path Traversal Defense
Incoming file names are sanitized:
- Directory separators (`/`, `\`) and traversal symbols (`..`) are replaced with underscores.
- Destination paths are checked via `destFile.canonicalPath.startsWith(targetMediaDir.canonicalPath)`. Any attempt to escape the staging directory is rejected.

---

## 5. Real Category Size Calculation

In `VaultBundlePackager.calculateCategorySizes()`:
```kotlin
data class BackupSizeEstimate(
    val databaseBytes: Long = 0L,
    val imagesBytes: Long = 0L,
    val videosBytes: Long = 0L,
    val audioBytes: Long = 0L,
    val documentsBytes: Long = 0L,
    val otherFilesBytes: Long = 0L,
    val settingsBytes: Long = 0L,
    val totalBytes: Long = 0L
)
```
- **Database:** Measures real byte length of `context.getDatabasePath("nexa_encrypted_vault.db")` if `includeChats` is enabled; otherwise estimates baseline schema overhead (~128 KB) if only settings are enabled.
- **Media:** Recursively walks external NEXA directories and internal vault, summing actual byte lengths of files whose extensions match the enabled categories.
- **Settings:** Measures actual stringified byte lengths of non-sensitive SharedPreferences keys.
- **DriveBackupRepository Integration:** `DriveBackupRepositoryImpl.calculateBackupSize` now delegates directly to `VaultBundlePackager.calculateCategorySizes(context, ...).totalBytes`.

---

## 6. Restoration & Safety Enhancements

In `VaultBundlePackager.unpackAndRestoreBundle()`:
1. **Zero-Disk RAM Key Envelope:** `key_envelope.bin` is intercepted in volatile memory from `ZipInputStream` and unsealed without touching flash storage.
2. **Dual-Destination Media Restoration:** Extracted media files are restored to internal `context.filesDir/media_vault` (so existing chat database references remain valid) and also copied to the respective user-accessible external folders (`Media/Images`, `Media/Videos`, etc.) via `NexaStorageManager` so they immediately appear in the user's gallery and file explorer.
3. **Preference Restoration:** Settings are restored back into SharedPreferences, preserving the scrubbed security policy.
4. **Atomic Database Swap:** Closes live Room instances, removes stale WAL/SHM/journal files, and atomically swaps `snapshotDb` into `liveDatabaseFile`.

---

## 7. Automated Test Suite & Verification Results

A dedicated automated test suite was created:  
`app/src/test/java/com/nexa/app/BackupSelectivePackagingAndPrivacyUnitTests.kt`

### Test Suite Execution Summary
All 9 unit tests passed with 100% success rate:
```
BackupSelectivePackagingAndPrivacyUnitTests:
  ✔ testClassifyFileCategory_classifiesAllFileTypesAccurately (PASSED)
  ✔ testMatchesMediaCategory_respectsGranularCategoryFlags (PASSED)
  ✔ testCollectMediaFiles_filtersByCategoriesAndCopiesFromExternalAndInternal (PASSED)
  ✔ testCollectMediaFiles_deduplicationAndPathTraversalDefense (PASSED)
  ✔ testCalculateCategorySizes_accuratelySumsRealFileBytes (PASSED)
  ✔ testSanitizeDatabaseSnapshot_chatsDisabled_executesCompletePurge (PASSED)
  ✔ testSanitizeDatabaseSnapshot_chatsEnabled_preservesChatTablesAndPurgesSecurityOnly (PASSED)
  ✔ testPackBundle_manifestZeroLeakageWhenChatsDisabled (PASSED)
  ✔ testPackBundle_settingsSanitizationStripsAuthTokensAndSecrets (PASSED)
```

### Full Backup Suite Regression Run
Running all backup-related suites concurrently:
- `BackupSelectivePackagingAndPrivacyUnitTests`
- `BackupArchitectureAndFoundationUnitTests`
- `KeyEnvelopeAndSelectiveBackupUnitTests`
- `GoogleDriveBackupUnitTests`
- `DriveUploadRecoveryUnitTests`
- `DriveRestoreDownloadResumeUnitTests`
- `AtomicRestoreCrashRecoveryUnitTests`

**Result:** `BUILD SUCCESSFUL in 27s` (All 28 actionable tasks passed).

### Full Debug Assembly Build
Execution: `./gradlew assembleDebug`  
**Result:** `BUILD SUCCESSFUL in 1m 7s` (40 actionable tasks, 0 errors).

---

## 8. Modified & Created Source Files

1. [`app/src/main/java/com/nexa/app/core/backup/VaultBundlePackager.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/backup/VaultBundlePackager.kt):
   - Implemented `sanitizeDatabaseSnapshot` using SQLite `ATTACH DATABASE` and `PRAGMA secure_delete = ON`.
   - Implemented `classifyFileCategory` covering all 7 official categories and file formats.
   - Implemented `collectMediaFiles` with dual-location scanning (scoped storage + internal vault), deduplication, and traversal prevention.
   - Implemented `calculateCategorySizes` with real file and DB measurements.
   - Updated `packBundle` to integrate snapshot sanitization, external media collection, settings scrubbing, and manifest format v2.
   - Updated `unpackAndRestoreBundle` to restore media into both internal vault and external NEXA folders.
2. [`app/src/main/java/com/nexa/app/data/repository/DriveBackupRepositoryImpl.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/repository/DriveBackupRepositoryImpl.kt):
   - Replaced naive backup sizing with `VaultBundlePackager.calculateCategorySizes`.
3. [`app/src/test/java/com/nexa/app/BackupSelectivePackagingAndPrivacyUnitTests.kt`](file:///H:/chat_app/app/src/test/java/com/nexa/app/BackupSelectivePackagingAndPrivacyUnitTests.kt):
   - Created comprehensive unit test suite covering classification, external media collection, deduplication, path traversal defense, database snapshot sanitization, manifest zero-leakage, and settings scrubbing.
4. [`docs/audits/NEXA Backup Part 3 — Selective Data Packaging & External Media Backup Implementation Report.md`](file:///H:/chat_app/docs/audits/NEXA%20Backup%20Part%203%20—%20Selective%20Data%20Packaging%20&%20External%20Media%20Backup%20Implementation%20Report.md):
   - This comprehensive implementation and verification report.

---

## 9. Conclusion & Next Steps (Part 4 Awaiting Authorization)

Part 3 has been successfully implemented and verified with zero dummy code and full regression test passing.

**Current State:**
- Part 1 (Audit & Gap Analysis): **DONE**
- Part 2 (Architecture & Foundation Refactoring): **DONE**
- Part 3 (Selective Data Packaging & External Media Backup): **DONE**

**Standing Down:**
In accordance with the project instructions, work is now paused. **Do NOT proceed to Part 4 (Cloud Backup Engine & 7-Restore-Point Retention on Google Drive) until the user has reviewed this report and provided authorization.**
