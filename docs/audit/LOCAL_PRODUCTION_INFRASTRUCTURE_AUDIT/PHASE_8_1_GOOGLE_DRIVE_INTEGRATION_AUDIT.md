# NEXA — Phase 8.1: Google Drive Integration Audit

## 1. Scope & Execution Boundary

This audit evaluates the end-to-end Google Drive integration across authentication, token acquisition, AppData REST communication, resumable chunked streaming, account switching, and cloud lifecycle management.

> [!WARNING]
> **Real Network Environment Limitation:**
> In this audit environment, physical Android hardware, active Google Play Services runtime, and authorized OAuth credentials were **not available**. All real-device network interactions with Google servers are formally classified as **NOT VERIFIED / BLOCKED**. The findings below are derived from static source code tracing, protocol compliance verification, and unit test analysis.

---

## 2. Complete End-to-End Execution Trace

```
[GoogleDriveAuthManager]
  │ (GoogleSignInClient -> intent -> account)
  ├── Scope: https://www.googleapis.com/auth/drive.appdata (LEAST PRIVILEGE)
  └── getAccessToken() -> GoogleAuthUtil.getToken(context, account, "oauth2:...")
       │
       v
[DriveBackupRepositoryImpl]
  │ (Coordinates snapshot, encryption, and upload)
  ├── 1. authManager.isAuthorized() Check
  ├── 2. DriveAppDataService.getExistingBackupMetadata() (Split-brain epoch check)
  ├── 3. VaultBundlePackager.packBundle() (VACUUM INTO + Envelope sealing)
  ├── 4. BackupCryptoEngine.createEncryptedVault() (AES-256-GCM encryption)
  └── 5. DriveAppDataService.startResumableUploadSession()
       │
       v
[DriveResumableUploader]
  │ (Chunks binary payload in 2 MB slices)
  ├── PUT Content-Range: bytes START-END/TOTAL
  ├── On failure: queryUploadOffset() -> Content-Range: bytes */TOTAL
  └── On 404/410: Throw UploadSessionExpiredException -> restart from byte 0
       │
       v
[Google Drive REST API v3]
  └── Files stored in hidden "appDataFolder" (Filename: "nexa_vault_backup.enc")
```

---

## 3. Account Isolation & Multi-Account Security

### 3.1 Account Switching Analysis
- **Source Inspection:** [`GoogleDriveAuthManager.kt:33-40`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/drive/GoogleDriveAuthManager.kt#L33-L40), [`BackupPrefs.kt:40-70`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/local/preferences/BackupPrefs.kt#L40-L70).
- **Behavior:**
  - `GoogleSignIn.getLastSignedInAccount(context)` returns the currently authenticated Google Account.
  - When the user taps "Switch Account" in `BackupSettingsScreen.kt`, `authManager.signOut()` is called, and the Google Sign-In intent is re-launched.
  - In `BackupPrefs`, `googleAccountEmail` is updated to the newly selected email.
- **Finding (P2 - Potential Account Desynchronization):**
  If Account A uploaded a backup with file ID `file_A`, and the user switches to Account B, `backupPrefs.remoteBackupFileId` still stores `file_A` until `checkRemoteBackup()` runs. If the user immediately clicks "Delete Cloud Backup" before scanning, the app attempts to delete `file_A` using Account B's OAuth token. Google Drive API correctly returns HTTP 404 (because `appDataFolder` is strictly isolated per Google account), preventing cross-account deletion, but the local UI briefly displays an error.
  *Remediation Recommendation:* Reset `remoteBackupFileId` to null immediately upon account sign-out or account switch.

### 3.2 Cloud Backup Preservation on Account Disconnection
- **Source Inspection:** [`BackupViewModel.kt:180-195`](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/backup/BackupViewModel.kt#L180-L195).
- **Behavior:**
  When the user taps "Disconnect" in `BackupSettingsScreen.kt`, the ViewModel calls `authManager.signOut()` and clears local cached credentials.
- **Verification:** Disconnecting an account does **NOT** issue a DELETE call to Google Drive. The remote backup remains safely preserved in Google Drive AppData.

---

## 4. OAuth Scope Minimization & Token Handling

| OAuth Aspect | Specification / Implementation | Audit Finding |
|---|---|---|
| **Requested Scope** | `https://www.googleapis.com/auth/drive.appdata` | **Compliant.** NEXA requests zero access to user's general Google Drive, documents, or photos. |
| **Token Invalidation** | `authManager.invalidateToken(token)` | **Compliant.** On receiving HTTP 401, `DriveAppDataService` catches the response and invokes `GoogleAuthUtil.clearToken(context, token)`. |
| **Token Refresh** | Handled via `GoogleAuthUtil.getToken()` | **Compliant.** Play Services automatically issues a refreshed access token on subsequent requests. |
| **Revoked Account Handling** | Throws `IllegalStateException` | **Compliant.** App catches null account and instructs the user to re-link Google Drive. |

---

## 5. Cloud Transport Resilience & Interruption Handling

### 5.1 Resumable Upload Interruption
- **Source Inspection:** [`DriveResumableUploader.kt:50-130`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/drive/DriveResumableUploader.kt#L50-L130).
- **Recovery Logic:**
  - Upload streams slices in 2 MB chunks (`CHUNK_SIZE = 2 * 1024 * 1024`).
  - After each chunk, `onChunkUploaded(confirmedOffset)` updates `secureStorage.updateUploadSessionOffset(confirmedOffset)`.
  - If a network disconnect occurs mid-upload, `queryUploadOffset()` queries Google Drive with `Content-Range: bytes */TOTAL`.
  - The server responds with HTTP 308 Resume Incomplete and `Range: bytes=0-K`.
  - NEXA resumes sending slices starting from byte $K+1$.
- **Session Expiration:**
  If the session URI has expired (HTTP 404 or 410), `DriveResumableUploader` throws `UploadSessionExpiredException`, which triggers `secureStorage.clearActiveUploadSession()`, allowing the next attempt to restart cleanly from byte 0.

### 5.2 Resumable Download Interruption
- **Source Inspection:** [`DriveAppDataService.kt:149-215`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/drive/DriveAppDataService.kt#L149-L215).
- **Recovery Logic:**
  - Download is written to temporary file `${destinationFile.name}.part`.
  - On restart, the request sends `Range: bytes=$currentBytes-`.
  - If server returns HTTP 206 Partial Content, bytes are appended to `.part`.
  - If server returns HTTP 416 (Range Not Satisfiable), `.part` is deleted and download restarts from byte 0.
  - When `.part` matches `expectedTotalBytes`, it is atomically renamed to `destinationFile`.

---

## 6. Real Integration Verification Verdict

| Verification Item | Status | Justification |
|---|---|---|
| **Code Structure & REST Protocol** | **VERIFIED** | Clean OkHttp implementation conforming to Google Drive v3 REST API specifications. |
| **OAuth Least-Privilege Scope** | **VERIFIED** | Restricted strictly to `drive.appdata`. |
| **Real Google Drive Network Execution** | **NOT VERIFIED / BLOCKED** | No Google account credentials or physical Android device present in audit sandbox. |
| **Google Drive 15GB Quota Exhaustion** | **UNVERIFIED AT RUNTIME** | Handled in code (HTTP 403 catch), but unverified against live Google API. |
