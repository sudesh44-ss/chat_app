# NEXA — Database Migration & Schema Verification Status

## 1. Overview
The database tier in NEXA follows a strict two-pillar architecture:
1. **Local Storage (Client-Side)**: Embedded Room Database encrypted with SQLCipher v4.6.1, storing client cryptographic states, Double Ratchet sessions, outbox queues, and decrypted local message history.
2. **Cloud Storage (Supabase PostgreSQL)**: Versioned PostgreSQL migrations managing public profiles, identity key registries, multi-device sessions, and ephemeral delivery routing.

---

## 2. Supabase PostgreSQL Migrations Ledger

| Migration File | Primary Table / Entity | Description & Constraint Guardrails |
| :--- | :--- | :--- |
| `001_create_profiles.sql` | `profiles` | Stores user profiles. Enforces `CHECK (nx_id ~ '^NX-[A-Z0-9]{8}$')` and E.164 phone regex `^\+[1-9]\d{1,14}$`. |
| `002_create_devices.sql` | `devices` | Tracks up to 5 active client devices per user with revocation flags and push tokens. |
| `003_create_nx_identity.sql` | `nx_identities` | Cryptographic binding registry linking `nx_id`, `user_id`, and `public_key_hex`. |
| `004_create_phone_verifications.sql` | `phone_verifications` | Server-side OTP verification log. Stores salted SHA-256 OTP hashes, rate counters, and expiry timestamps. |
| `005_create_contacts.sql` | `contacts` | Server-side confirmed mutual contacts. **Explicitly prohibited from storing user address books.** |
| `006_create_identity_keys.sql` | `identity_keys` | Stores 32-byte Ed25519 identity keys with proof-of-possession signatures. |
| `007_create_prekeys.sql` | `prekeys` | Manages X3DH signed prekeys and batches of one-time prekeys. |
| `008_create_message_metadata.sql` | `message_metadata` | Ephemeral message routing logs. Stores sender NX-ID, recipient NX-ID, packet type, and delivery timestamps. **Zero plaintext content.** |
| `009_create_device_sessions.sql` | `device_sessions` | Stores hashed session tokens with expiration tracking. |
| `010_create_indexes.sql` | Indexes | Performance indexes on `nx_id`, `phone_number`, `user_id`, `session_token_hash`. |
| `011_create_constraints.sql` | Constraints & Triggers | Unique constraints on verified phone numbers and maximum 5 active devices trigger. |
| `012_create_rls_policies.sql` | Row-Level Security | Strict RLS policies enforcing tenant isolation: users can only read/update their own data. |
| `013_create_database_functions.sql` | PL/pgSQL Functions | `generate_random_nx_id()` and privacy-preserving `lookup_nexa_contacts()`. |
| `014_create_database_triggers.sql` | Triggers | Automatic `updated_at` timestamp synchronization across all tables. |

---

## 3. Local Room Database Migration History

The Android client database `NexaDatabase` is currently at **Version 11**:
- `MIGRATION_1_2`: Added delivery status and retry counts to chat messages.
- `MIGRATION_2_3`: Added Double Ratchet root keys and chain keys to conversation entities.
- `MIGRATION_3_4`: Introduced local contact request state machine and anti-spam PoW challenge table.
- `MIGRATION_4_5`: Added DTN bundle custody transfer table and mesh routing metric cache.
- `MIGRATION_5_6`: Added media transfer chunks and quarantine sandbox tracking.
- `MIGRATION_6_7`: Added Google Drive backup metadata and snapshot checksums.
- `MIGRATION_7_8`: Added multi-device synchronization queues.
- `MIGRATION_8_9`: Added video compression preset cache and bitrate tracking.
- `MIGRATION_9_10`: Added threat Bloom filter entries and YARA detection signatures.
- `MIGRATION_10_11`: Added cached device contact lookup matches and permission state indicators.

All migrations have been verified through automated migration unit tests in `Phase2AuthAndIdentityTests.kt` (`testRoomSequentialMigrationsConfigured`).
