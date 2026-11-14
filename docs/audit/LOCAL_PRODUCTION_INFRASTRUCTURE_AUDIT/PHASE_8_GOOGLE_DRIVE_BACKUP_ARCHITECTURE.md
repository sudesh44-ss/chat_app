# NEXA — Phase 8: Google Drive Backup & Restore Architecture

## 1. Architectural Overview & Zero-Knowledge Invariant

The NEXA Cloud Backup & Restore subsystem provides an end-to-end encrypted (E2EE), zero-knowledge, category-selective persistence mechanism for the NEXA Android client. The architecture bridges local SQLCipher encrypted SQLite databases, application preferences, and high-density media vaults with the user's private Google Drive application data folder (`appDataFolder`).

```
+---------------------------------------------------------------------------------------------------+
|                                          NEXA CLIENT                                              |
|                                                                                                   |
|  [Room / SQLCipher DB]        [User Preferences]             [Media / Attachments]                |
|            |                          |                                |                          |
|            v                          v                                v                          |
|      VACUUM INTO              Export Sanitized              Category Filter & Pack                |
|            |                          |                                |                          |
|            +--------------------------+--------------------------------+                          |
|                                       |                                                           |
|                                       v                                                           |
|                       +--------------------------------+                                          |
|                       |  VaultBundlePackager (Zip)     |                                          |
|                       |  - live_snapshot.db            |                                          |
|                       |  - preferences.json            |                                          |
|                       |  - key_envelope.bin (NXKE v2)  |                                          |
|                       |  - media_vault/                |                                          |
|                       |  - backup_manifest.json (v2)   |                                          |
|                       +--------------------------------+                                          |
|                                       |                                                           |
|                                       v                                                           |
|                       +--------------------------------+                                          |
|                       |  BackupCryptoEngine            |                                          |
|                       |  (Streaming AES-256-GCM)       |                                          |
|                       +--------------------------------+                                          |
|                                       | Encrypted Vault Stream                                    |
+---------------------------------------|-----------------------------------------------------------+
                                        | (HTTPS / OAuth 2.0 Drive AppData)
                                        v
+---------------------------------------------------------------------------------------------------+
|                                 GOOGLE DRIVE (appDataFolder)                                      |
|                                                                                                   |
|   Remote Vault: nexa_vault_backup_<timestamp>.enc                                                 |
|   - 16B Salt + 12B IV + Encrypted Zip Payload + 16B GCM Auth Tag                                  |
|   Zero-Knowledge Guarantee: Google Drive holds only high-entropy ciphertext.                      |
+---------------------------------------------------------------------------------------------------+
```

### Core Invariants:
1. **Zero-Knowledge Guarantee:** The Google Drive cloud, Google OAuth servers, Go backend relays, and Redis transit queues never have access to raw user plaintext, message contents, attachment media, private cryptographic keys, or the SQLCipher database passphrase.
2. **Dual-Layer Key Envelope:** Cross-device portability is achieved without persisting raw keys to flash storage. The database passphrase is encrypted within an authenticated envelope (`NXKE` v2) bound to the backup's master vault key via HKDF-SHA256 and AES-256-GCM.
3. **Atomic Replacement with Rollback:** During restoration, the existing database and its Write-Ahead Log (WAL) are staged to `.rollback_bak` before new database files are placed into the production database directory. Any decryption or integrity failure triggers an instant atomic rollback.

---

## 2. Vault Packaging & Binary Format

### 2.1 Backup Vault Container Binary Format (Version 2)

An encrypted backup archive stored on Google Drive has the following binary wire structure:

| Offset (Bytes) | Field Name | Size (Bytes) | Description |
|---|---|---|---|
| `0x00 - 0x03` | `MAGIC` | 4 | Magic byte header: `'N' 'X' 'B' 'K'` (`0x4E 0x58 0x42 0x4B`) |
| `0x04 - 0x07` | `VERSION` | 4 | Format Version integer: Big-endian `2` (0x00000002) |
| `0x08 - 0x17` | `SALT` | 16 | Cryptographically secure random salt for PBKDF2 key derivation |
| `0x18 - 0x23` | `IV / NONCE` | 12 | Cryptographically secure random IV for AES-256-GCM authenticated cipher |
| `0x24 - ...` | `CIPHERTEXT` | Variable | Encrypted Zip payload containing the multi-pillar snapshot bundle |
| `End - 16` | `AUTH_TAG` | 16 | AES-256-GCM Poly1305/GHASH 128-bit integrity tag |

### 2.2 Unencrypted Internal Zip Bundle Layout

When decrypted into RAM by `BackupCryptoEngine`, the underlying archive is structured as follows:

```
bundle.zip
├── live_snapshot.db             (SQLite snapshot created via zero-lock VACUUM INTO)
├── preferences.json             (Sanitized non-sensitive UI/app configurations)
├── key_envelope.bin             (66-byte Dual-Layer Key Envelope, Version 2)
├── media_vault/                 (Locally cached encrypted media categorized by MIME type)
│   ├── images/
│   ├── videos/
│   ├── audio/
│   └── documents/
└── backup_manifest.json         (Version 2 JSON metadata manifest)
```

---

## 3. Atomic Live Database Snapshotting (`VACUUM INTO`)

To ensure zero database locking and avoid application freezes or SQLITE_BUSY crashes during backup creation, NEXA utilizes SQLite's native `VACUUM INTO` command:

```sql
VACUUM INTO '/data/user/0/com.nexa.app/cache/staging_<timestamp>/live_snapshot.db';
```

### Advantages:
- **Lock-Free Concurrency:** Active chat threads, incoming WebSocket messages, and background sync continue reading and writing to the active database without interruption.
- **Atomic Read Consistency:** SQLite creates a transactionally consistent, defragmented point-in-time snapshot.
- **WAL Reconciliation:** SQLite automatically folds WAL frames into the target file without truncating or locking the active WAL journal.

---

## 4. Dual-Layer Key Envelope (`NXKE` v2)

To solve the cross-device portability paradox (where Device A's database key is generated inside Android Keystore and cannot leave the hardware boundary), NEXA seals the database key inside a portable envelope:

### Envelope Wire Layout (66 Bytes):
- `[0..3]`: Magic Header `0x4E 0x58 0x4B 0x45` (`NXKE`)
- `[4..5]`: Envelope Version `0x00 0x02`
- `[6..17]`: 12-byte cryptographically secure random Nonce ($IV_{\text{env}}$)
- `[18..49]`: 32-byte AES-256-GCM Ciphertext of the SQLCipher Passphrase ($C_{\text{db}}$)
- `[50..65]`: 16-byte GCM Authentication Tag ($T_{\text{env}}$)

### Domain Separation & Key Derivation:
$$K_{\text{vault}} = \text{PBKDF2-HMAC-SHA256}(\text{BIP39-Mnemonic}, \text{Salt}_{\text{vault}}, 65536, 256)$$
$$K_{\text{env}} = \text{HKDF-SHA256}(K_{\text{vault}}, \text{Salt}_{\text{vault}}, \text{"NEXA\_V2\_KEY\_ENVELOPE\_KEY"}, 32)$$

### Authenticated Additional Data (AAD):
$$\text{AAD} = \text{"NEXA\_KEY\_ENVELOPE\_V2"} \mathbin{\Vert} \text{Salt}_{\text{vault}}$$

This cryptographic binding ensures that the envelope cannot be detached from its parent vault or decrypted using an envelope key derived from another vault's salt.

---

## 5. Google Drive Integration & Transport Layer

NEXA interacts with the Google Drive REST API v3 using user-authorized OAuth 2.0 scopes limited strictly to `https://www.googleapis.com/auth/drive.appdata`.

### 5.1 AppData Folder Isolation
- NEXA backups are stored exclusively within Google Drive's hidden `appDataFolder`.
- The user's standard Google Drive file manager does not display or index these files, protecting them from inadvertent user deletion or sync interference.
- Third-party apps with standard Google Drive scopes (`drive.file` or `drive.readonly`) are prohibited by Google API policies from accessing `appDataFolder`.

### 5.2 Resumable Chunked Uploader (`DriveResumableUploader`)
Backups larger than 5 MB are streamed via Google Drive's Resumable Upload protocol in 2 MB chunks:
1. **Initiate Session:** POST request with `uploadType=resumable` obtains a unique `Location` URI.
2. **Chunked Streaming:** PUT requests with `Content-Range: bytes START-END/TOTAL` stream chunks directly from disk.
3. **Interruption Recovery:** On network failure, a query request (`Content-Range: bytes */TOTAL`) queries the server's received byte count (`Range: bytes=0-K`), allowing the upload to resume seamlessly at byte $K+1$.

---

## 6. Atomic Restoration & Rollback Journaling

Restoration involves high-risk local file replacement. To eliminate catastrophic data loss:

```
[Staging Phase]
1. Decrypt vault into RAM ZipInputStream.
2. Intercept key_envelope.bin in-memory -> unseal to obtain K_db.
3. Test K_db against unpacked live_snapshot.db with SQLite test query.
4. If invalid, abort immediately (zero modifications to existing database).

[Rollback Preparation]
5. Rename live_snapshot.db -> nexa.db.rollback_bak
6. Rename live_snapshot.db-wal -> nexa.db-wal.rollback_bak

[Commit Phase]
7. Copy uncompressed snapshot to live database location.
8. Persist K_db into Android Keystore EncryptedSharedPreferences.
9. Delete .rollback_bak files.

[On Failure / Crash]
10. Catch block automatically moves .rollback_bak files back to live location.
11. NexaDatabase initialization checks for orphan .rollback_bak files on app boot and recovers them.
```
