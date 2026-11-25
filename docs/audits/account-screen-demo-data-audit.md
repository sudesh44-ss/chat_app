# NEXA — Settings → Account Screen Demo Data Audit Report

**Date:** 2026-11-14  
**Audit Document:** `docs/audits/account-screen-demo-data-audit.md`  
**Execution Mode:** Read-only audit (no source files or database schemas were modified)  
**Scope:** Settings → Account profile display, Room `user_profile` table, `DemoDataSeeder`, Google registration & reverse-SMS onboarding data flows.

---

## 1. Executive Summary & Root Cause Analysis

### 1.1 The Primary Root Cause: Primary Key & Query Mismatch in Room
The reason Settings → Account continues to display demo/placeholder profile information (`NX-DEV-USER`, "You", `+1234567890`, Age 25, Gender "O", Bio "NEXA Encrypted Vault") after a successful registration is a **dual flaw in local SQLite/Room data persistence and query structure**:

1. **Unconditional Seeding of Mock Profile at App Launch:**
   - In [`NexaApplication.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/NexaApplication.kt) (lines 738–744) and [`MainChatDashboardScreen.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/dashboard/MainChatDashboardScreen.kt) (lines 216–224), [`DemoDataSeeder.seedDemoConversations(database)`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/demo/DemoDataSeeder.kt) is invoked whenever `BuildConfig.DEBUG` is true.
   - In `DemoDataSeeder.kt` (lines 89–104), if `userDao.getLocalProfile() == null` on first launch, it inserts:
     ```kotlin
     UserEntity(
         nxId = "NX-DEV-USER",
         fullName = "You",
         phoneNumber = "+1234567890",
         age = 25,
         gender = "O",
         bio = "NEXA Encrypted Vault",
         publicKeyHex = "00".repeat(32),
         isVerified = true
     )
     ```
   - This writes Row 1 into SQLite table `user_profile` with `rowid = 1` and `nxId = "NX-DEV-USER"`.

2. **Room Entity Uses `nxId` as `@PrimaryKey`, so Registration Does Not Replace the Seeded Profile:**
   - In [`UserEntity.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/local/entity/UserEntity.kt) (line 9):
     ```kotlin
     @Entity(tableName = "user_profile")
     data class UserEntity(
         @PrimaryKey
         val nxId: String,
         ...
     )
     ```
   - In [`UserDao.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/local/dao/UserDao.kt) (line 13):
     ```kotlin
     @Insert(onConflict = OnConflictStrategy.REPLACE)
     suspend fun saveUserProfile(user: UserEntity): Long
     ```
   - When a real user completes registration, their actual NX ID (e.g., `NX-4K9W72A8`) is allocated. `authRepository.saveVerifiedSession()` calls `userDao.saveUserProfile(userEntity)`.
   - Because `OnConflictStrategy.REPLACE` only replaces rows with matching primary keys, and `"NX-4K9W72A8"` does not conflict with `"NX-DEV-USER"`, SQLite executes an `INSERT`.
   - The table `user_profile` now contains **two distinct rows**:
     - `rowid = 1`: `nxId = "NX-DEV-USER"` (Mock dev user)
     - `rowid = 2`: `nxId = "NX-4K9W72A8"` (Real registered user)

3. **`UserDao` Queries Without Filtering by Active NX ID:**
   - In [`UserDao.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/local/dao/UserDao.kt):
     ```kotlin
     @Query("SELECT * FROM user_profile LIMIT 1")
     fun getActiveUserFlow(): Flow<UserEntity?>

     @Query("SELECT * FROM user_profile LIMIT 1")
     suspend fun getLocalProfile(): UserEntity?

     @Query("SELECT nxId FROM user_profile LIMIT 1")
     suspend fun getActiveNxId(): String?
     ```
   - In SQLite, `SELECT * FROM user_profile LIMIT 1` with no `WHERE` clause and no `ORDER BY` clause returns the oldest row (`rowid = 1`), which is permanently `NX-DEV-USER`.
   - When [`AccountScreen.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/settings/account/AccountScreen.kt) observes `authRepository.getActiveProfile().collectAsState()`, it receives `NX-DEV-USER`.
   - Even when `AccountScreen` invokes `authRepository.fetchRemoteProfile()` in `LaunchedEffect`, `fetchRemoteProfile()` upserts the real profile into `rowid = 2` (`NX-4K9W72A8`). Room triggers a re-query of `SELECT * FROM user_profile LIMIT 1`, which **still returns `rowid = 1` (`NX-DEV-USER`)**.

---

### 1.2 The Secondary Root Cause: Email Parameter Dropped in Reverse-SMS Pipeline
A secondary defect causes the user's Google email to display as `"Not linked"` on Settings → Account:
1. When Google login returns `NEW_USER`, [`WelcomeViewModel.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/welcome/WelcomeViewModel.kt) captures the Google email from `GoogleIdTokenCredential.id` and navigates to `ProfileSetupScreen`.
2. [`SetupViewModel.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/setup/SetupViewModel.kt) holds the email in `ProfileSetupUiState.email`.
3. However, `authRepository.initiateReverseVerify(...)` in [`AuthRepository.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/domain/repository/AuthRepository.kt) has **no `email` parameter**.
4. In [`AuthRepositoryImpl.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/repository/AuthRepositoryImpl.kt) (line 423), `InitiateReverseVerifyRequestDto` is instantiated with `email = null`.
5. The Go backend receives `email: ""` and saves `NULL` into PostgreSQL `accounts.email`.
6. Furthermore, [`AuthNavGraph.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/navigation/AuthNavGraph.kt) (lines 105–131) defines `Screen.VerificationRadar` without an `email` route argument.
7. [`VerificationRadarViewModel.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/radar/VerificationRadarViewModel.kt) (lines 217–226) calls `saveVerifiedSession()` without an `email` argument (defaulting to `null`).
8. As a result, even if the primary key issue is resolved, `email` remains `null` locally and in the remote database.

---

## 2. Exact Source File Paths and Functions/Classes

| Component | File Path | Function / Class | Line Numbers | Role in Defect |
|---|---|---|---|---|
| **Demo Seeder** | `app/src/main/java/com/nexa/app/core/demo/DemoDataSeeder.kt` | `DemoDataSeeder.seedDemoConversations()` | 88–106 | Seeds `NX-DEV-USER` into Room `user_profile` table on debug app launch |
| **Room DAO** | `app/src/main/java/com/nexa/app/data/local/dao/UserDao.kt` | `getActiveUserFlow()`, `getLocalProfile()`, `getActiveNxId()` | 16–24 | Uses `SELECT * FROM user_profile LIMIT 1` without filtering by `activeNxId` or clearing old records |
| **Room Entity** | `app/src/main/java/com/nexa/app/data/local/entity/UserEntity.kt` | `data class UserEntity` | 7–21 | Uses `@PrimaryKey val nxId: String`, preventing replacing `NX-DEV-USER` when real NX ID is saved |
| **Account Screen UI** | `app/src/main/java/com/nexa/app/presentation/settings/account/AccountScreen.kt` | `AccountScreen()` | 101–124 | Collects `authRepository.getActiveProfile()`, which reads rowid 1 from `UserDao` |
| **Auth Repository** | `app/src/main/java/com/nexa/app/data/repository/AuthRepositoryImpl.kt` | `getActiveProfile()`, `fetchRemoteProfile()`, `saveVerifiedSession()` | 273–281, 458–487, 489–525 | Reads from `userDao.getActiveUserFlow()`; does not clear table or purge `NX-DEV-USER` on save |
| **Reverse SMS DTO** | `app/src/main/java/com/nexa/app/domain/repository/AuthRepository.kt` & `AuthRepositoryImpl.kt` | `initiateReverseVerify()` | Lines 29–39 (interface), 407–438 (impl) | Omits `email` parameter, resulting in `email: null` sent to Go backend |
| **Navigation Graph** | `app/src/main/java/com/nexa/app/presentation/navigation/AuthNavGraph.kt` | `Screen.VerificationRadar`, `onProceedToReverseVerify` | 105–131, 331–346 | Drops `email` argument when transitioning from Profile Setup to Verification Radar |
| **Radar ViewModel** | `app/src/main/java/com/nexa/app/presentation/radar/VerificationRadarViewModel.kt` | `startPollingStatus()` | 217–226 | Omits `email` in `saveVerifiedSession` call |

---

## 3. Registration-to-Supabase Data Flow

```
[Android User]
      │
      │ 1. Tap "Continue with Google"
      ▼
[Android CredentialManager]
      │
      │ 2. Returns Google ID Token (JWT)
      ▼
[WelcomeViewModel -> AuthRepositoryImpl]
      │
      │ 3. POST /api/v1/auth/google
      ▼
[Go Backend: google_verifier.go]
      │
      │ 4. Verifies RS256 with Google JWKS -> Returns status: "NEW_USER"
      │    Payload: { google_uid, email, prefill_name, prefill_avatar }
      ▼
[Android: ProfileSetupScreen]
      │
      │ 5. User enters phone (+91...), age, gender, bio
      │ 6. Taps "Verify" -> calls SetupViewModel.triggerReverseVerification()
      ▼
[AuthRepositoryImpl.initiateReverseVerify]
      │
      │ 7. [DEFECT] Request created with email = null
      │    POST /api/v1/auth/initiate-reverse-verify
      ▼
[Go Backend: reverse_sms.go]
      │
      │ 8. AllocateUniqueNxID() -> Generates "NX-XXXXXXXX"
      │ 9. Saves pending record into PostgreSQL table `phone_verifications`
      │ 10. Returns { status: "PENDING", nonce, inbound_receiver_number, sms_payload }
      ▼
[Android: VerificationRadarScreen]
      │
      │ 11. Dispatches SMS to carrier receiver number
      │ 12. Polls GET /api/v1/auth/verification-status?nonce=...
      ▼
[Carrier Inbound Webhook / Backend Match]
      │
      │ 13. Sender phone verified against SMS payload nonce
      │ 14. Backend runs PostgresRepository.CreateAccount():
      │     - INSERT INTO accounts (id, nx_id, public_key_hex, phone_hmac, phone_encrypted, email, profile_name, ...)
      │     - INSERT INTO devices (device_id, nx_id, public_key_hex, ...)
      │     - INSERT INTO sessions (session_token_hash, nx_id, device_id, expires_at)
      ▼
[Android: VerificationRadarViewModel]
      │
      │ 15. Polling receives status: "VERIFIED", nx_id, session_auth_token
      │ 16. Calls authRepository.saveVerifiedSession(nxId, sessionToken, name, phone, avatarUrl, age, gender, bio)
      │     [DEFECT] email is omitted (defaults to null)
      ▼
[AuthRepositoryImpl.saveVerifiedSession]
      │
      │ 17. secureStorage.saveAuthToken(sessionToken)
      │ 18. secureStorage.saveActiveNxId(nxId)
      │ 19. userDao.saveUserProfile(UserEntity(nxId = "NX-XXXXXXXX", ...))
      │     [DEFECT] Room table user_profile already contains rowid 1 ("NX-DEV-USER").
      │              Because nxId is primary key, "NX-XXXXXXXX" is inserted as rowid 2.
      ▼
[Android: Dashboard -> Settings -> Account]
```

---

## 4. Account-Screen Data Flow

```
[User Navigates to Settings -> Account]
      │
      │ 1. Screen composable AccountScreen() is rendered
      ▼
[AccountScreen.kt]
      │
      │ 2. val profileState by authRepository.getActiveProfile().collectAsState(initial = null)
      ▼
[AuthRepositoryImpl.getActiveProfile]
      │
      │ 3. userDao.getActiveUserFlow().map { it?.toDomain(secureStorage.getAvatarUrl()) }
      ▼
[UserDao.kt]
      │
      │ 4. Query executed: @Query("SELECT * FROM user_profile LIMIT 1")
      │    Table contains:
      │      - rowid 1: nxId="NX-DEV-USER", fullName="You", phone="+1234567890", age=25...
      │      - rowid 2: nxId="NX-4K9W72A8", fullName="Real User", phone="+919876543210"...
      │    SQLite returns: rowid 1 ("NX-DEV-USER")
      ▼
[AccountScreen.kt UI State]
      │
      │ 5. val profile = profileState (rowid 1: "NX-DEV-USER")
      │    - displayName = "You"
      │    - phoneNumber = "+1234567890"
      │    - email = "Not linked"
      │    - nxId = "NX-DEV-USER"
      │    - ageText = "25 years"
      │    - genderText = "O"
      │    - QR Code bitmap = "nexa://user?id=NX-DEV-USER"
      ▼
[LaunchedEffect(Unit)]
      │
      │ 6. authRepository.fetchRemoteProfile() runs concurrently in background
      ▼
[AuthRepositoryImpl.fetchRemoteProfile]
      │
      │ 7. GET /api/v1/profile with Bearer <sessionToken>
      │ 8. Server returns HTTP 200 with real profile (NX-4K9W72A8)
      │ 9. userDao.saveUserProfile(UserEntity(nxId = "NX-4K9W72A8", ...))
      │    Updates rowid 2 in user_profile table!
      │ 10. Room invalidates getActiveUserFlow() observer.
      │ 11. DAO re-evaluates: @Query("SELECT * FROM user_profile LIMIT 1")
      │     SQLite AGAIN returns rowid 1 ("NX-DEV-USER")!
      ▼
[Result on Screen]
Settings -> Account stays stuck displaying demo data ("NX-DEV-USER", "You", "+1234567890").
```

---

## 5. Exact Mismatch Between the Two Flows

1. **Storage vs Query Mismatch:**
   - Registration and remote synchronization store the user under their real, authoritative identifier `nxId = "NX-XXXXXXXX"`.
   - The query `SELECT * FROM user_profile LIMIT 1` does not filter by the authenticated user's `nxId` (`WHERE nxId = :activeNxId`).
   - The database seeding mechanism inserts `NX-DEV-USER` prior to registration and is never cleared or replaced.
2. **Table Design Inconsistency:**
   - `UserEntity` treats `nxId` as a multi-user primary key (`@PrimaryKey val nxId: String`).
   - `UserDao` treats `user_profile` as a singleton table (`LIMIT 1`).
   - Because `saveUserProfile` does not delete old rows, every new registration or demo seed accumulates rows without cleaning up existing entries.
3. **Data Loss in Onboarding Transit:**
   - Google Sign-In captures the user's verified Google email.
   - The transit layer (`AuthRepository.initiateReverseVerify`, `InitiateReverseVerifyRequestDto`, `Screen.VerificationRadar`, `VerificationRadarViewModel`) drops the email parameter.
   - The backend account is created with `email: null`, and the local `UserEntity` is saved with `email = null`.

---

## 6. Recommended Minimal Fix

To fix this issue cleanly without architectural breakage or unnecessary changes:

### Fix 1: Single-Active-User Room Invariant in `UserDao` and `AuthRepositoryImpl`
1. Update `UserDao.kt` to allow querying by active NX ID or clearing prior sessions on new save:
   ```kotlin
   @Query("SELECT * FROM user_profile WHERE nxId = :nxId LIMIT 1")
   fun getProfileByNxIdFlow(nxId: String): Flow<UserEntity?>

   @Query("SELECT * FROM user_profile WHERE nxId = :nxId LIMIT 1")
   suspend fun getProfileByNxId(nxId: String): UserEntity?
   ```
2. In `AuthRepositoryImpl.kt`:
   - In `saveVerifiedSession()`, `loginWithGoogle()`, and `verifyOtpAndRegister()`, call `userDao.clearProfile()` before saving the new verified profile, guaranteeing only the currently authenticated user's profile exists in `user_profile`:
     ```kotlin
     userDao.clearProfile()
     userDao.saveUserProfile(userEntity)
     ```
   - In `getActiveProfile()`:
     ```kotlin
     override fun getActiveProfile(): Flow<UserProfile?> {
         val activeNxId = secureStorage.getActiveNxId()
         val flow = if (!activeNxId.isNullOrBlank()) {
             userDao.getProfileByNxIdFlow(activeNxId)
         } else {
             userDao.getActiveUserFlow()
         }
         return flow
             .map { it?.toDomain(secureStorage.getAvatarUrl()) }
             .catch { emit(null) }
             .flowOn(Dispatchers.IO)
     }
     ```

### Fix 2: Prevent Demo Seeder Overwriting or Lingering During Authenticated Sessions
In `DemoDataSeeder.kt`:
- Add a guard to skip seeding if a real session or real NX ID is already active in `SecureStorage` or if `userDao.getLocalProfile()?.nxId != null && userDao.getLocalProfile()?.nxId != "NX-DEV-USER"`.

### Fix 3: Thread Google Email Through Reverse-SMS Registration
1. In `AuthRepository.kt`: Add `email: String? = null` to `initiateReverseVerify()`.
2. In `AuthRepositoryImpl.kt`: Forward `email = email` into `InitiateReverseVerifyRequestDto`.
3. In `SetupViewModel.kt`: Pass `email = state.email` into `authRepository.initiateReverseVerify()`.
4. In `AuthNavGraph.kt`: Add `email` as a route argument in `Screen.VerificationRadar` and forward it from `onProceedToReverseVerify`.
5. In `VerificationRadarViewModel.kt`: Pass `email = currentState.email` into `authRepository.saveVerifiedSession()`.

---

## 7. Required Regression Tests

Before and after applying any fix, verify:

| Test Case | Scenario | Expected Outcome |
|---|---|---|
| **TC-ACC-01** | Fresh Install & Google Sign-In | Settings → Account displays the real Google display name, email, allocated NX ID (`NX-XXXXXXXX`), and generated QR code |
| **TC-ACC-02** | Debug Build Launch | `DemoDataSeeder` does not overwrite or corrupt an existing authenticated user's profile |
| **TC-ACC-03** | Edit Display Name | Editing name on `AccountScreen` persists locally to Room and reflects immediately in the UI |
| **TC-ACC-04** | Remote Profile Sync | `authRepository.fetchRemoteProfile()` updates the active profile and Room Flow emits the updated entity |
| **TC-ACC-05** | App Kill & Restart | Relaunching the app opens Dashboard → Settings → Account still showing the authenticated user's profile, not `NX-DEV-USER` |
| **TC-ACC-06** | Logout & Re-login | Calling `clearSession()` purges `user_profile`; subsequent login creates a clean single profile for the new user |

---

## 8. Confirmation of Read-Only Compliance

- **No source files in `app/` or `backend/` were modified.**
- **No database schemas, migrations, or live records were altered.**
- **No secrets, tokens, or private keys were exposed in this report.**
- **The fix remains unimplemented pending user review and approval.**
