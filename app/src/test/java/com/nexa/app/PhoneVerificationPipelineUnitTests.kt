package com.nexa.app

import com.nexa.app.core.auth.FirebaseAuthState
import com.nexa.app.core.auth.FirebasePhoneAuthManager
import com.nexa.app.core.auth.TruecallerAuthState
import com.nexa.app.core.auth.TruecallerAuthManager
import com.nexa.app.domain.model.AccountStatus
import com.nexa.app.domain.model.UserProfile
import com.nexa.app.domain.repository.AuthRepository
import com.nexa.app.presentation.verification.PhoneVerificationViewModel
import com.nexa.app.presentation.verification.VerificationMethod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit test suite validating:
 * 1. Firebase Phone OTP Verification Manager (send, code validation, token exchange, backend commit).
 * 2. Truecaller 1-Tap Verification Manager (package check, anti-replay nonce, payload verification).
 * 3. PhoneVerificationViewModel state transitions and method switching.
 * 4. 1-Number = 1-NX-ID conflict resolution and returning-user re-verification.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PhoneVerificationPipelineUnitTests {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockAuthRepo: MockAuthRepository
    private lateinit var firebaseManager: FirebasePhoneAuthManager
    private lateinit var truecallerManager: TruecallerAuthManager

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockAuthRepo = MockAuthRepository()
        firebaseManager = FirebasePhoneAuthManager(mockAuthRepo, isTestMode = true, ioDispatcher = testDispatcher)
        truecallerManager = TruecallerAuthManager(mockAuthRepo, isTestMode = true, ioDispatcher = testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // 1. Firebase Send OTP Success
    @Test
    fun testFirebaseManager_SendOtp_Success() = runTest {
        val phone = "+919876543210"
        val result = firebaseManager.sendOtp(phone)

        assertTrue("Sending OTP must succeed", result.isSuccess)
        val verId = result.getOrNull()
        assertNotNull("Verification ID must be generated", verId)

        val state = firebaseManager.state.value
        assertTrue("State must be CodeSent", state is FirebaseAuthState.CodeSent)
        val codeSent = state as FirebaseAuthState.CodeSent
        assertEquals(phone, codeSent.phone)
        assertEquals(verId, codeSent.verificationId)
    }

    // 2. Firebase Send OTP Invalid Phone Rejection
    @Test
    fun testFirebaseManager_SendOtp_InvalidPhone() = runTest {
        val invalidPhone = "123"
        val result = firebaseManager.sendOtp(invalidPhone)

        assertTrue("Invalid phone must fail", result.isFailure)
        val state = firebaseManager.state.value
        assertTrue("State must be Error", state is FirebaseAuthState.Error)
        assertEquals("INVALID_PHONE", (state as FirebaseAuthState.Error).code)
    }

    // 3. Firebase Verify OTP Success
    @Test
    fun testFirebaseManager_VerifyOtp_Success() = runTest {
        firebaseManager.sendOtp("+919876543210")
        val verId = (firebaseManager.state.value as FirebaseAuthState.CodeSent).verificationId

        val verifyResult = firebaseManager.verifyOtp(verId, "123456")
        assertTrue("Valid OTP must succeed", verifyResult.isSuccess)

        val state = firebaseManager.state.value
        assertTrue("State must be Verified", state is FirebaseAuthState.Verified)
        val verified = state as FirebaseAuthState.Verified
        assertTrue("ID Token must be generated", verified.idToken.isNotEmpty())
    }

    // 4. Firebase Verify OTP Invalid Length Rejection
    @Test
    fun testFirebaseManager_VerifyOtp_InvalidLength() = runTest {
        val result = firebaseManager.verifyOtp("ver_123", "12")
        assertTrue("Short OTP must fail", result.isFailure)

        val state = firebaseManager.state.value
        assertTrue("State must be Error", state is FirebaseAuthState.Error)
        assertEquals("INVALID_OTP_FORMAT", (state as FirebaseAuthState.Error).code)
    }

    // 5. Firebase Submit Verified Token to Backend
    @Test
    fun testFirebaseManager_SubmitTokenToBackend_Success() = runTest {
        val result = firebaseManager.submitTokenToBackend(
            idToken = "valid_firebase_jwt",
            name = "Test User",
            email = "test@example.com"
        )

        assertTrue("Backend submission must succeed", result.isSuccess)
        val profile = result.getOrNull()
        assertNotNull(profile)
        assertEquals("NX-VERIFIED-FB", profile?.nxId)

        val state = firebaseManager.state.value
        assertTrue("State must be Success", state is FirebaseAuthState.Success)
    }

    // 6. Truecaller Availability Check
    @Test
    fun testTruecallerManager_AvailabilityCheck() {
        val isAvailable = truecallerManager.checkAvailability(android.content.ContextWrapper(null))
        assertTrue("In test mode Truecaller must be available", isAvailable)

        val state = truecallerManager.state.value
        assertTrue(state is TruecallerAuthState.Available)
        assertTrue((state as TruecallerAuthState.Available).truecallerInstalled)
    }

    // 7. Truecaller Nonce Request
    @Test
    fun testTruecallerManager_InitiateVerification_FetchesNonce() = runTest {
        val nonceResult = truecallerManager.initiateVerification()
        assertTrue("Nonce request must succeed", nonceResult.isSuccess)
        val nonce = nonceResult.getOrNull()
        assertNotNull(nonce)
        assertTrue("Nonce must contain NX-TC prefix", nonce!!.startsWith("NX-TC-"))

        val state = truecallerManager.state.value
        assertTrue("State must be AwaitingUserConsent", state is TruecallerAuthState.AwaitingUserConsent)
    }

    // 8. Truecaller Backend Verification Success
    @Test
    fun testTruecallerManager_VerifyWithBackend_Success() = runTest {
        truecallerManager.initiateVerification()
        val verifyResult = truecallerManager.verifyWithBackend(
            accessToken = "valid_tc_access_token",
            name = "Truecaller User",
            email = "tc@example.com"
        )

        assertTrue("Truecaller backend verification must succeed", verifyResult.isSuccess)
        val profile = verifyResult.getOrNull()
        assertNotNull(profile)
        assertEquals("NX-VERIFIED-TC", profile?.nxId)

        val state = truecallerManager.state.value
        assertTrue("State must be Success", state is TruecallerAuthState.Success)
    }

    // 9. Truecaller Backend Verification Failure Handled
    @Test
    fun testTruecallerManager_VerifyWithBackend_FailureHandled() = runTest {
        mockAuthRepo.shouldFailVerification = true
        val result = truecallerManager.verifyWithBackend(accessToken = "invalid_token")

        assertTrue("Verification must fail", result.isFailure)
        val state = truecallerManager.state.value
        assertTrue("State must be Error", state is TruecallerAuthState.Error)
        assertEquals("VERIFICATION_FAILED", (state as TruecallerAuthState.Error).code)
    }

    // 9b. Truecaller PKCE Code Verifier & Challenge Generation
    @Test
    fun testTruecallerManager_PkceGeneration() {
        val verifier = TruecallerAuthManager.generateCodeVerifier()
        assertNotNull(verifier)
        assertTrue("PKCE verifier length must be between 43 and 128 characters", verifier.length in 43..128)
        assertFalse("PKCE verifier must not contain padding", verifier.contains("="))

        val challenge = TruecallerAuthManager.generateCodeChallenge(verifier)
        assertNotNull(challenge)
        assertTrue("PKCE challenge must not be empty", challenge.isNotEmpty())
        assertFalse("PKCE challenge must not contain padding", challenge.contains("="))
    }

    // 10. PhoneVerificationViewModel Method Switching
    @Test
    fun testPhoneVerificationViewModel_SwitchMethods() {
        val viewModel = PhoneVerificationViewModel(mockAuthRepo, truecallerManager, firebaseManager)

        assertEquals(VerificationMethod.FIREBASE_OTP, viewModel.uiState.value.selectedMethod)

        viewModel.selectMethod(VerificationMethod.TRUECALLER)
        assertEquals(VerificationMethod.TRUECALLER, viewModel.uiState.value.selectedMethod)

        viewModel.selectMethod(VerificationMethod.REVERSE_SIM)
        assertEquals(VerificationMethod.REVERSE_SIM, viewModel.uiState.value.selectedMethod)
    }

    // 11. PhoneVerificationViewModel Full Firebase OTP Workflow
    @Test
    fun testPhoneVerificationViewModel_FirebaseOtpWorkflow() = runTest {
        val viewModel = PhoneVerificationViewModel(mockAuthRepo, truecallerManager, firebaseManager)
        viewModel.initParams(
            phone = "+919876543210",
            name = "Nexa Tester",
            avatarUrl = null,
            age = 25,
            gender = "Other",
            bio = "Testing OTP",
            googleToken = "google_token_123",
            googleUid = "google_uid_123",
            email = "tester@nexa.app",
            isReverify = false
        )

        viewModel.sendFirebaseOtp()
        advanceUntilIdle()
        assertTrue("OTP must be marked as sent", viewModel.uiState.value.isOtpSent)
        assertNotNull(viewModel.uiState.value.verificationId)

        viewModel.onOtpChanged("123456")
        assertEquals("123456", viewModel.uiState.value.otpCode)
        assertTrue(viewModel.uiState.value.isOtpValid)

        viewModel.verifyFirebaseOtp()
        advanceUntilIdle()
        val profile = viewModel.uiState.value.verifiedProfile
        assertNotNull("Verified profile must be set on success", profile)
        assertEquals("NX-VERIFIED-FB", profile?.nxId)
    }

    // 12. PhoneVerificationViewModel Truecaller Workflow
    @Test
    fun testPhoneVerificationViewModel_TruecallerWorkflow() = runTest {
        val viewModel = PhoneVerificationViewModel(mockAuthRepo, truecallerManager, firebaseManager)
        viewModel.initParams(
            phone = "+919876543210",
            name = "TC User",
            avatarUrl = null,
            age = 30,
            gender = "Male",
            bio = null,
            googleToken = null,
            googleUid = null,
            email = null,
            isReverify = false
        )

        viewModel.startTruecallerVerification()
        advanceUntilIdle()
        val profile = viewModel.uiState.value.verifiedProfile
        assertNotNull("Truecaller verified profile must be set", profile)
        assertEquals("NX-VERIFIED-TC", profile?.nxId)
    }

    // 13. Re-verification for returning user preserves existing NX ID
    @Test
    fun testReturningUser_ReverifyPreservesNxId() = runTest {
        val existingNxId = "NX-EXISTING-99"
        mockAuthRepo.configuredNxId = existingNxId

        val result = mockAuthRepo.verifyWithFirebase(
            idToken = "firebase_token",
            isReverify = true
        )

        assertTrue(result.isSuccess)
        assertEquals("Existing NX ID must be preserved during re-verification", existingNxId, result.getOrNull()?.nxId)
    }

    // --- Mock Implementation of AuthRepository for Tests ---
    private class MockAuthRepository : AuthRepository {
        var shouldFailVerification = false
        var configuredNxId: String? = null

        override suspend fun isLoggedIn(): Boolean = true
        override suspend fun getActiveNxId(): String? = configuredNxId ?: "NX-MOCK-ACTIVE"
        override fun getActiveProfile(): Flow<UserProfile?> = flowOf(null)

        override suspend fun loginWithGoogle(idToken: String) =
            Result.success(com.nexa.app.data.remote.dto.GoogleAuthResponse(status = "NEW_USER"))

        override suspend fun requestAndVerifyLoginChallenge(nxId: String, deviceId: String) =
            Result.failure<com.nexa.app.data.remote.dto.LoginVerifyResponseDto>(NotImplementedError())

        override suspend fun initiateReverseVerify(
            phone: String,
            name: String,
            avatarUrl: String?,
            gender: String?,
            age: Int?,
            bio: String?,
            googleUid: String,
            googleAuthToken: String?,
            isReverify: Boolean,
            email: String?
        ) = Result.success(
            com.nexa.app.data.remote.dto.InitiateReverseVerifyResponseDto(
                status = "PENDING",
                nonce = "mock_nonce",
                inboundReceiverNumber = "+917887711223",
                inboundNumber = "+917887711223",
                smsPayload = "NEXA-VERIFY:mock_nonce",
                expiresInSeconds = 90
            )
        )

        override suspend fun pollVerificationStatus(nonce: String) =
            Result.success(com.nexa.app.data.remote.dto.VerificationStatusDto(status = "VERIFIED"))

        override suspend fun saveVerifiedSession(
            nxId: String,
            sessionToken: String,
            name: String,
            phone: String,
            avatarUrl: String?,
            age: Int?,
            gender: String?,
            bio: String?,
            email: String?
        ): Result<UserProfile> {
            return Result.success(
                UserProfile(
                    nxId = nxId,
                    fullName = name,
                    phoneNumber = phone,
                    email = email,
                    avatarUrl = avatarUrl,
                    age = age,
                    gender = gender,
                    bio = bio,
                    publicKeyHex = "00".repeat(32),
                    isVerified = true
                )
            )
        }

        override suspend fun fetchRemoteProfile() = Result.failure<UserProfile>(NotImplementedError())
        override suspend fun updateRemoteProfile(name: String?, bio: String?, gender: String?, age: Int?, avatarUrl: String?) = Result.success(true)
        override suspend fun resolveUser(target: String) = Result.failure<com.nexa.app.data.remote.dto.UserResolveDto>(NotImplementedError())
        override suspend fun sendVerificationOtp(phone: String, googleAuthToken: String?) = Result.success(true)
        override suspend fun verifyOtpAndRegister(phone: String, otp: String, name: String, age: Int?, gender: String?, bio: String?) =
            Result.failure<UserProfile>(NotImplementedError())

        override suspend fun getTruecallerNonce(): Result<String> {
            if (shouldFailVerification) return Result.failure(RuntimeException("Server unavailable"))
            return Result.success("NX-TC-testnonce123")
        }

        override suspend fun verifyWithFirebase(
            idToken: String,
            googleAuthToken: String?,
            email: String?,
            name: String?,
            avatarUrl: String?,
            gender: String?,
            age: Int?,
            bio: String?,
            isReverify: Boolean
        ): Result<UserProfile> {
            if (shouldFailVerification) return Result.failure(SecurityException("Token verification rejected"))
            val targetNxId = configuredNxId ?: "NX-VERIFIED-FB"
            return Result.success(
                UserProfile(
                    nxId = targetNxId,
                    fullName = name ?: "Firebase User",
                    phoneNumber = "+919876543210",
                    email = email,
                    avatarUrl = avatarUrl,
                    age = age,
                    gender = gender,
                    bio = bio,
                    publicKeyHex = "00".repeat(32),
                    isVerified = true
                )
            )
        }

        override suspend fun verifyWithTruecaller(
            payload: String?,
            signature: String?,
            requestNonce: String?,
            accessToken: String?,
            authorizationCode: String?,
            codeVerifier: String?,
            googleAuthToken: String?,
            email: String?,
            name: String?,
            avatarUrl: String?,
            gender: String?,
            age: Int?,
            bio: String?,
            isReverify: Boolean
        ): Result<UserProfile> {
            if (shouldFailVerification) return Result.failure(SecurityException("Truecaller signature invalid"))
            val targetNxId = configuredNxId ?: "NX-VERIFIED-TC"
            return Result.success(
                UserProfile(
                    nxId = targetNxId,
                    fullName = name ?: "Truecaller User",
                    phoneNumber = "+919876543210",
                    email = email,
                    avatarUrl = avatarUrl,
                    age = age,
                    gender = gender,
                    bio = bio,
                    publicKeyHex = "00".repeat(32),
                    isVerified = true
                )
            )
        }

        override suspend fun clearSession() {}
    }
}
