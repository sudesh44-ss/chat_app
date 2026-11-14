# NEXA — Phase 7A: Implementation Approval Request & Verification Runbook

**Document Version:** 1.0.0  
**Target Subsystem:** Cross-Device Backup & Restore Cryptographic Engine  
**Target Codebase:** `H:\chat_app` (Android Application)  
**Security Classification:** Cryptographic Implementation Approval Gate  
**Date:** November 2026  
**Status:** **PENDING EXPLICIT USER DECISION**

---

## 1. Context & Purpose

In accordance with Phase 7 Section 4 and Phase 7A guidelines, **no cryptographic source code changes, SQLCipher alterations, or key hierarchy modifications may be committed to the codebase without an independent security design review and explicit user approval**.

This document summarizes the cryptographic review, details the exact code patches required under **Design C (Dual-Layer Cryptographic Key Envelope)**, presents the test verification runbook, and formally requests user determination.

---

## 2. Architecture Comparison: Current vs Option A vs Design C

```
┌──────────────────────────────────────────────────────────────────────────────────────────────────┐
│ CURRENT FAILED IMPLEMENTATION:                                                                   │
│ Device A: DB encrypted with K_dbA (in local Keystore). Snapshot retains K_dbA.                   │
│ Cloud:    Uploads vault. Manifest has NO key.                                                    │
│ Device B: Restores DB snapshot. Generates random K_dbB. Tries to open DB.                        │
│ Result:   CRASH (SQLite code 26: file is not a database). 100% Failure.                          │
├──────────────────────────────────────────────────────────────────────────────────────────────────┤
│ PROPOSED OPTION A (In-Vault Manifest Transfer):                                                  │
│ Device A: DB encrypted with K_dbA. Writes K_dbA into manifest.json. Zips and encrypts.           │
│ Cloud:    Uploads vault. K_dbA sits in manifest.json inside encrypted container.                 │
│ Device B: Unzips archive into cacheDir. Reads K_dbA from manifest.json on flash storage.         │
│ Result:   Restores, BUT LEAKS UNENCRYPTED DB KEY TO FLASH STORAGE IN CACHEDIR DURING UNZIP.      │
├──────────────────────────────────────────────────────────────────────────────────────────────────┤
│ RECOMMENDED DESIGN C (Dual-Layer Cryptographic Key Envelope & Zero-Disk RAM Staging):             │
│ Device A: DB encrypted with K_dbA. Derives K_env via HKDF(K_vault). Wraps K_dbA into             │
│           66-byte binary key_envelope.bin. manifest.json contains ZERO keys.                     │
│ Cloud:    Uploads v2 vault. Two independent AES-256-GCM authentication layers.                   │
│ Device B: Decrypts outer container. Intercepts key_envelope.bin directly from stream into RAM.   │
│           Unwraps K_dbA in RAM. Saves directly into Device B Keystore. Zero key on disk.         │
│ Result:   100% Successful Cross-Device Restore with ZERO Disk Exposure and Full Device Isolation!│
└──────────────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. End-to-End Cryptographic Flow (Design C)

### 3.1 Device A: Packaging & Encryption Flow

```
1. Live Database (Encrypted with K_dbA in Device A Android Keystore)
   │
   ▼
2. VACUUM INTO 'databases/nexa_master_snapshot.db' (Zero-lock consistent replica)
   │
   ▼
3. Compute Checksums & Manifest:
   - database_sha256 = SHA-256(snapshotDb)
   - manifest.json (Contains message counts, conversation counts, database_sha256; ZERO keys)
   │
   ▼
4. Generate Key Envelope (In RAM):
   - User 12-Word Mnemonic + Salt ──► PBKDF2(65,536) ──► K_vault (256-bit)
   - HKDF-Expand(K_vault, "NEXA_V2_KEY_ENVELOPE_KEY") ──► K_env (256-bit)
   - AES-256-GCM-Encrypt(K_env, Nonce, K_dbA, AAD="NXKE\x00\x02") ──► Sealed Envelope (66 Bytes)
   - Write to stagingDir/key_envelope.bin
   │
   ▼
5. Zip staging directory into bundle.zip:
   [ key_envelope.bin, manifest.json, databases/..., preferences/..., media_vault/... ]
   │
   ▼
6. Stream AES-256-GCM Encryption with 71-byte Header (version = 2):
   - Prepend Magic "NXVAULT" + Version 2 + Salt + IV + PayloadSha256
   - Stream cipher output to nx_backup_v2.vault
   │
   ▼
7. Resumable Upload to Google Drive appDataFolder (Safe atomic commit)
```

### 3.2 Device B: Download & Restoration Flow

```
1. Download nx_backup_v2.vault from Google Drive
   │
   ▼
2. Validate 71-byte Header:
   - Verify Magic == "NXVAULT"
   - Read Version ID (v1 vs v2)
   - Verify SHA-256(Ciphertext) == Header.payloadSha256 (Pre-flight sanity check)
   │
   ▼
3. Decrypt Outer Container:
   - User inputs 12-word mnemonic
   - Derive K_vault via PBKDF2-HMAC-SHA256 (65,536 rounds, header.salt)
   - AES-256-GCM Decrypt ciphertext stream (Authenticated by 128-bit tag)
   │
   ▼
4. In-Memory Zip Stream Processing:
   - As ZipInputStream reads entries:
     IF entry == "key_envelope.bin":
        Stream directly into byte[] in RAM (NEVER WRITE TO DISK)
        Derive K_env = HKDF(K_vault, "NEXA_V2_KEY_ENVELOPE_KEY")
        Decrypt envelope in RAM -> Recovers K_dbA
        Wipe intermediate byte arrays immediately
        Save recovered K_dbA into Device B SecureStorage (Wrapped by Device B Keystore MasterKey)
     ELSE:
        Extract database snapshot, preferences, and media files to stagingDir
   │
   ▼
5. Integrity Verification:
   - Compute SHA-256 of extracted nexa_master_snapshot.db
   - Verify SHA-256 matches manifest.database_sha256
   │
   ▼
6. Safe Atomic Database Swap:
   - Stage live database to .rollback_bak
   - Swap snapshotDb to liveDatabaseFile
   - Open database with recovered K_dbA: NexaDatabase.getInstance(context, K_dbA)
   - Run verification query: SELECT count(*) FROM chats
   - If query succeeds: delete .rollback_bak. RESTORATION COMPLETE!
   - If query fails: automatic rollback from .rollback_bak. Live DB preserved.
```

---

## 4. Implementation Code Changes (Scope of Proposed Work)

Implementation will touch **strictly 3 files** with **zero database schema changes**:

### File 1: `app/src/main/java/com/nexa/app/core/security/SecureStorage.kt`
- Add `fun saveDatabasePassphrase(passphrase: ByteArray)` to permit storing recovered database passphrases inside Device B's `EncryptedSharedPreferences`.

### File 2: `app/src/main/java/com/nexa/app/core/backup/BackupVaultHeader.kt`
- Increment `CURRENT_VERSION = 2`.
- Update header serialization and deserialization to support version 1 and version 2 interchangeably.

### File 3: `app/src/main/java/com/nexa/app/core/backup/VaultBundlePackager.kt` & `BackupCryptoEngine.kt`
- Implement HKDF-SHA256 key envelope generation during `packBundle`.
- Implement zero-disk RAM extraction of `key_envelope.bin` during `unpackAndRestoreBundle`.
- Retain `manifest.json` as clean metadata without key leakage.
- Add fail-closed diagnostic when encountering v1 backups on new devices.

---

## 5. Comprehensive Test & Verification Plan

Upon user approval, the following test suite will be executed to guarantee 100% verification:

1. **Unit Test: Key Envelope Cryptographic Round-Trip**
   - Pack a 32-byte key into `key_envelope.bin` with mock mnemonic.
   - Decrypt with correct mnemonic -> Verifies identical 32-byte key.
   - Decrypt with wrong mnemonic -> Verifies `AEADBadTagException` thrown; zero key leakage.
   - Tamper with envelope magic or AAD -> Verifies immediate rejection.
2. **Unit Test: Cross-Device Restore Simulation**
   - Device A creates and seeds Room database with 50 messages, 5 contacts, and media files.
   - Device A exports v2 backup with passphrase $K_A$.
   - Simulate Device B: Initialize fresh `SecureStorage` with brand-new Keystore MasterKey and zero prior database.
   - Device B decrypts v2 vault using 12-word phrase.
   - Device B recovers $K_A$, saves to local `SecureStorage`, and opens restored database.
   - **Assertion:** 50 messages, 5 contacts, and media files match 100% without SQLite error 26.
3. **Unit Test: Zero-Disk Staging Verification**
   - Run restore in instrumented environment.
   - Assert that `stagingDir` contains NO file named `key_envelope.bin` or containing hexadecimal keys.
4. **Unit Test: Version 1 Backward Compatibility**
   - Device B attempts to restore a v1 backup.
   - Assert that system fails closed with descriptive `LegacyBackupException` without corrupting live DB.
5. **Full Regression Suite Execution:**
   - Execute all 194 Android unit tests (`./gradlew testDebugUnitTest`).
   - Execute all 125 Go backend tests (`go test ./...`).
   - Assert 100% pass rate.

---

## 6. Official Recommendation & Approval Selection

### Formal Recommendation:
We recommend **APPROVE AN ALTERNATIVE DESIGN: DESIGN C (Dual-Layer Cryptographic Key Envelope & Zero-Disk RAM Staging)**.

### Rationale:
- **Raw Option A** is rejected because writing unencrypted database root keys to flash storage during archive decompression is an unacceptable security hazard in a privacy-first messenger.
- **Design B** is rejected because exporting unencrypted SQLite databases introduces massive I/O overhead, double storage requirements, and catastrophic leak potential on crash.
- **Design C** provides military-grade zero-knowledge security, double-layer authenticated encryption, zero flash storage key exposure, instant performance, and deterministic crash recovery.

---

## 7. Action Required from User

Please respond with one of the following explicit decisions:

1. **`APPROVE DESIGN C`** (Recommended)  
   *Authorizes implementing Design C (Dual-Layer Key Envelope + Zero-Disk RAM Staging) in `SecureStorage.kt`, `BackupVaultHeader.kt`, `VaultBundlePackager.kt`, and `BackupCryptoEngine.kt`, followed by unit test verification.*

2. **`APPROVE OPTION A`**  
   *Authorizes implementing raw Option A (storing passphrase in `manifest.json`).*

3. **`REJECT CURRENT DESIGNS — FURTHER SECURITY WORK REQUIRED`**  
   *Requests alternative design exploration before any implementation.*
