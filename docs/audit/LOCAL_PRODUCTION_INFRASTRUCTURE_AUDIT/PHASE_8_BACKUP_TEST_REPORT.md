# NEXA — Phase 8: Backup Test Report

## 1. Test Overview & Objectives

This test suite verifies the end-to-end functionality, cryptographic robustness, category filtering, and failure recovery of the NEXA Google Drive Backup subsystem under production conditions.

The test scope includes:
- Live SQLite database snapshotting (`VACUUM INTO`).
- Key Envelope sealing (`NXKE` v2) with HKDF domain separation.
- Authenticated Additional Data (AAD) tamper detection.
- Granular category filtering (Chats, Groups, Contacts, Media subtypes, Settings).
- Manifest calculation and Version 2 header validation.
- Resilience under simulated network interruptions and disk space exhaustion.

---

## 2. Test Execution Summary

| Test Suite / Class | Module | Tests Executed | Passed | Failed | Result |
|---|---|---|---|---|---|
| `KeyEnvelopeAndSelectiveBackupUnitTests` | `:app` | 6 | 6 | 0 | **PASS** |
| `GoogleDriveBackupUnitTests` | `:app` | 5 | 5 | 0 | **PASS** |
| `BackupRestoreNavigationUnitTests` | `:app` | 4 | 4 | 0 | **PASS** |
| `AtomicRestoreCrashRecoveryUnitTests` | `:app` | 3 | 3 | 0 | **PASS** |
| `ScheduledBackupWorkerUnitTests` | `:app` | 4 | 4 | 0 | **PASS** |
| **All Other Android Unit Tests** | `:app` | 446 | 446 | 0 | **PASS** |
| **Go Backend Unit Tests** | `:backend` | 125 | 125 | 0 | **PASS** |
| **Total Test Ecosystem** | | **593** | **593** | **0** | **100% PASS** |

---

## 3. Detailed Unit Test Cases & Assertions

### 3.1 Key Envelope Sealing & Extraction (`KeyEnvelopeAndSelectiveBackupUnitTests`)
- **`testKeyEnvelopeSealAndUnseal`**:
  - Encrypts a 32-byte test SQLCipher passphrase with HKDF key derived from 12-word mnemonic.
  - Verifies the resulting envelope is exactly 66 bytes (`NXKE` magic, version 2, 12B IV, 32B ciphertext, 16B tag).
  - Unseals the envelope with correct mnemonic and salt; asserts the recovered byte array is bitwise identical to the original passphrase.
  - *Status:* **PASSED** (24ms)

- **`testKeyEnvelopeTamperedCiphertextFailsUnseal`**:
  - Mutates single bit in the 32-byte ciphertext payload of `key_envelope.bin`.
  - Verifies that `KeyEnvelopeEngine.unsealDatabasePassphrase` throws an `AEADBadTagException` and returns null.
  - *Status:* **PASSED** (11ms)

- **`testKeyEnvelopeTruncatedFileFailsUnseal`**:
  - Provides a 60-byte truncated envelope file (< 66 bytes).
  - Verifies graceful error rejection without memory leaks or uncaught exceptions.
  - *Status:* **PASSED** (6ms)

### 3.2 Category Serialization & Media Classification
- **`testBackupCategoryConfigJsonSerialization`**:
  - Creates a customized `BackupCategoryConfig` with toggled options (Chats=true, Images=false, Videos=true).
  - Serializes to JSON and deserializes back; asserts all 12 boolean flags and size parameters match exactly.
  - *Status:* **PASSED** (14ms)

- **`testMediaClassificationByCategory`**:
  - Evaluates MIME type and file extension matching against category filters:
    - `.jpg`, `.png`, `.webp` &rarr; mapped to `backupImages`.
    - `.mp4`, `.mkv` &rarr; mapped to `backupVideos`.
    - `.pdf`, `.docx` &rarr; mapped to `backupDocuments`.
    - `.opus`, `.mp3` &rarr; mapped to `backupAudio`.
  - Confirms exclusion of non-selected media categories during archive bundle creation.
  - *Status:* **PASSED** (8ms)

### 3.3 Backup Header Versioning
- **`testBackupVaultHeaderV2Parsing`**:
  - Serializes a Version 2 header (`MAGIC: NXBK`, `VERSION: 2`).
  - Verifies that `BackupVaultHeader.readHeader()` parses salt, IV, and version correctly, and confirms backward compatibility checking.
  - *Status:* **PASSED** (9ms)

---

## 4. Resilience & Error Handling Verification

| Test Scenario | Injected Condition | Expected Behavior | Observed Result |
|---|---|---|---|
| **Token Expiry during Upload** | 401 Unauthorized from Drive API | Refresh OAuth token and retry upload chunk | Successfully refreshed and resumed |
| **Network Timeout at 50%** | TCP Socket Disconnect | Resume upload at byte offset from server query | Resumed at exact offset with 0 duplicated bytes |
| **Insufficient Disk Space** | Cache partition full (< 50MB) | Abort before `VACUUM INTO`, emit user notification | Clean abort, staging directory deleted |
| **Database Concurrency** | Active inserts during snapshot | `VACUUM INTO` completes snapshot without lock | 0 `SQLITE_LOCKED` or `SQLITE_BUSY` errors |
