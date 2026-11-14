# NEXA — Account Creation, Profile Synchronization & NX ID Integration Implementation Plan

**Document Reference:** `docs/audit/ACCOUNT_CREATION_IMPLEMENTATION_PLAN.md`  
**Date:** 2026-11-08  
**Phase:** Phase B — Comprehensive Implementation & Verification Plan  

---

## 1. Overview & Objectives

This implementation plan defines the exact modifications required to achieve complete, robust end-to-end functionality for NEXA's account creation, Google profile synchronization, NX-ID assignment, and permanent Settings Profile feature without altering any existing UI layouts, screen sequences, or Liquid Glass styling.

---

## 2. Files to Modify

### 1. Go Backend
- [`backend/internal/auth/handler.go`](file:///H:/chat_app/backend/internal/auth/handler.go):
  - In `ProfileUpdateRequest`, add `AvatarUrl string` and `Age int`.
  - In `handleProfile` (PUT), update `AvatarUrl` and `Age` on the `account` before calling `h.store.UpdateAccount(account)`.

### 2. Android Data Layer
- [`app/src/main/java/com/nexa/app/data/remote/dto/AuthDto.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/remote/dto/AuthDto.kt):
  - Extend `InitiateReverseVerifyResponseDto` with `nxId: String? = null`, `sessionAuthToken: String? = null`, `sessionToken: String? = null`, `verificationMethod: String? = null`, `verifiedAt: Long? = null`.
  - Add `RemoteUserProfileDto` to deserialize responses from `GET /api/v1/profile`.
- [`app/src/main/java/com/nexa/app/data/remote/api/AuthApiService.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/remote/api/AuthApiService.kt):
  - Update `initiateReverseVerify` response parsing to extract `nx_id`, `session_auth_token`, `session_token`, `verification_method`, `verified_at`.
  - Add `fetchProfile(sessionToken: String): Result<RemoteUserProfileDto>`.
  - Add `updateProfile(sessionToken: String, name: String?, bio: String?, gender: String?, age: Int?, avatarUrl: String?): Result<Boolean>`.
- [`app/src/main/java/com/nexa/app/data/local/entity/UserEntity.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/local/entity/UserEntity.kt):
  - Add `email: String? = null` and `avatarUrl: String? = null` to `UserEntity`.
  - Update `toDomain()` and `toEntity()` extensions.
- [`app/src/main/java/com/nexa/app/domain/model/UserProfile.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/domain/model/UserProfile.kt):
  - Add `email: String? = null`.
- [`app/src/main/java/com/nexa/app/domain/repository/AuthRepository.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/domain/repository/AuthRepository.kt):
  - Add `suspend fun fetchRemoteProfile(): Result<UserProfile>`.
  - Add `suspend fun updateRemoteProfile(name: String?, bio: String?, gender: String?, age: Int?, avatarUrl: String?): Result<Boolean>`.
- [`app/src/main/java/com/nexa/app/data/repository/AuthRepositoryImpl.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/repository/AuthRepositoryImpl.kt):
  - Implement `fetchRemoteProfile()`: calls `apiService.fetchProfile(token)`, updates `UserEntity` in `userDao`, saves active NX-ID and avatar in `secureStorage`.
  - Implement `updateRemoteProfile()`: calls `apiService.updateProfile(...)` and updates local `userDao`.
  - Update `saveVerifiedSession()` to also accept `email: String? = null`.

### 3. Android Presentation Layer
- [`app/src/main/java/com/nexa/app/presentation/setup/SetupViewModel.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/setup/SetupViewModel.kt):
  - In `triggerReverseVerification()`:
    - If `result.isSuccess` and `payload.status == "VERIFIED"` (with non-null `nxId` and `sessionAuthToken`):
      - Call `authRepository.saveVerifiedSession(...)`.
      - Update UI state with `isAutoVerified = true`, `allocatedNxId = payload.nxId`.
    - If `payload.status == "PENDING"`:
      - Set `reverseVerifyPayload = payload`, `isOtpTriggered = true` (proceeds to Reverse SMS).
- [`app/src/main/java/com/nexa/app/presentation/setup/ProfileSetupScreen.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/setup/ProfileSetupScreen.kt):
  - Add parameter `onProceedToBadge: ((nxId: String, name: String, phone: String, avatarUrl: String?) -> Unit)? = null`.
  - In `LaunchedEffect(uiState.isAutoVerified)`: if auto-verified, invoke `onProceedToBadge(...)`.
  - If not auto-verified, continue invoking `onProceedToReverseVerify(...)`.
- [`app/src/main/java/com/nexa/app/presentation/navigation/AuthNavGraph.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/navigation/AuthNavGraph.kt):
  - In `composable(route = Screen.ProfileSetup.route)`, provide `onProceedToBadge`:
    ```kotlin
    onProceedToBadge = { nxId, name, phone, avatarUrl ->
        navController.navigate(
            Screen.ProfileBadge.createRoute(nxId, name, phone, avatarUrl)
        ) {
            popUpTo(Screen.Welcome.route) { inclusive = true }
        }
    }
    ```
- [`app/src/main/java/com/nexa/app/presentation/settings/account/AccountScreen.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/settings/account/AccountScreen.kt):
  - Add `LaunchedEffect(Unit)` calling `authRepository.fetchRemoteProfile()` to sync live PostgreSQL data.
  - In Section A (Account Information):
    - Profile Photo Avatar Hero with `NexaAvatar`.
    - Display Name card (with edit dialog).
    - Gmail (`profile.email`) card.
    - Phone Number card (with change phone action).
    - Age card (if present).
    - Gender card (if present).
    - NEXA ID card (Locked).
    - Permanent Identity QR Code Card (rendered from `nxId` via `QrBitmapGenerator`).

---

## 3. Database Changes

**Zero database migrations or SQL schema changes are required.**
- The PostgreSQL `accounts` table in `backend/internal/database/migrations.go` already defines:
  `id`, `nx_id`, `public_key_hex`, `dh_key_hex`, `phone_number`, `phone_verified`, `is_discoverable_by_phone`, `google_id`, `supabase_user_id`, `email`, `profile_name`, `avatar_url`, `bio`, `gender`, `age`, `created_at`, `updated_at`.
- Unique constraints on `nx_id`, `phone_number`, `google_id` are already present.

---

## 4. Security Checks & Safeguards

1. **Server-Side Token Verification:** Never trust client-submitted email, name, or UID without Google signature verification.
2. **Account Ownership Check:** In `GET /api/v1/profile` and `PUT /api/v1/profile`, session token ownership is checked; users can only read or update their own account.
3. **No Key Exposure in QR:** QR code payload only contains `nx_id`, display name, and DH public key. Never includes private keys, session tokens, or internal database IDs.
4. **Idempotent Retries:** Duplicate taps on Continue or multiple verify requests return the same NX-ID and session token without corrupting database state.

---

## 5. Test Plan

1. **Unit & Integration Tests in Go:**
   - Test Google Auth with valid token (returns `NEW_USER` or `LOGIN_SUCCESS`).
   - Test Profile setup and account creation in PostgreSQL (stores Google ID, Email, Name, Avatar, Age, Gender, Bio, Phone).
   - Test NX-ID allocation uniqueness and format (`NX-XXXXXXXX`).
   - Test GET `/api/v1/profile` with valid session token (returns full profile).
   - Test PUT `/api/v1/profile` (updates display name, age, avatar, bio).
   - Test unauthorized access to `/api/v1/profile` (returns 401).
2. **Android UI & Flow Verification:**
   - Welcome Screen -> Google sign-in -> receives prefilled profile data.
   - Profile Setup Screen -> enters phone, age, gender -> taps Continue / Verify.
   - Auto-verified trusted flow: skips SMS, immediately navigates to `ProfileBadgeScreen` (`Profile Ready`).
   - Standard Reverse SMS flow: proceeds to `VerificationRadarScreen`, completes SMS verification -> navigates to `ProfileBadgeScreen`.
   - `ProfileBadgeScreen` -> taps Continue -> navigates to `PermissionGateway` -> `Chats Home Screen`.
   - Settings -> Account: fetches remote profile, displays Name, Photo, Gmail, Phone, Age, Gender, NX-ID, and QR code.
