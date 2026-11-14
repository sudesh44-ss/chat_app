# NEXA — Phase 8: Backup Encryption & Key Recovery Specification

## 1. Cryptographic Goals & Non-Negotiable Invariants

The NEXA Cloud Backup system enforces the following cryptographic security properties:
1. **Zero-Knowledge Cloud Storage:** Data stored on Google Drive AppData is cryptographically opaque. No server, relay, or cloud provider possesses the decryption keys.
2. **Dual-Layer Key Envelope (`NXKE` v2):** The SQLCipher database key is safely sealed in-RAM without ever exposing the key to persistent flash storage.
3. **Hardware Keystore Isolation:** Device-bound Android Keystore hardware private keys and device-unique identifiers are excluded from backups; target devices generate new hardware keys upon account activation.
4. **Cryptographic Authenticity & Tamper Evidence:** All ciphertext layers use AES-256-GCM authenticated encryption with 128-bit authentication tags. Tampering with even 1 bit causes instantaneous, silent decryption rejection.
5. **Deterministic Mnemonic Key Derivation:** Restoration requires only the user's 12-word BIP-39 mnemonic phrase.

---

## 2. Key Derivation Hierarchy

```
                    +--------------------------------+
                    |  12-Word BIP-39 Mnemonic Phrase|
                    +--------------------------------+
                                   |
                                   |  PBKDF2-HMAC-SHA256
                                   |  Iterations: 65,536
                                   |  Salt: 16-byte Cryptographic Random
                                   v
                    +--------------------------------+
                    | Master Vault Key (K_vault)     |
                    | (256-bit AES Key)              |
                    +--------------------------------+
                                   |
                   +---------------+---------------+
                   |                               |
                   | HKDF-SHA256                   | Direct AES-256-GCM
                   | Info: NEXA_V2_KEY_ENVELOPE_KEY| IV: 12-byte Cryptographic Random
                   v                               v
    +-----------------------------+  +-------------------------------+
    | Envelope Key (K_env)        |  | Outer Vault Encryption Stream |
    | (256-bit AES Key)           |  | - live_snapshot.db            |
    +-----------------------------+  | - preferences.json            |
                   |                 | - media_vault/                |
                   | Encrypts (GCM)  | - backup_manifest.json        |
                   v                 +-------------------------------+
    +-----------------------------+
    | key_envelope.bin (66 Bytes) |
    | - NXKE (4B)                 |
    | - Version 2 (2B)            |
    | - Nonce (12B)               |
    | - Encrypted K_db (32B)      |
    | - GCM Tag (16B)             |
    +-----------------------------+
```

---

## 3. The Dual-Layer Key Envelope (`NXKE` v2)

### 3.1 Binary Specification (66 Bytes Total)

| Byte Offset | Length (Bytes) | Field Name | Description |
|---|---|---|---|
| `0..3` | 4 | `MAGIC` | ASCII `'N' 'X' 'K' 'E'` (`0x4E 0x58 0x4B 0x45`) |
| `4..5` | 2 | `VERSION` | Big-Endian Short `2` (`0x00 0x02`) |
| `6..17` | 12 | `NONCE` | 96-bit cryptographically secure random Initialization Vector ($IV_{\text{env}}$) |
| `18..49` | 32 | `CIPHERTEXT` | 256-bit AES-GCM ciphertext of the SQLCipher Passphrase ($C_{\text{db}}$) |
| `50..65` | 16 | `AUTH_TAG` | 128-bit GHASH authentication tag verifying ciphertext and AAD integrity |

### 3.2 Authenticated Additional Data (AAD)
To prevent cross-vault splicing attacks where an attacker swaps `key_envelope.bin` from one backup into another, the envelope encryption uses AAD bound to the specific vault's salt:

$$\text{AAD} = \text{"NEXA\_KEY\_ENVELOPE\_V2"} \mathbin{\Vert} \text{Salt}_{\text{vault}}$$

Decryption verifies this exact AAD string; if an envelope is injected into a vault with a different salt, GCM tag verification fails immediately.

### 3.3 Zero-Disk In-Memory Extraction Safety
During recovery on Device B:
1. `VaultBundlePackager.unpackAndRestoreBundle()` opens the decrypted zip stream.
2. When the entry named `key_envelope.bin` is encountered, it is streamed directly into an in-memory `ByteArrayOutputStream`.
3. `KeyEnvelopeEngine.unsealDatabasePassphrase()` decrypts the passphrase directly into an in-RAM byte array.
4. The passphrase is immediately imported into the new device's local Android Keystore-backed `EncryptedSharedPreferences` via `secureStorage.saveDatabasePassphrase(recoveredPassphrase)`.
5. The RAM array is wiped (`passphrase.fill(0)`).
6. **Zero bytes of the plaintext key or raw envelope are ever written to Android flash storage.**

---

## 4. BIP-39 Recovery Phrase Validation

NEXA uses BIP-39 12-word mnemonics providing 128 bits of initial entropy:
- 128 bits entropy + 4 bits SHA-256 checksum = 132 bits.
- 132 bits / 11 bits per word = 12 words from the standard BIP-39 2048-word English dictionary.
- Normalization strips leading/trailing whitespace, converts to lowercase, and collapses internal whitespace.
- Checksum validation ensures typos or transposed words are detected immediately before attempting cryptographic operations.

---

## 5. Security Threat Matrix & Verification

| Threat Scenario | Attack Vector | NEXA Defense Mechanism |
|---|---|---|
| **Google Cloud Insider** | Cloud admin inspects AppData file | AES-256-GCM ciphertext is unbreakable without 12-word BIP-39 phrase (PBKDF2 65,536 iterations). |
| **Cross-Device Key Lock** | Device A destroyed, Keystore inaccessible | Key Envelope unseals $K_{\text{db}}$ deterministically from BIP-39 mnemonic into Device B Keystore. |
| **Flash Memory Forensics** | Forensic extraction from discarded flash | Key Envelope is unpacked in RAM; never written as a plaintext file to storage. |
| **Archive Tampering** | Bit flip in encrypted database or envelope | GCM 128-bit authentication tag check fails; restoration immediately aborts and cleans cache. |
| **Envelope Splicing** | Attacker substitutes envelope from another backup | AAD binding (`"NEXA_KEY_ENVELOPE_V2" + salt`) rejects foreign envelopes. |
| **Partial Rollback Failure** | Phone runs out of battery during database swap | Active files backed up to `.rollback_bak`; auto-recovered on subsequent app launch. |
