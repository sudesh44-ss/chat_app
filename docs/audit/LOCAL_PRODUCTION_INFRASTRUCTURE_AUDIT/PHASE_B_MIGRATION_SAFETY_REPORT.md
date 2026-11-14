# PHASE B — KEY MIGRATION SAFETY & CONTINUITY REPORT

## 1. Migration Architecture & Safety Invariants

### 1.1 In-Place Verification Without Seed Mutation
- The cryptographic master vault stores three elements per user identity:
  1. `publicKeyHex`: The derived hex string of the Ed25519 public key.
  2. `encryptedPrivKeyHex`: The AES-256-GCM ciphertext of the 32-byte Ed25519 private key seed.
  3. `ivHex`: The 12-byte initialization vector for AES-GCM decryption.
- During migration in `getOrCreateIdentityKeyPair`:
  - The private seed is decrypted into volatile memory using the Android KeyStore master key.
  - The authoritative RFC 8032 public key is recomputed via BouncyCastle.
  - If the recomputed public key differs from `publicKeyHex`, only `publicKeyHex` is overwritten.
  - The encrypted ciphertext and IV remain 100% untouched.

---

## 2. Crash-Safety & Rollback Evaluation

- **Atomic Writes:** `SecureStorage` utilizes Android's `EncryptedSharedPreferences.edit().apply()`, guaranteeing atomic XML commits.
- **Exception Fallback:** If decryption or re-derivation throws an unhandled exception (e.g. temporary KeyStore daemon timeout), the code falls back to returning the existing `publicKeyHex` rather than crashing or clearing credentials.
- **Zero Account Lock-Out:** Existing accounts retain their original private seeds, ensuring that subsequent attempts or future app launches can re-attempt migration cleanly.

---

## 3. Database & Network Sync Integrity

- The Go backend does not require private key migrations because the server only ever holds the public key.
- When an updated client reconnects, the backend verifies the newly derived RFC 8032 signature against the registered public key or updates the account record during authenticated profile sync.
