# NEXA P2-B Reliability Report: HTTP Range-Based Google Drive Restore Download Resume

## 1. Original Finding
In previous audits (`NEXA_RESTORE_NETWORK_RESILIENCE_AUDIT.md`), `DriveAppDataService.downloadVault()` performed a monolithic HTTP `GET` request without HTTP Range support (`Range: bytes=X-`). If a user was downloading a large backup vault (e.g. 50 MB) on an unstable cellular network and the connection dropped at 95%, the transfer terminated and the incomplete file had to restart from byte zero on the next restore attempt.

---

## 2. Verified Root Cause
1. **Missing HTTP Range Headers:** `DriveAppDataService.downloadVault()` constructed an OkHttp request without `Range` or `If-Match` headers.
2. **Non-Appending File Stream:** The method opened `FileOutputStream(destinationFile)` without append mode, overwriting any previously downloaded bytes.
3. **Absence of Staging File Partitioning:** Incomplete downloads were written directly to `destinationFile`, making it difficult to distinguish an in-flight partial download from a completed vault without re-checking length.

---

## 3. Source Files Inspected & Modified

| File | Status | Description of Changes |
|---|---|---|
| `app/src/main/java/com/nexa/app/data/drive/DriveAppDataService.kt` | **MODIFIED** | Implemented HTTP Range requests (`bytes=X-`), Content-Range validation, HTTP 206 append handling, HTTP 200 safe reset/truncation, 416 recovery, and ETag change protection |
| `app/src/main/java/com/nexa/app/data/repository/DriveBackupRepositoryImpl.kt` | **MODIFIED** | Passed `expectedTotalBytes` to `downloadVault()`, verified exact file size before staging, and retained partial files on transient drop |
| `app/src/test/java/com/nexa/app/DriveRestoreDownloadResumeUnitTests.kt` | **NEW** | 6 unit tests verifying Range headers, Content-Range parsing, 206 vs 200 handling, ETag change detection, 416 handling, and fail-closed staging |

---

## 4. Implementation Details

### A. HTTP Range Download Protocol
When `downloadVault(fileId, destinationFile, expectedTotalBytes, expectedEtag, onProgress)` is called:
1. Target file is isolated in a temporary staging file: `${destinationFile.name}.part`.
2. If `.part` file exists with size `currentBytes > 0`:
   - Adds request header: `Range: bytes=$currentBytes-`.
   - Adds `If-Match: $expectedEtag` if an ETag is present.
3. If no partial file exists, sends a standard `GET`.

### B. Strict Response Code & Header Validation
- **HTTP 206 Partial Content:**
  - Extracts and parses `Content-Range: bytes START-END/TOTAL`.
  - Confirms that `START == partFile.length()`.
  - Appends bytes using `FileOutputStream(partFile, true)`.
  - If `START != partFile.length()`, truncates `.part` file and restarts from byte zero to prevent file corruption.
- **HTTP 200 OK:**
  - Triggered if Google Drive does not honor Range or if remote content was refreshed.
  - Automatically truncates `.part` file (`FileOutputStream(partFile, false)`).
  - Never appends a 200 response to an existing partial file.
- **HTTP 416 Range Not Satisfiable:**
  - Triggered if local `.part` file exceeds remote total size.
  - Deletes `.part` file and re-issues fresh request from byte zero.
- **ETag Validation:**
  - If the returned `ETag` differs from `expectedEtag`, the remote file was updated during the download session.
  - Deletes `.part` file to prevent combining bytes from two different backup versions.

### C. Fail-Closed Staging Architecture
The 3-stage gatekeeper remains strictly enforced:
1. Incomplete downloads live strictly as `.part` files in `cacheDir`.
2. When download reaches 100% and matches `totalBytes`, it is atomically promoted to `destinationFile`.
3. Decryption into `nx_restored_<ts>.db` verifies AEAD authentication tag and SQLite schema integrity.
4. Only upon 100% verification is `NexaDatabase.closeAndResetInstance()` called and live DB swapped.
If download or restore is interrupted at any point, the live database is completely untouched.

---

## 5. Test Verification & Results

- **Unit Test File:** [`DriveRestoreDownloadResumeUnitTests.kt`](file:///H:/chat_app/app/src/test/java/com/nexa/app/DriveRestoreDownloadResumeUnitTests.kt)
- **Tests Executed:**
  1. `testRangeHeaderGenerationForPartialFile`: **PASS**. Verifies `bytes=1048576-` header syntax.
  2. `testContentRangeParsingAndValidation`: **PASS**. Verifies start/end/total parsing and offset matching.
  3. `testSafeRestartOnHttp200InsteadOfHttp206`: **PASS**. Verifies HTTP 200 wipes partial file and restarts from byte zero.
  4. `testEtagMismatchRejectsAppendingToPreventMultiVersionCorruption`: **PASS**. Verifies modified remote files force fresh downloads.
  5. `testHttp416RangeNotSatisfiableHandling`: **PASS**. Verifies 416 error triggers clean reset.
  6. `testFailClosedStagingPromotionOnlyOnFullSizeMatch`: **PASS**. Verifies `.part` file is promoted only when 100% of bytes are written.

---

## 6. Security and Data-Integrity Impact
- **Anti-Corruption Guarantee:** Multi-version byte concatenation is impossible due to ETag and start-byte checks.
- **Zero Live DB Exposure:** Staging isolation guarantees incomplete downloads never overwrite active user conversations.
- **Fail-Closed Verification:** AEAD tags prevent corrupt or tampered downloads from activating.

---

## 7. Status & Verdict
- **Status:** **PASS**
- **Residual Limitations:** Google Drive media downloads honor standard byte ranges; if a user's OAuth2 session expires during download, `downloadVault()` invalidates the token and requires authentication refresh before resuming.
