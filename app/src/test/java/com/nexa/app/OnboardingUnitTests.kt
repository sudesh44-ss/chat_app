package com.nexa.app

import com.nexa.app.core.security.hexToByteArray
import com.nexa.app.core.security.toHex
import com.nexa.app.data.remote.dto.GoogleAuthResponse
import com.nexa.app.data.remote.dto.InitiateReverseVerifyResponseDto
import com.nexa.app.data.remote.dto.UserResolveDto
import com.nexa.app.data.remote.dto.VerificationStatusDto
import com.nexa.app.domain.model.UserProfile
import com.nexa.app.domain.repository.AuthRepository
import com.nexa.app.presentation.otp.OtpUiEvent
import com.nexa.app.presentation.otp.OtpViewModel
import com.nexa.app.presentation.setup.ProfileSetupUiEvent
import com.nexa.app.presentation.setup.SetupViewModel
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingUnitTests {

    private val testDispatcher = StandardTestDispatcher()

    private val fakeAuthRepository = object : AuthRepository {
        var isOtpSentCalled = false
        var isVerifyCalled = false
        var isReverseVerifyCalled = false

        override suspend fun isLoggedIn(): Boolean = false
        override suspend fun getActiveNxId(): String? = "NX-9842-K7X"
        override fun getActiveProfile(): Flow<UserProfile?> = flowOf(null)

        override suspend fun loginWithGoogle(idToken: String): Result<GoogleAuthResponse> {
            return Result.success(
                GoogleAuthResponse(
                    status = "NEW_USER",
                    googleUid = "google_123",
                    email = "test@example.com",
                    prefillName = "Test User"
                )
            )
        }

        override suspend fun initiateReverseVerify(
            phone: String,
            name: String,
            avatarUrl: String?,
            gender: String?,
            age: Int?,
            bio: String?,
            googleUid: String,
            googleAuthToken: String?,
            isReverify: Boolean
        ): Result<InitiateReverseVerifyResponseDto> {
            isReverseVerifyCalled = true
            isOtpSentCalled = true
            return Result.success(
                InitiateReverseVerifyResponseDto(
                    status = "PENDING",
                    nonce = "NX-TEST1",
                    inboundReceiverNumber = "+917887711223",
                    smsPayload = "NX-VERIFY NX-TEST1",
                    expiresInSeconds = 90
                )
            )
        }

        override suspend fun pollVerificationStatus(nonce: String): Result<VerificationStatusDto> {
            return Result.success(
                VerificationStatusDto(
                    status = "VERIFIED",
                    nxId = "NX-9842-K7X",
                    sessionAuthToken = "token_xyz",
                    name = "Test User",
                    phone = "+919876543210"
                )
            )
        }

        override suspend fun saveVerifiedSession(
            nxId: String,
            sessionToken: String,
            name: String,
            phone: String,
            avatarUrl: String?,
            age: Int?,
            gender: String?,
            bio: String?
        ): Result<UserProfile> {
            return Result.success(
                UserProfile(
                    nxId = nxId,
                    fullName = name,
                    phoneNumber = phone,
                    avatarUrl = avatarUrl,
                    publicKeyHex = "abcd"
                )
            )
        }

        override suspend fun resolveUser(target: String): Result<UserResolveDto> {
            return Result.success(
                UserResolveDto(
                    nxId = target,
                    name = "Resolved User",
                    isFriend = true
                )
            )
        }

        override suspend fun sendVerificationOtp(phone: String, googleAuthToken: String?): Result<Boolean> {
            isOtpSentCalled = true
            return Result.success(true)
        }

        override suspend fun verifyOtpAndRegister(
            phone: String,
            otp: String,
            name: String,
            age: Int?,
            gender: String?,
            bio: String?
        ): Result<UserProfile> {
            isVerifyCalled = true
            return Result.success(
                UserProfile(
                    nxId = "NX-9842-K7X",
                    fullName = name,
                    phoneNumber = phone,
                    publicKeyHex = "abcd"
                )
            )
        }

        override suspend fun clearSession() {}
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun setupViewModel_formValidation_requiresNonBlankNameAnd10DigitPhone() {
        val viewModel = SetupViewModel(fakeAuthRepository)

        // Initially invalid
        assertFalse(viewModel.uiState.value.isFormValid)

        // Only name -> still invalid
        viewModel.onEvent(ProfileSetupUiEvent.OnNameChanged("Alice Vance"))
        assertFalse(viewModel.uiState.value.isFormValid)

        // Phone < 10 digits -> still invalid
        viewModel.onEvent(ProfileSetupUiEvent.OnPhoneChanged("12345"))
        assertFalse(viewModel.uiState.value.isFormValid)

        // Phone >= 10 digits -> valid
        viewModel.onEvent(ProfileSetupUiEvent.OnPhoneChanged("+1234567890"))
        assertTrue(viewModel.uiState.value.isFormValid)
    }

    @Test
    fun setupViewModel_requestReverseVerification_callsRepositoryWhenValid() = runTest {
        val viewModel = SetupViewModel(fakeAuthRepository)
        viewModel.onEvent(ProfileSetupUiEvent.OnNameChanged("Alice Vance"))
        viewModel.onEvent(ProfileSetupUiEvent.OnPhoneChanged("+919876543210"))

        viewModel.onEvent(ProfileSetupUiEvent.RequestReverseVerification)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isOtpTriggered)
        assertNotNull(viewModel.uiState.value.reverseVerifyPayload)
        assertEquals("NX-TEST1", viewModel.uiState.value.reverseVerifyPayload?.nonce)
        assertTrue(fakeAuthRepository.isReverseVerifyCalled)
    }

    @Test
    fun setupViewModel_domesticIndianMobile_validatesAccurately() {
        val viewModel = SetupViewModel(fakeAuthRepository)
        viewModel.onEvent(ProfileSetupUiEvent.OnNameChanged("Rajesh"))

        // Invalid Indian mobile starting with 5
        viewModel.onEvent(ProfileSetupUiEvent.OnPhoneChanged("5876543210"))
        assertFalse(viewModel.uiState.value.isPhoneValid)

        // Valid Indian mobile starting with 9
        viewModel.onEvent(ProfileSetupUiEvent.OnPhoneChanged("9876543210"))
        assertTrue(viewModel.uiState.value.isPhoneValid)

        // Valid Indian mobile with +91 prefix
        viewModel.onEvent(ProfileSetupUiEvent.OnPhoneChanged("+919876543210"))
        assertTrue(viewModel.uiState.value.isPhoneValid)
    }

    @Test
    fun otpViewModel_entering6Digits_triggersVerification() = runTest {
        val viewModel = OtpViewModel(fakeAuthRepository)
        viewModel.onEvent(
            OtpUiEvent.Init(
                phone = "+1234567890",
                name = "Alice",
                age = 25,
                gender = "Female",
                bio = "Offline enthusiast"
            )
        )

        // Enter 5 digits -> not yet complete
        viewModel.onEvent(OtpUiEvent.OnOtpChanged("12345"))
        assertFalse(viewModel.uiState.value.isOtpComplete)

        // Enter 6th digit -> triggers verification
        viewModel.onEvent(OtpUiEvent.OnOtpChanged("123456"))
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isOtpComplete)
        assertNotNull(viewModel.uiState.value.verifiedProfile)
        assertEquals("NX-9842-K7X", viewModel.uiState.value.verifiedProfile?.nxId)
    }

    @Test
    fun crypto_hexUtils_convertsByteArrayToHexAndBackAccurately() {
        val originalBytes = byteArrayOf(0x00, 0x0F, 0x4A, 0xFF.toByte(), 0x7E)
        val hexString = originalBytes.toHex()
        assertEquals("000f4aff7e", hexString)

        val restoredBytes = hexString.hexToByteArray()
        assertTrue(originalBytes.contentEquals(restoredBytes))
    }
}
