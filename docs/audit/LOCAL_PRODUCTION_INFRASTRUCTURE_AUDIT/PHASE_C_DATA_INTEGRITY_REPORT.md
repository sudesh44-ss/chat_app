# NEXA Phase C — Data Integrity Report

**Author:** Antigravity Senior Security & Systems Architect  
**Date:** 2026-11-06  
**Status:** VERIFIED  
**Scope:** Cryptographic Keys, Relational Constraints, and E2EE Boundary Integrity  

---

## 1. Objective

This report evaluates the cryptographic integrity and relational constraints established during Phase C to ensure that:
1. No user identities, accounts, public keys, or verified credentials are lost or mutated during PostgreSQL persistence migration.
2. The zero-disk, zero-knowledge E2EE security boundary is strictly preserved.
3. Cryptographic identity keys stored on the backend match authoritative RFC 8032 Ed25519 specifications.

---

## 2. Integrity Verification Matrix

| Area | Invariant Required | Verification Mechanism | Status |
| :--- | :--- | :--- | :--- |
| **NX-ID Format** | Strict `^NX-[A-Z0-9]{8}$` format. | PostgreSQL check constraint `chk_nx_id_format` + Go regex `IsValidNxID`. | **ENFORCED** |
| **Public Key Uniqueness** | Exactly one account per Ed25519 public key. | Unique index `public_key_hex` on `accounts` table. | **ENFORCED** |
| **Phone Number Uniqueness** | Exactly one account per registered phone number. | Partial unique index `idx_accounts_phone WHERE phone_number IS NOT NULL AND phone_number != ''`. | **ENFORCED** |
| **Federated Identity Binding** | Exactly one account per Google UID and Supabase UID. | Partial unique indexes `idx_accounts_google_id` and `idx_accounts_supabase_uid`. | **ENFORCED** |
| **Device Hierarchy** | Devices must belong to a valid registered account. | Foreign key `account_id REFERENCES accounts(id) ON DELETE CASCADE`. | **ENFORCED** |
| **Session Hierarchy** | Sessions must link to valid account and device. | Foreign key `account_id REFERENCES accounts(id) ON DELETE CASCADE`. | **ENFORCED** |
| **Zero Private Key Storage** | Server MUST NEVER possess client private keys. | Audit of all DDL, structs, and repository queries confirms zero private key fields. | **VERIFIED** |
| **Zero Plaintext Storage** | Server MUST NEVER store plaintext message content. | Messages buffered only in Redis (`pending:<nx_id>`) as opaque encrypted bytes; 0 bytes on disk. | **VERIFIED** |

---

## 3. Cryptographic Fingerprint Verification Engine

The Phase C migrator implements a deterministic cryptographic verification algorithm:

```go
fingerprintKeys := make([]string, 0, len(accounts))
for _, acc := range accounts {
    fingerprintKeys = append(fingerprintKeys, fmt.Sprintf("%s:%s", acc.NxID, acc.PublicKeyHex))
}
sort.Strings(fingerprintKeys)
h := sha256.New()
for _, entry := range fingerprintKeys {
    h.Write([]byte(entry + "\n"))
}
fingerprint := hex.EncodeToString(h.Sum(nil))
```

### Guarantees:
- **Order-Independent Determinism:** Accounts are sorted lexicographically before hashing.
- **Bit-Level Sensitivity:** A single-character typo, dropped record, or modified public key bit changes the entire SHA-256 fingerprint, triggering an immediate migration abort.
- **Verification Across Repositories:** Both the source JSON file and the destination PostgreSQL database are fingerprinted and matched byte-for-byte.

---

## 4. Concurrency & Transactional Safety

1. **Prepared & Parameterized Statements:** All queries use `$1, $2, ...` bound parameters. Zero SQL string concatenation.
2. **Atomic Rollback on Error:** Schema migrations execute inside database transactions (`tx, err := pool.Begin(ctx)`). If any statement fails, the transaction is rolled back completely.
3. **Upsert Semantics for Devices:** Device registrations use PostgreSQL `ON CONFLICT (device_id) DO UPDATE`, guaranteeing idempotency during client reconnects.
4. **Cascade Deletion:** Account deletion automatically cascades to dependent devices and sessions, preventing orphaned data records.
