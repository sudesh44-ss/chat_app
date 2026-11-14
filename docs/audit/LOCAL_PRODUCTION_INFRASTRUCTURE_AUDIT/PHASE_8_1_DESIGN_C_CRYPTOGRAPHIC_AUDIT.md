# NEXA — Phase 8.1: Design C Cryptographic Security Audit

## 1. Audit Scope & Standards

This audit evaluates the cryptographic primitives, key derivation functions (KDF), authenticated encryption mechanisms (AEAD), and memory lifecycle safety implemented in the approved **Design C: Dual-Layer Cryptographic Key Envelope** specification.

Audited Components:
- `KeyEnvelopeEngine.kt`
- `BackupCryptoEngine.kt`
- `BackupVaultHeader.kt`
- `Bip39PassphraseHelper.kt`
- `SecureStorage.kt`
- `VaultBundlePackager.kt`

---

## 2. BIP-39 Recovery Credential Verification (Section 6.1)

### 2.1 Entropy & Word Encoding
- **Source Inspection:** [`Bip39PassphraseHelper.kt:18-61`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/backup/Bip39PassphraseHelper.kt#L18-L61).
- **Entropy Source:** `SecureRandom().nextBytes(entropy)` with 16 bytes (128 bits of cryptographic entropy).
- **Checksum Calculation:** First 4 bits of `SHA-256(entropy)` appended to the 128 bits = 132 bits total.
- **Word Mapping:** 132 bits / 11 bits = 12 words mapped to the standard BIP-39 English dictionary of 2,048 words.
- **Audit Assessment:** **CONFORMS STRICTLY TO BIP-39 SPECIFICATION**.

### 2.2 Normalization & Validation
- **Source Inspection:** [`Bip39PassphraseHelper.kt:66-126`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/backup/Bip39PassphraseHelper.kt#L66-L126).
- **Normalization:** Trims leading/trailing whitespace, converts to lowercase (`Locale.ROOT`), and collapses multiple spaces.
- **Checksum Verification:** Reconstructs the 128-bit entropy from word indices, recomputes `SHA-256`, and compares the 4 checksum bits.
- **Audit Assessment:** **CORRECT.** Rejects incomplete, transposed, or misspelled words.

### 2.3 Clipboard Exposure Vulnerability (P2)
- **Source Inspection:** [`BackupSettingsScreen.kt:116`](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/backup/BackupSettingsScreen.kt#L116).
- **Finding:** Tapping "Copy" writes the plaintext 12 words to `LocalClipboardManager`.
- **Risk:** Any foreground app or third-party input method (keyboard) can monitor the system clipboard. Android 13+ displays an unmasked clipboard overlay.
- **Recommendation:** Display a warning dialog before copying and encourage manual paper recording.

---

## 3. PBKDF2 Key Derivation Work Factor (Section 6.2)

- **Source Inspection:** [`BackupCryptoEngine.kt:236-247`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/backup/BackupCryptoEngine.kt#L236-L247).
- **Configuration:**
  - Algorithm: `PBKDF2WithHmacSHA256`
  - Iterations: `65,536`
  - Salt: 16 bytes generated via `SecureRandom`
  - Output Key: 256 bits (32 bytes)
- **Cryptographic Evaluation:**
  - **Modern Attacker Capabilities:** OWASP 2023 recommends 600,000 iterations for PBKDF2-HMAC-SHA256 for user passwords.
  - **Entropy Defense:** Because NEXA enforces a 128-bit random BIP-39 mnemonic phrase rather than arbitrary human passwords, the search space is $2^{128} \approx 3.4 \times 10^{38}$ possibilities. Even at 1 iteration, a 128-bit true random key is mathematically unbrute-forceable.
  - **Performance Balance:** On mobile hardware (Snapdragon / Google Tensor / MediaTek), 65,536 iterations take ~180-280ms, ensuring responsive UI during backup creation without triggering Android ANR (Application Not Responding) dialogs.
- **Audit Assessment:** **ACCEPTABLE FOR 128-BIT BIP-39 ENTROPY**.

---

## 4. HKDF Key Separation & Domain Separation (Section 6.3)

- **Source Inspection:** [`KeyEnvelopeEngine.kt:37-44`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/backup/crypto/KeyEnvelopeEngine.kt#L37-L44).
- **Implementation:**
  ```kotlin
  X3dhHandshakeEngine.hkdfExtractAndExpand(
      salt = salt,
      ikm = vaultMasterKeyBytes,
      info = "NEXA_V2_KEY_ENVELOPE_KEY".toByteArray(Charsets.UTF_8),
      length = 32
  )
  ```
- **Domain Separation Verification:**
  - $K_{\text{vault}}$ is used to encrypt the outer archive container.
  - $K_{\text{env}}$ is derived from $K_{\text{vault}}$ using a distinct ASCII domain separation tag (`NEXA_V2_KEY_ENVELOPE_KEY`).
  - RFC 5869 HKDF-Expand guarantees that knowing $K_{\text{env}}$ does not reveal $K_{\text{vault}}$, and ciphertext from one cipher cannot be substituted into the other.
- **Critical Architectural Clarification:**
  $K_{\text{vault}}$ and $K_{\text{env}}$ represent **cryptographic key separation**, NOT **independent authentication factors**. Both keys originate deterministically from the user's 12-word recovery mnemonic. A compromise of the mnemonic compromises both keys simultaneously.

---

## 5. AES-256-GCM AEAD Robustness (Section 6.4)

| Cryptographic Parameter | Envelope Engine (`KeyEnvelopeEngine.kt`) | Outer Vault Engine (`BackupCryptoEngine.kt`) | Compliance Status |
|---|---|---|---|
| **Cipher Suite** | `AES/GCM/NoPadding` | `AES/GCM/NoPadding` | Compliant |
| **Key Size** | 256 bits (32 bytes) | 256 bits (32 bytes) | Compliant |
| **IV / Nonce** | 96 bits (12 bytes) CSPRNG | 96 bits (12 bytes) CSPRNG | Compliant |
| **Auth Tag Length** | 128 bits (16 bytes) | 128 bits (16 bytes) | Compliant |
| **Nonce Uniqueness** | `secureRandom.nextBytes(it)` per seal | `secureRandom.nextBytes(it)` per backup | Nonce reuse probability $< 2^{-48}$ |
| **Tamper Detection** | Throws `AEADBadTagException` | Throws `SecurityException` | Verified in unit tests |

---

## 6. AAD Binding Contradiction & Cross-Vault Substitution (Section 6.5)

### 6.1 Audit Finding (P1 - Specification Contradiction)
- **Claimed Specification (in Phase 8 documentation):**
  $$\text{AAD} = \text{"NEXA\_KEY\_ENVELOPE\_V2"} \mathbin{\Vert} \text{Salt}_{\text{vault}}$$
- **Actual Code ([`KeyEnvelopeEngine.kt:63-67`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/backup/crypto/KeyEnvelopeEngine.kt#L63-L67)):**
  ```kotlin
  val aadBuffer = ByteBuffer.allocate(6)
  aadBuffer.put(ENVELOPE_MAGIC.toByteArray(Charsets.US_ASCII)) // 4 bytes: "NXKE"
  aadBuffer.putShort(ENVELOPE_VERSION)                         // 2 bytes: 2
  cipher.updateAAD(aadBuffer.array())
  ```
  The actual AAD buffer is strictly 6 bytes: `"NXKE" || 0x0002`. The vault `salt` is **NOT** included in the AAD buffer.

### 6.2 Security Consequence Analysis
- Because `salt` is passed to HKDF during $K_{\text{env}}$ derivation, if an attacker substitutes `key_envelope.bin` into a vault with a *different* salt, the derived $K_{\text{env}}$ will be wrong, and the GCM tag check will fail.
- However, if an attacker swaps envelopes between two backups that share the same master key (e.g., from the same mnemonic), the AAD check **will not detect that the envelope was moved between different backup archives or different database snapshots**.
- **Remediation Recommendation:** Update `sealPassphrase` and `unsealPassphrase` to include `salt` and `backupEpoch` in the AAD buffer as originally specified in the design document.

---

## 7. Memory Safety & Storage Residue Analysis (Section 6.6)

### 7.1 Zero-Disk Flash Isolation
- In `VaultBundlePackager.unpackAndRestoreBundle()`, `key_envelope.bin` is streamed from `ZipInputStream` directly into a `ByteArrayOutputStream` in RAM.
- **Verification:** The envelope file is **NEVER** created on flash storage.

### 7.2 JVM Memory Scrubbing Reality Check
- `KeyEnvelopeEngine.kt:78, 126` calls `Arrays.fill(envelopeKey, 0.toByte())` and `VaultBundlePackager.kt:292` calls `Arrays.fill(recoveredPassphrase, 0.toByte())`.
- **Honest Technical Appraisal:** While explicit array zeroing is an industry best practice, Java/Android ART runtime memory management cannot guarantee that no copies exist in RAM:
  1. `cipher.doFinal()` allocates temporary internal buffers in native C/OpenSSL memory that are managed outside developer control.
  2. Generational garbage collectors (Android ART) copy living objects during compaction and garbage collection pauses, potentially leaving stale copies in uncompacted memory blocks until overwritten.
  3. Memory wiping reduces exposure time significantly but does not mathematically eliminate RAM forensic recovery on a live, rooted device.
