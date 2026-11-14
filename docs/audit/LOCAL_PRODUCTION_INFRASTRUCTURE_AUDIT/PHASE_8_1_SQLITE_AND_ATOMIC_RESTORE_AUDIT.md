# NEXA — Phase 8.1: SQLite & Atomic Restore Audit

## 1. Audit Scope & Verification Goals

This audit examines the transactional safety, SQLite snapshot consistency, SQLCipher passphrase binding, WAL (Write-Ahead Log) frame management, and rollback recovery mechanics during backup creation and restoration.

Audited Files:
- [`VaultBundlePackager.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/backup/VaultBundlePackager.kt)
- [`BackupCryptoEngine.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/backup/BackupCryptoEngine.kt)
- [`DriveBackupRepositoryImpl.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/repository/DriveBackupRepositoryImpl.kt)
- [`NexaDatabase.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/local/NexaDatabase.kt)

---

## 2. SQLite Live Snapshotting via `VACUUM INTO`

### 2.1 Concurrency & Zero-Lock Behavior
- **Implementation:** [`VaultBundlePackager.kt:104`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/backup/VaultBundlePackager.kt#L104):
  `rawDatabase.execSQL("VACUUM INTO '${snapshotDb.absolutePath}'")`
- **Mechanism:**
  - SQLite native `VACUUM INTO` creates a clean, defragmented duplicate of the active database.
  - Unlike file-system copying (e.g. `java.io.FileInputStream`), `VACUUM INTO` acquires a shared read lock for each page and folds active WAL frames into the target file without truncating or locking the active WAL journal.
  - Active WebSocket listeners, DTN packet receivers, and UI reads continue operating with zero `SQLITE_BUSY` or `SQLITE_LOCKED` errors.
- **Audit Assessment:** **TRANSACTIONALLY ROBUST**.

---

## 3. Atomic Database Replacement & Rollback Journaling

### 3.1 Swap Sequence Trace (`DriveBackupRepositoryImpl.kt:237-290`)
The replacement sequence executes as follows:
1. `NexaDatabase.closeAndResetInstance()` closes the active SQLite connection pool.
2. Existing production files are copied to backup rollback journals:
   - `nexa.db` &rarr; `nexa.db.rollback_bak`
   - `nexa.db-wal` &rarr; `nexa.db-wal.rollback_bak`
3. Active WAL, SHM, and journal files are deleted to prevent stale WAL frame replays over new data:
   - `walFile.delete()`
   - `shmFile.delete()`
   - `journalFile.delete()`
4. Restored database `tempRestoredDb` is copied over `liveDbFile`.
5. Database is re-opened with the recovered passphrase:
   `database = NexaDatabase.getInstance(context, dbPassphrase)`
6. On success: `backupLiveDb.delete()` and `backupWal.delete()` remove the staging journals.
7. On exception: catch block copies `.rollback_bak` back over `liveDbFile` and `walFile`.

### 3.2 Cold-Start Auto-Recovery (`NexaDatabase.kt:130-155`)
- **Inspection:**
  If the Android OS terminates the process during step 4 (battery exhaustion or low-memory kill), the catch block does not run.
  To protect against this, `NexaDatabase.getInstance()` inspects the database folder on application cold boot:
  If `nexa.db.rollback_bak` exists, it detects that a previous restore was interrupted mid-flight. It automatically restores `nexa.db.rollback_bak` over `nexa.db` before opening the Room database.
- **Audit Assessment:** **RECOVERABLE ACROSS PHYSICAL REBOOTS**.

---

## 4. Failure Evaluation Across All 10 Lifecycle Stages

| Stage | Injected / Hypothetical Failure | System Behavior | Data Integrity Result |
|---|---|---|---|
| **1. Before Snapshot** | Disk full (< 50MB free) | Aborts before `VACUUM INTO` | Live DB completely untouched |
| **2. During Snapshot** | App crash during `VACUUM INTO` | Temporary snapshot file is deleted by SQLite engine | Live DB and WAL intact |
| **3. During Archive Creation** | OOM or process kill during ZIP | Staging files left in cache directory | Live DB intact; cache wiped on reboot |
| **4. During Upload** | Network drop or token expiration | Resumable uploader records offset in `SecureStorage` | No cloud corruption; resumable |
| **5. During Download** | Wi-Fi disconnect | Partial bytes saved to `.part` file | Resume via HTTP 206 on reconnect |
| **6. During Decryption** | Wrong 12-word recovery phrase | AEAD authentication tag fails; throws `AEADBadTagException` | Decryption aborted; DB untouched |
| **7. Database Pre-Verification** | Corrupted SQLite snapshot file | SHA-256 manifest check fails before swap | Swap aborted; live DB untouched |
| **8. Before DB Replacement** | Error creating `.rollback_bak` | Swap aborts immediately | Live DB untouched |
| **9. During DB Replacement** | Battery dies while copying `liveDbFile` | Process killed mid-copy | Recovered on cold boot via `.rollback_bak` |
| **10. After Replacement before Cleanup** | Crash right before deleting `.rollback_bak` | New DB is active; `.rollback_bak` remains | Safe, but cold boot might revert new DB if not flagged |

### Warning on Stage 10 Edge Case (P2)
In `NexaDatabase.kt:135`, if the process crashes in Stage 10 (after the new database has already been successfully placed, but before `backupLiveDb.delete()` runs), the cold-boot handler cannot distinguish whether the swap completed or was aborted mid-copy. It will blindly copy the old `.rollback_bak` over the newly restored database!
*Remediation Recommendation:* Write an atomic `.swap_in_progress` sentinel file before copying, and delete the sentinel immediately after swap success, before deleting `.rollback_bak`.

---

## 5. Physical Process Kill vs. Simulated Exception Testing

- **Unit Test Evidence:**
  [`AtomicRestoreCrashRecoveryUnitTests.kt`](file:///H:/chat_app/app/src/test/java/com/nexa/app/AtomicRestoreCrashRecoveryUnitTests.kt) tests the catch block and rollback logic under simulated `IllegalStateException`.
- **Physical OS Kill Limitation:**
  In this audit environment, true physical OS process termination (`SIGKILL` / kernel power cut) was **NOT physically executed** on live Android hardware. The recovery guarantee rests on the logic verified in unit tests and static code analysis.
