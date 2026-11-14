# NEXA P2-A Reliability Report: Persistent Google Drive Upload Session Recovery

## 1. Original Finding
In previous audits (`NEXA_BACKUP_NETWORK_RESILIENCE_AUDIT.md`), `DriveResumableUploader.kt` implemented a 256 KB chunked upload protocol using Google Drive REST API v3 resumable sessions. However, the active `sessionUri` was held only in memory. If the Android application was terminated by the Android OS under memory pressure, killed by the user, or if the device rebooted during an active backup, the upload session was permanently lost and the temporary encrypted vault file in `cacheDir` was deleted, forcing subsequent backup attempts to restart from byte zero.

---

## 2. Verified Root Cause
1. **Volatile Session Storage:** `DriveBackupRepositoryImpl.kt` stored `sessionUri` in a local coroutine variable without persisting it to `SecureStorage` or `BackupPrefs`.
2. **Aggressive Cleanup in `finally` Block:** The cleanup block unconditionally deleted `vaultFile` upon coroutine termination (`finally { if (vaultFile.exists()) vaultFile.delete() }`), preventing resumption of the local ciphertext.
3. **Missing Process-Death Detection:** Neither `createAndUploadBackup()` nor `performSilentBackup()` inspected whether an incomplete upload was pending before generating a new snapshot.

---

## 3. Source Files Inspected & Modified

| File | Status | Description of Changes |
|---|---|---|
| `app/src/main/java/com/nexa/app/data/drive/UploadSessionState.kt` | **NEW** | Immutable model of active resumable upload session, URI scheme & host validation (`www.googleapis.com`), and `UploadSessionExpiredException` |
| `app/src/main/java/com/nexa/app/core/security/SecureStorage.kt` | **MODIFIED** | Hardware-backed encrypted persistence for active session state (`saveActiveUploadSession`, `getActiveUploadSession`, `updateUploadSessionOffset`, `clearActiveUploadSession`) |
| `app/src/main/java/com/nexa/app/data/drive/DriveResumableUploader.kt` | **MODIFIED** | Added URI host validation, `onChunkUploaded` offset callback, and HTTP 404/410 expiration detection |
| `app/src/main/java/com/nexa/app/data/repository/DriveBackupRepositoryImpl.kt` | **MODIFIED** | Added active session detection, offset query probe reconciliation, fail-safe resumption, and conditioned vault file cleanup |
| `app/src/test/java/com/nexa/app/DriveUploadRecoveryUnitTests.kt` | **NEW** | 6 unit tests verifying URI validation, state validation, offset parsing, process-death recovery, and expiration handling |

---

## 4. Implementation Details

### A. Encrypted Session State Persistence
An `UploadSessionState` data class stores:
- `sessionUri`: The Google Drive resumable upload endpoint.
- `versionEpoch`: The epoch timestamp of the snapshot.
- `vaultFilePath`: Absolute path of the encrypted `.vault` file on disk.
- `expectedFileSize`: Total size of the encrypted vault.
- `lastConfirmedOffset`: Last confirmed byte offset reported by Google Drive.
- `updatedAt`: Timestamp of last successful chunk dispatch.
- `existingFileId`: Optional remote file ID if updating an existing backup.

All values are encrypted using AES-256-GCM via Android's `EncryptedSharedPreferences` through `SecureStorage`.

### B. Anti-SSRF and Security Controls
Before using any persisted `sessionUri`:
1. Scheme must strictly equal `https`.
2. Host must strictly equal `www.googleapis.com`.
3. Path must strictly start with `/upload/drive/v3/files`.
Any malformed, non-HTTPS, or third-party URIs are rejected immediately, protecting against SSRF and intent injection attacks.

### C. Process-Death & Reboot Recovery Flow
1. Upon `createAndUploadBackup()` or `performSilentBackup()` execution:
2. The repository checks `secureStorage.getActiveUploadSession()`.
3. If an active session exists and is valid:
   - Verifies that `File(session.vaultFilePath)` exists and its size equals `session.expectedFileSize`.
   - Sends an empty `Content-Range: bytes */totalBytes` probe to Google Drive.
   - If Google Drive returns `308 Resume Incomplete` with `Range: bytes=0-X`, the upload resumes from byte `X + 1`.
   - If Google Drive returns `404` or `410` (session expired), the repository catches `UploadSessionExpiredException`, safely clears session state, and falls back to a fresh snapshot.
4. After each successful 256 KB chunk upload, `onChunkUploaded(confirmedOffset)` updates the stored offset.
5. `finally` block only deletes `vaultFile` if `getActiveUploadSession() == null` (i.e. upload succeeded or session was cleared), ensuring interrupted uploads remain recoverable on disk.

---

## 5. Test Verification & Results

- **Unit Test File:** [`DriveUploadRecoveryUnitTests.kt`](file:///H:/chat_app/app/src/test/java/com/nexa/app/DriveUploadRecoveryUnitTests.kt)
- **Tests Executed:**
  1. `testValidGoogleDriveSessionUriAllowed`: **PASS**. Verifies standard and update Google Drive resumable URIs are accepted.
  2. `testMalformedOrSuspiciousSessionUriRejected`: **PASS**. Verifies HTTP, attacker domains, subdomain spoofing, and invalid paths are rejected.
  3. `testUploadSessionStateValidation`: **PASS**. Verifies state validation rules.
  4. `testParseOffsetFromRangeHeader`: **PASS**. Verifies HTTP 308 `Range: bytes=0-X` offset extraction.
  5. `testProcessDeathRecoveryVerification`: **PASS**. Verifies that valid temporary vault files match recorded sizes and recover safely.
  6. `testUploadSessionExpiredExceptionThrownOnServerPurge`: **PASS**. Verifies 404/410 errors trigger clean session clearing.

---

## 6. Security and Data-Integrity Impact
- **Zero Plaintext Leakage:** Uploaded vaults remain AES-256-GCM encrypted; session tokens are protected by Android Keystore.
- **Fail-Closed Cleanup:** Expired or corrupted session files are safely wiped before starting new sessions.
- **No In-Memory-Only Fallback:** Recovery state is persisted directly to disk.

---

## 7. Status & Verdict
- **Status:** **PASS**
- **Residual Limitations:** Google Drive restricts resumable sessions to a rolling 1-week window; sessions older than this will naturally expire and restart cleanly from byte 0.
