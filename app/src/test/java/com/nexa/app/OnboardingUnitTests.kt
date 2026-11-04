package com.nexa.app

import com.nexa.app.core.security.hexToByteArray
import com.nexa.app.core.security.toHex
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

        override suspend fun isLoggedIn(): Boolean = false
        override suspend fun getActiveNxId(): String? = "NX-9842-K7X"
        override fun getActiveProfile(): Flow<UserProfile?> = flowOf(null)

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
    fun setupViewModel_requestOtp_callsRepositoryWhenValid() = runTest {
        val viewModel = SetupViewModel(fakeAuthRepository)
        viewModel.onEvent(ProfileSetupUiEvent.OnNameChanged("Alice Vance"))
        viewModel.onEvent(ProfileSetupUiEvent.OnPhoneChanged("+1234567890"))

        viewModel.onEvent(ProfileSetupUiEvent.RequestOtp)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isOtpTriggered)
        assertTrue(fakeAuthRepository.isOtpSentCalled)
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
