# NEXA — Account Creation, Profile Synchronization & NX ID Integration Audit

**Document Reference:** `docs/audit/ACCOUNT_CREATION_PROFILE_BACKEND_AUDIT.md`  
**Date:** 2026-11-08  
**Phase:** Phase A — Comprehensive Read-Only System Audit  
**Components Audited:** Android (Jetpack Compose), Go Backend (`wire`), PostgreSQL (`Supabase`), Local Keystore & SQLCipher  

---

## 1. Executive Summary

This audit assesses the end-to-end readiness of NEXA's account creation, Google identity integration, profile persistence, NX-ID assignment, and Settings Profile synchronization flows.

The application has existing implementations in both the Android Jetpack Compose client (`app/src/main/java/com/nexa/app`) and the Go backend (`backend/internal/auth`, `backend/internal/identity`, `backend/internal/database`). The user-facing screens and Liquid Glass design system are built, and the cryptographic identity and Reverse SMS verification models are established.

However, several critical integration gaps exist between the client and backend:
1. When auto-verification or trusted phone numbers succeed on `/api/v1/auth/initiate-reverse-verify`, the client fails to parse the returned `nx_id` and `session_auth_token`, unconditionally navigating to the Reverse SMS radar screen.
2. The client lacks remote profile synchronization from GET `/api/v1/profile`, meaning profile data stored in PostgreSQL is never reloaded into Room.
3. The Settings Account/Profile screen only renders a subset of user fields (`Display Name`, `Phone Number`, `NEXA ID`), omitting `Profile Photo`, `Gmail`, `Age`, `Gender`, and the user's permanent `QR Code`.
4. The local Android `UserEntity` Room database entity does not store `email` or `avatar_url` persistently.

---

## 2. Existing Screen Sequence & Navigation Flow

The existing Android screen flow in [`AuthNavGraph.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/navigation/AuthNavGraph.kt) adheres to the required sequence:

```
+-------------------------------------------------------------------------------+
|                             SCREEN SEQUENCE                                   |
+-------------------------------------------------------------------------------+
|  1. WelcomeScreen (Google Account Selection & Auth)                           |
|         │                                                                     |
|         ▼                                                                     |
|  2. POST /api/v1/auth/google (Backend Token Verification & Claims Extraction) |
|         │                                                                     |
|         ▼                                                                     |
|  3. ProfileSetupScreen (Profile Information: Photo, Email, Name, Phone, etc.) |
|         │                                                                     |
|         ▼ [Continue Button / Verify Phone with SIM]                           |
|  4. POST /api/v1/auth/initiate-reverse-verify (Backend Processing & Persist)  |
|         │                                                                     |
|         ├────────────────────────────────────────┬────────────────────────────┤
|         ▼ [If Route B / Auto-Verified]           ▼ [If Route A / Reverse SMS] |
|  5. Direct Session Commit & NX ID Return  5. VerificationRadarScreen (SMS)   |
|         │                                        │                            |
|         │                                        ▼                            |
|         │                                 Carrier Webhook + Status Polling    |
|         │                                        │                            |
|         └────────────────────────────────────────┴────────────────────────────┤
|                                                  ▼                            |
|  6. ProfileBadgeScreen (Profile Ready / Holographic Identity Reveal)          |
|         │                                                                     |
|         ▼ [Continue]                                                          |
|  7. PermissionGatewayScreen -> MainChatDashboardScreen (Chats Home Screen)    |
|                                                                               |
|  *  SettingsScreen -> AccountScreen (Permanent Settings Profile + QR Code)    |
+-------------------------------------------------------------------------------+
```

---

## 3. Current Frontend / Backend Connections

| Operation | Client File & Component | Backend Endpoint | Status |
| :--- | :--- | :--- | :---: |
| **Google Sign-In** | `WelcomeViewModel.kt` (Credential Manager) | `POST /api/v1/auth/google` | **Implemented & Connected** |
| **Google ID Token Verify** | `AuthApiService.kt` (`googleAuth`) | `POST /api/v1/auth/google/verify` | **Implemented & Connected** |
| **Profile Setup Initiation** | `SetupViewModel.kt` (`initiateReverseVerify`) | `POST /api/v1/auth/initiate-reverse-verify` | **Implemented, DTO Incomplete** |
| **Reverse SMS Nonce Polling** | `VerificationRadarViewModel.kt` | `GET /api/v1/auth/verification-status` | **Implemented & Connected** |
| **Carrier SMS Match Webhook** | Server-side only | `POST /api/v1/webhooks/sms-inbound` | **Implemented & Connected** |
| **Challenge Login (Returning)**| `WelcomeViewModel.kt` | `POST /api/v1/auth/login-challenge` & `login-verify` | **Implemented & Connected** |
| **Remote Profile Fetch** | Missing in `AuthRepositoryImpl.kt` | `GET /api/v1/profile` | **Missing Client Connection** |
| **Remote Profile Update** | Only updates Room locally | `PUT /api/v1/profile` | **Missing Backend Dispatch** |
| **Settings Account / Profile**| `AccountScreen.kt` | Local `userDao` Flow only | **Partial (Missing Remote Sync)** |
| **Permanent Identity QR Code**| `QrViewModel.kt` (`QrDisplayScreen.kt`) | Local ECDSA/Ed25519 Signer | **Implemented in QrDisplay, Not in Account** |

---

## 4. Existing API Endpoints Analysis

### 1. `POST /api/v1/auth/google`
- **Handler:** `handleGoogleAuth` in [`backend/internal/auth/handler.go`](file:///H:/chat_app/backend/internal/auth/handler.go#L859)
- **Input:** `{ "id_token": string, "device_id": string, "public_key_hex": string }`
- **Verification:** Calls `googleVerifier.VerifyIdToken(token)` using Google's public JWKS keys.
- **Behavior:**
  - If existing account with same device: returns `REQUIRE_DEVICE_CHALLENGE` with challenge nonce.
  - If existing account with new device: returns `REQUIRE_SIM_VERIFICATION`.
  - If new user: returns `NEW_USER` with `google_uid`, `email`, `prefill_name`, `prefill_avatar`.

### 2. `POST /api/v1/auth/initiate-reverse-verify`
- **Handler:** `handleInitiateReverseVerify` in [`backend/internal/auth/reverse_sms.go`](file:///H:/chat_app/backend/internal/auth/reverse_sms.go#L210)
- **Input:** `InitiateReverseVerifyRequest` with `GoogleUID`, `GoogleAuthToken`, `Email`, `PhoneNumber`, `Name`, `AvatarURL`, `Gender`, `Age`, `Bio`, `PublicKeyHex`, `DhPublicKeyHex`, `DeviceID`, `DeviceName`, `IsReverify`.
- **Validation:** Server verifies `GoogleAuthToken` matches `GoogleUID`. Normalizes phone to E.164.
- **Behavior:**
  - Route B (Trusted Number): Allocates authentic `NX-XXXXXXXX`, commits `identity.UserAccount` and `identity.Device`, returns `status: "VERIFIED"`, `nx_id`, `session_auth_token`.
  - Route A (Reverse SMS): Generates 90s single-use nonce, returns `status: "PENDING"`, `inbound_receiver_number`, `sms_payload`.

### 3. `GET /api/v1/profile`
- **Handler:** `handleProfile` in [`backend/internal/auth/handler.go`](file:///H:/chat_app/backend/internal/auth/handler.go#L645)
- **Authentication:** Requires `Authorization: Bearer <sessionToken>`.
- **Behavior:** Validates session from `store.GetSession(token)`; queries `store.GetAccountByNxID(session.NxID)`; returns full `UserAccount` JSON.

### 4. `PUT /api/v1/profile`
- **Handler:** `handleProfile` in [`backend/internal/auth/handler.go`](file:///H:/chat_app/backend/internal/auth/handler.go#L665)
- **Authentication:** Requires `Authorization: Bearer <sessionToken>`.
- **Behavior:** Updates `ProfileName`, `Bio`, `Gender`, `IsDiscoverableByPhone`.
- **Gaps:** Currently does not handle updating `AvatarUrl` or `Age`.

---

## 5. Existing Database Schema & Columns

From [`backend/internal/database/migrations.go`](file:///H:/chat_app/backend/internal/database/migrations.go#L23):

```sql
CREATE TABLE IF NOT EXISTS accounts (
    id VARCHAR(64) PRIMARY KEY,
    nx_id VARCHAR(32) NOT NULL UNIQUE,
    public_key_hex VARCHAR(128) NOT NULL UNIQUE,
    dh_key_hex VARCHAR(128),
    phone_number VARCHAR(32),
    phone_verified BOOLEAN NOT NULL DEFAULT FALSE,
    is_discoverable_by_phone BOOLEAN NOT NULL DEFAULT TRUE,
    google_id VARCHAR(128),
    supabase_user_id VARCHAR(128),
    email VARCHAR(255),
    profile_name VARCHAR(128) NOT NULL DEFAULT '',
    avatar_url TEXT,
    bio VARCHAR(512),
    gender VARCHAR(32),
    age INT DEFAULT 0,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    CONSTRAINT chk_nx_id_format CHECK (nx_id ~ '^NX-[A-Z0-9]{8}$')
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_accounts_phone ON accounts(phone_number) 
    WHERE phone_number IS NOT NULL AND phone_number != '';
CREATE UNIQUE INDEX IF NOT EXISTS idx_accounts_google_id ON accounts(google_id) 
    WHERE google_id IS NOT NULL AND google_id != '';
CREATE UNIQUE INDEX IF NOT EXISTS idx_accounts_supabase_uid ON accounts(supabase_user_id) 
    WHERE supabase_user_id IS NOT NULL AND supabase_user_id != '';
CREATE INDEX IF NOT EXISTS idx_accounts_pubkey ON accounts(public_key_hex);
```

### Schema Assessment:
- All required fields for Google profile synchronization, identity, phone verification, and demographic metadata **are already present**.
- Unique index on `google_id` prevents duplicate accounts per Google user.
- Unique index on `nx_id` and regex format constraint `^NX-[A-Z0-9]{8}$` guarantees NX-ID format integrity.
- Unique index on `phone_number` prevents multiple accounts per phone.
- **Zero database migrations or schema alterations are required.**

---

## 6. Google Identity Persistence Status

- **Verification:** Securely performed on the backend using `GoogleVerifier` (JWKS RS256 signature verification). Client cannot forge Google claims.
- **Stable Identity Key:** `GoogleUID` (`claims.Subject`) is stored in `accounts.google_id` as the primary immutable identifier.
- **Email Storage:** `claims.Email` is stored in `accounts.email`.
- **Prefilled Metadata:** `claims.Name` and `claims.Picture` are returned to the client and prefilled in `ProfileSetupScreen`.
- **Status:** **Fully designed and implemented on the backend; client needs DTO and state alignment.**

---

## 7. Supabase Auth vs NEXA Account Relationship

- **Distinction:**
  - Supabase `auth.users` is Supabase's internal OAuth/JWT identity table.
  - NEXA `accounts` is the application's PostgreSQL identity table holding `nx_id`, cryptographic public keys, and profile metadata.
- **Current Architecture:**
  - NEXA backend authenticates Google users directly via Google ID tokens (JWKS RS256) without depending on Supabase Auth redirects.
  - The `accounts.supabase_user_id` column is available as an optional secondary federated identifier.
  - Direct Google authentication does NOT create unnecessary Supabase Auth users, avoiding redundant auth roundtrips and synchronization issues.
- **Status:** **Correctly architected and aligned.**

---

## 8. Profile Persistence Status

| Field | Source | Stored in PostgreSQL? | Stored in Room (Android)? |
| :--- | :--- | :---: | :---: |
| `nx_id` | Server Allocator | Yes (`accounts.nx_id`) | Yes (`UserEntity.nxId`) |
| `profile_name` / `fullName` | Google / User | Yes (`accounts.profile_name`) | Yes (`UserEntity.fullName`) |
| `phone_number` | User + SIM Verification | Yes (`accounts.phone_number`) | Yes (`UserEntity.phoneNumber`) |
| `google_id` | Google Verified Subject | Yes (`accounts.google_id`) | No (Kept in SecureStorage / Backend) |
| `email` | Google Verified Email | Yes (`accounts.email`) | **No (Missing in `UserEntity`)** |
| `avatar_url` | Google / User Photo | Yes (`accounts.avatar_url`) | Only in `SecureStorage` |
| `age` | User | Yes (`accounts.age`) | Yes (`UserEntity.age`) |
| `gender` | User | Yes (`accounts.gender`) | Yes (`UserEntity.gender`) |
| `bio` | User | Yes (`accounts.bio`) | Yes (`UserEntity.bio`) |
| `public_key_hex` | Android Keystore (Ed25519) | Yes (`accounts.public_key_hex`) | Yes (`UserEntity.publicKeyHex`) |

---

## 9. NX ID Generation and Assignment Status

- **Algorithm:** Base32 alphanumeric (8 chars), Luhn mod-32 checksum, prefix `NX-` (total 11 chars).
- **Backend Generator:** Implemented in [`backend/internal/identity/nxid.go`](file:///H:/chat_app/backend/internal/identity/nxid.go) and [`database/repository.go`](file:///H:/chat_app/backend/internal/database/repository.go#L28).
- **Idempotency:** `AllocateNxID(pubKeyHex)` checks if the public key already has an assigned NX-ID. If so, it returns the existing NX-ID without regenerating.
- **Database Guarantee:** Checked via `SELECT EXISTS(SELECT 1 FROM accounts WHERE nx_id = $1)` with up to 10 collision retries. Enforced by `UNIQUE` constraint and `chk_nx_id_format` regex.
- **Status:** **Fully implemented on the backend; client receives the assigned NX-ID.**

---

## 10. Profile Ready Navigation Status

- **Screen:** `ProfileBadgeScreen` in [`app/src/main/java/com/nexa/app/presentation/badge/ProfileBadgeScreen.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/badge/ProfileBadgeScreen.kt).
- **Current Trigger:** Navigated to from `VerificationRadarScreen` after polling returns `status: "VERIFIED"`, or from `OtpVerificationScreen`.
- **Missing Trigger:** Not navigated to directly from `ProfileSetupScreen` when `initiateReverseVerify` returns `status: "VERIFIED"` (Route B auto-verification).
- **Post-Ready Navigation:** On `ProfileBadgeScreen`, clicking Continue navigates to `PermissionGatewayScreen` -> `MainChatDashboardScreen` (Chats Home Screen). This sequence is preserved.

---

## 11. Settings Profile Data-Fetch Status

- **Screen:** `AccountScreen` in [`app/src/main/java/com/nexa/app/presentation/settings/account/AccountScreen.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/settings/account/AccountScreen.kt).
- **Current State:** Collects `authRepository.getActiveProfile()` (Room `user_profile` table only).
- **Missing Elements:**
  1. No remote fetch on screen mount to sync from `GET /api/v1/profile`.
  2. Does not display `email` (Gmail address).
  3. Does not display `avatar_url` (Profile photo).
  4. Does not display `age` or `gender`.
  5. Does not display the user's permanent `QR Code` card.

---

## 12. QR Code Generation Status

- **Onboarding (Profile Ready):** `ProfileBadgeScreen` generates a user QR code via `QrBitmapGenerator.generate("nexa://user?id=$nxId", sizePx = 400)`.
- **Handshake Screen:** `QrDisplayScreen` generates a cryptographically signed payload via `QrHandshakeRepositoryImpl.generateMyQrBitmap(...)`.
- **Settings Profile:** Currently missing from `AccountScreen`. Needs to be embedded using the existing `QrBitmapGenerator` or `QrCodeCard` component bound to the authenticated user's NX-ID.
- **Privacy Security:** The QR payload contains only the public NX-ID, display name, and DH public key. Never contains private keys, session tokens, or internal database IDs.

---

## 13. Missing Implementations & Gaps (Action Items)

1. **Android Client DTOs (`AuthDto.kt` & `AuthApiService.kt`):**
   - Extend `InitiateReverseVerifyResponseDto` to parse `nx_id`, `session_auth_token`, `verification_method`, `verified_at`.
   - Add `fetchRemoteProfile(sessionToken): Result<UserProfile>` calling `GET /api/v1/profile`.
   - Add `updateRemoteProfile(sessionToken, name, bio, gender, age, avatarUrl): Result<Boolean>` calling `PUT /api/v1/profile`.

2. **Android Client Repository (`AuthRepository` & `AuthRepositoryImpl`):**
   - Implement `fetchRemoteProfile()` to sync backend PostgreSQL profile into Room `UserDao` and `SecureStorage`.
   - Update `UserEntity` and `UserProfile` to include `email: String? = null` and `avatarUrl: String? = null`.
   - Handle Room migration or fallback gracefully.

3. **Android Client Flow (`SetupViewModel.kt` & `ProfileSetupScreen.kt`):**
   - In `SetupViewModel`: If `initiateReverseVerify` returns `status == "VERIFIED"` (trusted/auto-verified), save the verified session immediately and notify UI.
   - In `ProfileSetupScreen`: When `status == "VERIFIED"` is received, invoke `onProceedToBadge(nxId, name, phone, avatarUrl)` to open `ProfileBadgeScreen` directly.
   - In `AuthNavGraph.kt`: Wire `onProceedToBadge` in `ProfileSetup` to navigate to `Screen.ProfileBadge`.

4. **Android Settings Screen (`AccountScreen.kt`):**
   - Trigger `authRepository.fetchRemoteProfile()` on launch to ensure fresh data from PostgreSQL.
   - Add display items for:
     - Profile Photo Hero (`NexaAvatar`)
     - Gmail (`email`)
     - Age / Date of Birth
     - Gender
     - Identity QR Code card (rendered with `nx_id`)
   - Connect name and profile updates to `updateRemoteProfile`.

5. **Go Backend (`backend/internal/auth/handler.go`):**
   - In `handleProfile` (PUT): ensure `Age` and `AvatarUrl` can also be updated along with `ProfileName`, `Bio`, `Gender`.

---

## 14. Exact Files to Modify

| Component | File Path | Scope of Modification |
| :--- | :--- | :--- |
| **Backend** | [`backend/internal/auth/handler.go`](file:///H:/chat_app/backend/internal/auth/handler.go) | Enhance `handleProfile` (PUT) to update `age` and `avatar_url` |
| **Android DTO** | [`app/src/main/java/com/nexa/app/data/remote/dto/AuthDto.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/remote/dto/AuthDto.kt) | Add missing fields to `InitiateReverseVerifyResponseDto` and `RemoteProfileDto` |
| **Android API** | [`app/src/main/java/com/nexa/app/data/remote/api/AuthApiService.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/remote/api/AuthApiService.kt) | Add `fetchProfile()` and `updateProfile()` HTTP calls; parse `nx_id` and `session_auth_token` |
| **Android Room** | [`app/src/main/java/com/nexa/app/data/local/entity/UserEntity.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/local/entity/UserEntity.kt) | Add `email` and `avatarUrl` fields to `UserEntity` and mapping in `toDomain()` |
| **Android Domain** | [`app/src/main/java/com/nexa/app/domain/model/UserProfile.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/domain/model/UserProfile.kt) | Add `email: String?` |
| **Android Repo** | [`app/src/main/java/com/nexa/app/domain/repository/AuthRepository.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/domain/repository/AuthRepository.kt) | Add `fetchRemoteProfile()` and `updateProfile()` signatures |
| **Android Repo** | [`app/src/main/java/com/nexa/app/data/repository/AuthRepositoryImpl.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/repository/AuthRepositoryImpl.kt) | Implement `fetchRemoteProfile()` and `updateProfile()`; update Room persistence |
| **Android VM** | [`app/src/main/java/com/nexa/app/presentation/setup/SetupViewModel.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/setup/SetupViewModel.kt) | Handle immediate `status == "VERIFIED"` by saving session and setting ready flag |
| **Android Screen** | [`app/src/main/java/com/nexa/app/presentation/setup/ProfileSetupScreen.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/setup/ProfileSetupScreen.kt) | Add `onProceedToBadge` callback when verified immediately |
| **Android Nav** | [`app/src/main/java/com/nexa/app/presentation/navigation/AuthNavGraph.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/navigation/AuthNavGraph.kt) | Wire `onProceedToBadge` in `ProfileSetup` composable to navigate to `ProfileBadge` |
| **Android Settings**| [`app/src/main/java/com/nexa/app/presentation/settings/account/AccountScreen.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/settings/account/AccountScreen.kt) | Fetch remote profile; display Avatar, Gmail, Age, Gender, and QR Code |
