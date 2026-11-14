# NEXA — Phase 7: Cross-Device Backup & Restore Cryptographic Audit & Migration Design

**Document Version:** 1.0.0  
**Target Subsystem:** Google Drive Zero-Knowledge Backup, SQLCipher 4 & Cross-Device Restore  
**Target Codebase:** `H:\chat_app` (Android Application)  
**Evaluation Standard:** Zero-Knowledge Cryptographic Portability Invariant  
**Audit Date:** November 2026  
**Status:** **PROPOSED ARCHITECTURAL DESIGN — AWAITING EXPLICIT USER APPROVAL**

---

## 1. Executive Summary & Root Cause Analysis

A critical defect exists in NEXA's backup and restore subsystem that prevents a user from restoring their backup on a new physical device or after a clean reinstallation.

### The Problem in Current Source Code

1. **Local SQLCipher Key Generation:**  
   In [`SecureStorage.kt:50–61`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/security/SecureStorage.kt#L50-L61), `getOrCreateDatabasePassphrase()` generates a 32-byte cryptographically secure random byte array:
   ```kotlin
   val newPassphrase = ByteArray(32)
   SecureRandom().nextBytes(newPassphrase)
   prefs.edit().putString(KEY_DB_PASSPHRASE, hex).apply()
   ```
   This passphrase ($K_{\text{device}}$) is stored in `EncryptedSharedPreferences`, which is encrypted using a MasterKey held exclusively inside the local device's hardware Android Keystore (TEE / StrongBox).

2. **Snapshot Creation via `VACUUM INTO`:**  
   In [`VaultBundlePackager.kt:73`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/backup/VaultBundlePackager.kt#L73), the backup snapshot is created via:
   ```kotlin
   rawDatabase.execSQL("VACUUM INTO '${snapshotDb.absolutePath}'")
   ```
   In SQLCipher 4 (`net.zetetic:sqlcipher-android`), `VACUUM INTO` creates an exact replica database that **inherits the encryption key of the parent database** ($K_{\text{Device A}}$).

3. **Backup Container Encryption:**  
   In [`BackupCryptoEngine.kt:60–120`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/backup/BackupCryptoEngine.kt#L60-L120), the entire multi-pillar bundle (containing `nexa_master_snapshot.db`, preferences, and media files) is zipped and encrypted with AES-256-GCM using a key ($K_{\text{vault}}$) derived from the user's 12-word BIP-39 mnemonic via PBKDF2-HMAC-SHA256 (65,536 iterations).

4. **The Restoration Failure on Device B:**  
   When the user acquires Device B (or reinstalls the app on Device A):
   - Device B prompts for the 12-word recovery phrase.
   - `BackupCryptoEngine.decryptAndRestoreVault` derives $K_{\text{vault}}$ from the 12-word phrase and **successfully decrypts** the outer AES-256-GCM envelope.
   - `VaultBundlePackager` extracts `nexa_master_snapshot.db` (which is still internally encrypted with $K_{\text{Device A}}$).
   - In [`DriveBackupRepositoryImpl.kt:254`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/repository/DriveBackupRepositoryImpl.kt#L254), the app calls:
     ```kotlin
     val dbPassphrase = secureStorage.getOrCreateDatabasePassphrase()
     database = NexaDatabase.getInstance(context, dbPassphrase)
     ```
   - Because Device B is a fresh install, `getOrCreateDatabasePassphrase()` generates a **brand-new random 32-byte key** ($K_{\text{Device B}}$).
   - `NexaDatabase.getInstance` attempts to open the database using $K_{\text{Device B}}$.
   - SQLCipher cannot authenticate the page HMACs because $K_{\text{Device B}} \neq K_{\text{Device A}}$.
   - SQLCipher throws:  
     `net.zetetic.database.sqlcipher.database.SQLiteException: file is not a database (code 26)`
   - The restoration **100% fails**, leaving Device B unable to restore user messages.

---

## 2. Security Boundaries & Mandatory Requirements

Any remediation MUST uphold the following inviolable security invariants:

1. **Zero-Knowledge Cloud Invariant:** Google Drive, Cloudflare, Supabase, and the Go relay must NEVER receive or be able to derive the database encryption key, the 12-word recovery phrase, or plaintext database contents.
2. **Keystore Isolation:** Device A's Android Keystore private keys must NEVER be exported or transferred over any network. Device B must generate its own hardware Keystore keys.
3. **Local Encryption at Rest:** The SQLite database stored on device storage must always remain encrypted with a 256-bit key.
4. **No Shared Static Keys:** Hardcoding an application-wide static key or using a trivial password is strictly prohibited.
5. **Atomic Safety:** An interrupted restore must never destroy the existing local database.

---

## 3. Comparison of Architectural Solutions

Two architectural solutions were evaluated:

### Solution Comparison Matrix

| Evaluation Dimension | Option A: In-Vault Symmetric Key Transfer | Option B: Plaintext Snapshot Export & Rekey |
| :--- | :--- | :--- |
| **Mechanics** | Include the 32-byte $K_{\text{Device A}}$ inside the AES-256-GCM encrypted vault bundle (in `manifest.json`). On Device B, import $K_{\text{Device A}}$ into Device B's local `SecureStorage`. | Export an unencrypted SQLite snapshot into the vault container (`ATTACH ... AS plaintext KEY ''; SELECT sqlcipher_export(...)`). On Device B, import and re-encrypt with $K_{\text{Device B}}$. |
| **Zero-Knowledge Security** | 🟢 **100% Secure:** $K_{\text{Device A}}$ is protected by the user's 12-word BIP-39 mnemonic (PBKDF2 65,536 rounds + AES-256-GCM). Google Drive only sees ciphertext. | 🟢 **100% Secure:** The plaintext snapshot is contained entirely within the AES-256-GCM vault. Google Drive only sees ciphertext. |
| **Performance & Speed** | 🟢 **Instant:** Zero re-encryption pass needed. `VACUUM INTO` clones directly in one step. | 🔴 **Slow (2x I/O):** Requires a full database export pass during backup AND a full re-encryption pass during restore. |
| **Disk Space Overhead** | 🟢 **Zero extra overhead:** Uses existing snapshot pipeline. | 🔴 **High:** Requires temporary space for both unencrypted and encrypted database copies simultaneously. |
| **Implementation Risk** | 🟢 **Low:** Pure metadata extension in `VaultBundlePackager` and `SecureStorage`. | 🔴 **High:** `sqlcipher_export` with schema transitions can corrupt complex multi-table constraints or fail on partial WAL states. |
| **Backward Compatibility**| 🟢 **Full:** Existing single-device restores continue working identically. | 🟡 **Partial:** Requires handling different container schemas. |

---

## 4. Proposed Design: Option A (In-Vault Key Transfer)

### 4.1 Backup Flow (Device A)

```
┌────────────────────────────────────────────────────────────────────────┐
│ Device A: Backup Packaging                                             │
└────────────────────────────────────────────────────────────────────────┘
  1. Local Database is encrypted with K_deviceA (stored in Keystore prefs).
  2. VACUUM INTO 'nexa_master_snapshot.db' (inherits K_deviceA encryption).
  3. VaultBundlePackager adds K_deviceA to manifest.json:
     {
       "version_epoch": 1731234567890,
       "database_sha256": "...",
       "database_passphrase_hex": "<Hex-Encoded 32-byte K_deviceA>",
       "total_messages": 1420,
       "total_conversations": 38
     }
  4. Zip container packages [manifest.json, nexa_master_snapshot.db, prefs, media].
  5. BackupCryptoEngine encrypts Zip container with AES-256-GCM using K_vault
     (derived from user's 12-word BIP-39 mnemonic).
  6. Encrypted vault uploaded to Google Drive appDataFolder.
```

### 4.2 Restoration Flow (Device B)

```
┌────────────────────────────────────────────────────────────────────────┐
│ Device B: Restoration & Key Import                                     │
└────────────────────────────────────────────────────────────────────────┘
  1. User enters 12-word BIP-39 mnemonic.
  2. BackupCryptoEngine derives K_vault and decrypts AES-256-GCM container.
  3. VaultBundlePackager unpacks manifest.json and reads "database_passphrase_hex".
  4. Device B saves the recovered passphrase into its local SecureStorage:
     secureStorage.saveDatabasePassphrase(recoveredPassphraseBytes)
     (Now protected under Device B's Android Keystore MasterKey).
  5. Live database is replaced using the safe atomic rollback swap.
  6. NexaDatabase.getInstance(context, recoveredPassphraseBytes) opens the
     database cleanly — page HMACs match perfectly!
  7. Result: 100% successful restoration on Device B!
```

---

## 5. Security & Threat Analysis of Proposed Design

1. **Can Google Drive read $K_{\text{deviceA}}$?**  
   **NO.** The manifest containing $K_{\text{deviceA}}$ is inside the inner Zip archive, which is encrypted client-side with AES-256-GCM *before* transmission to Google Drive. Neither Google nor any network eavesdropper can inspect the manifest without the 12-word mnemonic.
2. **Can a malicious app on Device B steal $K_{\text{deviceA}}$?**  
   **NO.** As soon as the vault is unpacked, $K_{\text{deviceA}}$ is saved directly into `EncryptedSharedPreferences`, which is encrypted using Android Keystore AES-256-GCM MasterKey hardware-bound to Device B. The temporary unzipped manifest in cache is deleted immediately in the `finally` block.
3. **What if the user enters the wrong 12-word recovery phrase?**  
   AES-256-GCM decryption fails immediately with `AEADBadTagException` (authenticated encryption). Zero data or keys are extracted.

---

## 6. Implementation Code Specifications (For Explicit Approval)

### Patch 1: `SecureStorage.kt`
Add helper to import an existing database passphrase:
```kotlin
fun saveDatabasePassphrase(passphrase: ByteArray) {
    prefs.edit().putString(KEY_DB_PASSPHRASE, passphrase.toHex()).apply()
}
```

### Patch 2: `VaultBundlePackager.kt`
Include database passphrase in `packBundle`:
```kotlin
fun packBundle(
    context: Context,
    rawDatabase: SupportSQLiteDatabase,
    dbPassphrase: ByteArray, // Added parameter
    outputArchiveFile: File,
    stagingDir: File,
    onProgress: (phase: String, percent: Float) -> Unit = { _, _ -> }
): BackupManifest {
    // ...
    val manifestObj = JSONObject().apply {
        put("version_epoch", System.currentTimeMillis())
        put("database_sha256", dbSha256)
        put("database_passphrase_hex", dbPassphrase.toHex())
        // ...
    }
}
```

And in `unpackAndRestoreBundle`:
```kotlin
fun unpackAndRestoreBundle(
    context: Context,
    archiveFile: File,
    stagingDir: File,
    liveDatabaseFile: File,
    secureStorage: SecureStorage, // Added parameter
    onProgress: (phase: String, percent: Float) -> Unit = { _, _ -> }
): Boolean {
    // ...
    val manifestObj = JSONObject(manifestFile.readText())
    if (manifestObj.has("database_passphrase_hex")) {
        val passphraseHex = manifestObj.getString("database_passphrase_hex")
        secureStorage.saveDatabasePassphrase(passphraseHex.hexToByteArray())
    }
    // ...
}
```

---

## 7. Approval Gate Request

Per Section 4 of the Phase 7 instructions:
> *"If solving this requires changing the backup cryptographic format, recovery protocol or key hierarchy, first produce a detailed migration and security design. Do not implement the cryptographic redesign until explicitly approved."*

**STATUS: AWAITING EXPLICIT USER APPROVAL TO IMPLEMENT OPTION A.**
