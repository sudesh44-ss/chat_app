# NEXA — Authentication & Identity System Deep Audit

**Audit Date:** November 4, 2026  
**Audited Location:** `H:\chat_app`  
**Subsystems Audited:** Credential Manager, OTP Verification, Profile Creation, NX-ID Generation, Identity Directory, Keystore Keypair Generation.

---

## 1. Authentication Lifecycle & Execution Trace

### A. Google Sign-In Execution Flow
- **UI Screen:** `presentation/welcome/WelcomeScreen.kt`
- **ViewModel:** `presentation/welcome/WelcomeViewModel.kt`
- **Credential API:** Android Credential Manager API (`androidx.credentials.CredentialManager`) with `GetGoogleIdOption`.
- **Target Route:** `Screen.ProfileSetup.createRoute(googleToken)`

```
[ User Clicks "Continue with Google" ]
                 │
                 ▼
[ WelcomeViewModel.signInWithCredentialManager() ]
   ├─ Instantiates CredentialManager.create(context)
   ├─ Builds GetCredentialRequest with GetGoogleIdOption (serverClientId = webClientId)
   │
   ├─[ SUCCESS: Valid Google Play Services & OAuth Web Client ID ]
   │     Extracts:
   │       - googleIdTokenCredential.idToken
   │       - googleIdTokenCredential.id (Email)
   │       - googleIdTokenCredential.displayName
   │     Updates WelcomeUiState(isSuccess = true, googleIdToken = token)
   │     Navigates to ProfileSetupScreen with googleToken param
   │
   └─[ EXCEPTION: GetCredentialException / Missing Client ID / De-Googled ROM ]
         Catch block triggers fallbackSimulatedToken()
         Generates: "simulated_oauth_" + UUID.randomUUID().toString()
         Updates WelcomeUiState(isSuccess = true)
         Allows user to proceed seamlessly without crashing
```

#### Code Evidence: `presentation/welcome/WelcomeViewModel.kt` (lines 50–90)
```kotlin
val credentialManager = CredentialManager.create(context)
val googleIdOption = GetGoogleIdOption.Builder()
    .setFilterByAuthorizedAccounts(false)
    .setServerClientId(webClientId)
    .setAutoSelectEnabled(false)
    .build()

val request = GetCredentialRequest.Builder()
    .addCredentialOption(googleIdOption)
    .build()

// Exception / Missing configuration fallback:
private fun fallbackSimulatedToken() {
    val simulatedToken = "simulated_oauth_" + UUID.randomUUID().toString()
    _uiState.update {
        it.copy(
            isLoading = false,
            googleIdToken = simulatedToken,
            isSuccess = true
        )
    }
}
```

### B. Supabase Auth Verification
- **Audit Target:** Does NEXA authenticate via Supabase Auth?
- **Finding:** **NEGATIVE / ABSENT**.
- **Evidence:** There are zero Supabase SDK dependencies in `app/build.gradle.kts` and zero references to Supabase in any Kotlin or Go file. The authentication architecture was designed as zero-cloud-storage, opting for local cryptographic identity and direct Google Drive backup rather than a proprietary backend BaaS.

---

## 2. Phone Number Verification & OTP Flow

### Execution Flow:
1. User enters Profile data (`name`, `phone`, optional `age`, `gender`, `bio`) on `ProfileSetupScreen.kt`.
2. `SetupViewModel.triggerOtp()` calls `authRepository.sendVerificationOtp(phone, googleToken)`.
3. `AuthRepositoryImpl` forwards request to `AuthApiService.requestOtp(RegisterInitRequest)`.
4. User enters 6-digit code on `OtpVerificationScreen.kt`.
5. `OtpViewModel.verifyAndRegister()` calls `authRepository.verifyOtpAndRegister(...)`.
6. Hardware Keystore generates Curve25519 identity keypair (`keystoreManager.generateIdentityKeyPair()`).
7. Resulting `UserEntity` is persisted in local encrypted Room DB (`NexaDatabase.userDao()`).
8. Auth token & active NX-ID saved to `SecureStorage` (`EncryptedSharedPreferences`).
9. App navigates to `Screen.ProfileBadge` / `Screen.Dashboard`.

### Critical Finding: Simulated In-Memory Service (`AuthApiServiceImpl`)
In `data/remote/api/AuthApiService.kt`:
```kotlin
class AuthApiServiceImpl : AuthApiService {
    override suspend fun requestOtp(request: RegisterInitRequest): Result<RegisterInitResponse> {
        delay(800) // Simulated latency
        val challenge = UUID.randomUUID().toString()
        val expiresAt = System.currentTimeMillis() + (60 * 1000)
        return Result.success(RegisterInitResponse(challenge, true, expiresAt))
    }

    override suspend fun verifyOtpAndRegister(request: VerifyOtpRequest): Result<RegistrationSuccessResponse> {
        delay(1000)
        if (request.otpCode.length != 6 || !request.otpCode.all { it.isDigit() }) {
            return Result.failure(IllegalArgumentException("Invalid 6-digit OTP code"))
        }

        // Assigned NX-ID generation
        val randomDigits = (1000 + secureRandom.nextInt(9000)).toString()
        val charset = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val randomSuffix = (1..3).map { charset[secureRandom.nextInt(charset.length)] }.joinToString("")
        val allocatedNxId = "NX-$randomDigits-$randomSuffix"

        val sessionToken = "nexa_sess_" + UUID.randomUUID().toString().replace("-", "")
        return Result.success(RegistrationSuccessResponse(allocatedNxId, sessionToken, System.currentTimeMillis()))
    }
}
```

#### Evaluation:
- **UI & State Machine:** 100% functional, responsive, with cooldown timers and back-navigation guards.
- **Verification Engine:** **SIMULATED**. Any 6-digit numeric string (e.g., `123456`, `000000`) is accepted.
- **SMS Gateway:** No live SMS gateway (Twilio, Msg91, Firebase Phone Auth) is currently invoked over HTTP.

---

## 3. User Profile Creation & Local Schema Audit

Immediately following OTP verification, the profile is mapped and inserted into the encrypted database.

### Local Room Table: `users` (`UserEntity.kt`)
```kotlin
@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey
    val nxId: String,
    val fullName: String,
    val phoneNumber: String,
    val age: Int?,
    val gender: String?,
    val bio: String?,
    val publicKeyHex: String,
    val isVerified: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)
```

| Field | Source | Stored Locally? | Stored in Cloud? |
|---|---|:---:|:---:|
| `nxId` | Allocated by `AuthApiService` | YES (`UserEntity`) | Ephemeral Go Hub / Directory |
| `fullName` | User input (`ProfileSetupScreen`) | YES (`UserEntity`) | Handshake Envelope / Directory |
| `phoneNumber`| User input (`ProfileSetupScreen`) | YES (`UserEntity`) | Salted hash (Key Directory spec) |
| `age` / `gender` / `bio`| User input | YES (`UserEntity`) | Local only |
| `publicKeyHex` | Curve25519 identity key (`CryptoKeystoreManager`) | YES (`UserEntity`) | Server Prekey Directory |
| `isVerified` | Auth verification flag | YES (`UserEntity`) | Client-side state |
| `createdAt` | System timestamp | YES (`UserEntity`) | Local only |

---

## 4. NEXA ID Generation & Identity System Audit

### A. Current Implementation Algorithm
In `data/remote/api/AuthApiService.kt` (lines 56–60):
- **Format:** `NX-XXXX-XXX` (e.g., `NX-4821-K9Z`)
- **Prefix:** `NX-`
- **Numeric Block:** 4-digit random integer (`1000` to `9999`)
- **Alpha-numeric Suffix:** 3 characters selected randomly from Base32 Crockford charset (`ABCDEFGHJKLMNPQRSTUVWXYZ23456789`).
- **Keyspace:** $9,000 \times 32^3 = 294,912,000$ possible IDs (~295 Million unique handles).

### B. Original Specification vs Current Implementation

| Requirement Parameter | Specification in `docs/done/login system.md` | Actual Code in `AuthApiServiceImpl.kt` | Audit Finding |
|---|---|---|---|
| **Derivation Basis** | SHA-256 hash of Ed25519 Public Identity Key | Pseudo-random number generator (`SecureRandom`) | **DEVIATION** (Random vs Key-Derived) |
| **Generation Location** | Key-Directory Server | Client-side simulated API service | **CLIENT GENERATED** |
| **Global Uniqueness** | Enforced by Database Unique Constraint on Server | No central check in mock service | **COLLISION RISK** across uncoordinated devices |
| **Modifiable by User** | Immutable, permanently tied to public key | Immutable in local DB | **COMPLIED** |
| **Spoof Prevention** | Mathematically impossible (tied to Ed25519 key) | Dependent on server trust | **WEAKENED** by client PRNG |

### C. Security Weaknesses & Collision Analysis
1. **Collision Risk:** With 295M possible IDs, the Birthday Paradox dictates a 50% probability of collision after ~20,000 simultaneous client registrations if generated purely client-side without a central server lock.
2. **Impersonation Vulnerability:** Because `AuthApiServiceImpl` generates the NX-ID independently of `publicKeyHex`, an attacker could register an NX-ID with an arbitrary public key unless validated by the Go backend's `handshake/memory_store.go`.

---

## 5. Audit Recommendations for Production Hardening

1. **Implement Server-Side Ed25519 NX-ID Allocation:**
   Migrate NX-ID generation to `backend/cmd/handshake`: derive the ID deterministically from `Base32(SHA256(publicKey)[:5])` so that the NX-ID is self-verifying and globally unique.
2. **Connect Production SMS Gateway:**
   Replace `AuthApiServiceImpl` with a Retrofit/Ktor client calling a backend `/api/v1/auth/request-otp` route integrated with Twilio or Firebase Phone Auth.
3. **Provision Google OAuth Web Client ID:**
   Supply a production Google OAuth 2.0 Web Client ID in `WelcomeViewModel` to replace the development `fallbackSimulatedToken()`.
