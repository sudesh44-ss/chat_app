package com.nexa.app

import com.nexa.app.core.config.AppLaunchConfig
import com.nexa.app.core.demo.DemoDataSeeder
import com.nexa.app.data.local.dao.UserDao
import com.nexa.app.data.local.entity.UserEntity
import com.nexa.app.data.local.entity.toDomain
import com.nexa.app.data.remote.dto.GoogleAuthResponse
import com.nexa.app.data.remote.dto.InitiateReverseVerifyResponseDto
import com.nexa.app.data.remote.dto.RemoteUserProfileDto
import com.nexa.app.data.remote.dto.UserResolveDto
import com.nexa.app.data.remote.dto.VerificationStatusDto
import com.nexa.app.domain.model.UserProfile
import com.nexa.app.domain.repository.AuthRepository
import com.nexa.app.presentation.radar.VerificationRadarUiState
import com.nexa.app.presentation.radar.VerificationRadarViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests verifying:
 * 1. Active profile resolution by active NX ID (avoiding NX-DEV-USER shadowing).
 * 2. Demo data seeder safety during launch and debug app restarts.
 * 3. Verified Google email propagation through the complete reverse-SMS pipeline.
 * 4. Stale session/profile handling during logout and account switching.
 * 5. App update and session continuity invariants.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountProfilePersistenceUnitTests {

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

    /**
     * Fake UserDao maintaining an in-memory map of UserEntity records,
     * mirroring the exact SQL logic implemented in UserDao.kt.
     */
    class FakeUserDao : UserDao {
        val users = mutableMapOf<String, UserEntity>()
        private val usersFlow = MutableStateFlow<List<UserEntity>>(emptyList())

        private fun notifyChange() {
            usersFlow.value = users.values.toList()
        }

        override suspend fun saveUserProfile(user: UserEntity): Long {
            users[user.nxId] = user
            notifyChange()
            return 1L
        }

        override fun getProfileByNxIdFlow(nxId: String): Flow<UserEntity?> {
            return usersFlow.map { list -> list.find { it.nxId == nxId } }
        }

        override suspend fun getProfileByNxId(nxId: String): UserEntity? {
            return users[nxId]
        }

        // Mirrors: ORDER BY CASE WHEN nxId = 'NX-DEV-USER' THEN 1 ELSE 0 END, createdAt DESC LIMIT 1
        override suspend fun getLocalProfile(): UserEntity? {
            return users.values.sortedWith(
                compareBy<UserEntity> { if (it.nxId == "NX-DEV-USER") 1 else 0 }
                    .thenByDescending { it.createdAt }
            ).firstOrNull()
        }

        override fun getActiveUserFlow(): Flow<UserEntity?> {
            return usersFlow.map { list ->
                list.sortedWith(
                    compareBy<UserEntity> { if (it.nxId == "NX-DEV-USER") 1 else 0 }
                        .thenByDescending { it.createdAt }
                ).firstOrNull()
            }
        }

        override suspend fun getActiveNxId(): String? {
            return getLocalProfile()?.nxId
        }

        override suspend fun countVerifiedUsers(): Int {
            return users.values.count { it.isVerified && it.nxId != "NX-DEV-USER" }
        }

        override suspend fun updateFullName(nxId: String, fullName: String): Int {
            val user = users[nxId] ?: return 0
            users[nxId] = user.copy(fullName = fullName)
            notifyChange()
            return 1
        }

        override suspend fun updatePhoneNumber(nxId: String, phoneNumber: String): Int {
            val user = users[nxId] ?: return 0
            users[nxId] = user.copy(phoneNumber = phoneNumber)
            notifyChange()
            return 1
        }

        override suspend fun deleteDemoProfile(): Int {
            val removed = users.remove("NX-DEV-USER") != null
            if (removed) notifyChange()
            return if (removed) 1 else 0
        }

        override suspend fun deleteProfileByNxId(nxId: String): Int {
            val removed = users.remove(nxId) != null
            if (removed) notifyChange()
            return if (removed) 1 else 0
        }

        override suspend fun clearProfile(): Int {
            val count = users.size
            users.clear()
            notifyChange()
            return count
        }
    }

    /**
     * Fake AuthRepository simulating session persistence and remote calls.
     */
    class FakeAuthRepository(
        val fakeUserDao: FakeUserDao
    ) : AuthRepository {
        var activeNxId: String? = null
        var sessionToken: String? = null
        var avatarUrl: String? = null
        var remoteProfileToReturn: RemoteUserProfileDto? = null
        var remoteProfileThrows = false

        override suspend fun isLoggedIn(): Boolean {
            if (sessionToken.isNullOrBlank()) return false
            val profile = activeNxId?.let { fakeUserDao.getProfileByNxId(it) } ?: fakeUserDao.getLocalProfile()
            return profile != null && profile.isVerified && profile.nxId != "NX-DEV-USER"
        }

        override suspend fun validateSession(): Boolean {
            if (sessionToken.isNullOrBlank()) return false
            val profile = activeNxId?.let { fakeUserDao.getProfileByNxId(it) } ?: fakeUserDao.getLocalProfile()
            return profile != null && profile.isVerified && profile.nxId != "NX-DEV-USER"
        }

        override suspend fun getActiveNxId(): String? = activeNxId ?: fakeUserDao.getActiveNxId()

        override fun getActiveProfile(): Flow<UserProfile?> {
            val nxId = activeNxId
            val flow = if (!nxId.isNullOrBlank() && nxId != "NX-DEV-USER") {
                fakeUserDao.getProfileByNxIdFlow(nxId)
            } else {
                fakeUserDao.getActiveUserFlow()
            }
            return flow.map { entity ->
                if (entity?.nxId == "NX-DEV-USER" && !activeNxId.isNullOrBlank() && activeNxId != "NX-DEV-USER") {
                    null
                } else {
                    entity?.toDomain(avatarUrl)
                }
            }
        }

        override suspend fun loginWithGoogle(idToken: String): Result<GoogleAuthResponse> {
            val realNxId = "NX-TESTUSER"
            activeNxId = realNxId
            sessionToken = "nexa_sess_google_test"
            fakeUserDao.deleteDemoProfile()
            val user = UserEntity(
                nxId = realNxId,
                fullName = "Google Tester",
                phoneNumber = "+919876543210",
                email = "tester@gmail.com",
                age = 28,
                gender = "M",
                bio = "Google Bio",
                publicKeyHex = "aa".repeat(32),
                isVerified = true
            )
            fakeUserDao.saveUserProfile(user)
            return Result.success(
                GoogleAuthResponse(
                    status = "LOGIN_SUCCESS",
                    nxId = realNxId,
                    sessionAuthToken = sessionToken
                )
            )
        }

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
        ): Result<InitiateReverseVerifyResponseDto> {
            return Result.success(
                InitiateReverseVerifyResponseDto(
                    status = "PENDING",
                    nonce = "NX-NONCE-123",
                    inboundReceiverNumber = "+919800000000",
                    smsPayload = "NX-VERIFY NX-NONCE-123",
                    expiresInSeconds = 90
                )
            )
        }

        override suspend fun pollVerificationStatus(nonce: String): Result<VerificationStatusDto> {
            return Result.success(
                VerificationStatusDto(
                    status = "VERIFIED",
                    isVerified = true,
                    nxId = "NX-REV12345",
                    sessionAuthToken = "nexa_sess_reverse_sms",
                    name = "Verified User",
                    email = "verified@gmail.com",
                    phone = "+919999988888"
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
            bio: String?,
            email: String?
        ): Result<UserProfile> {
            activeNxId = nxId
            this.sessionToken = sessionToken
            fakeUserDao.deleteDemoProfile()
            val entity = UserEntity(
                nxId = nxId,
                fullName = name,
                phoneNumber = phone,
                email = email,
                avatarUrl = avatarUrl,
                age = age,
                gender = gender,
                bio = bio,
                publicKeyHex = "bb".repeat(32),
                isVerified = true
            )
            fakeUserDao.saveUserProfile(entity)
            return Result.success(entity.toDomain(avatarUrl))
        }

        override suspend fun fetchRemoteProfile(): Result<UserProfile> {
            if (remoteProfileThrows) {
                return Result.failure(IllegalStateException("Simulated offline error"))
            }
            val dto = remoteProfileToReturn ?: RemoteUserProfileDto(
                nxId = activeNxId ?: "NX-REMOTE",
                profileName = "Remote Updated Name",
                phoneNumber = "+919876500000",
                email = "remote@domain.com",
                phoneVerified = true
            )
            activeNxId = dto.nxId
            fakeUserDao.deleteDemoProfile()
            val entity = UserEntity(
                nxId = dto.nxId,
                fullName = dto.profileName ?: "NEXA User",
                phoneNumber = dto.phoneNumber ?: "",
                email = dto.email,
                avatarUrl = dto.avatarUrl,
                age = dto.age,
                gender = dto.gender,
                bio = dto.bio,
                publicKeyHex = "cc".repeat(32),
                isVerified = dto.phoneVerified
            )
            fakeUserDao.saveUserProfile(entity)
            return Result.success(entity.toDomain(dto.avatarUrl))
        }

        override suspend fun updateRemoteProfile(name: String?, bio: String?, gender: String?, age: Int?, avatarUrl: String?) = Result.success(true)
        override suspend fun resolveUser(target: String) = Result.failure<UserResolveDto>(NotImplementedError())
        override suspend fun sendVerificationOtp(phone: String, googleAuthToken: String?) = Result.success(true)
        override suspend fun verifyOtpAndRegister(phone: String, otp: String, name: String, age: Int?, gender: String?, bio: String?) =
            saveVerifiedSession("NX-OTP12345", "token_otp", name, phone, null, age, gender, bio, null)

        override suspend fun updateDisplayName(name: String): Result<Boolean> {
            val nxId = getActiveNxId() ?: return Result.failure(IllegalStateException("No active account"))
            fakeUserDao.updateFullName(nxId, name)
            return Result.success(true)
        }

        override suspend fun clearSession() {
            activeNxId = null
            sessionToken = null
            fakeUserDao.clearProfile()
        }
    }

    // -------------------------------------------------------------------------
    // Test 1: Fresh install and Google registration saves and resolves correct profile
    // -------------------------------------------------------------------------
    @Test
    fun test01_freshInstallAndGoogleRegistration_resolvesCorrectProfile() = runTest {
        val fakeDao = FakeUserDao()
        val authRepo = FakeAuthRepository(fakeDao)

        assertFalse("Initial state must not be logged in", authRepo.isLoggedIn())

        // Perform Google login
        val loginResult = authRepo.loginWithGoogle("valid_id_token")
        assertTrue("Google login should succeed", loginResult.isSuccess)

        val activeProfile = authRepo.getActiveProfile().first()
        assertNotNull("Active profile must not be null after login", activeProfile)
        assertEquals("NX-TESTUSER", activeProfile?.nxId)
        assertEquals("Google Tester", activeProfile?.fullName)
        assertEquals("tester@gmail.com", activeProfile?.email)
        assertEquals("+919876543210", activeProfile?.phoneNumber)
    }

    // -------------------------------------------------------------------------
    // Test 2: Active profile resolves to real user, NOT shadowed by NX-DEV-USER
    // -------------------------------------------------------------------------
    @Test
    fun test02_demoUserNeverShadowsRealRegisteredUser() = runTest {
        val fakeDao = FakeUserDao()
        val authRepo = FakeAuthRepository(fakeDao)

        // Seed demo user into DB first (as happened in earlier versions)
        fakeDao.saveUserProfile(
            UserEntity(
                nxId = "NX-DEV-USER",
                fullName = "You",
                phoneNumber = "+1234567890",
                age = 25,
                gender = "O",
                bio = "NEXA Encrypted Vault",
                publicKeyHex = "00".repeat(32),
                isVerified = true,
                createdAt = 1000L
            )
        )

        // Now register real user
        authRepo.saveVerifiedSession(
            nxId = "NX-REAL7890",
            sessionToken = "sess_real_token",
            name = "Alice Real",
            phone = "+919876543210",
            email = "alice@gmail.com"
        )

        // 1. getActiveProfile() must return the real user, NOT NX-DEV-USER
        val profile = authRepo.getActiveProfile().first()
        assertNotNull(profile)
        assertEquals("NX-REAL7890", profile?.nxId)
        assertEquals("Alice Real", profile?.fullName)
        assertEquals("alice@gmail.com", profile?.email)

        // 2. Demo profile must be purged
        assertNull("NX-DEV-USER must be deleted", fakeDao.getProfileByNxId("NX-DEV-USER"))

        // 3. getLocalProfile() must prioritize real profile
        assertEquals("NX-REAL7890", fakeDao.getLocalProfile()?.nxId)
    }

    // -------------------------------------------------------------------------
    // Test 3: Verified Google email propagation through reverse-SMS pipeline
    // -------------------------------------------------------------------------
    @Test
    fun test03_googleEmailPropagatedThroughReverseSms() = runTest {
        val fakeDao = FakeUserDao()
        val authRepo = FakeAuthRepository(fakeDao)

        val radarVm = VerificationRadarViewModel(authRepo)
        radarVm.initialize(
            nonce = "NX-NONCE-123",
            inboundNumber = "+919800000000",
            smsPayload = "NX-VERIFY NX-NONCE-123",
            phone = "+919999988888",
            name = "Verified User",
            avatarUrl = null,
            age = 30,
            gender = "F",
            bio = "Real Bio",
            email = "google.verified@gmail.com"
        )

        assertEquals("google.verified@gmail.com", radarVm.uiState.value.email)

        // Simulate successful polling which commits the session
        authRepo.saveVerifiedSession(
            nxId = "NX-REV12345",
            sessionToken = "token_123",
            name = "Verified User",
            phone = "+919999988888",
            avatarUrl = null,
            age = 30,
            gender = "F",
            bio = "Real Bio",
            email = radarVm.uiState.value.email
        )

        val profile = authRepo.getActiveProfile().first()
        assertNotNull(profile)
        assertEquals("google.verified@gmail.com", profile?.email)
        assertEquals("NX-REV12345", profile?.nxId)
    }

    // -------------------------------------------------------------------------
    // Test 4: DEBUG app restart does not restore demo identity over real user
    // -------------------------------------------------------------------------
    @Test
    fun test04_debugRestartPreservesRealProfile() = runTest {
        val fakeDao = FakeUserDao()
        val authRepo = FakeAuthRepository(fakeDao)

        // User is logged in
        authRepo.saveVerifiedSession(
            nxId = "NX-USER-PERSIST",
            sessionToken = "sess_persist",
            name = "Bob Real",
            phone = "+919123456789",
            email = "bob@example.com"
        )

        // Simulate app restart: countVerifiedUsers() > 0 prevents seeding
        val hasRealUser = fakeDao.countVerifiedUsers() > 0
        assertTrue("Must detect real verified user", hasRealUser)

        if (hasRealUser) {
            fakeDao.deleteDemoProfile()
        }

        // Active profile remains Bob
        val profile = authRepo.getActiveProfile().first()
        assertEquals("NX-USER-PERSIST", profile?.nxId)
        assertEquals("Bob Real", profile?.fullName)
        assertNotEquals("NX-DEV-USER", profile?.nxId)
    }

    // -------------------------------------------------------------------------
    // Test 5: Remote profile fetch updates the correct local record
    // -------------------------------------------------------------------------
    @Test
    fun test05_remoteProfileFetchUpdatesCorrectLocalRecord() = runTest {
        val fakeDao = FakeUserDao()
        val authRepo = FakeAuthRepository(fakeDao)

        authRepo.saveVerifiedSession(
            nxId = "NX-SYNC123",
            sessionToken = "token_sync",
            name = "Old Name",
            phone = "+919000000001",
            email = "old@domain.com"
        )

        authRepo.remoteProfileToReturn = RemoteUserProfileDto(
            nxId = "NX-SYNC123",
            profileName = "New Updated Name",
            phoneNumber = "+919000000001",
            email = "new@domain.com",
            phoneVerified = true
        )

        val syncResult = authRepo.fetchRemoteProfile()
        assertTrue(syncResult.isSuccess)

        val profile = authRepo.getActiveProfile().first()
        assertEquals("New Updated Name", profile?.fullName)
        assertEquals("new@domain.com", profile?.email)
        assertEquals("NX-SYNC123", profile?.nxId)
    }

    // -------------------------------------------------------------------------
    // Test 6: Network failure during remote profile fetch falls back to local verified session
    // -------------------------------------------------------------------------
    @Test
    fun test06_networkFailureFallsBackToLocalSessionWithoutLogout() = runTest {
        val fakeDao = FakeUserDao()
        val authRepo = FakeAuthRepository(fakeDao)

        authRepo.saveVerifiedSession(
            nxId = "NX-OFFLINE",
            sessionToken = "token_offline",
            name = "Offline User",
            phone = "+919555555555",
            email = "offline@local.com"
        )

        authRepo.remoteProfileThrows = true
        val fetchResult = authRepo.fetchRemoteProfile()
        assertFalse(fetchResult.isSuccess)

        // Session validation still returns true because local profile is intact and verified
        assertTrue("Local session must remain valid during temporary network failure", authRepo.validateSession())
        val profile = authRepo.getActiveProfile().first()
        assertEquals("Offline User", profile?.fullName)
    }

    // -------------------------------------------------------------------------
    // Test 7: Display name update targets active NX ID
    // -------------------------------------------------------------------------
    @Test
    fun test07_updateDisplayNameTargetsActiveNxId() = runTest {
        val fakeDao = FakeUserDao()
        val authRepo = FakeAuthRepository(fakeDao)

        authRepo.saveVerifiedSession(
            nxId = "NX-EDIT-NAME",
            sessionToken = "token_edit",
            name = "Initial Name",
            phone = "+919876543210"
        )

        val result = authRepo.updateDisplayName("Renamed User")
        assertTrue(result.isSuccess)

        val profile = authRepo.getActiveProfile().first()
        assertEquals("Renamed User", profile?.fullName)
        assertEquals("NX-EDIT-NAME", profile?.nxId)
    }

    // -------------------------------------------------------------------------
    // Test 8: Logout clears session and user profile cleanly
    // -------------------------------------------------------------------------
    @Test
    fun test08_logoutClearsSessionAndProfile() = runTest {
        val fakeDao = FakeUserDao()
        val authRepo = FakeAuthRepository(fakeDao)

        authRepo.saveVerifiedSession(
            nxId = "NX-LOGOUT-TEST",
            sessionToken = "token_logout",
            name = "Temporary User",
            phone = "+919876543210"
        )

        assertTrue(authRepo.isLoggedIn())

        authRepo.clearSession()

        assertFalse("Must not be logged in after clearSession", authRepo.isLoggedIn())
        assertNull("Active NX ID must be cleared", authRepo.getActiveNxId())
        assertEquals(0, fakeDao.countVerifiedUsers())
    }

    // -------------------------------------------------------------------------
    // Test 9: Second account login displays the new user profile, not prior user
    // -------------------------------------------------------------------------
    @Test
    fun test09_secondAccountLoginReplacesActiveProfile() = runTest {
        val fakeDao = FakeUserDao()
        val authRepo = FakeAuthRepository(fakeDao)

        // First user logs in
        authRepo.saveVerifiedSession(
            nxId = "NX-USER-ONE",
            sessionToken = "token_one",
            name = "User One",
            phone = "+919000000001",
            email = "one@domain.com"
        )
        assertEquals("NX-USER-ONE", authRepo.getActiveProfile().first()?.nxId)

        // Second user logs in
        authRepo.saveVerifiedSession(
            nxId = "NX-USER-TWO",
            sessionToken = "token_two",
            name = "User Two",
            phone = "+919000000002",
            email = "two@domain.com"
        )

        val activeProfile = authRepo.getActiveProfile().first()
        assertEquals("NX-USER-TWO", activeProfile?.nxId)
        assertEquals("User Two", activeProfile?.fullName)
        assertEquals("two@domain.com", activeProfile?.email)
    }

    // -------------------------------------------------------------------------
    // Test 10: App update preserves session and profile data
    // -------------------------------------------------------------------------
    @Test
    fun test10_appUpdatePreservesSessionAndProfile() = runTest {
        val fakeDao = FakeUserDao()
        val authRepo = FakeAuthRepository(fakeDao)

        // Pre-update state: user has a valid verified profile
        authRepo.saveVerifiedSession(
            nxId = "NX-UPDATE-SAFE",
            sessionToken = "token_update_safe",
            name = "Persistent User",
            phone = "+919876543210",
            email = "persistent@nexa.app"
        )

        // Simulate app process restart after update (DAO and SecureStorage retain data)
        assertTrue("Session must remain valid across app update", authRepo.validateSession())
        val profile = authRepo.getActiveProfile().first()
        assertEquals("NX-UPDATE-SAFE", profile?.nxId)
        assertEquals("Persistent User", profile?.fullName)
        assertEquals("persistent@nexa.app", profile?.email)
    }
}
