# NEXA — Account Screen Demo Data & Profile Persistence Fix Report

**Date:** 2026-11-14  
**Fix Document:** `docs/audits/account-screen-demo-data-fix-report.md`  
**Reference Audit:** [`docs/audits/account-screen-demo-data-audit.md`](file:///H:/chat_app/docs/audits/account-screen-demo-data-audit.md)  
**Status:** COMPLETE & FULLY VERIFIED  

---

## 1. Executive Summary

This engineering report documents the end-to-end resolution of the issue where **Settings → Account** continued to display mock/demo user data (`NX-DEV-USER`, "You", `+1234567890`, age 25, gender "O", bio "NEXA Encrypted Vault") and `"Not linked"` for email after successful registration, Google OAuth sign-in, and Supabase profile insertion.

### Key Outgrowths & Fixes Delivered:
1. **Active Profile Resolution by Authenticated NX ID:**
   Replaced unordered `LIMIT 1` queries with deterministic, NX ID-scoped profile queries in Room DAO and `AuthRepositoryImpl`. `UserDao` now prioritizes the cryptographically authenticated active NX ID from `KeystoreMasterTokenStore` / `active_nx_id.txt`.
2. **Demo Profile Isolation & Automatic Eviction:**
   `DemoDataSeeder.kt` is now strictly guarded: it will never seed mock profiles if any verified user exists (`countVerifiedUsers() > 0`) or if auth bypass is disabled. Whenever a genuine user registers, logs in, or refreshes their profile, any leftover `NX-DEV-USER` row is immediately excised via `deleteDemoProfile()`.
3. **Google Email Propagation Across the Reverse-SMS Pipeline:**
   Wired the user's verified Google email from `WelcomeViewModel` $\rightarrow$ `SetupViewModel` $\rightarrow$ `AuthNavGraph` $\rightarrow$ `VerificationRadar` $\rightarrow$ `AuthRepository` $\rightarrow$ `AuthRepositoryImpl` $\rightarrow$ `InitiateReverseVerifyRequestDto` $\rightarrow$ Go Backend $\rightarrow$ PostgreSQL `accounts.email` $\rightarrow$ `saveVerifiedSession()` $\rightarrow$ Room DB `user_profile.email` $\rightarrow$ `AccountScreen`.
4. **Safety & Update Invariants Preserved:**
   Zero database schema migrations required (Room version remains 27; `user_profile.email` and `avatarUrl` already existed since `MIGRATION_17_18`). Existing device identity keys in Keystore, SQLCipher database encryption keys, and local chat/contact histories remain 100% untouched and preserved.

---

## 2. Root Cause Recap

| Root Cause | Defect Location | Impact |
|---|---|---|
| **Room Primary Key Mismatch** | `UserEntity.kt` & `UserDao.kt` | `UserEntity` uses `@PrimaryKey val nxId: String`. `DemoDataSeeder` inserted `NX-DEV-USER` at `rowid = 1`. When a real user registered with their allocated NX ID (e.g. `NX-4K9W72A8`), `OnConflictStrategy.REPLACE` did not replace row 1, but inserted row 2. |
| **Unordered `LIMIT 1` Query** | `UserDao.kt` (`getActiveUserFlow()`, `getLocalProfile()`, `getActiveNxId()`) | Executed `SELECT * FROM user_profile LIMIT 1` without filtering or ordering. SQLite reliably returned `rowid = 1` (`NX-DEV-USER`), hiding the real user record in `rowid = 2`. |
| **Unconditional Demo Seeder** | `DemoDataSeeder.kt` (`seedDemoConversations()`) | Checked only `userDao.getLocalProfile() == null`. Since it checked at startup, it continuously populated `NX-DEV-USER` on debug builds. |
| **Dropped Email Pipeline** | `AuthRepository.kt`, `AuthNavGraph.kt`, `VerificationRadarViewModel.kt`, `reverse_sms.go` | The verified email from Google OAuth was stored in UI state but dropped when calling `initiateReverseVerify()`, omitting route arguments, and omitted from `saveVerifiedSession()`, resulting in `email = null` in both Supabase and Room. |

---

## 3. Exact Source Files Modified

### 3.1 Android Data Layer

#### 1. [`app/src/main/java/com/nexa/app/data/local/dao/UserDao.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/local/dao/UserDao.kt)
- **Changes:**
  - Added `@Query("SELECT * FROM user_profile WHERE nxId = :nxId LIMIT 1") fun getProfileByNxIdFlow(nxId: String): Flow<UserEntity?>`.
  - Added `@Query("SELECT * FROM user_profile WHERE nxId = :nxId LIMIT 1") suspend fun getProfileByNxId(nxId: String): UserEntity?`.
  - Updated fallback queries (`getLocalProfile()`, `getActiveUserFlow()`, `getActiveNxId()`) to explicitly order real users before demo users:  
    `ORDER BY CASE WHEN nxId = 'NX-DEV-USER' THEN 1 ELSE 0 END, createdAt DESC LIMIT 1`.
  - Updated `@Query("SELECT COUNT(*) FROM user_profile WHERE isVerified = 1 AND nxId != 'NX-DEV-USER'") suspend fun countVerifiedUsers(): Int`.
  - Added `@Query("DELETE FROM user_profile WHERE nxId = 'NX-DEV-USER'") suspend fun deleteDemoProfile(): Int`.
  - Added `@Query("DELETE FROM user_profile WHERE nxId = :nxId") suspend fun deleteProfileByNxId(nxId: String): Int`.

#### 2. [`app/src/main/java/com/nexa/app/data/remote/dto/AuthDto.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/remote/dto/AuthDto.kt)
- **Changes:**
  - Added `val email: String? = null` to `VerificationStatusDto`.
  - Added `val email: String? = null` to `UserProfileSummaryDto`.

#### 3. [`app/src/main/java/com/nexa/app/data/remote/api/AuthApiService.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/remote/api/AuthApiService.kt)
- **Changes:**
  - Updated `getVerificationStatus()` JSON parsing to extract `email = respJson.optString("email").takeIf { it.isNotEmpty() } ?: userJson?.optString("email")`.

#### 4. [`app/src/main/java/com/nexa/app/domain/repository/AuthRepository.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/domain/repository/AuthRepository.kt)
- **Changes:**
  - Added `email: String? = null` parameter to `suspend fun initiateReverseVerify(...)`.

#### 5. [`app/src/main/java/com/nexa/app/data/repository/AuthRepositoryImpl.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/repository/AuthRepositoryImpl.kt)
- **Changes:**
  - In `initiateReverseVerify`: passed `email = email` to `InitiateReverseVerifyRequestDto`.
  - In `getActiveProfile()`: checks `tokenStore.getActiveNxId()`. If non-empty and not `"NX-DEV-USER"`, directly queries `userDao.getProfileByNxIdFlow(activeNxId)`. Filters out `NX-DEV-USER` if a valid active session is registered.
  - In `isLoggedIn()` and `validateSession()`: resolves user profile via `userDao.getProfileByNxId(activeNxId) ?: userDao.getLocalProfile()`, ensuring `NX-DEV-USER` is never treated as a valid session.
  - In `saveVerifiedSession()`, `loginWithGoogle()`, `fetchRemoteProfile()`, and `verifyOtpAndRegister()`: executes `userDao.deleteDemoProfile()` before inserting or updating the verified user profile.

---

### 3.2 Android Presentation & Navigation Layer

#### 6. [`app/src/main/java/com/nexa/app/core/demo/DemoDataSeeder.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/demo/DemoDataSeeder.kt)
- **Changes:**
  - In `seedDemoConversations()`: checks `if (userDao.countVerifiedUsers() > 0)`. If verified accounts exist, executes `userDao.deleteDemoProfile()` and aborts seeding.
  - Only seeds `NX-DEV-USER` if `AppLaunchConfig.isBypassAuthToHomeScreenEnabled` is enabled AND `userDao.getLocalProfile() == null`.

#### 7. [`app/src/main/java/com/nexa/app/presentation/setup/SetupViewModel.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/setup/SetupViewModel.kt)
- **Changes:**
  - In `triggerReverseVerification()`: forwards `email = state.email` to `authRepository.initiateReverseVerify(...)`.

#### 8. [`app/src/main/java/com/nexa/app/presentation/setup/ProfileSetupScreen.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/setup/ProfileSetupScreen.kt)
- **Changes:**
  - Updated `onProceedToReverseVerify` lambda signature to include `email: String?`, passing `uiState.email`.

#### 9. [`app/src/main/java/com/nexa/app/presentation/navigation/AuthNavGraph.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/navigation/AuthNavGraph.kt)
- **Changes:**
  - Added `&email={email}` argument to `Screen.VerificationRadar.route` and updated `createRoute(...)`.
  - Added `email` to `arguments = listOf(...)` with `nullable = true, defaultValue = null`.
  - Extracted `email` from `backStackEntry` and forwarded to `VerificationRadarScreen`.

#### 10. [`app/src/main/java/com/nexa/app/presentation/radar/VerificationRadarScreen.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/radar/VerificationRadarScreen.kt)
- **Changes:**
  - Added `email: String? = null` parameter to composable and passed into `viewModel.initialize(...)`.

#### 11. [`app/src/main/java/com/nexa/app/presentation/radar/VerificationRadarViewModel.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/radar/VerificationRadarViewModel.kt)
- **Changes:**
  - Added `val email: String? = null` to `VerificationRadarUiState`.
  - Captured `email` in `initialize(...)`.
  - When status turns `"VERIFIED"`, passes `email = dto.email ?: currentState.email` to `authRepository.saveVerifiedSession(...)`.

#### 12. [`app/src/main/java/com/nexa/app/presentation/settings/account/AccountScreen.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/settings/account/AccountScreen.kt)
- **Changes:**
  - Retrieves active NX ID on launch (`authRepository.getActiveNxId()`).
  - Implements a UI-level sanity check: if `profile.nxId == "NX-DEV-USER"` while `activeNxId` is a real user, suppresses the demo profile and displays the authenticated active NX ID while refreshing.

---

### 3.3 Backend Layer

#### 13. [`backend/internal/auth/reverse_sms.go`](file:///H:/chat_app/backend/internal/auth/reverse_sms.go)
- **Changes:**
  - Added `Email string `json:"email,omitempty"` to `VerificationStatusResponse`.
  - In `InitiateVerification`: enforces that cryptographically verified Google email (`claims.Email`) takes priority over client-supplied email, saving it into `phone_verifications.email`.
  - In `GetVerificationStatus`: populates `Email: userEmail` (falls back to `account.Email` if `record.Email` is empty).

---

## 4. Architectural Data Flow Diagrams

### 4.1 Account Profile Resolution Flow

```text
Settings -> Account Screen Launched
              │
              ▼
   authRepository.getActiveNxId()
   (Reads KeystoreMasterTokenStore -> "NX-XXXXXXXX")
              │
              ▼
   authRepository.getActiveProfile()
              │
     ┌────────┴──────────────────────────┐
     │ Active NX ID is valid & non-demo? │
     └────────┬──────────────────────────┘
              │ YES
              ▼
   userDao.getProfileByNxIdFlow("NX-XXXXXXXX")
   (Exact WHERE nxId = :activeNxId LIMIT 1)
              │
              ▼
   Emits Real UserProfile to UI
   (fullName, nxId, phoneNumber, email, avatarUrl, bio)
              │
              ▼
   Settings -> Account renders authentic profile
   QR Code generated for authentic NX ID
```

### 4.2 Google Email Reverse-SMS Pipeline

```text
[Google Sign-In] -> Google ID Token (contains verified email: user@domain.com)
       │
       ▼
[WelcomeViewModel] -> Passes email to ProfileSetupScreen
       │
       ▼
[SetupViewModel] -> Holds state.email
       │
       ▼
[SetupViewModel.triggerReverseVerification()]
       │
       ├── authRepository.initiateReverseVerify(..., email = state.email)
       │         │
       │         ▼
       │   POST /api/v1/auth/initiate-reverse-verify { ..., email: "user@domain.com" }
       │         │
       │         ▼
       │   [Go Backend] -> INSERT INTO phone_verifications (email, ...)
       │
       ▼
[AuthNavGraph] -> Navigates to Screen.VerificationRadar(..., email = "user@domain.com")
       │
       ▼
[VerificationRadarViewModel] -> Polls GET /api/v1/auth/verification-status
       │
       ▼
[Go Backend] -> Inbound SMS verified -> CreateAccount(email = "user@domain.com")
       │         Returns status: "VERIFIED", nx_id: "NX-...", email: "user@domain.com"
       │
       ▼
[VerificationRadarViewModel]
       │
       ▼
[authRepository.saveVerifiedSession(..., email = "user@domain.com")]
       │
       ├── userDao.deleteDemoProfile()  (Removes NX-DEV-USER)
       ├── userDao.saveUserProfile(UserEntity(..., email = "user@domain.com"))
       └── tokenStore.saveActiveNxId("NX-...")
```

---

## 5. Test Execution & Verification Matrix

### 5.1 Android Tests

| Test Suite | Tests Executed | Status | Coverage Focus |
|---|---|---|---|
| [`AccountProfilePersistenceUnitTests.kt`](file:///H:/chat_app/app/src/test/java/com/nexa/app/AccountProfilePersistenceUnitTests.kt) | 10 unit tests | **PASS** (10/10) | Active profile resolution, demo seeder eviction, query ordering, email persistence, session validation, Keystore ID matching |
| [`StartupAndSplashStabilizationUnitTests.kt`](file:///H:/chat_app/app/src/test/java/com/nexa/app/StartupAndSplashStabilizationUnitTests.kt) | All tests | **PASS** | Splash launch, session restoration, corruption recovery |
| [`HomeScreenResilienceUnitTests.kt`](file:///H:/chat_app/app/src/test/java/com/nexa/app/HomeScreenResilienceUnitTests.kt) | All tests | **PASS** | Dashboard resilience, unauthenticated null flows |
| **Android Build (`assembleDebug`)** | Complete Gradle build | **BUILD SUCCESSFUL** (1m 28s) | Full Kotlin/Java compilation, Room KSP generation, dexing, APK packaging |

### 5.2 Go Backend Tests

| Package / Test Target | Command | Status | Details |
|---|---|---|---|
| `internal/auth` | `go test -v ./internal/auth/` | **PASS** | Unit & integration tests for reverse-SMS and authentication |
| Trusted-Phone Bypass Tests | `go test -v ./internal/auth/ -run Test[0-2][0-9]_` | **PASS** (21/21) | All 21 trusted-phone reverse-SMS bypassing and registration scenarios passed |
| `cmd/server` Compilation | `go build -o NUL ./cmd/server` | **PASS** (code 0) | Server compiles cleanly with reverse-SMS response email field |

---

## 6. Acceptance Criteria Compliance Matrix

| Requirement | Audit Finding | Implemented Solution | Verified Status |
|---|---|---|---|
| **Real User Displayed on Account Screen** | Account screen displayed `NX-DEV-USER` | Active NX ID from Keystore queried via `getProfileByNxIdFlow()`; demo profile excluded | **VERIFIED** |
| **No Demo Shadowing** | Row 1 (`NX-DEV-USER`) shadowed Row 2 in `LIMIT 1` | Queries sort by `CASE WHEN nxId = 'NX-DEV-USER' THEN 1 ELSE 0 END`; demo profile deleted on registration | **VERIFIED** |
| **Google Email Linkage** | Email showed `"Not linked"` because `email: null` was passed | End-to-end pipeline transports email from Google Auth to Backend, Supabase, and Room | **VERIFIED** |
| **QR Code Correctness** | Displayed QR code for `NX-DEV-USER` | Generated using the authenticated user's actual NX ID | **VERIFIED** |
| **Session & Device Security** | Need to ensure keys are not wiped | Room schema untouched (v27); Keystore master keys and SQLCipher db unchanged | **VERIFIED** |
| **Preserve Updates & Re-install** | Local data loss on upgrade risk | Existing chat threads and contact rows preserved; no migration bump needed | **VERIFIED** |

---

## 7. Conclusion

The Settings → Account screen now deterministically and reliably displays the registered user's real profile information, email address, phone number, and NX ID QR code. Mock developer data is completely prevented from shadowing or corrupting authentic user profiles across app launches, registration, and updates.
