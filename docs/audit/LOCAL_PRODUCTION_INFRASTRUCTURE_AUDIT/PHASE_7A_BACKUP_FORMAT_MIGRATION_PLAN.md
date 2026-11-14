# NEXA — Phase 7A: Backup Format Specification & Safe Version Migration Plan

**Document Version:** 1.0.0  
**Target Subsystem:** Backup Vault Format (v1 to v2 Migration), Key Envelope Protocol, Atomic Migration Recovery  
**Target Codebase:** `H:\chat_app` (Android Client Subsystem)  
**Security Classification:** Cryptographic Wire Format & Protocol Migration Specification  
**Audit Standard:** Zero-Risk Migration Invariant, Backward Compatibility, Crash Resilience  
**Date:** November 2026  
**Status:** **DESIGN SPECIFICATION — AWAITING EXPLICIT USER APPROVAL**

---

## 1. Objective & Invariable Constraints

This document defines the exact binary wire layout, cryptographic envelope structure, integrity verification pipeline, and backward-compatible migration plan for transitioning NEXA's cloud backup format from **Version 1 (Single-Layer Container)** to **Version 2 (Dual-Layer Key Envelope)**.

### Mandatory Migration Rules:
1. **Never Overwrite Without Recovery Path:** A pre-existing valid cloud backup on Google Drive must NEVER be replaced or overwritten during format migration unless the local client has successfully created, encrypted, and verified the integrity of the new v2 backup container.
2. **Old Backup Compatibility:** Clients running the v2 engine must seamlessly detect and process v1 backups.
3. **Fail-Closed on Envelope Corruption:** If a key envelope fails cryptographic authentication or checksum verification, the restoration must fail immediately without touching the live local database.
4. **Crash-Resilient Migration:** An interrupted migration (e.g. process death during upload) must leave the previous cloud backup fully intact and recoverable.

---

## 2. Backup Format Specifications: Version 1 vs Version 2

### 2.1 Version 1 Wire Layout (Legacy Format)

Version 1 uses a 71-byte rigid binary header prepended to an AES-256-GCM encrypted zip archive:

```text
┌──────────────┬──────────────┬──────────────┬──────────────┬────────────────────────┬─────────────────────────┐
│ Magic Header │ Version ID   │  Salt Bytes  │ Nonce / IV   │ Payload SHA-256 Hash   │ Encrypted + Compressed  │
│  "NXVAULT"   │  (4 Bytes)   │  (16 Bytes)  │  (12 Bytes)  │ (32 Bytes)             │ Multi-Pillar Zip Bundle │
│  (7 Bytes)   │  (Int: 1)    │              │              │                        │ (Variable Length)       │
└──────────────┴──────────────┴──────────────┴──────────────┴────────────────────────┴─────────────────────────┘
```

#### Defect in v1 Bundle Contents:
```text
bundle.zip
├── manifest.json                  <-- Contains counts & DB SHA-256 (NO DATABASE KEY)
├── databases/
│   └── nexa_master_snapshot.db    <-- Encrypted with local Device A passphrase
├── preferences/...
└── media_vault/...
```
Because `manifest.json` in v1 does not contain $K_{\text{db\_DeviceA}}$, a v1 backup can only be restored on the exact same physical device that created it (while that device's Keystore remains intact).

---

### 2.2 Version 2 Wire Layout (Dual-Layer Key Envelope Format)

Version 2 introduces a dedicated binary Key Envelope (`key_envelope.bin`) packaged inside the encrypted zip bundle, accompanied by header version increment (`version = 2`):

#### 2.2.1 Outer Vault Header (71 Bytes)
```text
┌──────────────┬──────────────┬──────────────┬──────────────┬────────────────────────┬─────────────────────────┐
│ Magic Header │ Version ID   │  Salt Bytes  │ Nonce / IV   │ Payload SHA-256 Hash   │ Encrypted + Compressed  │
│  "NXVAULT"   │  (4 Bytes)   │  (16 Bytes)  │  (12 Bytes)  │ (32 Bytes)             │ V2 Zip Archive Payload  │
│  (7 Bytes)   │  (Int: 2)    │              │              │                        │ (Variable Length)       │
└──────────────┴──────────────┴──────────────┴──────────────┴────────────────────────┴─────────────────────────┘
```

#### 2.2.2 Version 2 Bundle Internal Hierarchy
```text
backup_bundle_v2/
├── key_envelope.bin               <-- 66-Byte Binary Sealed Key Envelope (Extracted ONLY in RAM)
├── manifest.json                  <-- Sanitized Application Statistics (ZERO keys)
├── databases/
│   └── nexa_master_snapshot.db    <-- Page-encrypted snapshot (VACUUM INTO)
├── preferences/
│   ├── app_user_settings.json
│   └── custom_chat_configs.json
└── media_vault/
    └── {blake3_hash}.bin
```

---

## 3. Cryptographic Key Envelope (`key_envelope.bin`) Structure

The Key Envelope isolates the database encryption passphrase from general application metadata. It is encrypted under a domain-separated key derived from the user's recovery phrase.

### 3.1 Binary Layout of `key_envelope.bin` (66 Bytes Total)

```text
┌──────────────────────┬──────────────────────┬──────────────────────┬──────────────────────┬──────────────────────┐
│ Envelope Magic       │ Envelope Version     │ Envelope IV / Nonce  │ Ciphertext Payload   │ AES-GCM Auth Tag     │
│ "NXKE" (ASCII)       │ (Short: 2)           │ (12 Bytes CSPRNG)    │ (32 Bytes Encrypted  │ (16 Bytes Tag)       │
│ (4 Bytes)            │ (2 Bytes)            │                      │  DB Passphrase)      │                      │
└──────────────────────┴──────────────────────┴──────────────────────┴──────────────────────┴──────────────────────┘
Bytes: 0..3             4..5                   6..17                  18..49                 50..65
```

### 3.2 Envelope Key Derivation & Wrapping Pipeline

```
User 12-Word BIP-39 Mnemonic
           │
           ▼
[ PBKDF2-HMAC-SHA256 (65,536 iterations, Vault Salt) ]
           │
           ▼
     K_vault (256 Bits) ───► [ Outer Container AES-256-GCM Encryption ]
           │
           ▼
[ HKDF-Expand-SHA256 ]
  - PRK: K_vault
  - Info: "NEXA_V2_KEY_ENVELOPE_KEY" (Domain Separation String)
  - Length: 32 Bytes
           │
           ▼
     K_envelope (256 Bits)
           │
           ▼
[ AES-256-GCM Encryptor ]
  - Key: K_envelope
  - IV: 12-byte CSPRNG Nonce
  - Plaintext: 32-byte Local Database Passphrase (K_db)
  - AAD (Additional Authenticated Data): "NXKE" || 0x0002
           │
           ▼
Sealed key_envelope.bin (66 Bytes)
```

### 3.3 Security Invariants of the Key Envelope:
1. **Domain Separation:** Even if an attacker obtained $K_{\text{vault}}$, using HKDF with distinct info strings prevents cross-protocol key reuse between container stream encryption and envelope encryption.
2. **Additional Authenticated Data (AAD):** The envelope magic (`NXKE`) and envelope version (`2`) are bound to the AES-GCM authentication tag as AAD. Any tampering with version or magic terminates decryption immediately.
3. **Zero-Disk In-Memory Processing:** When reading the archive on Device B, `VaultBundlePackager` streams `key_envelope.bin` directly into a `ByteArray` in volatile RAM. It is **never** written as a file to `stagingDir` or `cacheDir`.

---

## 4. Manifest Sanitization & Integrity Schema

In Version 2, `manifest.json` is strictly confined to non-sensitive structural and verification metadata.

### 4.1 Schema Definition (`manifest.json`)

```json
{
  "format_version": 2,
  "version_epoch": 1731234567890,
  "database_sha256": "4b2e8a1f7d9c3e5a6b0c2d4e8f1a3b5c7d9e1f3a5b7c9d1e3f5a7b9c1d3e5f7a",
  "total_conversations": 42,
  "total_messages": 1850,
  "media_files_count": 64,
  "media_vault_total_bytes": 142850912,
  "app_version": "1.0.0-beta",
  "schema_version": 20
}
```

### 4.2 Multi-Stage Integrity Verification Pipeline

Before applying any restored data to the live system, the restore pipeline executes five mandatory checks:

```
┌────────────────────────────────────────────────────────────────────────┐
│ Stage 1: Header Checksum Validation                                    │
│ SHA-256(Ciphertext) == Header.payloadSha256                            │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ PASS
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│ Stage 2: Outer AES-256-GCM Container Authentication                    │
│ AES-GCM tag verification over complete archive ciphertext             │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ PASS
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│ Stage 3: In-Memory Key Envelope Authentication                         │
│ Verify "NXKE" AAD + AES-GCM auth tag over K_db ciphertext in RAM       │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ PASS
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│ Stage 4: Snapshot Database SHA-256 Verification                        │
│ SHA-256(extracted nexa_master_snapshot.db) == manifest.database_sha256 │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ PASS
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│ Stage 5: Database Key Pre-Flight Probe                                 │
│ Attempt to open snapshot with recovered K_db: SELECT count(*) FROM chats│
└────────────────────────────────────────────────────────────────────────┘
```

If ANY stage fails, the operation throws an explicit exception, deletes all staging data from cache, and preserves the live database via the atomic rollback journal.

---

## 5. Backward Compatibility & Version Migration Strategy

NEXA clients must handle two distinct real-world situations:
- **Scenario A:** Device A creates a new backup (Upgrades remote cloud backup from v1 to v2).
- **Scenario B:** Device B attempts to restore an existing historical v1 backup created by Phase 6.

### 5.1 Scenario A: Safe Cloud Backup Migration (Device A)

When Device A runs a scheduled or manual backup:
1. Device A queries Google Drive metadata via `driveService.getExistingBackupMetadata()`.
2. Device A reads the cloud version (`appProperties["format_version"]`).
3. If cloud backup is v1 (or does not exist):
   - Device A builds the complete v2 vault bundle locally in `cacheDir` (`stagingDir` -> `bundle.zip` with `key_envelope.bin` -> `nx_backup_v2.vault`).
   - Device A verifies the local v2 vault passes Stages 1, 2, and 3 integrity checks.
   - Device A uploads `nx_backup_v2.vault` via Google Drive Resumable Uploader using `uploadType=resumable`.
   - Google Drive performs atomic commit upon completion of the final chunk.
   - The legacy v1 cloud backup is only superseded once Google Drive confirms `200 OK` with the new file ID.
   - If network disconnects or process dies mid-upload, Google Drive retains the previous v1 backup intact.

### 5.2 Scenario B: Handling Historical Version 1 Backups on Device B

If a user installs NEXA on Device B and their Google Drive contains a historical v1 backup (which lacks `key_envelope.bin`):

```
Device B reads BackupVaultHeader:
- Magic: "NXVAULT"
- Version: 1 (Detected legacy format)
```

1. Device B decrypts the outer container with the 12-word mnemonic.
2. Device B inspects bundle contents for `key_envelope.bin`.
3. If `key_envelope.bin` is ABSENT:
   - Device B inspects its local `SecureStorage` for an existing database passphrase.
   - If Device B is a fresh install (`KEY_DB_PASSPHRASE` was never set on this hardware), Device B cannot open Device A's snapshot.
   - **Fail-Closed UX Diagnostic:** Device B aborts restoration BEFORE touching the live database and displays a clear, actionable diagnostic message:
     ```text
     "This cloud backup was created with Legacy Version 1 on another device and cannot 
     be opened here because it lacks a portable key envelope. 
     Please open NEXA on your original device, perform an updated backup (Version 2), 
     and then retry restoration on this device."
     ```
   - This prevents SQLite crash error 26 and guarantees zero data loss or database corruption.

---

## 6. Interrupted Migration & Crash Recovery Protocol

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                           MIGRATION CRASH MATRIX                                │
├──────────────────────────┬──────────────────────────────────────────────────────┤
│ Interruption Point       │ Recovery Action & State Guarantee                    │
├──────────────────────────┼──────────────────────────────────────────────────────┤
│ 1. Crash during VACUUM   │ Temporary snapshot deleted on restart. Live DB untouched.
│ 2. Crash during pack     │ stagingDir purged on startup. Zero disk bloat.       │
│ 3. Crash during encrypt  │ Incomplete vault file purged from cacheDir.          │
│ 4. Crash during upload   │ Active upload session tracked in SecureStorage.      │
│                          │ Cloud preserves old backup until 100% upload commit. │
│ 5. Crash during restore  │ Startup check restores from .rollback_bak.           │
│                          │ Zero partial database activation.                    │
└──────────────────────────┴──────────────────────────────────────────────────────┘
```

### Automatic Startup Cleanup Hook:
In `NexaApplication.onCreate()` / `NexaDatabase.checkAndRecoverFromRollbackJournal()`:
1. Scan `cacheDir` for orphaned `staging_*` directories and delete them recursively.
2. Scan `cacheDir` for incomplete `.vault` or `.bin` files and delete them.
3. Validate integrity of live database against `SecureStorage` passphrase.

---

## 7. Implementation Checklist (Design Phase)

- [x] Wire layout for v2 header defined (71 bytes, `version = 2`).
- [x] Key envelope binary format specified (66 bytes, magic `NXKE`).
- [x] HKDF domain separation info string specified (`"NEXA_V2_KEY_ENVELOPE_KEY"`).
- [x] Zero-disk RAM extraction architecture defined.
- [x] 5-stage pre-flight integrity verification pipeline mapped.
- [x] Backward-compatibility handler for v1 backups specified.
- [x] Safe cloud replacement rule verified (No overwrite without local integrity pass).

**STATUS: MIGRATION PLAN COMPLETE. AWAITING USER APPROVAL.**
