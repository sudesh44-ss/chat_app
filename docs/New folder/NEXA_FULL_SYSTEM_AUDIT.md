# NEXA — Full System Architecture & Security Audit

**Audit Date:** November 4, 2026  
**Auditor:** Senior Software Architect, Security Auditor & Platform Engineer  
**Codebase:** `H:\chat_app`  
**Physical Validation Device:** vivo Y28 5G (Model V2315, API 35, Android 15, arm64-v8a)  

---

## 1. Executive Summary

NEXA is a privacy-first, local-first end-to-end encrypted messaging platform. This deep, evidence-based audit inspects the complete codebase across the Android application (Kotlin + Jetpack Compose), Go backend, Supabase PostgreSQL persistence, local Room SQLCipher database, and cryptographic subsystems.

Every finding is categorized based on live source code inspection, unit test execution, and physical hardware validation:
- **PASS**: Verified working in production-ready state with automated and live physical evidence.
- **PARTIAL**: Implemented but contains gaps against locked architectural specifications.
- **FAIL**: Defective, violated security boundaries, or incompatible with locked architecture.
- **BLOCKED**: Requires external third-party production credentials (e.g. carrier SMS API, Play Console).
- **NOT IMPLEMENTED**: Feature or layer completely absent from source code.

---

## 2. System Component Audit Matrix

| Subsystem / Layer | Component | Status | Source File / Path | Core Evidence & Gap Analysis |
|---|---|---|---|---|
| **Android App Shell** | Startup & App Lifecycle | **PASS** | `NexaApplication.kt`<br>`ScheduledBackupWorker.kt` | Startup crash on Android 15 fixed; WorkManager idle backoff conflict removed; 5 cold starts verified live. |
| **Android UI** | Design System & Liquid Glass | **PASS** | `com/nexa/app/core/theme/`<br>`MainChatDashboardScreen.kt` | Material 3 Liquid Glass theme active; hardware Gralloc4 rendering verified on physical display. |
| **Authentication** | Google Credential Manager | **PASS** | `WelcomeViewModel.kt`<br>`WelcomeScreen.kt` | Android Credential Manager integration retrieves Google ID token cleanly. |
| **Authentication** | Supabase Auth Authority | **NOT IMPLEMENTED** | `backend/internal/auth/handler.go`<br>`AuthApiService.kt` | Backend historically validated Google ID tokens directly using Google certs; Supabase JWT verification not yet wired. |
| **Identity System** | NX-ID Format & Generation | **FAIL** | `backend/internal/identity/nxid.go`<br>`AuthApiService.kt` | Currently generates `NX-XXXX-XXX` derived deterministically from public key; violates locked `NX-XXXXXXXX` random format. |
| **Cryptographic Identity**| Hardware Keystore & Ed25519 | **PASS** | `AndroidKeyStoreManager.kt`<br>`Ed25519Signer.kt` | Master keys stored in AndroidKeyStore; private keys never leave physical device; proof-of-possession signatures supported. |
| **End-to-End Encryption**| Double Ratchet & X25519 | **PASS** | `DoubleRatchetEngine.kt`<br>`EncryptedEnvelope.kt` | Signal-style DH ratchet with HKDF-SHA256 chains; skipped-key table active; 131/131 tests passing. |
| **Local Storage** | Room + SQLCipher (v11) | **PASS** | `NexaDatabase.kt`<br>`DatabaseMigrations.kt` | 13 Room tables encrypted under SQLCipher 4.6.1 AES-256; keying returned 0 (`SQLITE_OK`); migrations 1->11 intact. |
| **Cloud Database** | Supabase PostgreSQL Schema | **NOT IMPLEMENTED** | `database/migrations/` | Zero SQL migration files exist in repository; Go backend currently relies on ephemeral JSON store `identity_store.json`. |
| **Phone Verification** | Server-Side OTP & Rate Limits | **PASS (Mock) / BLOCKED (Live)** | `otp_service.go`<br>`sms_provider.go` | Cryptographic 6-digit OTP, salted SHA-256, 5m TTL, max 3 attempts, 60s cooldown; live SMS blocked on API credentials. |
| **Contacts Subsystem** | Android Contacts Page UI | **PARTIAL** | `presentation/contacts/`<br>`ContactListScreen.kt` | Existing screen handles mutual friends and connection requests; dedicated address-book contacts page missing. |
| **Contacts Discovery** | Local Address Book & ContentObserver | **NOT IMPLEMENTED** | `ContactRepositoryImpl.kt` | Address book `ContentResolver` queries and real-time `ContentObserver` not yet wired into active UI. |
| **Contacts Privacy** | Zero Cloud Address Book Storage | **PASS (Architectural)** | `NexaDatabase.kt`<br>`identity/store.go` | Full address book is never uploaded to server; privacy boundary maintained. |
| **Contacts Discovery** | Backend Phone Number Lookup | **NOT IMPLEMENTED** | `backend/internal/auth/handler.go` | Authenticated batch endpoint `POST /api/v1/contacts/lookup` for phone matching absent. |
| **Messaging** | Blind Relay & Outbox Queue | **PASS** | `WebSocketManager.kt`<br>`ChatRepositoryImpl.kt` | Ephemeral WebSocket challenge-response active; wire envelope 49B overhead; key reuse on outbox retry verified. |
| **P2P File Transfer** | WebRTC DataChannels & ICE | **PASS** | `WebRtcClient.kt`<br>`P2PTransferRepositoryImpl.kt` | Clean `NEW->CLOSED` state machine; proper DC/PC disposal order; ICE restart supported. |
| **Media Processing** | Video Compression & Scanning | **PASS** | `VideoCompressionEngine.kt`<br>`DualScanEngine.kt` | MediaCodec hardware compression; magic bytes & Bloom filter SHA-256 inspection verified. |
| **Deployment** | Backend Hosting & Production TLS | **PARTIAL** | `backend/Dockerfile`<br>`docker-compose.yml` | Containerized relay & signaling services ready; production domain and TLS certs not deployed in host environment. |

---

## 3. Top Security & Architectural Findings

### Finding 1: Divergence from Supabase Auth Single Authority
- **Severity**: High (Architectural Lock Violation)
- **Detail**: The Go backend previously implemented `GoogleVerifier` to validate Google ID tokens directly against Google OAuth tokeninfo endpoints, issuing custom session tokens `nexa_sess_...`.
- **Remediation**: Reconfigure Go backend to validate Supabase JWTs signed by Supabase Auth (`sub` claim extraction). Android exchanges Google tokens through Supabase Auth and passes the Supabase access token to Go.

### Finding 2: NX-ID Format Non-Compliance
- **Severity**: Medium (Protocol Inconsistency)
- **Detail**: Existing `identity/nxid.go` computes `NX-%04d-%c%c%c` (e.g. `NX-1044-M2P`) derived from Ed25519 public keys. The locked decision mandates `NX-XXXXXXXX` (8 uppercase random alphanumeric characters, not key-derived).
- **Remediation**: Update `nxid.go` to generate 8 cryptographically secure random alphanumeric characters from `ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789`, with database uniqueness enforcement and collision retry.

### Finding 3: Missing Supabase PostgreSQL Migrations
- **Severity**: High (Production Readiness)
- **Detail**: The repository contains no SQL migration files. The backend currently uses an in-memory map persisted to `data/identity_store.json`.
- **Remediation**: Author complete, versioned SQL migration suite (`001_create_profiles.sql` through `014_create_database_triggers.sql`) with Row-Level Security (RLS) policies and PostgreSQL CHECK constraints.

### Finding 4: Absence of Dedicated Phone Contacts Page
- **Severity**: High (Feature Gap)
- **Detail**: The Android app lacks a dedicated Contacts page displaying device address book entries with real-time `ContentObserver` sync, search, and backend NEXA account detection.
- **Remediation**: Implement `ContactsScreen.kt`, `ContactsProviderHelper.kt`, address-book phone normalization, authenticated backend lookup API, and contact-to-chat navigation.
