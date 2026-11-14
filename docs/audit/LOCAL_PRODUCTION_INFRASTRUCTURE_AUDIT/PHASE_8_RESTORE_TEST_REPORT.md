# NEXA — Phase 8: Restore Test Report

## 1. Test Overview & Objectives

This report documents the verification of the complete restoration lifecycle for NEXA, including remote backup discovery, cryptographic integrity verification, selective restore filtering, cross-device key import, atomic database replacement, and crash rollback safety.

---

## 2. Test Execution Summary

| Test Phase | Scenarios Tested | Pass Count | Fail Count | Status |
|---|---|---|---|---|
| **Backup Discovery** | Remote AppData listing, metadata parsing, multiple backups | 5 | 0 | **PASS** |
| **Selective Category Restore** | Partial restore (Chats only, No Media, Audio only) | 6 | 0 | **PASS** |
| **BIP-39 Mnemonic Validation** | Checksum validation, whitespace normalization, bad words | 4 | 0 | **PASS** |
| **In-RAM Key Envelope Unsealing** | Cross-device passphrase recovery, AAD verification | 6 | 0 | **PASS** |
| **Atomic Database Replacement** | Staging verification, live swap, lock-free replacement | 4 | 0 | **PASS** |
| **Crash & Interruption Rollback** | Mid-swap kill, corrupted payload, missing WAL journal | 4 | 0 | **PASS** |
| **Total Restore Test Cases** | | **29** | **0** | **100% PASS** |

---

## 3. Deep Dive Test Case Results

### 3.1 BIP-39 Mnemonic Passphrase Validation (`BackupRestoreNavigationUnitTests`)
- **`testBip39MnemonicPassphraseValidationForRestore`**:
  - Generates 12-word phrase via `Bip39PassphraseHelper.generateMnemonic()`.
  - Asserts normalized word count equals 12.
  - Tests rejection of:
    - 11-word phrase (truncated).
    - 13-word phrase (excess).
    - Invalid words not present in the BIP-39 dictionary.
    - Checksum bit corruption.
  - *Result:* **PASS**.

- **`testBip39SeedDerivationProducesDeterministicKey`**:
  - Validates that two distinct invocations with the same mnemonic generate bitwise identical 512-bit seeds.
  - *Result:* **PASS**.

### 3.2 Atomic Swap & Rollback Safety (`AtomicRestoreCrashRecoveryUnitTests`)
- **`testAtomicRollbackOnSwapFailure`**:
  - Simulates an active database (`liveDb`) and WAL file (`liveWal`).
  - Creates `.rollback_bak` copies.
  - Injects a fatal corruption during the swap phase (`IllegalStateException: SQLCipher Key Mismatch`).
  - Verifies that the rollback recovery block immediately restores the original database and WAL file without data corruption.
  - *Result:* **PASS**.

- **`testStartupAutoRecoveryWhenProcessKilledDuringSwap`**:
  - Simulates the scenario where the Android operating system terminates the app process while replacing `nexa.db`.
  - Leaves an orphaned `nexa.db.rollback_bak` file on disk.
  - Triggers `NexaDatabase` cold-boot initialization.
  - Verifies that the initialization hook automatically detects the `.rollback_bak` file, replaces the incomplete live database, and removes the journal.
  - *Result:* **PASS**.

### 3.3 Cross-Device Portability Verification
- **Scenario:** Backup created on simulated "Device A" with distinct random SQLCipher key $K_{\text{db\_A}}$. Device B starts with empty database and new random key $K_{\text{db\_B}}$.
- **Execution:**
  1. Device B enters 12-word BIP-39 recovery phrase from Device A.
  2. Backup vault downloaded from Google Drive AppData.
  3. `VaultBundlePackager.unpackAndRestoreBundle()` unseals `key_envelope.bin` in RAM.
  4. $K_{\text{db\_A}}$ is recovered and stored in Device B's `SecureStorage`.
  5. Device B opens the restored database with $K_{\text{db\_A}}$:
     - All user contacts, chat histories, group memberships, and bookmarks are immediately readable.
     - Device B generates fresh local Keystore keys for future local sessions.
- **Verification:** 100% cross-device compatibility achieved without persistent key residue.

---

## 4. UI / UX State Transition Verification

| Step | User Action | UI State | Observed System Response |
|---|---|---|---|
| 1 | Navigate to Restore Backup | Screen loads | Discovered cloud backup cards rendered via `NexaGlassSurface` |
| 2 | Select categories | Checkboxes update | Deselecting "Videos" reduces expected download footprint |
| 3 | Paste 12-word mnemonic | Input grid populated | Instant BIP-39 checksum evaluation; green checkmark appears |
| 4 | Tap "Start Restore" | Progress Card active | Phase updates sequentially: `Downloading` &rarr; `Verifying` &rarr; `Unsealing` &rarr; `Swapping` |
| 5 | Completion | Dialog shown | "Restore Complete. NEXA will now restart." |
