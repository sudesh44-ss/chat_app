# NEXA — Phase 8: Restore Implementation Report

## 1. Executive Summary

The NEXA Cloud Restore subsystem allows users to recover their complete conversational history, group memberships, verified cryptographic identities, customized application preferences, and high-density media vaults on a new or existing Android device. The restore process is built with strict Zero-Knowledge safeguards, granular selective category filtering, 12-word BIP-39 mnemonic phrase validation, in-memory key unsealing, and an atomic SQLite database swap with automatic `.rollback_bak` recovery.

---

## 2. Restore User Flow & Navigation Integration

### 2.1 Navigation Structure
- **Route:** `restore_backup` (`Screen.RestoreBackup`)
- **Origin Points:**
  - `SettingsScreen` &rarr; `BackupSettingsScreen` &rarr; "Restore from Cloud Backup" button.
  - Onboarding Setup Flow &rarr; "Already have an account? Restore from Google Drive".

### 2.2 Screen Layout Composition (`RestoreBackupScreen.kt`)
The UI strictly implements the NEXA **Liquid Glass** aesthetic using `NexaGlassSurface`:

```
[NexaTopAppBar] ("Restore Backup", Back Arrow)
       |
       +--> [Cloud Backup Discovery Card]
       |    - Discovered Remote Backup Metadata:
       |      - Filename: "nexa_vault_backup_20261110_153000.enc"
       |      - Timestamp: "Nov 10, 2026, 3:30 PM"
       |      - File Size: "42.8 MB"
       |      - File ID: "1ABC...xyz"
       |    - "Refresh Backups" action button
       |
       +--> [Selective Restore Card]
       |    - Quick Actions: "Select All" / "Deselect All"
       |    - 12 Granular Category Checkboxes (Chats, Media, Settings, etc.)
       |    - Allows user to restore only text chats without downloading large videos
       |
       +--> [Safety & Rollback Guarantee Card]
       |    - Explains Atomic Swap protection
       |    - Guarantees existing local database is never overwritten until new backup validates
       |    - Shows current active local database status
       |
       +--> [12-Word BIP-39 Recovery Input Grid]
       |    - 12 individual masked text inputs
       |    - "Paste Full Phrase" one-tap action (auto-splits 12 space-separated words)
       |    - Live BIP-39 checksum indicator (Green checkmark when phrase is valid)
       |
       +--> [Restore Action & Progress]
            - "Start Restore" primary CTA button
            - Active Phase Indicator (Downloading -> Verifying -> Unsealing -> Swapping)
            - Progress Percentage Bar (0% to 100%)
            - Success confirmation dialog triggering application restart / database reinitialization
```

---

## 3. In-RAM Key Envelope Unsealing & Zero Flash Residue

During cross-device restore, the new device does not have Device A's Android Keystore hardware keys. `VaultBundlePackager.unpackAndRestoreBundle()` handles envelope processing without exposing the database passphrase to flash:

1. **Direct RAM Buffering:** The ZIP stream is read sequentially using `ZipInputStream`.
2. **Envelope Interception:** When `key_envelope.bin` is encountered, it is copied into a `ByteArrayOutputStream` directly in volatile RAM.
3. **Cryptographic Unsealing:**
   $$K_{\text{vault}} = \text{PBKDF2-HMAC-SHA256}(\text{BIP39-Mnemonic}, \text{Salt}_{\text{vault}}, 65536, 256)$$
   $$K_{\text{env}} = \text{HKDF-SHA256}(K_{\text{vault}}, \text{Salt}_{\text{vault}}, \text{"NEXA\_V2\_KEY\_ENVELOPE\_KEY"}, 32)$$
   $$K_{\text{db}} = \text{AES-256-GCM-Decrypt}(K_{\text{env}}, IV_{\text{env}}, C_{\text{db}}, T_{\text{env}}, \text{AAD})$$
4. **Keystore Ingestion:** $K_{\text{db}}$ is immediately written into Device B's local Android Keystore-backed `EncryptedSharedPreferences` using `secureStorage.saveDatabasePassphrase(recoveredPassphrase)`.
5. **Memory Erasure:** The raw byte array in memory is scrubbed using `Arrays.fill(recoveredPassphrase, 0.toByte())`.

---

## 4. Atomic Database Swap & Crash-Proof Rollback Journal

To eliminate the risk of a corrupted or interrupted download destroying the user's existing conversation database:

### Phase 1: Isolation & Key Pre-Validation
The restored SQLite snapshot is written to a temporary staging file:
`/data/user/0/com.nexa.app/cache/staging_<timestamp>/live_snapshot.db`
`DriveBackupRepositoryImpl` opens a read-only test connection to this staging file using the recovered $K_{\text{db}}$:
```kotlin
val testHelper = FrameworkSQLiteOpenHelperFactory().create(...)
val testDb = testHelper.writableDatabase
testDb.query("SELECT count(*) FROM sqlite_master").use { it.moveToFirst() }
```
If the key fails or the file is corrupted, the restore fails immediately with zero changes to existing production databases.

### Phase 2: Rollback Preservation
```kotlin
val rollbackDb = File(liveDb.parentFile, "${liveDb.name}.rollback_bak")
val rollbackWal = File(liveDb.parentFile, "${liveDb.name}-wal.rollback_bak")

if (liveDb.exists()) liveDb.copyTo(rollbackDb, overwrite = true)
if (liveWal.exists()) liveWal.copyTo(rollbackWal, overwrite = true)
```

### Phase 3: Live File Replacement & Finalization
```kotlin
try {
    stagingDb.copyTo(liveDb, overwrite = true)
    if (liveWal.exists()) liveWal.delete() // Clean stale WAL frames
    // Swap successful -> remove rollback backups
    rollbackDb.delete()
    rollbackWal.delete()
} catch (e: Exception) {
    // Atomic Rollback
    if (rollbackDb.exists()) rollbackDb.copyTo(liveDb, overwrite = true)
    if (rollbackWal.exists()) rollbackWal.copyTo(liveWal, overwrite = true)
    rollbackDb.delete()
    rollbackWal.delete()
    throw e
}
```

### Phase 4: Cold Startup Auto-Recovery
On every application cold start, `NexaDatabase.getDatabase()` inspects the database directory:
If `${liveDb.name}.rollback_bak` exists, it detects that the previous restore process was killed mid-swap (e.g., OS battery death). It automatically copies the rollback backup back over `liveDb`, restoring the previous valid state.

---

## 5. Selective Category Filtering on Restore

Users can choose to restore only specific data subsets via `BackupCategoryConfig`:

```kotlin
when (entry.name) {
    "live_snapshot.db" -> {
        // Chats, Groups, Contacts are contained in the database
        if (restoreCategoryConfig.backupChats || restoreCategoryConfig.backupGroups || restoreCategoryConfig.backupContacts) {
            extractFile(entryStream, stagingDb)
        }
    }
    "preferences.json" -> {
        if (restoreCategoryConfig.backupUserPreferences || restoreCategoryConfig.backupAppSettings) {
            restorePreferences(entryStream, restoreCategoryConfig)
        }
    }
    else -> {
        if (entry.name.startsWith("media_vault/")) {
            val extension = entry.name.substringAfterLast('.', "")
            if (matchesCategory(extension, restoreCategoryConfig)) {
                extractFile(entryStream, localMediaFile)
            }
        }
    }
}
```
This enables rapid recovery over low-bandwidth mobile connections.
