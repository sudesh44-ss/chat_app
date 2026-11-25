# NEXA Backup Part 7.2 — Restore Engine Remediation & Verification Report

**Project:** NEXA Android Secure Communication Suite  
**Repository Location:** `H:\chat_app`  
**Phase:** Part 7.2 Remediation & Comprehensive Verification  
**Authority:** `1architecture.md`, NEXA PRD, Part 7 Implementation Report, Part 7.1 Deep Verification Audit  
**Status:** **ALL FINDINGS REMEDIATED & VERIFIED** (100% Pass Rate, 139/139 Unit Tests Passed, Clean `assembleDebug` Build)  
**Strict Boundary Notice:** Part 8 and Part 9 are strictly withheld and awaiting authorization.

---

## Executive Summary

Following the comprehensive audit conducted in **Part 7.1: Restore Engine Deep Verification Audit**, six specific architectural, security, and UI findings were identified and slated for systematic remediation in **Part 7.2**.

Every finding has been verified against the live codebase, refactored according to the highest security and architectural standards, and validated through deterministic unit test suites. In addition, full regression testing across all 12 backup and restore test suites was executed with zero failures, zero errors, and zero skipped tests. The complete Android debug application artifact (`app-debug.apk`) was assembled successfully.

### Summary Scorecard

| Area | Audit Finding / Requirement | Remediation Status | Verification |
| :--- | :--- | :--- | :--- |
| **Fix A** | Settings restoration used sensitive denylist instead of strict allowlist | **VERIFIED FIXED** | `RestoreEngine.APPROVED_SETTINGS_ALLOWLIST` & strict key parser implemented; rejects unknown/sensitive keys by default |
| **Fix B** | `includeGroups = false` did not fully filter group chats & members | **VERIFIED FIXED** | Filtered `effectiveConvIds` injected into `chat_items`, `chat_members`, messages, and Drive repository plan |
| **Fix C** | "Other Files & Downloads" UI toggle was missing in `RestoreBackupScreen.kt` | **VERIFIED FIXED** | Category toggle row for `OTHER_FILES` added with real-time reactive counts |
| **Fix D** | Unencrypted staging cache persisted if user navigated away or cancelled | **VERIFIED FIXED** | Implemented `cleanupStagingData()`, lifecycle-aware pruning in `BackupViewModel`, and Jetpack Compose `BackHandler` in `RestoreBackupScreen` |
| **Fix E** | Legacy hardcoded password verification & empty passphrase validation | **VERIFIED FIXED** | Confirmed zero production fallbacks; added immediate exception guard against empty/blank passphrases in `BackupCryptoEngine` |
| **Fix F** | Restore failure injection & deterministic rollback test coverage | **VERIFIED FIXED** | Implemented 13 focused failure/remediation unit tests in `RestoreCrashAndRemediationUnitTests.kt` |

---

## 1. Findings Confirmation & Live Code Analysis

Before making code modifications, each finding from Part 7.1 was confirmed against the source tree:

1. **Settings Denylist Vulnerability:** In [RestoreEngine.kt](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/backup/RestoreEngine.kt) (and [VaultBundlePackager.kt](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/backup/VaultBundlePackager.kt)), settings restoration previously checked `SENSITIVE_PREF_PATTERNS.none { pattern -> key.contains(pattern, ignoreCase = true) }`. This negative filtering (denylist) risked allowing newly introduced sensitive preference keys into restored settings.
2. **Group Conversation Leakage Risk:** In `executeMerge` and `executeReplace` within [RestoreEngine.kt](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/backup/RestoreEngine.kt), filtering checked `effectiveConvIds.contains(...)`, but `chat_members` and group conversation entities were not strictly decoupled when `includeGroups == false`.
3. **UI Inconsistency:** In [RestoreBackupScreen.kt](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/backup/RestoreBackupScreen.kt), the category selection screen rendered toggles for Chats, Photos, Audio, Video, and Settings, but omitted a toggle for `OTHER_FILES` ("Other Files & Downloads").
4. **Staging Cache Lifecycle Leakage:** When an encrypted archive was unpacked to local cache (`cacheDir/restore_staging_*`), navigating back or aborting the screen did not reliably invoke cache deletion, leaving unencrypted decrypted JSON and media artifacts on disk until app process death or system cache trimming.
5. **Passphrase Guard Verification:** [BackupCryptoEngine.kt](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/backup/BackupCryptoEngine.kt) properly derived keys via Argon2id with random salts, but lacked an explicit precondition prohibiting blank or whitespace-only passphrases before invoking native key derivation routines.

---

## 2. Detailed Technical Remediations

### Fix A — Settings Restoration Privacy & Strict Explicit Allowlist

1. **Strict Allowlist Definition:**
   Created an immutable `Set<String>` of approved safe, portable UI and non-sensitive preference keys in [RestoreEngine.kt](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/backup/RestoreEngine.kt):
   ```kotlin
   val APPROVED_SETTINGS_ALLOWLIST: Set<String> = setOf(
       "app_theme_mode",
       "appearance_mode",
       "key_appearance_mode",
       "font_size_scale",
       "chat_bubble_style",
       "chat_wallpaper",
       "notifications_enabled",
       "notification_sound",
       "vibration_enabled",
       "enter_to_send",
       "media_visibility_enabled",
       "high_contrast_messages",
       "selected_language",
       "auto_download_wifi_images",
       "auto_download_wifi_videos",
       "auto_download_wifi_documents",
       "auto_download_wifi_audio",
       "auto_download_cellular_images",
       "auto_download_cellular_videos",
       "auto_download_cellular_documents",
       "auto_download_cellular_audio",
       "reduce_animations",
       "key_reduce_animations",
       "font_style",
       "key_font_style",
       "app_text_size",
       "key_app_text_size",
       "chat_text_size",
       "key_chat_text_size",
       "message_font_size",
       "media_visibility",
       "high_contrast",
       "backup_frequency",
       "backup_over_wifi_only",
       "backup_when_charging",
       "backup_notifications_enabled",
       "backup_mode"
   )
   ```

2. **Per-Conversation Configuration Strict Property Parsing:**
   Defined approved conversation configuration property suffixes:
   ```kotlin
   val APPROVED_CHAT_CONFIG_PROPERTIES: Set<String> = setOf(
       "wallpaper",
       "pinned_order",
       "muted",
       "custom_theme",
       "notifications",
       "bubble_style"
   )

   fun isApprovedChatConfigKey(key: String): Boolean {
       val lower = key.lowercase()
       return APPROVED_CHAT_CONFIG_PROPERTIES.any { prop ->
           lower.endsWith("_$prop") && lower.length > prop.length + 1
       }
   }
   ```

3. **Safe Filtering Engine:**
   Replaced regex pattern matching with `sanitizeJsonObject`:
   ```kotlin
   fun sanitizeJsonObject(source: JSONObject, allowlist: Set<String>): JSONObject {
       val clean = JSONObject()
       for (key in source.keys()) {
           if (key in allowlist) {
               clean.put(key, source.get(key))
           }
       }
       return clean
   }
   ```
   Applied this strict sanitization in:
   - [RestoreEngine.kt](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/backup/RestoreEngine.kt) during unpack and merge/replace stages.
   - [VaultBundlePackager.kt](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/backup/VaultBundlePackager.kt) during backup export and restore stages.

---

### Fix B — Group Conversation Selection & Member Isolation

1. **Scoped Conversation ID Filtering:**
   In [RestoreEngine.kt](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/backup/RestoreEngine.kt), when `includeGroups == false`, group conversation IDs are excluded from `effectiveConvIds`:
   ```kotlin
   val effectiveConvIds = if (plan.categories.includeChats) {
       if (plan.selectedConversationIds.isNotEmpty()) {
           if (!plan.categories.includeGroups) {
               plan.selectedConversationIds.filter { convId ->
                   val item = plan.inventory.chatItems.find { it.conversationId == convId }
                   item?.isGroup == false
               }.toSet()
           } else {
               plan.selectedConversationIds.toSet()
           }
       } else if (!plan.categories.includeGroups) {
           plan.inventory.chatItems.filter { !it.isGroup }.map { it.conversationId }.toSet()
       } else {
           plan.inventory.chatItems.map { it.conversationId }.toSet()
       }
   } else {
       emptySet()
   }
   ```

2. **Group Member Table Quarantine:**
   In `executeMerge` and `executeReplace`:
   ```kotlin
   if (includeGroups) {
       db.execSQL("""
           INSERT OR IGNORE INTO main.chat_members (
               chat_id, user_id, role, joined_at
           )
           SELECT chat_id, user_id, role, joined_at
           FROM staging.chat_members
           WHERE chat_id IN ($placeholders)
       """.trimIndent(), effectiveConvIds.toTypedArray())
       groupsImported = countRows(db, "staging.chat_members", "chat_id IN ($placeholders)", effectiveConvIds.toTypedArray())
   }
   ```

3. **Drive Repository Plan Alignment:**
   In [DriveBackupRepositoryImpl.kt](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/repository/DriveBackupRepositoryImpl.kt), `downloadAndRestoreBackup` ensures initial candidate chat items filter out groups when `!restoreCategoryConfig.includeGroups`.

---

### Fix C — "Other Files & Downloads" UI Exposure

In [RestoreBackupScreen.kt](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/backup/RestoreBackupScreen.kt):
1. Added a dedicated `CategoryToggleRow` for `includeOtherFiles`:
   ```kotlin
   CategoryToggleRow(
       title = "Other Files & Downloads",
       subtitle = "PDFs, archives, and generic documents",
       icon = Icons.Default.Folder,
       checked = categoryConfig.includeOtherFiles,
       count = inventory.fileCountByCategory[BackupCategory.OTHER_FILES] ?: 0,
       sizeBytes = inventory.categorySizesBytes[BackupCategory.OTHER_FILES] ?: 0L,
       onCheckedChange = { checked ->
           onCategoryConfigChange(categoryConfig.copy(includeOtherFiles = checked))
       }
   )
   ```
2. Reactive synchronization ensured toggle states dynamically update selected chat items and aggregate restore sizes.

---

### Fix D — Decrypted Staging Cache Cleanup & Privacy

1. **ViewModel Staging Lifecycle Management:**
   In [BackupViewModel.kt](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/backup/BackupViewModel.kt):
   - Tracked active staging directory: `private var activeStagingDir: File? = null`.
   - Added recursive deletion and pruning routines:
     ```kotlin
     fun cleanupStagingData() {
         activeStagingDir?.let { dir ->
             safeDeleteStagingDir(dir)
             activeStagingDir = null
         }
     }

     fun pruneDanglingStagingDirs() {
         viewModelScope.launch(Dispatchers.IO) {
             try {
                 val cacheDir = getApplication<Application>().cacheDir
                 cacheDir.listFiles()?.filter {
                     it.isDirectory && (it.name.startsWith("restore_staging_") || it.name.startsWith("backup_staging_"))
                 }?.forEach { safeDeleteStagingDir(it) }
             } catch (e: Exception) {
                 Log.w(TAG, "Failed to prune dangling staging directories", e)
             }
         }
     }
     ```
   - Invoked `cleanupStagingData()` on `cancelActiveRestore()`, `resetStagedInventory()`, and in `onCleared()`.
   - Pruned dangling staging directories during `init`.

2. **Jetpack Compose Navigation Interception:**
   In [RestoreBackupScreen.kt](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/backup/RestoreBackupScreen.kt):
   - Added `BackHandler { viewModel.cleanupStagingData(); onNavigateBack() }`.
   - Wired TopAppBar navigation click to trigger `viewModel.cleanupStagingData()` prior to popping back stack.

---

### Fix E — Passphrase Hardcoded Fallback & Blank Passphrase Defense

1. **Argon2id Key Derivation Precondition:**
   In [BackupCryptoEngine.kt](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/backup/BackupCryptoEngine.kt):
   ```kotlin
   if (passphraseWords.isBlank()) {
       throw IllegalArgumentException("Passphrase cannot be empty or blank")
   }
   ```
2. **Deterministic Context Decoupling:**
   Refactored `BackupCryptoEngine` constructor to accept an optional `customCacheDir: File? = null`, ensuring tests and headless workers can operate safely without requiring mock Android context instances.

---

### Fix F — Crash Recovery & Failure Injection Test Suite

Created a dedicated unit test suite in [RestoreCrashAndRemediationUnitTests.kt](file:///H:/chat_app/app/src/test/java/com/nexa/app/RestoreCrashAndRemediationUnitTests.kt) containing 13 deterministic tests covering all remediated areas:
1. `testSettingsRestoration_strictAllowlist_rejectsSensitiveAndUnknownKeys`
2. `testChatConfigsAllowlist_strictKeyFormatAndPropertyValidation`
3. `testIncludeGroupsFalse_strictlyExcludesGroupConversationsAndMembers`
4. `testIncludeGroupsTrue_importsGroupConversationsAndMembers`
5. `testStagingDirectoryCleanup_wipesDecryptedFilesRecursively`
6. `testPruneDanglingStagingDirs_cleansOrphanedRestoreDirs`
7. `testNoHardcodedPassword_blankPassphraseRejectedImmediately`
8. `testValidPassphrase_derivesDeterministicKeyWithSalt`
9. `testAtomicRollback_onSimulatedSqliteCrash_restoresOriginalDatabaseState`
10. `testSimulatedCorruptStagedDatabase_abortsWithoutModifyingLiveDatabase`
11. `testSimulatedDiskFullDuringMediaCopy_cleansUpPartialFilesAndAborts`
12. `testSimulatedZipSlipAttack_abortsAndRejectsTraversalEntries`
13. `testCategoryToggleOtherFiles_includedInRestorePlan`

---

## 3. Comprehensive Verification & Regression Test Results

All 12 test suites across the entire NEXA backup and restore engine were executed against the debug build target using Gradle:

```powershell
Set-Location "H:\chat_app"; .\gradlew.bat testDebugUnitTest `
  --tests "com.nexa.app.RestoreCrashAndRemediationUnitTests" `
  --tests "com.nexa.app.RestoreEngineMergeAndConflictUnitTests" `
  --tests "com.nexa.app.AtomicRestoreCrashRecoveryUnitTests" `
  --tests "com.nexa.app.BackupArchitectureAndFoundationUnitTests" `
  --tests "com.nexa.app.BackupSchedulerAndWorkManagerUnitTests" `
  --tests "com.nexa.app.BackupSelectivePackagingAndPrivacyUnitTests" `
  --tests "com.nexa.app.ChatPriorityAndConversationPolicyUnitTests" `
  --tests "com.nexa.app.DriveRestoreDownloadResumeUnitTests" `
  --tests "com.nexa.app.DriveUploadRecoveryUnitTests" `
  --tests "com.nexa.app.ExistingUserLoginAndDeviceMigrationUnitTests" `
  --tests "com.nexa.app.GoogleDriveBackupUnitTests" `
  --tests "com.nexa.app.GoogleDriveMultiPointRetentionUnitTests"
```

### Detailed Test Execution Matrix

| Test Suite Class | Tests Run | Failures | Errors | Skipped | Status | Execution Time |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: |
| `RestoreCrashAndRemediationUnitTests` | 13 | 0 | 0 | 0 | **PASSED** | 0.479s |
| `RestoreEngineMergeAndConflictUnitTests` | 12 | 0 | 0 | 0 | **PASSED** | 0.381s |
| `AtomicRestoreCrashRecoveryUnitTests` | 4 | 0 | 0 | 0 | **PASSED** | 0.127s |
| `BackupArchitectureAndFoundationUnitTests` | 19 | 0 | 0 | 0 | **PASSED** | 0.061s |
| `BackupSchedulerAndWorkManagerUnitTests` | 25 | 0 | 0 | 0 | **PASSED** | 0.049s |
| `BackupSelectivePackagingAndPrivacyUnitTests` | 9 | 0 | 0 | 0 | **PASSED** | 0.436s |
| `ChatPriorityAndConversationPolicyUnitTests` | 10 | 0 | 0 | 0 | **PASSED** | 0.040s |
| `DriveRestoreDownloadResumeUnitTests` | 6 | 0 | 0 | 0 | **PASSED** | 0.012s |
| `DriveUploadRecoveryUnitTests` | 6 | 0 | 0 | 0 | **PASSED** | 0.018s |
| `ExistingUserLoginAndDeviceMigrationUnitTests` | 12 | 0 | 0 | 0 | **PASSED** | 0.323s |
| `GoogleDriveBackupUnitTests` | 13 | 0 | 0 | 0 | **PASSED** | 1.283s |
| `GoogleDriveMultiPointRetentionUnitTests` | 10 | 0 | 0 | 0 | **PASSED** | 0.201s |
| **TOTALS** | **139** | **0** | **0** | **0** | **100% PASS** | **3.41s** |

---

## 4. Android Debug Application Assembly Verification

The full debug application APK was compiled and assembled cleanly:

- **Command:** `.\gradlew.bat assembleDebug`
- **Result:** `BUILD SUCCESSFUL in 1m 43s`
- **Exit Code:** `0`
- **Actionable Tasks:** 40 actionable tasks (3 executed, 37 up-to-date)
- **Generated Artifact:** [app-debug.apk](file:///H:/chat_app/app/build/outputs/apk/debug/app-debug.apk)
- **Artifact Size:** 102,225,366 bytes (~97.5 MB)
- **Verification Timestamp:** November 24, 2026 10:48:45

---

## 5. Artifacts and Source Code References

The following files were updated or added during Part 7.2:

1. [RestoreEngine.kt](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/backup/RestoreEngine.kt) — Strict settings allowlist, chat config property validator, `effectiveConvIds` filtering, and group member import guards.
2. [VaultBundlePackager.kt](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/backup/VaultBundlePackager.kt) — Integrated strict settings allowlist during backup packaging and restore unpack.
3. [DriveBackupRepositoryImpl.kt](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/repository/DriveBackupRepositoryImpl.kt) — Filtered candidate conversations by `includeGroups` in restore plan creation.
4. [BackupViewModel.kt](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/backup/BackupViewModel.kt) — Staging cache tracking, `cleanupStagingData()`, and startup/shutdown cache pruning.
5. [RestoreBackupScreen.kt](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/backup/RestoreBackupScreen.kt) — Added `OTHER_FILES` category toggle, back navigation cache cleanup handler.
6. [BackupCryptoEngine.kt](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/backup/BackupCryptoEngine.kt) — Added empty passphrase validation and decoupled optional `customCacheDir`.
7. [RestoreEngineMergeAndConflictUnitTests.kt](file:///H:/chat_app/app/src/test/java/com/nexa/app/RestoreEngineMergeAndConflictUnitTests.kt) — Updated settings tests to validate allowlist enforcement.
8. [RestoreCrashAndRemediationUnitTests.kt](file:///H:/chat_app/app/src/test/java/com/nexa/app/RestoreCrashAndRemediationUnitTests.kt) — Added 13 new deterministic failure injection and remediation verification tests.

---

## 6. Strict Scope Boundary Enforcement

As established in the Part 7.2 remediation mandate:
- **Part 8 (End-to-End Encryption Key Management & Backup Passphrase Experience)** and **Part 9 (Final Polish, Recovery Testing & Production Signoff)** have **NOT** been started.
- All code modifications were strictly confined to correcting the verified Part 7.1 findings.
- The system is in a stable, verified, zero-regression state awaiting explicit user direction.
