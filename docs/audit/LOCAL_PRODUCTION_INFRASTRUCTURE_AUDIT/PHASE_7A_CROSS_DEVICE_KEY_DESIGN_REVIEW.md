# NEXA — Phase 7A: Cross-Device Backup Key Portability & Cryptographic Architecture Review

**Document Version:** 1.0.0  
**Target Subsystem:** Google Drive Zero-Knowledge Backup, SQLCipher 4.5.4, Android KeyStore & Cross-Device Restore  
**Target Codebase:** `H:\chat_app` (Android Client Subsystem)  
**Security Classification:** Cryptographic Architecture & Design Specification  
**Audit Standard:** Zero-Knowledge Key Portability, Defense-in-Depth, Hardware Keystore Isolation  
**Date:** November 2026  
**Status:** **DESIGN REVIEW COMPLETE — AWAITING EXPLICIT USER APPROVAL**

---

## 1. Executive Summary & Problem Formulation

In Phase 7 independent validation, the cross-device backup and restore flow was classified as **FAILED**. While single-device backup and restoration on the originating hardware functions correctly, any attempt to restore an encrypted backup bundle on a second device (Device B) or on Device A following an application reinstallation fails catastrophically with:

```text
net.zetetic.database.sqlcipher.database.SQLiteException: file is not a database (code 26)
```

### 1.1 The Root Cause in Current Implementation

1. **Local Key Isolation:** In [`SecureStorage.kt:50–61`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/security/SecureStorage.kt#L50-L61), the SQLCipher database encryption passphrase ($K_{\text{db}}$) is generated locally via `SecureRandom().nextBytes(ByteArray(32))` and persisted in `EncryptedSharedPreferences`. The master encryption key for `EncryptedSharedPreferences` is backed by Android KeyStore (`_androidx_security_master_key_`) inside the physical device's Trusted Execution Environment (TEE) or StrongBox.
2. **Snapshot Key Inheritance:** In [`VaultBundlePackager.kt:73`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/backup/VaultBundlePackager.kt#L73), the backup snapshot is generated via `rawDatabase.execSQL("VACUUM INTO '${snapshotDb.absolutePath}'")`. In SQLCipher 4, `VACUUM INTO` creates an exact replica database page-by-page, retaining the identical 256-bit AES page encryption key ($K_{\text{db\_DeviceA}}$).
3. **Backup Container Encryption:** In [`BackupCryptoEngine.kt:60–120`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/backup/BackupCryptoEngine.kt#L60-L120), the bundle is encrypted with AES-256-GCM using $K_{\text{vault}}$, derived from the user's 12-word BIP-39 mnemonic phrase via PBKDF2-HMAC-SHA256 (65,536 iterations).
4. **Key Mismatch on Device B:** When Device B downloads and decrypts the outer `.vault` container with the 12-word recovery phrase, the outer container unpacks successfully. However, when [`DriveBackupRepositoryImpl.kt:254`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/repository/DriveBackupRepositoryImpl.kt#L254) invokes `secureStorage.getOrCreateDatabasePassphrase()`, Device B has no record of Device A's passphrase. It generates a brand-new random 32-byte key ($K_{\text{db\_DeviceB}}$). SQLCipher attempts to authenticate page HMACs using $K_{\text{db\_DeviceB}}$ against pages encrypted with $K_{\text{db\_DeviceA}}$, fails authentication, and terminates with SQLite error 26.

This review presents a comprehensive cryptographic assessment of **Option A: In-Vault Symmetric Key Transfer**, evaluates its architectural limitations, analyzes alternative cryptographic designs, and defines the authoritative production remediation design.

---

## 2. NEXA Cryptographic Key Hierarchy & Lifecycle Analysis

To prevent cryptographic conflation, the six distinct cryptographic keys operating within the NEXA client ecosystem are rigorously mapped below:

```
┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│                                 NEXA CLIENT KEY TAXONOMY                                    │
├───────────────────────────────┬───────────────────────────────┬─────────────────────────────┤
│ 1. Android KeyStore MasterKeys│ 2. Local SQLCipher DB Key     │ 3. Backup Encryption Key    │
│    (Hardware TEE / StrongBox) │    (K_db / 32-byte CSPRNG)    │    (K_vault / PBKDF2)       │
├───────────────────────────────┼───────────────────────────────┼─────────────────────────────┤
│ 4. BIP-39 Recovery Credential │ 5. E2EE Identity Keys         │ 6. Device Session Tokens    │
│    (128-bit Entropy / 12 Words│    (Ed25519 & X25519 Pairs)   │    (CSPRNG Auth Bearer)     │
└───────────────────────────────┴───────────────────────────────┴─────────────────────────────┘
```

### Detailed Key Lifecycle Specifications

| Dimension | 1. Android KeyStore MasterKeys | 2. Local SQLCipher DB Passphrase ($K_{\text{db}}$) | 3. Backup Encryption Key ($K_{\text{vault}}$) | 4. BIP-39 Recovery Credential | 5. E2EE Identity Keypairs | 6. Device Session Token |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Primary Code Location** | [`CryptoKeystoreManager.kt:31`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/security/CryptoKeystoreManager.kt#L31), [`SecureStorage.kt:13`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/security/SecureStorage.kt#L13) | [`SecureStorage.kt:50–61`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/security/SecureStorage.kt#L50-L61) | [`BackupCryptoEngine.kt:224`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/backup/BackupCryptoEngine.kt#L224) | [`Bip39PassphraseHelper.kt:18`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/backup/Bip39PassphraseHelper.kt#L18) | [`CryptoKeystoreManager.kt:80`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/security/CryptoKeystoreManager.kt#L80), `159` | [`SecureStorage.kt:30`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/security/SecureStorage.kt#L30) |
| **Algorithm / Scheme** | AES-256-GCM / Keymaster Spec | AES-256-CBC with HMAC-SHA512 (SQLCipher 4 default) | AES-256-GCM (128-bit tag) | 12 BIP-39 English words | Ed25519 (Signing), X25519 (ECDH) | Bearer string `nexa_sess_<uuid>` |
| **Generation** | AndroidKeyStore hardware provider | `SecureRandom().nextBytes(32)` | PBKDF2-HMAC-SHA256 (65,536 iterations) | `SecureRandom().nextBytes(16)` + 4-bit SHA-256 checksum | RFC 8032 curve point mult; Tink X25519 | Cryptographically secure random UUID |
| **Entropy Source** | Hardware TRNG (TEE / Secure Element) | Linux `/dev/urandom` via CSPRNG | 128-bit mnemonic + 16-byte random salt | 128 bits OS CSPRNG entropy | 256-bit OS CSPRNG seeds | 128-bit OS CSPRNG entropy |
| **Storage Location** | Hardware TEE / StrongBox KeyStore | `EncryptedSharedPreferences` (`key_db_passphrase`) | Ephemeral (Volatile RAM only) | User paper/memory; cached in `EncryptedSharedPreferences` | Private keys wrapped by Keystore in prefs | `EncryptedSharedPreferences` (`key_auth_token`) |
| **Wrapping Method** | Non-exportable hardware root key | Wrapped by Keystore MasterKey (AES-256-SIV / GCM) | Not stored; generated dynamically | Wrapped by Keystore MasterKey in `SecureStorage` | Wrapped by `nexa_master_vault_key` (AES-256-GCM) | Wrapped by Keystore MasterKey in `SecureStorage` |
| **Access Permissions** | App UID (`com.nexa.app`) via KeyStore SPI | App process runtime memory | App backup/restore coroutine only | User display during onboarding; backup worker | Signing / DH operations via memory closure | Network transport client |
| **Device Binding** | **100% Hardware-Bound** (Cannot leave device) | **Previously device-bound** (Root cause of failure) | **Portable** (Bound to user mnemonic) | **Portable** (User-held credential) | **100% Device-Bound** (Signal-style per-device keys) | Device-bound (Bound to `dev_<uuid>`) |
| **Rotation Behavior** | Kept for application lifetime | None currently implemented | Rotates every backup (New salt + IV per backup) | User re-generation supported | Prekeys rotate; identity keys persist per device | 30-day server TTL; revocable via API |
| **Recovery Mechanism** | Non-recoverable (Destroyed on uninstall) | **BROKEN IN CURRENT CODE** | Re-derived via 12-word mnemonic entry | User offline physical backup | Newly registered per device on Device B | Re-authenticated via Reverse SMS / Google |
| **Destruction Conditions** | App uninstall / Factory reset | App uninstall / Data wipe | Process death / GC cycle | Account deletion / Data wipe | App uninstall / Key revocation | Explicit logout / Device revocation |

---

## 3. Rigorous Evaluation of Proposed Option A

Option A as originally proposed in `PHASE_7_CROSS_DEVICE_BACKUP_RESTORE_AUDIT.md` serializes the 32-byte $K_{\text{db\_DeviceA}}$ into `manifest.json` as a hexadecimal string inside the Zip archive, encrypts the entire archive with $K_{\text{vault}}$, and extracts it on Device B into `SecureStorage`.

### 3.1 Ten-Point Architectural Invariant Verification

1. **Allows Device B to restore Device A's encrypted database:**  
   **VERDICT: PASS.** Device B obtains $K_{\text{db\_DeviceA}}$ and can open the snapshot database cleanly.
2. **Preserves local device-specific Keystore protection:**  
   **VERDICT: CONDITIONAL PASS.** Once imported on Device B, the passphrase is saved in Device B's `EncryptedSharedPreferences`, sealed under Device B's hardware `MasterKey`. However, Device A and Device B now share the identical symmetric database key.
3. **Prevents Google Drive from accessing plaintext keys:**  
   **VERDICT: PASS.** The entire archive containing `manifest.json` is encrypted client-side with AES-256-GCM before reaching Google Drive. Google Drive has zero knowledge of the 12-word mnemonic.
4. **Prevents Supabase and Go relay from accessing backup keys:**  
   **VERDICT: PASS.** Backups bypass the application relay entirely; transmission is directly between the Android client and Google Drive's hidden `appDataFolder`.
5. **Prevents unauthorized devices from extracting backup keys:**  
   **VERDICT: PASS.** Decryption requires possession of both the Google OAuth account credential and the 12-word BIP-39 recovery mnemonic (128 bits of entropy + 65,536 PBKDF2 rounds).
6. **Protects against backup replacement and tampering:**  
   **VERDICT: PASS.** Authenticated encryption via AES-256-GCM verifies payload integrity via the 128-bit authentication tag before any bytes are decrypted, supplemented by the 71-byte header SHA-256 checksum.
7. **Supports recovery after application reinstall:**  
   **VERDICT: PASS.** Reinstallation on the same physical device operates identically to Device B.
8. **Supports recovery when original device is permanently lost/stolen:**  
   **VERDICT: PASS.** Restoration depends strictly on the cloud vault and the user's 12-word mnemonic.
9. **Supports backup key rotation:**  
   **VERDICT: FAIL.** Option A provides no mechanism to re-key the database or rotate $K_{\text{db}}$.
10. **Prevents accidental permanent loss of historical backups:**  
    **VERDICT: CONDITIONAL PASS.** Non-destructive atomic swap preserves `.rollback_bak` during swap.

### 3.2 Critical Cryptographic Deficiencies of Raw Option A

While Option A solves the immediate restoration crash, a comprehensive security analysis reveals **three major vulnerabilities**:

```
CRITICAL DEFICIENCY IN RAW OPTION A:
┌──────────────────────────────────────────────────────────────────────────────┐
│  Zip Archive Unpacked to App Cache:                                          │
│  cacheDir/restore_staging_1731234567/manifest.json                           │
│  ┌────────────────────────────────────────────────────────────────────────┐  │
│  │ {                                                                      │  │
│  │   "database_sha256": "8a3f...",                                        │  │
│  │   "database_passphrase_hex": "b71e84a92c019d...<RAW 32-BYTE ROOT KEY>"│  │
│  │ }                                                                      │  │
│  └────────────────────────────────────────────────────────────────────────┘  │
│  ▲                                                                           │
│  └─ UNENCRYPTED ROOT KEY LEAKED TO PHYSICAL FLASH STORAGE DURING UNZIP!       │
└──────────────────────────────────────────────────────────────────────────────┘
```

1. **Flash Storage Exposure Vulnerability:** In `VaultBundlePackager.unpackAndRestoreBundle`, the zip archive is extracted onto disk inside `stagingDir` in `cacheDir`. Under Option A, `manifest.json` containing the unencrypted 32-byte master database passphrase sits as plaintext JSON on physical flash storage until `stagingDir.deleteRecursively()` completes. If a crash, out-of-memory kill, or sudden battery disconnect occurs during extraction, the unencrypted database key remains persisted on flash memory.
2. **Architectural Entanglement (Violating Separation of Concerns):** `manifest.json` is designed for human/application metadata (message count, conversation count, schema version). Storing raw cryptographic root secrets inside application metadata violates fundamental cryptographic encapsulation.
3. **No Key Rekeying or Device Isolation:** Under Option A, Device B adopts Device A's local database key forever. If Device A was stolen, Device B's local database remains encrypted with the compromised device's historical key.

---

## 4. Comprehensive Design Alternatives Comparison

Three architectural alternatives were rigorously evaluated to address cross-device restore:

```
┌────────────────────────────────────────────────────────────────────────────────────────────────┐
│ DESIGN A: In-Vault Symmetric Key Transfer (Baseline)                                           │
│ VACUUM INTO -> DB Key in manifest.json -> Zip -> Encrypt with K_vault -> Decrypt on Device B   │
├────────────────────────────────────────────────────────────────────────────────────────────────┤
│ DESIGN B: Plaintext Snapshot Export & Rekey in Transit                                         │
│ sqlcipher_export to plaintext SQLite -> Encrypt in AES-GCM stream -> Re-encrypt on Device B    │
├────────────────────────────────────────────────────────────────────────────────────────────────┤
│ DESIGN C: Dual-Layer Cryptographic Key Envelope & Zero-Disk RAM Staging (Recommended)         │
│ VACUUM INTO -> DB Key sealed in binary Envelope via HKDF -> In-Memory Extract -> Optional Rekey│
└────────────────────────────────────────────────────────────────────────────────────────────────┘
```

### Architectural Comparison Matrix

| Evaluation Dimension | Option A: In-Vault Manifest Transfer | Option B: Plaintext Snapshot Export & Rekey | Option C: Dual-Layer Cryptographic Key Envelope (Recommended) |
| :--- | :--- | :--- | :--- |
| **Cryptographic Hygiene** | 🔴 Low (Root key in plain JSON) | 🟡 Medium (Plaintext DB in transit) | 🟢 **Superior** (Key wrapped with HKDF + AES-GCM; zero plain text) |
| **Disk Key Exposure** | 🔴 High (Key written to flash in cache) | 🔴 High (Entire unencrypted DB on flash) | 🟢 **Zero** (Extracted and imported strictly in RAM) |
| **Performance Overhead** | 🟢 **Fastest** (Zero re-encryption pass) | 🔴 Slow (2x I/O: Export + Import passes) | 🟢 **Fast** (Instant RAM unwrap; zero DB re-write overhead) |
| **Flash Memory Wear & RAM** | 🟢 Minimal (64 KB stream buffer) | 🔴 Severe (Requires 2x DB size free) | 🟢 Minimal (Envelope is 64 bytes in RAM) |
| **Key Rekeying Capability** | 🔴 None (Device B inherits Device A key)| 🟢 Full (Device B creates fresh key) | 🟢 **Full** (Device B can optionally rekey via `PRAGMA rekey`) |
| **Crash Safety** | 🟡 Risk of key residue on disk crash | 🔴 Severe risk of unencrypted DB residue | 🟢 **Maximum** (Zero key or unencrypted DB on disk) |
| **Format Versioning** | 🔴 Brittle (Ad-hoc JSON key addition) | 🟡 Moderate (Different zip layout) | 🟢 **Rigid** (Formal Envelope Magic `NXKE` + v2 Header) |
| **v1 Backward Compatibility** | 🔴 Fails on v1 backups without warning | 🔴 Incompatible with v1 backups | 🟢 **Graceful** (v1 fallback detection with clear UX diagnostic) |
| **Implementation Complexity** | 🟢 Low (2 files, ~40 lines) | 🔴 High (Complex `sqlcipher_export` hooks)| 🟡 **Medium** (~120 lines across crypto and packager) |

---

## 5. Architectural Recommendation: Adopt Design C (Dual-Layer Cryptographic Key Envelope)

We **REJECT raw Option A** due to the unacceptable security risk of writing unencrypted database root keys to flash storage during archive unpacking.

We **RECOMMEND DESIGN C (Dual-Layer Cryptographic Key Envelope & Zero-Disk RAM Staging)**.

### How Design C Operates:

1. **Domain-Separated Key Derivation (HKDF):**  
   From the primary recovery key $K_{\text{vault}}$, a dedicated Key Envelope Key ($K_{\text{env}}$) is derived using HKDF-SHA256 with strict domain separation:
   $$K_{\text{env}} = \text{HKDF-SHA256}(K_{\text{vault}}, \text{info} = \text{"NEXA_V2_BACKUP_KEY_ENVELOPE"}, \text{salt} = \text{header.salt})$$
2. **Binary Key Envelope (`key_envelope.bin`):**  
   The 32-byte $K_{\text{db\_DeviceA}}$ is encrypted using $K_{\text{env}}$ via AES-256-GCM with a fresh 12-byte IV, producing a 64-byte sealed binary envelope:
   $$\text{Envelope} = \text{Magic(4B)} \parallel \text{Version(2B)} \parallel \text{IV(12B)} \parallel \text{Ciphertext(32B)} \parallel \text{Tag(16B)}$$
3. **Zero-Disk In-Memory Extraction:**  
   During restoration on Device B, `VaultBundlePackager` intercepts `key_envelope.bin` directly from the `ZipInputStream` in memory without ever writing it to the filesystem. The envelope is decrypted in RAM, and the resulting 32-byte key is stored directly into Device B's `SecureStorage` (sealed under Device B's hardware `MasterKey`).
4. **Immediate Memory Sanitization:**  
   All intermediate byte arrays (`rawPassphrase`, `K_env`) are wiped with `Arrays.fill(bytes, 0.toByte())` immediately after storage.
5. **Sanitized Manifest:**  
   `manifest.json` remains strictly dedicated to non-sensitive backup statistics (message count, conversation count, database SHA-256) and contains zero cryptographic keys.

---

## 6. Verification and Approval Status

This review establishes that **Design C** satisfies all zero-knowledge requirements, eliminates on-disk key exposure, maintains high-performance snapshotting, and ensures reliable cross-device restoration.

**STATUS: DESIGN COMPLETE. IMPLEMENTATION MUST NOT PROCEED UNTIL USER APPROVES.**
