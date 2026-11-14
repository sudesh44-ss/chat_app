# NEXA — Phase 3: Backup & Restore Interruption Recovery Implementation Plan

**Plan Type:** Step-by-Step Remediation & Integration Plan  
**Target Codebase:** `H:\chat_app` (Android Application & Database Subsystem)  
**Authority Hierarchy:** `1architecture.md` / `docs/*.md` / `PHASE_3_BACKUP_RESTORE_RECOVERY_AUDIT.md`  
**Status:** **PROPOSED — AWAITING EXPLICIT USER APPROVAL TO COMMENCE IMPLEMENTATION**

---

## 1. Executive Summary & Objective

This plan defines the step-by-step remediation procedures to resolve the critical data loss vulnerabilities, multi-device restoration blockers, memory exhaustion risks, and interruption gaps identified in `PHASE_3_BACKUP_RESTORE_RECOVERY_AUDIT.md`.

### Core Remediation Objectives:
1. **[P0] True Atomic Database Replacement with Crash-Safe Rollback:** Eliminate destructive delete-before-copy logic. Implement staging-and-commit with automatic rollback journal to guarantee zero data loss if restoration fails at any point.
2. **[P0] Universal Multi-Device SQLCipher Keying:** Resolve SQLCipher snapshot key lock-in so that backups created on Device A can be successfully restored and rekeyed on Device B using only the 12-word BIP-39 mnemonic.
3. **[P1] True Streaming Decryption (OOM Prevention):** Replace monolithic `ByteArray` heap allocations in `BackupCryptoEngine.kt` with chunked streaming decryption (64 KB buffers) to support arbitrary vault sizes safely.
4. **[P1] Explicit SQLite WAL Checkpoint:** Invoke `PRAGMA wal_checkpoint(TRUNCATE)` before snapshotting to ensure consistent, low-latency `VACUUM INTO` operations.
5. **[P1] Android Foreground Execution & Lifecycle Safety:** Provide foreground execution protection with progress notifications for manual backups to prevent OEM background killing.
6. **[P2] In-Flight OAuth2 Token Refresh:** Automatically refresh expired Google Drive access tokens (HTTP 401) during active chunk uploads and file downloads without failing the entire transfer.
7. **[P2] Backup Concurrency Mutex:** Prevent concurrent execution conflicts between Android WorkManager (`ScheduledBackupWorker`) and manual user backups.
8. **[P2] Automated Failure-Injection Test Suite:** Author end-to-end unit tests verifying `packBundle`, `unpackAndRestoreBundle`, rollback recovery, and tamper rejection.

---

## 2. Issue 1: Destructive Delete-Before-Copy Without Rollback (P0)

### 2.1 File & Class Details
- **Exact Files:**  
  - `app/src/main/java/com/nexa/app/data/repository/DriveBackupRepositoryImpl.kt`  
  - `app/src/main/java/com/nexa/app/core/backup/VaultBundlePackager.kt`
- **Class/Function:**  
  - `DriveBackupRepositoryImpl.downloadAndRestoreBackup()`  
  - `VaultBundlePackager.unpackAndRestoreBundle()`

### 2.2 Current Behavior
The code executes `liveDbFile.delete()`, `walFile.delete()`, and `shmFile.delete()` *before* copying the restored database. If `tempRestoredDb.copyTo(liveDbFile)` fails or `NexaDatabase.getInstance()` rejects the restored database, the original database is already permanently gone, and the `finally` block deletes the temporary decrypted database.

### 2.3 Expected Behavior
The original live database and its WAL files must remain completely safe and untouched until the restored database is fully staged, verified, opened, and validated. If activation fails or the process dies during swap, the system must automatically restore the original database from a backup journal (`nexa_encrypted_vault.db.bak`).

### 2.4 Required Fix
1. Remove duplicate database file manipulation from `VaultBundlePackager.unpackAndRestoreBundle()`. `unpackAndRestoreBundle` must only extract and stage files into `tempRestoredDb`.
2. In `DriveBackupRepositoryImpl.kt`, implement a 4-step Commit-or-Rollback Transaction:
   ```kotlin
   // Step 1: Pre-validation of Staged Database
   // Open tempRestoredDb and run integrity check before touching live DB
   
   // Step 2: Atomic Staging Backup
   NexaDatabase.closeAndResetInstance()
   val liveDbFile = context.getDatabasePath(DATABASE_NAME)
   val backupFile = File(liveDbFile.parentFile, "$DATABASE_NAME.bak")
   val backupWalFile = File(liveDbFile.parentFile, "$DATABASE_NAME-wal.bak")
   val backupShmFile = File(liveDbFile.parentFile, "$DATABASE_NAME-shm.bak")

   // Rename live files to .bak (atomic filesystem rename)
   if (liveDbFile.exists()) liveDbFile.renameTo(backupFile)
   val liveWal = File(liveDbFile.path + "-wal")
   if (liveWal.exists()) liveWal.renameTo(backupWalFile)
   val liveShm = File(liveDbFile.path + "-shm")
   if (liveShm.exists()) liveShm.renameTo(backupShmFile)

   var activationSuccess = false
   try {
       // Step 3: Promote Staged DB to Live
       tempRestoredDb.copyTo(liveDbFile, overwrite = true)
       
       // Verify live DB opens and passes PRAGMA quick_check
       val dbPassphrase = secureStorage.getOrCreateDatabasePassphrase()
       val testInstance = NexaDatabase.getInstance(context, dbPassphrase)
       testInstance.openHelper.readableDatabase.query("PRAGMA quick_check").use { cursor ->
           if (cursor.moveToFirst()) {
               val checkResult = cursor.getString(0)
               if (!checkResult.equals("ok", ignoreCase = true)) {
                   throw IllegalStateException("Database quick_check failed: $checkResult")
               }
           }
       }
       activationSuccess = true
       
       // Step 4: Commit - purge .bak files
       backupFile.delete()
       backupWalFile.delete()
       backupShmFile.delete()
   } catch (activationError: Exception) {
       Log.e(TAG, "Restored database failed activation verification. Executing rollback!", activationError)
       // ROLLBACK: Restore original live DB from .bak
       NexaDatabase.closeAndResetInstance()
       if (liveDbFile.exists()) liveDbFile.delete()
       if (backupFile.exists()) backupFile.renameTo(liveDbFile)
       if (backupWalFile.exists()) backupWalFile.renameTo(liveWal)
       if (backupShmFile.exists()) backupShmFile.renameTo(liveShm)
       
       // Re-open original database
       val dbPassphrase = secureStorage.getOrCreateDatabasePassphrase()
       database = NexaDatabase.getInstance(context, dbPassphrase)
       throw activationError
   }
   ```
3. Add a startup crash-recovery probe in `NexaApplication` or `NexaDatabase`: if `$DATABASE_NAME.bak` is found upon app launch and `$DATABASE_NAME` is missing or invalid, automatically recover from `$DATABASE_NAME.bak`.

### 2.5 Risk & Dependencies
- **Data-Loss Risk:** **ELIMINATED** (Zero data loss possible; original DB is preserved on failure).
- **Security Risk:** None. The `.bak` file remains encrypted with the device's SQLCipher key.
- **Dependencies:** Room Database instance manager.
- **Priority:** **P0 (Critical Blocker)**.

---

## 3. Issue 2: SQLCipher Key Mismatch Across Devices (P0)

### 3.1 File & Class Details
- **Exact Files:**  
  - `app/src/main/java/com/nexa/app/core/backup/VaultBundlePackager.kt`  
  - `app/src/main/java/com/nexa/app/core/backup/BackupCryptoEngine.kt`  
  - `app/src/main/java/com/nexa/app/data/repository/DriveBackupRepositoryImpl.kt`
- **Class/Function:**  
  - `VaultBundlePackager.packBundle()`  
  - `VaultBundlePackager.unpackAndRestoreBundle()`

### 3.2 Current Behavior
In `VaultBundlePackager.packBundle()`, `rawDatabase.execSQL("VACUUM INTO '${snapshotDb.absolutePath}'")` produces a snapshot encrypted with Device A's local SQLCipher passphrase ($K_{\text{deviceA}}$). When restored on Device B (or a fresh install), Device B generates a different key ($K_{\text{deviceB}}$). Opening the restored database with $K_{\text{deviceB}}$ fails with `file is not a database`.

### 3.3 Expected Behavior
A backup must be restorable on any Android device possessing the correct 12-word recovery phrase, regardless of device hardware, Keystore keys, or installation IDs.

### 3.4 Required Fix
Use SQLCipher's native export / rekey capability to standardize the snapshot key:
1. **Option A (Standardized Vault Export Key):**
   - In `VaultBundlePackager.packBundle()`, prior to `VACUUM INTO`, execute SQLCipher's vacuum into key pragma:
     ```sql
     ATTACH DATABASE '${snapshotDb.absolutePath}' AS backup_db KEY '';
     SELECT sqlcipher_export('backup_db');
     DETACH DATABASE backup_db;
     ```
     OR:
     Use `PRAGMA cipher_default_vacuum_into_key = ''` (or a standardized deterministic key derived from the BIP-39 mnemonic).
   - Because the snapshot is packaged inside a zip bundle that is immediately encrypted with **AES-256-GCM using the user's BIP-39 master key**, exporting the snapshot as a standard SQLite database inside the zip bundle is cryptographically sound and eliminates the multi-device key mismatch entirely.
2. **On Restoration:**
   - In `unpackAndRestoreBundle()`, the staged snapshot is imported into SQLCipher and encrypted with Device B's local Keystore passphrase ($K_{\text{deviceB}}$):
     ```sql
     ATTACH DATABASE '${snapshotDb.absolutePath}' AS imported_plaintext KEY '';
     SELECT sqlcipher_export('main');
     DETACH DATABASE imported_plaintext;
     ```
   - This ensures `nexa_encrypted_vault.db` on Device B is cleanly encrypted with Device B's native Keystore passphrase.

### 3.5 Risk & Dependencies
- **Data-Loss Risk:** None.
- **Security Risk:** Zero leakage. The snapshot exists on disk only during the in-memory execution inside `cacheDir`, and is deleted immediately after packing/activation. The uploaded file is fully encrypted with AES-256-GCM.
- **Priority:** **P0 (Critical Blocker)**.

---

## 4. Issue 3: Monolithic Heap Allocations in Restore Decryption (P1)

### 4.1 File & Class Details
- **Exact File:** `app/src/main/java/com/nexa/app/core/backup/BackupCryptoEngine.kt`
- **Class/Function:** `BackupCryptoEngine.decryptAndRestoreVault()`

### 4.2 Current Behavior
Allocates `ByteArray((vaultFile.length() - 71).toInt())` in RAM, hashes it in memory, and then calls `cipher.doFinal(ciphertext)`, allocating another full copy of decrypted bytes in heap. On vaults > 50 MB, this causes `OutOfMemoryError` on 32-bit devices or standard Android heap budgets.

### 4.3 Expected Behavior
Decryption, SHA-256 calculation, and bundle unpacking must operate through streaming 64 KB buffers with a fixed memory footprint capped under 1 MB, regardless of whether the vault is 5 MB or 500 MB.

### 4.4 Required Fix
1. Refactor `decryptAndRestoreVault` to use `CipherInputStream` and `DigestInputStream`:
   ```kotlin
   val sha256Digest = MessageDigest.getInstance("SHA-256")
   // Stream pre-decryption checksum validation:
   FileInputStream(vaultFile).use { fis ->
       fis.skip(BackupVaultHeader.HEADER_SIZE.toLong())
       val buffer = ByteArray(64 * 1024)
       var read: Int
       while (fis.read(buffer).also { read = it } != -1) {
           sha256Digest.update(buffer, 0, read)
       }
   }
   val computedHash = sha256Digest.digest()
   if (!computedHash.contentEquals(header.payloadSha256)) {
       throw SecurityException("Backup vault checksum mismatch.")
   }
   
   // Stream decryption directly to tempArchive file via CipherInputStream:
   val cipher = Cipher.getInstance("AES/GCM/NoPadding")
   cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, header.iv))
   
   FileInputStream(vaultFile).use { fis ->
       fis.skip(BackupVaultHeader.HEADER_SIZE.toLong())
       javax.crypto.CipherInputStream(fis, cipher).use { cis ->
           FileOutputStream(tempArchive).use { fos ->
               val buffer = ByteArray(64 * 1024)
               var read: Int
               while (cis.read(buffer).also { read = it } != -1) {
                   fos.write(buffer, 0, read)
               }
               fos.flush()
           }
       }
   }
   ```
2. Memory consumption will remain strictly below 128 KB regardless of backup file size.

### 4.5 Priority: **P1 (High)**

---

## 5. Issue 4: SQLite WAL Checkpoint Execution Before Snapshot (P1)

### 5.1 File & Class Details
- **Exact File:** `app/src/main/java/com/nexa/app/core/backup/VaultBundlePackager.kt`
- **Class/Function:** `VaultBundlePackager.packBundle()`

### 5.2 Current Behavior
Executes `VACUUM INTO` directly without checkpointing the Write-Ahead Log (WAL). If the WAL file is large, `VACUUM INTO` must read and merge all uncheckpointed frames, causing high CPU usage and prolonged snapshot duration.

### 5.3 Expected Behavior
Execute `PRAGMA wal_checkpoint(TRUNCATE)` or `PRAGMA wal_checkpoint(PASSIVE)` immediately before creating the snapshot to flush pending write transactions into the master database pages.

### 5.4 Required Fix
```kotlin
// In VaultBundlePackager.packBundle() before VACUUM INTO:
onProgress("Checkpointing Database WAL...", 0.10f)
try {
    rawDatabase.query("PRAGMA wal_checkpoint(PASSIVE)").use { cursor ->
        if (cursor.moveToFirst()) {
            val busy = cursor.getInt(0)
            val log = cursor.getInt(1)
            val checkpointed = cursor.getInt(2)
            Log.d(TAG, "WAL Checkpoint completed: busy=$busy, log=$log, checkpointed=$checkpointed")
        }
    }
} catch (e: Exception) {
    Log.w(TAG, "WAL checkpoint warning: ${e.message}")
}
```

### 5.5 Priority: **P1 (High)**

---

## 6. Issue 5: Manual Backup Android Foreground Lifecycle Safety (P1)

### 6.1 File & Class Details
- **Exact Files:**  
  - `app/src/main/java/com/nexa/app/presentation/backup/BackupViewModel.kt`  
  - `app/src/main/java/com/nexa/app/data/worker/ScheduledBackupWorker.kt`
- **Class/Function:** `BackupViewModel.triggerBackup()`

### 6.2 Current Behavior
Manual backup runs inside `viewModelScope.launch`. If the user switches apps or aggressive OEM battery managers (Vivo, Xiaomi, Samsung) background the app, the process is killed, aborting the upload.

### 6.3 Expected Behavior
Manual backups must either delegate to a one-time WorkManager task with `OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST` or bind to an application-scoped job that survives configuration changes and screen transitions.

### 6.4 Required Fix
1. In `BackupViewModel.kt`, inject an application-level coroutine scope (`@ApplicationScope CoroutineScope`) or delegate to a `OneTimeWorkRequestBuilder<ManualBackupWorker>()`.
2. Display an ongoing notification with progress percentage so the user is informed of progress while multitasking.

### 6.5 Priority: **P1 (High)**

---

## 7. Issue 6: In-Flight OAuth2 Token Refresh & Retry (P2)

### 7.1 File & Class Details
- **Exact Files:**  
  - `app/src/main/java/com/nexa/app/data/drive/DriveAppDataService.kt`  
  - `app/src/main/java/com/nexa/app/data/drive/DriveResumableUploader.kt`

### 7.2 Current Behavior
When Google Drive returns HTTP 401 Unauthorized, `DriveAppDataService` invalidates the token in Google Play Services cache and immediately throws `IOException("Drive authorization expired (HTTP 401)")`, terminating the transfer.

### 7.3 Expected Behavior
Catch HTTP 401, invalidate the expired token, acquire a fresh token via `authManager.getAccessToken()`, and retry the request once before throwing.

### 7.4 Required Fix
```kotlin
// In DriveAppDataService.kt wrapper function:
private suspend fun <T> executeWithTokenRetry(block: suspend (token: String) -> T): T {
    var token = authManager.getAccessToken()
    return try {
        block(token)
    } catch (e: IOException) {
        if (e.message?.contains("HTTP 401") == true) {
            Log.w(TAG, "Access token expired during Drive operation. Invalidating and refreshing...")
            authManager.invalidateToken(token)
            token = authManager.getAccessToken()
            block(token) // Retry once with fresh token
        } else {
            throw e
        }
    }
}
```

### 7.5 Priority: **P2 (Medium)**

---

## 8. Issue 7: Backup Concurrency Mutex (P2)

### 8.1 File & Class Details
- **Exact File:** `app/src/main/java/com/nexa/app/data/repository/DriveBackupRepositoryImpl.kt`

### 8.2 Current Behavior
If `ScheduledBackupWorker` triggers a nightly backup at the exact moment a user triggers a manual backup from settings, both coroutines attempt to create snapshots and upload concurrently.

### 8.3 Expected Behavior
Use a repository-level `kotlinx.coroutines.sync.Mutex` to enforce single-flight backup execution. If a backup is already active, return a progress/busy state instead of starting a concurrent snapshot.

### 8.4 Required Fix
```kotlin
// In DriveBackupRepositoryImpl.kt
private val backupMutex = Mutex()

override fun createAndUploadBackup(...): Flow<DriveResource<String>> = flow {
    if (!backupMutex.tryLock()) {
        emit(DriveResource.Error("A backup operation is already in progress. Please wait."))
        return@flow
    }
    try {
        // ... existing backup flow ...
    } finally {
        backupMutex.unlock()
    }
}
```

### 8.5 Priority: **P2 (Medium)**

---

## 9. Issue 8: Automated Failure-Injection Test Suite (P2)

### 9.1 File & Class Details
- **Create:** `app/src/test/java/com/nexa/app/BackupRollbackAndIntegrityUnitTests.kt`

### 9.2 Test Specifications
1. `testAtomicRollbackOnActivationFailure`:  
   Simulate a corrupted restored database file; verify that live database is restored from `.bak` and remains 100% accessible.
2. `testMultiDeviceSqlCipherImportExport`:  
   Create a snapshot on Device A; verify that Device B (with a different passphrase) can successfully import and open the database.
3. `testStreamingDecryptionMemoryCapped`:  
   Run streaming decryption on a simulated 50 MB vault file; verify heap allocation does not scale with file size.
4. `testWalCheckpointBeforeVacuum`:  
   Verify that `PRAGMA wal_checkpoint` is invoked before `VACUUM INTO`.
5. `testConcurrentBackupRejection`:  
   Verify that triggering two simultaneous backups rejects the second one cleanly via mutex.

---

## 10. Step-by-Step Implementation Sequence

```
┌────────────────────────────────────────────────────────┐
│ Phase 3 Implementation Gates                           │
└────────────────────────────────────────────────────────┘
                           │
                           ▼
 [Gate 1: User Approval of Audit & Implementation Plan]
                           │
                           ▼
 [Step 1: SQLCipher Multi-Device Keying Fix]
  - Update VaultBundlePackager.packBundle & unpackAndRestoreBundle
  - Ensure snapshot database can be imported across devices with different Keystore keys
                           │
                           ▼
 [Step 2: Atomic Database Replacement with Crash-Safe Rollback]
  - Implement .bak staging-and-commit in DriveBackupRepositoryImpl
  - Add PRAGMA quick_check before finalizing swap
  - Implement automatic rollback on verification failure
                           │
                           ▼
 [Step 3: True Streaming Decryption & OOM Prevention]
  - Update BackupCryptoEngine.kt to use CipherInputStream & DigestInputStream
  - Eliminate monolithic ByteArray allocations
                           │
                           ▼
 [Step 4: WAL Checkpoint & Concurrency Mutex]
  - Add PRAGMA wal_checkpoint(PASSIVE) before VACUUM INTO
  - Add Mutex in DriveBackupRepositoryImpl
                           │
                           ▼
 [Step 5: Google Drive Token Refresh & Retry]
  - Wrap Drive API calls in executeWithTokenRetry
                           │
                           ▼
 [Step 6: Automated Failure-Injection & Rollback Tests]
  - Author BackupRollbackAndIntegrityUnitTests.kt
  - Run full test suite: ./gradlew testDebugUnitTest
                           │
                           ▼
 [Gate 2: Phase 3 Verification Report & Completion]
```

---

## 11. Verification & Phase Completion Criteria

The Phase 3 remediation will be considered **COMPLETE** when:
1. Restoration failure tests demonstrate 100% rollback to the original live database with zero data loss.
2. Backups created on one device can be decrypted and opened on a different device using only the 12-word BIP-39 mnemonic.
3. Vault decryption runs in streaming mode with strictly bounded heap usage (< 1 MB).
4. All existing and new unit tests compile and pass.
5. All code strictly adheres to `1architecture.md` and repository guidelines.

---

**End of Implementation Plan.**  
*Awaiting explicit user approval before touching source files.*
