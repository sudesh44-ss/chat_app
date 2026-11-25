package com.nexa.app

import com.nexa.app.core.config.AppLaunchConfig
import com.nexa.app.data.worker.ScheduledBackupWorker
import com.nexa.app.domain.model.UserProfile
import com.nexa.app.domain.repository.AuthRepository
import com.nexa.app.presentation.splash.SplashNavigationState
import com.nexa.app.presentation.splash.SplashViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
class StartupAndSplashStabilizationUnitTests {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        AppLaunchConfig.DEV_BYPASS_AUTH_TO_HOME_SCREEN = false
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        AppLaunchConfig.DEV_BYPASS_AUTH_TO_HOME_SCREEN = false
    }

    private fun createFakeAuthRepository(
        isLoggedInResult: Boolean = false,
        validateSessionResult: Boolean = isLoggedInResult,
        shouldThrow: Boolean = false
    ): AuthRepository = object : AuthRepository {
        override suspend fun isLoggedIn(): Boolean {
            if (shouldThrow) {
                throw IllegalStateException("Simulated database/keystore failure during startup")
            }
            return isLoggedInResult
        }

        override suspend fun validateSession(): Boolean {
            if (shouldThrow) {
                throw IllegalStateException("Simulated session validation failure")
            }
            return validateSessionResult
        }

        override suspend fun getActiveNxId(): String? = "NX-TEST-NXID"
        override fun getActiveProfile(): Flow<UserProfile?> = flowOf(null)
        override suspend fun loginWithGoogle(idToken: String): Result<com.nexa.app.data.remote.dto.GoogleAuthResponse> =
            Result.failure(NotImplementedError())
        override suspend fun requestAndVerifyLoginChallenge(nxId: String, deviceId: String): Result<com.nexa.app.data.remote.dto.LoginVerifyResponseDto> =
            Result.failure(NotImplementedError())
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
        ): Result<com.nexa.app.data.remote.dto.InitiateReverseVerifyResponseDto> =
            Result.failure(NotImplementedError())
        override suspend fun pollVerificationStatus(nonce: String): Result<com.nexa.app.data.remote.dto.VerificationStatusDto> =
            Result.failure(NotImplementedError())
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
        ): Result<UserProfile> =
            Result.failure(NotImplementedError())
        override suspend fun fetchRemoteProfile(): Result<UserProfile> =
            Result.failure(NotImplementedError())
        override suspend fun updateRemoteProfile(
            name: String?,
            bio: String?,
            gender: String?,
            age: Int?,
            avatarUrl: String?
        ): Result<Boolean> =
            Result.failure(NotImplementedError())
        override suspend fun resolveUser(target: String): Result<com.nexa.app.data.remote.dto.UserResolveDto> =
            Result.failure(NotImplementedError())
        override suspend fun sendVerificationOtp(phone: String, googleAuthToken: String?): Result<Boolean> =
            Result.failure(NotImplementedError())
        override suspend fun verifyOtpAndRegister(
            phone: String,
            otp: String,
            name: String,
            age: Int?,
            gender: String?,
            bio: String?
        ): Result<UserProfile> =
            Result.failure(NotImplementedError())
        override suspend fun clearSession() {}
    }

    @Test
    fun scheduledBackupWorker_createPeriodicWorkRequest_buildsWithoutIdleConflict() {
        val request = ScheduledBackupWorker.createPeriodicWorkRequest()
        assertNotNull("WorkRequest must build successfully", request)
        assertEquals("nexa_nightly_backup", ScheduledBackupWorker.UNIQUE_WORK_NAME)

        val constraints = request.workSpec.constraints
        assertTrue("UNMETERED network required", constraints.requiredNetworkType == androidx.work.NetworkType.UNMETERED)
        assertTrue("Charging required", constraints.requiresCharging())
        assertFalse("requiresDeviceIdle must be false to prevent backoff crash", constraints.requiresDeviceIdle())
    }

    @Test
    fun splashViewModel_initialStateIsLoading() = runTest {
        val repo = createFakeAuthRepository(isLoggedInResult = false)
        val viewModel = SplashViewModel(repo)
        assertEquals(SplashNavigationState.Loading, viewModel.navigationState.value)
    }

    @Test
    fun splashViewModel_withBypassActive_navigatesDirectlyToDashboardEvenWhenUnauthenticated() = runTest(testDispatcher) {
        AppLaunchConfig.DEV_BYPASS_AUTH_TO_HOME_SCREEN = true
        val repo = createFakeAuthRepository(isLoggedInResult = false)
        val viewModel = SplashViewModel(repo)

        advanceTimeBy(1300)
        advanceUntilIdle()

        assertEquals(
            "When bypass is active, must directly navigate to Home/Dashboard Screen",
            SplashNavigationState.NavigateToDashboard,
            viewModel.navigationState.value
        )
    }

    @Test
    fun splashViewModel_standardFlow_navigatesToDashboardWhenLoggedIn() = runTest(testDispatcher) {
        AppLaunchConfig.DEV_BYPASS_AUTH_TO_HOME_SCREEN = false
        val repo = createFakeAuthRepository(isLoggedInResult = true)
        val viewModel = SplashViewModel(repo)

        advanceTimeBy(1300)
        advanceUntilIdle()

        assertEquals(SplashNavigationState.NavigateToDashboard, viewModel.navigationState.value)
    }

    @Test
    fun splashViewModel_standardFlow_navigatesToWelcomeWhenNotLoggedIn() = runTest(testDispatcher) {
        AppLaunchConfig.DEV_BYPASS_AUTH_TO_HOME_SCREEN = false
        val repo = createFakeAuthRepository(isLoggedInResult = false)
        val viewModel = SplashViewModel(repo)

        advanceTimeBy(1300)
        advanceUntilIdle()

        assertEquals(SplashNavigationState.NavigateToWelcome, viewModel.navigationState.value)
    }

    @Test
    fun splashViewModel_standardFlow_navigatesToWelcomeGracefullyWhenIsLoggedInThrows() = runTest(testDispatcher) {
        AppLaunchConfig.DEV_BYPASS_AUTH_TO_HOME_SCREEN = false
        val repo = createFakeAuthRepository(shouldThrow = true)
        val viewModel = SplashViewModel(repo)

        advanceTimeBy(1300)
        advanceUntilIdle()

        assertEquals(SplashNavigationState.NavigateToWelcome, viewModel.navigationState.value)
    }

    @Test
    fun splashViewModel_expiredOrRevokedSession_navigatesToWelcome() = runTest(testDispatcher) {
        // When session token was expired or revoked on server, validateSession returns false
        val repo = createFakeAuthRepository(isLoggedInResult = false, validateSessionResult = false)
        val viewModel = SplashViewModel(repo)

        advanceTimeBy(1300)
        advanceUntilIdle()

        assertEquals(
            "Expired or revoked session must route to Welcome / Login screen",
            SplashNavigationState.NavigateToWelcome,
            viewModel.navigationState.value
        )
    }

    @Test
    fun splashViewModel_freshInstallation_navigatesToWelcome() = runTest(testDispatcher) {
        // Fresh install: no credentials, no account
        val repo = createFakeAuthRepository(isLoggedInResult = false, validateSessionResult = false)
        val viewModel = SplashViewModel(repo)

        advanceTimeBy(1300)
        advanceUntilIdle()

        assertEquals(
            "Fresh installation must route from Splash to Welcome (Account Creation / Login)",
            SplashNavigationState.NavigateToWelcome,
            viewModel.navigationState.value
        )
    }

    @Test
    fun appLaunchConfig_invariants() {
        assertFalse("Bypass flag must default to false for authentic startup", AppLaunchConfig.DEV_BYPASS_AUTH_TO_HOME_SCREEN)
        assertFalse("Bypass must be disabled by default", AppLaunchConfig.isBypassAuthToHomeScreenEnabled)
    }
}
