# NEXA — Phase 8.1: ZIP Container & Archive Security Audit

## 1. Audit Scope & Vulnerability Matrix

This audit evaluates the ZIP archive packaging and extraction logic implemented in [`VaultBundlePackager.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/backup/VaultBundlePackager.kt) against industry security standards (OWASP Zip Slip, Zip Bomb Denial-of-Service, Unauthenticated Extraction, and Path Traversal).

---

## 2. Vulnerability Assessment Findings

| Security Control | Implementation Status | Exact Source Reference | Vulnerability Severity |
|---|---|---|---|
| **Path Traversal (Zip Slip)** | **PROTECTED** | [`VaultBundlePackager.kt:261-263`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/backup/VaultBundlePackager.kt#L261-L263) | None |
| **Zip Bomb Decompression Ratio** | **NOT IMPLEMENTED** | [`VaultBundlePackager.kt:268-274`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/backup/VaultBundlePackager.kt#L268-L274) | **P1 (High DoS Risk)** |
| **Max Uncompressed Size Cap** | **NOT IMPLEMENTED** | [`VaultBundlePackager.kt:268-274`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/backup/VaultBundlePackager.kt#L268-L274) | **P1 (Storage Exhaustion)** |
| **Entry Count Cap** | **NOT IMPLEMENTED** | [`VaultBundlePackager.kt:244-278`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/backup/VaultBundlePackager.kt#L244-L278) | **P2 (Inode / File Limit Exhaustion)** |
| **Duplicate Entry Rejection** | **NOT IMPLEMENTED** | [`VaultBundlePackager.kt:268-274`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/backup/VaultBundlePackager.kt#L268-L274) | **P2 (Silent File Overwrite)** |
| **Pre-Extraction Manifest Check** | **NOT IMPLEMENTED** | [`VaultBundlePackager.kt:296-311`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/backup/VaultBundlePackager.kt#L296-L311) | **P2 (Unauthenticated Extraction)** |

---

## 3. Deep-Dive Code Analysis

### 3.1 Zip Slip Protection (VERIFIED SECURE)
- **Code:**
  ```kotlin
  val newFile = File(stagingDir, entry.name)
  if (!newFile.canonicalPath.startsWith(stagingDir.canonicalPath)) {
      throw SecurityException("Zip Slip directory traversal detected in entry: ${entry.name}")
  }
  ```
- **Evaluation:** Canonical path matching prevents relative directory escapes (e.g. `../../../../system/bin` or `../../databases/corrupt.db`). Any path breaking out of `stagingDir` throws an immediate `SecurityException`.
- **Verdict:** **PASS.**

### 3.2 Zip Bomb & Storage Exhaustion (P1 DEFECT)
- **Code:**
  ```kotlin
  FileOutputStream(newFile).use { fos ->
      val buffer = ByteArray(BUFFER_SIZE)
      var read: Int
      while (zis.read(buffer).also { read = it } != -1) {
          fos.write(buffer, 0, read)
      }
  }
  ```
- **Evaluation:**
  `zis.read()` loops indefinitely until the zip stream is exhausted.
  There is:
  1. No check on total bytes extracted across the entire archive.
  2. No check on individual file extraction size.
  3. No compression ratio limit (e.g., abort if uncompressed size exceeds 100x compressed size).
- **Attack Scenario:** A malicious or corrupted 50 KB vault file containing a 10 GB stream of zeroes will write until physical flash storage on the Android device reaches 0 bytes free (`IOException: No space left on device`), causing the app or the Android OS to crash.
- **Verdict:** **FAIL (P1).**

### 3.3 Unauthenticated Extraction before Checksum Verification (P2 DEFECT)
- **Code Trace:**
  - In `VaultBundlePackager.unpackAndRestoreBundle()`:
    - **Step 1 (lines 242-278):** Unzips ALL entries in the archive to `stagingDir` on disk.
    - **Step 2 (lines 281-294):** Unseals key envelope.
    - **Step 3 (lines 296-311):** Checks `manifest.json` and verifies `database_sha256`.
- **Evaluation:**
  Untrusted, unverified files from the decrypted archive are written to disk *before* `manifest.json` is validated or the database checksum is computed. While the AES-256-GCM cipher verifies outer vault ciphertext authenticity, internal bundle tampering (e.g., bundle packed by an adversary with access to the user's recovery phrase) is extracted onto storage before validation occurs.
- **Verdict:** **FAIL (P2).**

### 3.4 Missing Duplicate Entry Detection (P2 DEFECT)
- **Evaluation:**
  If a ZIP file contains two distinct entries with the path `databases/nexa_master_snapshot.db`, the second entry will silently overwrite the first. An attacker could exploit this to bypass manifest checks if the manifest was evaluated against the first entry.
- **Verdict:** **FAIL (P2).**

---

## 4. Remediation Recommendations (For Future Phase)

1. **Enforce Aggregate Uncompressed Cap:** Limit total extracted bytes to 2 GB (or device free space minus 500 MB).
2. **Enforce Entry Count Cap:** Limit total entries to 5,000.
3. **Reject Duplicates:** Maintain a `HashSet<String>` of canonical relative paths during extraction; abort if an entry path is seen twice.
4. **Enforce Compression Ratio Guard:** Abort if uncompressed size exceeds 50x compressed size for any single entry.
