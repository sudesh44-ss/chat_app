# NEXA — Database Architecture & SQL Migrations Audit

**Audit Date:** November 4, 2026  
**Auditor:** Database Architect & Cybersecurity Specialist  
**Engines Inspected:** Local Room + SQLCipher (Android) & Cloud Supabase PostgreSQL  

---

## 1. Local-First Android Database: Room + SQLCipher v11

### A. Engine & Configuration
- **Engine**: Android Room (`androidx.room:room-runtime:2.6.1`)
- **Encryption**: Zetetic SQLCipher (`net.zetetic:android-database-sqlcipher:4.6.1`)
- **Cipher Parameters**: AES-256 in CBC mode, 64,000 PBKDF2 HMAC-SHA512 iterations.
- **Database File**: `nexa_encrypted_vault.db` (Storage: `/data/user/0/com.nexa.app/databases/`)
- **Current Version**: `11`
- **Key Storage**: 256-bit passphrase generated via `SecureRandom`, stored in hardware-backed `EncryptedSharedPreferences`.

### B. Table Inventory & Entity Mapping (13 Tables)
1. `users` (`UserEntity`): Local profile and Ed25519 public identity key.
2. `messages` (`MessageEntity`): E2EE outbox and inbox records.
3. `chats` (`ChatEntity`): Pairwise conversation thread directory.
4. `contacts` (`ContactEntity`): Bilateral mutual trust and verified contact relationships.
5. `ratchet_sessions` (`RatchetStateEntity`): Double Ratchet session state per contact.
6. `skipped_keys` (`SkippedKeyEntity`): Out-of-order message decryption keys (24-hour TTL).
7. `dtn_envelopes` (`DtnEnvelopeEntity`): Store-and-forward mesh bundles.
8. `transfers` (`TransferEntity`): P2P WebRTC chunk transfer states.
9. `file_metadata` (`FileMetadataEntity`): Hashes, MIME types, and quarantine statuses.
10. `quarantine` (`QuarantineEntity`): Suspicious file sandbox records.
11. `transfer_sessions` (`TransferSessionEntity`): Resilient multi-chunk transfer tracking.
12. `mesh_envelopes` (`MeshEnvelopeEntity`): Opportunistic multi-hop routing cache.
13. `device_sync` (`DeviceSyncEntity`): Multi-device fanout sync queue.

### C. Migration History
- Sequential migrations `MIGRATION_1_2` through `MIGRATION_10_11` exist in `DatabaseMigrations.kt`.
- Verified non-destructive: `fallbackToDestructiveMigration()` is explicitly omitted in production builds.

---

## 2. Cloud Persistence: Supabase PostgreSQL Audit

### A. Current Status: Zero Cloud Migrations
- **Status**: **NOT IMPLEMENTED**
- **Evidence**:
  - The repository currently contains **0 SQL migration files**.
  - The Go backend historically utilized an in-memory JSON file store (`data/identity_store.json`).
  - No database tables exist in Supabase for user profiles, devices, or prekeys.

### B. Required Migration Suite
To support production deployment with Supabase PostgreSQL, a modular, versioned migration suite must be authored in `database/migrations/`:

| Migration File | Target Purpose & Core Constraints |
|---|---|
| `001_create_profiles.sql` | `profiles` table (`supabase_user_id UUID PK`, `nx_id TEXT UNIQUE CHECK (nx_id ~ '^NX-[A-Z0-9]{8}$')`, `phone_number TEXT`, `phone_verified BOOLEAN`) |
| `002_create_devices.sql` | `devices` table (`device_id TEXT PK`, `user_id UUID FK`, `device_name TEXT`, `public_key_hex TEXT`, `is_revoked BOOLEAN`) |
| `003_create_nx_identity.sql` | `nx_identities` table (`nx_id TEXT PK`, `supabase_user_id UUID UNIQUE`, `fingerprint TEXT`) |
| `004_create_phone_verifications.sql` | `phone_verifications` table (`phone_number TEXT`, `hashed_otp TEXT`, `attempts INT`, `expires_at TIMESTAMPTZ`) |
| `005_create_contacts.sql` | `contacts` table (Server-side bilateral mutual trust relationships; **NEVER stores device address books**) |
| `006_create_identity_keys.sql` | `identity_keys` table (`user_id UUID`, `public_key_hex TEXT`, `signature TEXT`) |
| `007_create_prekeys.sql` | `prekeys` table (`key_id INT`, `user_id UUID`, `public_key_hex TEXT`, `is_consumed BOOLEAN`) |
| `008_create_message_metadata.sql` | `message_metadata` table (Ephemeral delivery tracking only; **ZERO plaintext message storage**) |
| `009_create_device_sessions.sql` | `device_sessions` table (`session_token TEXT PK`, `device_id TEXT`, `expires_at TIMESTAMPTZ`) |
| `010_create_indexes.sql` | Performance B-Tree indices on `nx_id`, `phone_number`, `user_id`, `created_at` |
| `011_create_constraints.sql` | Foreign key cascades, check constraints for NX-ID regex and E.164 phone formats |
| `012_create_rls_policies.sql` | Row-Level Security policies restricting SELECT/UPDATE to `auth.uid() = user_id` |
| `013_create_database_functions.sql` | Helper functions for cryptographic challenge validation and NX-ID allocation |
| `014_create_database_triggers.sql` | `updated_at` automatic timestamp triggers |

### C. Strict Privacy Policy Enforcement
- **Address Book Isolation**: The complete phone address book of a user must **never** be stored in PostgreSQL.
- Only verified, registered NEXA user profiles and their public identity keys are stored for authenticated lookup.
