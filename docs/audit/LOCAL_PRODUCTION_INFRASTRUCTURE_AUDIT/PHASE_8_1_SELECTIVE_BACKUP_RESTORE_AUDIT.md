# NEXA — Phase 8.1: Selective Backup & Restore Functional Audit

## 1. Executive Summary & Critical Verdict

> [!CAUTION]
> **CRITICAL P0 DEFECT IDENTIFIED — UNCONDITIONAL DATABASE REPLACEMENT:**
> While `RestoreBackupScreen.kt` and `BackupSettingsScreen.kt` present 12 granular category checkboxes ("Chats & Direct Messages", "Group Chats & Channels", "Contacts & Identities", "Bookmarks", etc.), **neither the backup packaging engine nor the restore engine implements table-level or entity-level filtering on SQLite databases**.
>
> Restoring a backup unconditionally executes `tempRestoredDb.copyTo(liveDbFile, overwrite = true)`, replacing the target device's entire SQLite database wholesale. Any unselected database categories are overwritten regardless of user configuration.

---

## 2. End-to-End Category Verification Matrix

| Category | UI Toggle Exists? | Config Serialized? | Pack Filtered? | Archive Segregated? | Restore Filtered? | True Selective Behavior Status |
|---|---|---|---|---|---|---|
| **Chats & DMs** | Yes | Yes | **NO (100% Snapshotted)** | Inside monolithic DB | **NO (100% Overwritten)** | **FAILED (P0)** |
| **Groups & Channels** | Yes | Yes | **NO (100% Snapshotted)** | Inside monolithic DB | **NO (100% Overwritten)** | **FAILED (P0)** |
| **Contacts & Identities** | Yes | Yes | **NO (100% Snapshotted)** | Inside monolithic DB | **NO (100% Overwritten)** | **FAILED (P0)** |
| **Bookmarks / Starred** | Yes | Yes | **NO (100% Snapshotted)** | Inside monolithic DB | **NO (100% Overwritten)** | **FAILED (P0)** |
| **Images & Photos** | Yes | Yes | **YES (Extension check)** | `media_vault/` | **YES (Extension check)** | **VERIFIED** |
| **Videos & Clips** | Yes | Yes | **YES (Extension check)** | `media_vault/` | **YES (Extension check)** | **VERIFIED** |
| **Voice & Audio** | Yes | Yes | **YES (Extension check)** | `media_vault/` | **YES (Extension check)** | **VERIFIED** |
| **Documents & PDFs** | Yes | Yes | **YES (Extension check)** | `media_vault/` | **YES (Extension check)** | **VERIFIED** |
| **Other Media** | Yes | Yes | **YES (Extension check)** | `media_vault/` | **YES (Extension check)** | **VERIFIED** |
| **User Preferences** | Yes | Yes | **YES (JSON Export)** | `preferences.json` | **YES (JSON Import)** | **VERIFIED** |
| **App Settings** | Yes | Yes | **YES (JSON Export)** | `preferences.json` | **YES (JSON Import)** | **VERIFIED** |
| **Device Settings** | Yes | Yes | **NO (Excluded by design)** | Not exported | N/A | **VERIFIED (Keystore Safe)** |

---

## 3. Deep-Dive Code Trace of the Failure Mode

### 3.1 Backup Creation (`VaultBundlePackager.kt:100-105`)
```kotlin
// 1. Zero-Lock Atomic Snapshot via VACUUM INTO
onProgress("Creating Zero-Lock Database Snapshot...", 0.15f)
val snapshotDb = File(dbDir, "nexa_master_snapshot.db")
if (snapshotDb.exists()) snapshotDb.delete()
rawDatabase.execSQL("VACUUM INTO '${snapshotDb.absolutePath}'")
```
- **Analysis:**
  `VACUUM INTO` snapshots the *entire* active SQLite database file.
  Even if the user unchecks `includeChats = false` or `includeContacts = false`, the raw SQLite database snapshot includes every table: `messages`, `conversations`, `contacts`, `groups`, `group_members`, and `file_envelopes`.
- **Privacy & Security Consequence:**
  The user is misled into believing that unchecking "Chats" will prevent their private conversations from being uploaded to Google Drive. In reality, the entire chat database is uploaded inside the encrypted vault.

### 3.2 Restore Execution (`VaultBundlePackager.kt:370-384` & `DriveBackupRepositoryImpl.kt:257-264`)
In `VaultBundlePackager.kt`:
```kotlin
// 6. Atomic Database Swap
onProgress("Activating Database Snapshot...", 0.90f)
NexaDatabase.closeAndResetInstance()

if (liveDatabaseFile.exists()) liveDatabaseFile.delete()
snapshotDb.copyTo(liveDatabaseFile, overwrite = true)
```
And in `DriveBackupRepositoryImpl.kt`:
```kotlin
tempRestoredDb.copyTo(liveDbFile, overwrite = true)
```
- **Analysis:**
  There is zero inspection of `restoreCategoryConfig.includeChats`, `includeGroups`, or `includeContacts`.
  `tempRestoredDb.copyTo(liveDbFile, overwrite = true)` unconditionally overwrites the active production database file `nexa.db`.
- **Data-Loss Consequence (Real-World Disaster Scenario):**
  1. A user installs NEXA on Device B and has been using it for 3 weeks, generating 500 new conversations.
  2. The user realizes they need old family photos from a backup made on Device A.
  3. The user opens "Restore Backup", selects **ONLY "Images & Photos"**, and unchecks "Chats & Direct Messages" (to preserve their current Device B conversations).
  4. The user taps "Start Restore".
  5. The restore engine restores the images to `media_vault/`, and then **wipes Device B's current `nexa.db` and replaces it with Device A's 3-week-old snapshot**.
  6. **All 500 new conversations on Device B are permanently erased with zero user warning.**

---

## 4. Defect Classification & Impact Assessment

- **Defect Severity:** **P0 / Blocker**.
- **Impact:** Irreversible data loss and false representation of user privacy controls.
- **Root Cause:** Treating a multi-table relational SQLite database as a single monolithic file copy instead of implementing entity-level table export/import or SQLite database merging (`ATTACH DATABASE`).
- **Audit Requirement Satisfaction:** Section 8 explicitly mandates: *"If category checkboxes are visible but restore replaces the complete database, classify this as a P0/P1 functional defect according to the actual data-loss impact. Do not approve selective restore based only on UI presence or configuration serialization tests."*
- **Final Classification:** **FAILED (P0 DEFECT).**
