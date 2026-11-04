# NEXA — Supabase Live Schema & Migration Status Report

## 1. Migration Lifecycle Distinction

In compliance with audit rules, all migrations are categorized according to their exact verification lifecycle state:
- **CREATED**: The DDL script exists in the repository.
- **VALIDATED**: The DDL script has been checked for structural constraints, syntax validity, foreign keys, regex validation, and RLS rules.
- **APPLIED**: The migration has been executed against an active, live PostgreSQL instance.

---

## 2. Migrations Ledger & Status

| Migration File | Primary Table / Object | Status | Validation Notes |
| :--- | :--- | :---: | :--- |
| `001_create_profiles.sql` | `profiles` | **VALIDATED** | Contains `CHECK (nx_id ~ '^NX-[A-Z0-9]{8}$')` and E.164 phone check `^\+[1-9]\d{1,14}$`. |
| `002_create_devices.sql` | `devices` | **VALIDATED** | Foreign key to `profiles(user_id)` ON DELETE CASCADE; indexes on `device_id` and `user_id`. |
| `003_create_nx_identity.sql` | `nx_identities` | **VALIDATED** | Unique constraint on `nx_id` and `public_key_hex`. |
| `004_create_phone_verifications.sql` | `phone_verifications` | **VALIDATED** | Salted OTP hash storage (`otp_hash` VARCHAR(64)), cooldown and attempt tracking. |
| `005_create_contacts.sql` | `contacts` | **VALIDATED** | Confirmed mutual contacts only. **Explicitly zero device address book storage.** |
| `006_create_identity_keys.sql` | `identity_keys` | **VALIDATED** | Ed25519 identity key registry with 64-byte proof-of-possession signatures. |
| `007_create_prekeys.sql` | `prekeys` | **VALIDATED** | Signed prekeys and batches of one-time prekeys with unique key IDs. |
| `008_create_message_metadata.sql` | `message_metadata` | **VALIDATED** | Ephemeral delivery timestamps and packet IDs. **Zero plaintext content columns.** |
| `009_create_device_sessions.sql` | `device_sessions` | **VALIDATED** | SHA-256 session token hashes with expiration timestamps. |
| `010_create_indexes.sql` | Performance Indexes | **VALIDATED** | B-Tree indexes on `nx_id`, `phone_number`, `user_id`, `created_at`. |
| `011_create_constraints.sql` | Constraints & Triggers | **VALIDATED** | Trigger restricting active devices to maximum 5 per account. |
| `012_create_rls_policies.sql` | Row-Level Security | **VALIDATED** | RLS enabled on all tables; policies restrict operations to `auth.uid() = user_id`. |
| `013_create_database_functions.sql` | PL/pgSQL Functions | **VALIDATED** | Functions `generate_random_nx_id()` and `lookup_nexa_contacts()`. |
| `014_create_database_triggers.sql` | System Triggers | **VALIDATED** | `update_updated_at_column()` trigger on mutable tables. |

---

## 3. Remote Live Database Verification Findings

- **Environment Audit**: Neither `$env:SUPABASE_URL` nor `$env:DATABASE_URL` is configured in the current host environment.
- **Remote Host Verification**: Because no live Supabase project credentials are provided in the environment, connecting to a remote PostgreSQL database to run `\dt` or inspect live tables is **BLOCKED**.
- **Destructive Migration Protection**: In strict compliance with audit rules, no destructive migrations (`DROP TABLE`, `TRUNCATE`) were run.
- **Summary**: All 14 migrations are **CREATED** and **VALIDATED**; application to an external hosted Supabase project is **BLOCKED on external provisioning**.
