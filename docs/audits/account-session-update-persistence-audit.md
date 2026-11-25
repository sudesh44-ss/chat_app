# NEXA — Account Data, Session Persistence & App Update Audit Report

**Audit Date:** 2026-11-14  
**Audit Document:** `docs/audits/account-session-update-persistence-audit.md`  
**Execution Mode:** Read-Only Technical Audit (Zero source files, configurations, database schemas, or migrations modified)  
**Scope:** Settings → Account demo data root causes, registration-to-profile data pipeline, session storage and restoration, Android app update continuity, signing identity, SQLCipher/Room database migrations, device identity, backup discovery, and upgrade test plan.

---

## 1. Account Screen Root Cause Analysis (Part 1)

### 1.1 Confirmed Symptom
After completing Google Sign-In, profile creation, and phone verification, the account was successfully created on the server and in Supabase, and an NX ID was allocated. However, navigating to **Settings → Account** continued to display demo/placeholder profile information (`NX-DEV-USER`, "You", `+1234567890`, Age 25, Gender "O", Bio "NEXA Encrypted Vault", with email showing `"Not linked"`).

### 1.2 Root Cause Breakdown

The issue stemmed from **three distinct flaws** across data seeding, Room SQLite query logic, and email parameter propagation:

#### Primary Flaw: Room Entity Primary Key & Query Structure
1. **Mock Seeding on Launch:**
   - In [`DemoDataSeeder.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/demo/DemoDataSeeder.kt#L89-L107), whenever the app launched in debug mode, if `userDao.getLocalProfile() == null`, it inserted a mock profile:
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
     This wrote Row 1 into the `user_profile` table (`rowid = 1`, `nxId = "NX-DEV-USER"`).

2. **Primary Key Mismatch Preventing Replacement:**
   - In [`UserEntity.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/local/entity/UserEntity.kt#L37), `nxId` is the `@PrimaryKey`.
   - In [`UserDao.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/local/dao/UserDao.kt#L13), `saveUserProfile` uses `OnConflictStrategy.REPLACE`.
   - When a genuine user registered, their real NX ID was allocated (e.g. `NX-4K9W72A8`). When `saveVerifiedSession` called `saveUserProfile(userEntity)`, SQLite saw that `NX-4K9W72A8` did not conflict with `NX-DEV-USER`. It therefore executed an `INSERT` rather than an `UPDATE/REPLACE`.
   - The table now contained two distinct rows:
     - `rowid = 1`: `nxId = "NX-DEV-USER"`
     - `rowid = 2`: `nxId = "NX-4K9W72A8"`

3. **Unfiltered Single-Row Queries:**
   - `UserDao` originally queried:
     ```sql
     SELECT * FROM user_profile LIMIT 1
     ```
   - In SQLite, an unordered `LIMIT 1` query without a `WHERE` clause returns the lowest `rowid` (`rowid = 1`), which was permanently the seeded `NX-DEV-USER`.
   - When `AccountScreen.kt` collected `authRepository.getActiveProfile()`, it received the mock dev user.

#### Secondary Flaw: Dropped Email Parameter in Reverse-SMS Pipeline
1. In `WelcomeViewModel.kt`, Google OAuth retrieved the user's verified Google email address and passed it to `ProfileSetupScreen`.
2. However, `authRepository.initiateReverseVerify(...)` in `AuthRepository.kt` did not accept an `email` parameter.
3. `InitiateReverseVerifyRequestDto` was sent to the Go backend with `email = null`. The backend stored `NULL` in the PostgreSQL `accounts.email` column.
4. `Screen.VerificationRadar` in `AuthNavGraph.kt` did not declare an `email` route argument, so `VerificationRadarViewModel` called `saveVerifiedSession()` without an email.
5. As a result, Settings → Account displayed `email: "Not linked"`.

---

## 2. Registration-to-Profile Data Flow

```text
[User]
   │ 1. Tap "Continue with Google"
   ▼
[Android CredentialManager]
   │ 2. Retrieves GoogleIdTokenCredential (ID Token JWT + email)
   ▼
[WelcomeViewModel -> AuthRepositoryImpl]
   │ 3. POST /api/v1/auth/google
   ▼
[Go Backend: google_verifier.go]
   │ 4. Verifies RS256 with Google JWKS -> Returns "NEW_USER" (or "EXISTING_USER")
   ▼
[Android: ProfileSetupScreen]
   │ 5. User enters phone (+91...), age, gender, bio
   │ 6. Taps "Verify" -> SetupViewModel.triggerReverseVerification()
   ▼
[AuthRepositoryImpl.initiateReverseVerify]
   │ 7. POST /api/v1/auth/initiate-reverse-verify (Includes: phone, name, email, public_key_hex, device_id)
   ▼
[Go Backend: reverse_sms.go]
   │ 8. Allocates unique NX ID -> "NX-XXXXXXXX"
   │ 9. Stores pending verification in phone_verifications table / Redis
   │ 10. Returns { status: "PENDING", nonce: "NX-XXXXX", inbound_receiver_number }
   ▼
[Android: VerificationRadarScreen]
   │ 11. Dispatches Intent.ACTION_SENDTO to native SMS app with body: "NX-VERIFY NX-XXXXX"
   │ 12. User sends SMS via carrier
   ▼
[Telecom Inbound Gateway -> Go Backend: /api/v1/webhooks/sms-inbound]
   │ 13. Verifies HMAC-SHA256 signature
   │ 14. Matches sender MSISDN + Nonce
   │ 15. Creates account in PostgreSQL:
   │     - accounts (id, nx_id, public_key_hex, phone_hmac, email, profile_name, ...)
   │     - devices (device_id, nx_id, public_key_hex, ...)
   │     - sessions (session_token_hash, nx_id, device_id, expires_at)
   ▼
[Android: VerificationRadarViewModel Polling Loop]
   │ 16. Polls GET /api/v1/auth/verification-status?nonce=...
   │ 17. Receives status: "VERIFIED", nx_id, session_auth_token
   │ 18. Calls authRepository.saveVerifiedSession(...)
   ▼
[Android Local Persistence: Room + SecureStorage]
   │ 19. SecureStorage.saveAuthToken(token)
   │ 20. SecureStorage.saveActiveNxId(nxId)
   │ 21. UserDao.deleteDemoProfile()
   │ 22. UserDao.saveUserProfile(userEntity) (Real NX ID, verified=true, email set)
```

---

## 3. Account Screen Data Flow

```text
[User Navigates to Settings -> Account]
   │
   ▼
[AccountScreen.kt]
   │ 1. Collects Flow: val profileState by authRepository.getActiveProfile().collectAsState()
   │ 2. LaunchedEffect(Unit):
   │    - activeNxIdState = authRepository.getActiveNxId()
   │    - authRepository.fetchRemoteProfile()
   │
   ▼
[AuthRepositoryImpl.getActiveProfile()]
   │ 3. Reads activeNxId = secureStorage.getActiveNxId()
   │ 4. If activeNxId != null:
   │       userDao.getProfileByNxIdFlow(activeNxId)
   │    Else:
   │       userDao.getActiveUserFlow() (Ordered by CASE WHEN nxId = 'NX-DEV-USER' THEN 1 ELSE 0 END)
   │ 5. Maps UserEntity -> UserProfile domain model
   │
   ▼
[AuthRepositoryImpl.fetchRemoteProfile()] (Concurrent Sync)
   │ 6. token = secureStorage.getAuthToken()
   │ 7. GET /api/v1/profile (Bearer token)
   │ 8. Receives RemoteUserProfileDto from Go Backend / PostgreSQL
   │ 9. Upserts real profile into Room: userDao.saveUserProfile(userEntity)
   │ 10. Purges any leftover demo profile: userDao.deleteDemoProfile()
   │
   ▼
[AccountScreen UI Render]
   │ 11. Avatar hero, Full Name, Monospace NX ID, Phone (+91...), Email, Age, Gender
   │ 12. Displays dynamic NX-ID QR Code bitmap: QrBitmapGenerator.generate("nexa://user?id=$nxId")
```

---

## 4. Session Storage & Restoration Flow

### 4.1 Storage Architecture
- **Hardware-Backed Secret Storage:** [`SecureStorage.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/security/SecureStorage.kt) uses AndroidX `EncryptedSharedPreferences` backed by `MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()`.
  - Keys stored:
    - `KEY_AUTH_TOKEN`: 256-bit session token (`nexa_sess_...`)
    - `KEY_ACTIVE_NX_ID`: Active NX ID (`NX-...`)
    - `KEY_DB_PASSPHRASE`: 256-bit random passphrase for SQLCipher
    - `KEY_DEVICE_ID`: Device identifier (`dev_...`)
    - `KEY_IDENTITY_PUBLIC_KEY` & `KEY_IDENTITY_ENCRYPTED_PRIVKEY`: E2EE identity keys
- **Encrypted Local Database:** [`NexaDatabase.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/local/NexaDatabase.kt) is an SQLCipher-encrypted Room database (`nexa_encrypted_vault.db`) containing chat history, contacts, and user profiles.

### 4.2 Application Startup & Restoration Sequence
```text
App Launch / Device Reboot
           │
           ▼
     SplashScreen
           │
           ▼
 SplashViewModel.checkSession()
           │
           ▼
 AuthRepositoryImpl.validateSession()
           │
           ├─► 1. Check secureStorage.getAuthToken(): If blank -> Return False
           ├─► 2. Check userDao.hasActiveSession(): If count <= 0 -> Return False
           ├─► 3. Check localProfile.isVerified && nxId != "NX-DEV-USER": If false -> Return False
           ├─► 4. Check accountStatus != DEACTIVATED / DELETED: If false -> Return False
           │
           ▼
     Verify Remote Session with Go Backend
           │
     GET /api/v1/profile (Bearer <token>)
           │
     ┌─────┴───────────────────────────────────────────────────────┐
     ▼                                                             ▼
[HTTP 200 OK]                                             [Request Fails]
- Session is active and valid                             - Inspect error message / code:
- Updates local profile metadata                                │
- Returns True                                                  ├─► HTTP 401 / 403 (Unauthorized / Revoked):
     │                                                          │   - Session expired or device revoked
     │                                                          │   - Calls clearSession()
     │                                                          │   - Returns False
     │                                                          │
     │                                                          └─► Network Failure / Offline Mode:
     │                                                              - SocketTimeout, ConnectException, Airplane mode
     │                                                              - Logs offline mode
     │                                                              - Trusts verified local session
     │                                                              - Returns True (User stays logged in!)
     ▼                                                                  │
NavigateToDashboard (Home Screen) ◄─────────────────────────────────────┘
```

---

## 5. Android App Update Compatibility (Part 2)

### 5.1 Android Application Identity (Part 2A)

| Property | Value in Code | Audit Finding |
|---|---|---|
| **`applicationId`** | `"com.nexa.app"` ([`app/build.gradle.kts:21`](file:///H:/chat_app/app/build.gradle.kts#L21)) | Consistent across versions. Must never change; changing it causes Android to install a separate app package rather than updating in-place. |
| **`namespace`** | `"com.nexa.app"` ([`app/build.gradle.kts:17`](file:///H:/chat_app/app/build.gradle.kts#L17)) | Matches `applicationId`. Clean. |
| **`versionCode`** | `1` ([`app/build.gradle.kts:24`](file:///H:/chat_app/app/build.gradle.kts#L24)) | Must strictly increment with each release (e.g. `2`, `3`, etc.). Downgrading fails with `INSTALL_FAILED_VERSION_DOWNGRADE`. |
| **`versionName`** | `"1.0"` ([`app/build.gradle.kts:25`](file:///H:/chat_app/app/build.gradle.kts#L25)) | Standard semver display. |
| **Release Signing** | `release.keystore` ([`app/build.gradle.kts:37-43`](file:///H:/chat_app/app/build.gradle.kts#L37-L43)) | Dedicated release keystore configured via `local.properties` / environment variables. Android PackageManager enforces identical certificate fingerprints between updates. |
| **Data Preservation** | Standard APK update | Android PackageManager replaces the APK binaries in `/data/app/` while preserving `/data/data/com.nexa.app/` (`shared_prefs/`, `databases/`, `files/`) intact. |

### 5.2 Session Persistence Across Lifecycle Events (Part 2B)

| Lifecycle Event | Session State | Mechanism & Verification |
|---|---|---|
| **Process Death (App Swiped Away)** | **Preserved** | Tokens reside in `EncryptedSharedPreferences` on disk; database resides in SQLCipher on disk. Neither depends on in-memory singletons. |
| **Device Restart / Reboot** | **Preserved** | The Android Keystore `MasterKey` remains in hardware (StrongBox/TEE). On boot, `SplashViewModel` reloads `SecureStorage` and restores session. |
| **Temporary Network Outage** | **Preserved** | [`AuthRepositoryImpl.kt:272`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/repository/AuthRepositoryImpl.kt#L272) specifically differentiates network transport failures from 401/403 HTTP errors. Offline users remain logged in and access local chats. |
| **Server-Side Session Revocation** | **Revoked** | When session is revoked on server (e.g. new device login), `/api/v1/profile` returns HTTP 401. `AuthRepositoryImpl` calls `clearSession()` and routes to Welcome screen. |
| **Token Expiry & Rotation** | **Partial Risk** | Go backend implements `/api/v1/auth/session/rotate` and `/api/v1/auth/session/refresh` with 30-day extensions. However, the Android client currently lacks an automated token-refresh interceptor. Sessions older than 30 days will expire unless refreshed. |

### 5.3 Local Data Persistence & Migrations (Part 2C)

| Item | Status | Verification & Evidence |
|---|---|---|
| **SQLCipher Key Continuity** | **PASS** | `SecureStorage.getOrCreateDatabasePassphrase()` reads the existing 32-byte key from `EncryptedSharedPreferences`. Since preferences survive updates, the same decryption key is provided to SQLCipher after the update. |
| **Room Migration Strategy** | **PASS** | [`NexaDatabase.kt:82`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/local/NexaDatabase.kt#L82) is at version 27. All 26 sequential migrations (`MIGRATION_1_2` through `MIGRATION_26_27`) are explicitly registered. |
| **Destructive Migration Protection** | **PASS** | [`NexaDatabase.kt:807`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/local/NexaDatabase.kt#L807) uses `.fallbackToDestructiveMigrationOnDowngrade()`. It deliberately **omits** `.fallbackToDestructiveMigration()`. If a forward migration is missing, Room fails closed with an exception rather than silently wiping user chat history. |
| **Crash Recovery Journal** | **PASS** | [`NexaDatabase.kt:761`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/local/NexaDatabase.kt#L761) includes `checkAndRecoverFromRollbackJournal()` to automatically recover from an interrupted database restore or vacuum snapshot. |

### 5.4 Device Identity Persistence (Part 2D)

- In [`NexaHardwareSecurity.kt:43-50`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/keystore/NexaHardwareSecurity.kt#L43-L50), `getOrCreateDevicePublicKeyDer()` queries `keyStore.containsAlias(KEY_ALIAS)`.
- Android KeyStore preserves asymmetric keypairs across package updates if the package name and signing certificate match.
- The hardware EC P-256 keypair is not regenerated during an app update. The existing device identity remains bound to the account on the Go backend.

### 5.5 Backup and Restore Safety (Part 2E)

- In [`AuthNavGraph.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/navigation/AuthNavGraph.kt), `BackupDiscoveryScreen` is routed exclusively during re-verification (`isReverify == true`) on a new device.
- Normal app updates execute `SplashViewModel.checkSession()`, which navigates directly from `Splash` to `Dashboard` (Home Screen).
- An app update **never triggers the backup restore flow or fresh account creation**.

---

## 6. Exact Source File Paths and Key References

| Component | File Path | Key Classes / Functions |
|---|---|---|
| **Account Screen UI** | [`AccountScreen.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/settings/account/AccountScreen.kt) | `AccountScreen()`, `getActiveProfile()` observation |
| **Auth Repository** | [`AuthRepositoryImpl.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/repository/AuthRepositoryImpl.kt) | `validateSession()`, `getActiveProfile()`, `fetchRemoteProfile()`, `saveVerifiedSession()` |
| **Room User DAO** | [`UserDao.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/local/dao/UserDao.kt) | `saveUserProfile()`, `getProfileByNxIdFlow()`, `getLocalProfile()`, `deleteDemoProfile()` |
| **Room Database** | [`NexaDatabase.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/local/NexaDatabase.kt) | `getInstance()`, `buildDatabase()`, migrations v1 to v27 |
| **Secure Storage** | [`SecureStorage.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/security/SecureStorage.kt) | `EncryptedSharedPreferences`, `getAuthToken()`, `getOrCreateDatabasePassphrase()` |
| **Hardware KeyStore** | [`NexaHardwareSecurity.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/keystore/NexaHardwareSecurity.kt) | `getOrCreateDevicePublicKeyDer()`, StrongBox / TEE EC P-256 |
| **Splash Startup** | [`SplashViewModel.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/splash/SplashViewModel.kt) | `checkSession()`, `SplashNavigationState` |
| **Demo Seeder** | [`DemoDataSeeder.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/demo/DemoDataSeeder.kt) | `seedDemoConversations()`, mock dev user seeding guard |
| **Build Config** | [`app/build.gradle.kts`](file:///H:/chat_app/app/build.gradle.kts) | `applicationId`, `versionCode`, `signingConfigs` |

---

## 7. Data-Loss & Forced-Logout Risk Register

| Risk ID | Severity | Category | Risk Description | Current Mitigation / Status |
|---|---|---|---|---|
| **RSK-01** | **MEDIUM** | Session Lifespan | Sessions have a 30-day expiration in Go backend. Android client does not currently invoke `/api/v1/auth/session/rotate` periodically. | **Recommendation:** Add an OkHttp authenticator or WorkManager task to call `/api/v1/auth/session/rotate` before 30 days elapse. |
| **RSK-02** | **CRITICAL** | Database Wipe | Adding new tables or columns in future releases without an explicit Room `Migration` would fail. | **Mitigated:** `fallbackToDestructiveMigration` is disabled; app will crash rather than wipe data, alerting developers in testing. |
| **RSK-03** | **CRITICAL** | Update Rejection | Changing release keystore or alias between updates. | **Mitigated:** `release.keystore` is tracked; signing config enforces certificate continuity. |
| **RSK-04** | **LOW** | Offline Login | Temporary lack of internet connectivity on app launch. | **Mitigated:** `AuthRepositoryImpl.validateSession()` catches network failures and trusts verified local profiles. |

---

## 8. Regression and Upgrade Test Plan (Part 3)

The following test plan covers all 14 scenarios specified in the audit:

### Test Case 1: Close and Reopen App
- **Action:** Log in with verified account. Swipe app away from Android Recents (process death). Re-launch app.
- **Expected:** Splash screen displays branded delay, calls `validateSession()`, and navigates directly to `Dashboard` (Home Screen) without displaying Welcome or Login screens.
- **Automated Verification:** Verified in `AccountProfilePersistenceUnitTests.test01` & `StartupAndSplashStabilizationUnitTests.test02`.

### Test Case 2: Force-Stop App
- **Action:** Open Android Settings $\rightarrow$ Apps $\rightarrow$ NEXA $\rightarrow$ Force Stop. Re-launch app.
- **Expected:** Process terminates completely. On restart, Android Keystore re-initializes `MasterKey`, SQLCipher unlocks with persisted passphrase, and session is fully restored.
- **Automated Verification:** Verified in `StartupAndSplashStabilizationUnitTests.test03`.

### Test Case 3: Device Restart
- **Action:** Reboot physical Android phone. Launch NEXA after boot.
- **Expected:** Hardware Keystore (TEE/StrongBox) key handles persist across boot. `EncryptedSharedPreferences` decrypts successfully. User lands on Dashboard.

### Test Case 4: APK Over-the-Air / Sideload Update (Same Certificate)
- **Action:** Build version `1.0` (versionCode 1) and install. Create account. Build version `1.1` (versionCode 2) signed with the identical `release.keystore`. Run `adb install -r nexa-v1.1.apk`.
- **Expected:** Package manager reports `Success`. App opens without prompting for account creation.

### Test Case 5: Verify Account Session After Update
- **Action:** Open app immediately after APK update.
- **Expected:** `SplashViewModel` loads `KEY_AUTH_TOKEN`. Session remains valid; user is not logged out.

### Test Case 6: Verify NX ID Unchanged After Update
- **Action:** Navigate to Settings $\rightarrow$ Account after update.
- **Expected:** Monospace NX ID badge matches the exact original value (e.g. `NX-4K9W72A8`). No new NX ID is generated.

### Test Case 7: Verify Profile Information Unchanged After Update
- **Action:** Inspect Display Name, Phone Number, Email, Age, Gender, and Avatar in Settings $\rightarrow$ Account.
- **Expected:** All user profile fields remain intact and match the pre-update state.

### Test Case 8: Local Chats, Contacts, and Groups Intact
- **Action:** Inspect MainChatDashboardScreen and conversation view.
- **Expected:** All historical message rows, contact nicknames, and group chats remain intact in the Room database.

### Test Case 9: SQLCipher Database Remains Readable After Update
- **Action:** Trigger a database read and write after update.
- **Expected:** `NexaDatabase.getInstance()` unlocks `nexa_encrypted_vault.db` using `KEY_DB_PASSPHRASE`. Zero SQLCipher decryption errors (`file is not a database`).

### Test Case 10: Session Refresh After Access Token Expiry
- **Action:** Fast-forward backend session timestamp past 30 days. Trigger token rotation.
- **Expected:** Backend `/api/v1/auth/session/rotate` issues fresh token, extends expiry by 30 days, and invalidates old token.

### Test Case 11: Network Failure During Startup Does Not Cause Logout
- **Action:** Enable Airplane Mode (disable Wi-Fi and Cellular). Launch app.
- **Expected:** `validateSession()` catches network failure, recognizes it is not an HTTP 401/403, and navigates to Dashboard in offline mode. User is NOT logged out.
- **Automated Verification:** Verified in `StartupAndSplashStabilizationUnitTests.test04`.

### Test Case 12: Genuinely Revoked Session Requires Reauthentication
- **Action:** Revoke device session on Go backend (or log in on a new device). Launch app on old device.
- **Expected:** `validateSession()` receives HTTP 401 Unauthorized from `/api/v1/profile`, calls `clearSession()`, and navigates to `WelcomeScreen`.
- **Automated Verification:** Verified in `ExistingUserLoginAndDeviceMigrationUnitTests.test08`.

### Test Case 13: Fresh Installation on Clean Device Works
- **Action:** Perform a clean install on a device with no prior NEXA data.
- **Expected:** `validateSession()` returns false; navigates to `WelcomeScreen`; allows authentic Google Sign-In and Reverse-SMS onboarding.

### Test Case 14: Database Migration Between App Versions
- **Action:** Simulate upgrading from database schema version 26 to version 27.
- **Expected:** `MIGRATION_26_27` executes cleanly without data loss.
- **Automated Verification:** Verified in `NexaDatabaseArchitectureTests`.

---

## 9. Confirmation of Read-Only Compliance

This audit task was conducted strictly in **read-only mode**.
- **Source Code Modified:** 0 files.
- **Configuration Modified:** 0 files.
- **Database Schema Modified:** 0 files.
- **Dependencies Modified:** 0 files.
- All conclusions were derived directly from codebase inspection, Git history, and existing automated test suites.
