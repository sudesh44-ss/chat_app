# NEXA — Detailed Implementation Status

## 1. System Implementation Summary

Every core architectural requirement has been translated from technical specification into production code across the Android Kotlin client, Go backend, and Supabase PostgreSQL schema.

---

## 2. Component Implementation Status

### A. Authentication & Supabase Authority
- **Authority**: Supabase Auth serves as the single authentication authority.
- **Go JWT Verifier**: Implemented in `backend/internal/auth/supabase_verifier.go`. Validates:
  - Cryptographic signature via HMAC-SHA256 (`HS256`) against `SUPABASE_JWT_SECRET`.
  - Expiration timestamp (`exp`) and not-before (`nbf`).
  - Issuer claim (`iss`) matching the expected Supabase project URL.
  - User ID extraction from subject (`sub`).
- **Google Sign-In**: Client passes Google ID token to Supabase Auth; the resulting Supabase session JWT is exchanged with the Go backend via `/api/v1/auth/supabase/verify`.
- **Status**: **PRODUCTION IMPLEMENTED**.

### B. Locked NX-ID Specification
- **Format**: Strictly locked to `^NX-[A-Z0-9]{8}$` (e.g., `NX-29AB731Q`).
- **Generation**: Implemented in `backend/internal/identity/nxid.go` via cryptographically secure pseudo-random number generator (`crypto/rand`).
- **Collision Resistance**: Alphabet `A-Z0-9` (36 characters) over 8 positions yields $36^8 \approx 2.82 \times 10^{12}$ distinct combinations. The server allocation loop executes up to 10 collision retry attempts before failing.
- **Independence**: Completely decoupled from public keys, phone numbers, and emails.
- **Status**: **PRODUCTION IMPLEMENTED**.

### C. Database Architecture & Migrations
- **Local Client**: Room v11 backed by SQLCipher 4.6.1 with 16 KB ELF segment alignment for Android 15. Stores Double Ratchet keys, ratchet sessions, outbox packets, and decrypted chat history.
- **Supabase Cloud**: 14 PostgreSQL migrations authored in `database/migrations/`:
  - `001_create_profiles.sql` (Profiles, regex validation for `NX-XXXXXXXX`, E.164 phone check)
  - `002_create_devices.sql` (Device registry, push tokens, revocation)
  - `003_create_nx_identity.sql` (NX-ID mapping, public key linkage)
  - `004_create_phone_verifications.sql` (OTP verification hashes, rate limiting)
  - `005_create_contacts.sql` (Mutual friend relationships only; zero address-book storage)
  - `006_create_identity_keys.sql` (Ed25519 identity keys with proof of possession)
  - `007_create_prekeys.sql` (X3DH signed prekeys & one-time prekeys)
  - `008_create_message_metadata.sql` (Ephemeral delivery tracking only; zero message ciphertext)
  - `009_create_device_sessions.sql` (Hashed device session tokens)
  - `010_create_indexes.sql` (Performance B-Tree indexes)
  - `011_create_constraints.sql` (Unique verified phones, max 5 active devices trigger)
  - `012_create_rls_policies.sql` (Row-Level Security user isolation)
  - `013_create_database_functions.sql` (Stored functions for NX-ID generation & contact discovery)
  - `014_create_database_triggers.sql` (Automatic `updated_at` timestamps)
- **Status**: **PRODUCTION IMPLEMENTED**.

### D. Contact Discovery & Android Contacts Page
- **Zero Address Book Upload**: Strict privacy architecture. User address-book contacts are queried locally via Android `ContentResolver` (`ContactsContract.CommonDataKinds.Phone`). Names, emails, notes, and avatars are never transmitted.
- **Batch Lookup**: Only normalized E.164 numbers are submitted to Go backend `/api/v1/contacts/lookup`. Max 100 per request.
- **Android Contacts Screen**: Implemented in `ContactListScreen.kt` with Liquid Glass card design, real-time `ContentObserver` with 500ms debouncing, search filtering, empty states, and permission rationale handling.
- **Navigation**: Integrated into `AuthNavGraph.kt` (`Screen.Contacts`) and accessible from `MainChatDashboardScreen.kt` TopAppBar.
- **Status**: **PRODUCTION IMPLEMENTED**.

### E. Cryptographic Identity & Forward Secrecy
- **Key Storage**: Hardware Keystore / StrongBox backing.
- **Signing**: Ed25519 signatures on all outgoing identity updates and handshakes.
- **Ratchet**: Double Ratchet with per-message Diffie-Hellman stepping and symmetric HKDF chains.
- **Status**: **PRODUCTION IMPLEMENTED**.
