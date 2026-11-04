# NEXA — Complete Implementation Roadmap

## 1. Architectural Overview & Vision
NEXA is an uncompromising, privacy-first, Local-First End-to-End Encrypted (E2EE) messaging platform built for high-security environments, offline resilience, and distributed P2P communication.

The core architectural tenet of NEXA is:
**Zero Plaintext in the Cloud + Zero Device Address Book Storage + Zero Trust Client-Side Cryptography.**

---

## 2. Milestone Breakdown

```
+---------------------------------------------------------------------------------------------------+
| MILESTONE 1: Cryptographic Core & Local Security (COMPLETED)                                     |
| - Android Hardware Keystore (StrongBox/TEE backing for AES-256-GCM DB keys)                       |
| - SQLCipher v4.6.1 + 16 KB page-size ELF alignment for Android 15                                |
| - Ed25519 anti-impersonation signing & X25519 / X3DH Triple Diffie-Hellman handshake              |
| - Double Ratchet dynamic key ratcheting with HKDF per-message forward secrecy                     |
+---------------------------------------------------------------------------------------------------+
                                                  │
                                                  ▼
+---------------------------------------------------------------------------------------------------+
| MILESTONE 2: Supabase PostgreSQL Architecture & Cloud Authority (COMPLETED)                      |
| - Versioned SQL migrations (001_ to 014_) with Row-Level Security (RLS)                          |
| - Locked NX-ID specification: ^NX-[A-Z0-9]{8}$ format with collision-proof generation           |
| - Supabase Auth single-authority verification (HS256 HMAC JWT verification in Go backend)         |
| - Zero-Cloud-Storage rule: No cloud message storage, no contact address-book tables               |
+---------------------------------------------------------------------------------------------------+
                                                  │
                                                  ▼
+---------------------------------------------------------------------------------------------------+
| MILESTONE 3: Contact Discovery & Real-Time Android Contacts Page (COMPLETED)                     |
| - Dedicated Android Contacts Page with Liquid Glass design & real-time ContentObserver            |
| - Privacy-preserving batch phone lookup API: only hashes/E.164 phones sent, zero names/metadata   |
| - Add Contact by Phone dialog with Android system contact insertion intent                        |
| - Direct encrypted chat navigation from contacts roster                                           |
+---------------------------------------------------------------------------------------------------+
                                                  │
                                                  ▼
+---------------------------------------------------------------------------------------------------+
| MILESTONE 4: Physical Device Validation & Release Hardening (COMPLETED)                           |
| - Verification on vivo Y28 5G (Android 15, API 35, arm64-v8a)                                     |
| - 133/133 automated unit tests passing across all cryptographic and network subsystems            |
| - SQLCipher keying returned: 0 without runtime crashes or deadlocks                               |
+---------------------------------------------------------------------------------------------------+
```

---

## 3. Technology Deliverables Matrix

| Subsystem | Components | Status | Deliverable Path |
| :--- | :--- | :--- | :--- |
| **Identity Core** | Locked `NX-XXXXXXXX` Engine | **COMPLETED** | `backend/internal/identity/nxid.go` |
| **Auth Verifier** | Supabase HS256 JWT Validator | **COMPLETED** | `backend/internal/auth/supabase_verifier.go` |
| **Backend Store** | In-Memory / PostgreSQL Store | **COMPLETED** | `backend/internal/identity/store.go` |
| **API Endpoints** | Supabase Verify, Profile, Contacts Lookup | **COMPLETED** | `backend/internal/auth/handler.go` |
| **Database Migrations** | 14 Versioned SQL Migrations | **COMPLETED** | `database/migrations/001_` to `014_` |
| **Address Book Helper** | ContentResolver + E.164 Normalizer | **COMPLETED** | `app/src/main/java/com/nexa/app/core/contacts/` |
| **Contacts UI** | ContactListScreen + Liquid Glass | **COMPLETED** | `app/src/main/java/com/nexa/app/presentation/contacts/` |
| **Navigation** | AuthNavGraph + Dashboard Actions | **COMPLETED** | `app/src/main/java/com/nexa/app/presentation/navigation/` |

---

## 4. Verification & Testing Milestones
- **Automated Unit Tests**: 133 passing unit tests spanning cryptographic signing, Double Ratchet, wire protobufs, and contact discovery.
- **Physical Device Target**: Tested on live vivo Y28 5G (Android 15 / API 35) via USB ADB debugging.
- **Memory & Lifecycle**: Verified Android 15 App Freezer compatibility and cold/warm launch benchmarks.
