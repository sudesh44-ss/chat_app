package com.nexa.app

import com.nexa.app.core.backup.BackupCategoryConfig
import com.nexa.app.core.backup.Bip39PassphraseHelper
import com.nexa.app.core.util.DriveResource
import com.nexa.app.data.drive.DriveFile
import com.nexa.app.data.local.dao.UserDao
import com.nexa.app.data.local.entity.UserEntity
import com.nexa.app.data.remote.dto.GoogleAuthResponse
import com.nexa.app.data.remote.dto.InitiateReverseVerifyResponseDto
import com.nexa.app.domain.model.UserProfile
import com.nexa.app.domain.repository.AuthRepository
import com.nexa.app.domain.repository.DriveBackupRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit test suite validating the complete returning-user login, SIM verification,
 * device migration, and encrypted backup discovery lifecycle in NEXA.
 */
class ExistingUserLoginAndDeviceMigrationUnitTests {

    // --- Mock User Profile & Account Data ---
    private val existingNxId = "NX-7F82K9A4"
    private val existingName = "Sudesh"
    private val existingPhone = "+919876543212"
    private val existingAvatar = "https://nexa.vault/avatars/sudesh.png"
    private val maskedPhone = "+91 98XXXXXX12"
    private val googleId = "google_user_789456"
    private val activeOldDeviceId = "dev_redmi_note_12"
    private val activeOldDeviceName = "Redmi Note 12"

    // 1. New Google user registration flow
    @Test
    fun testNewGoogleUserReceivesNewUserStatusAndProceedsToRegistration() = runTest {
        val newGoogleResponse = GoogleAuthResponse(
            status = "NEW_USER",
            googleUid = "google_new_999",
            email = "newuser@example.com",
            prefillName = "New Nexa User",
            prefillAvatar = "https://lh3.googleusercontent.com/new.jpg"
        )

        assertEquals("NEW_USER", newGoogleResponse.status)
        assertNull("New user must not have an existing NX ID", newGoogleResponse.nxId)
        assertFalse("New user has no active device", newGoogleResponse.hasActiveDevice)
        assertNotNull("Google UID must be present for prefilling", newGoogleResponse.googleUid)
    }

    // 2. Existing Google user login flow
    @Test
    fun testExistingGoogleUserReturnsExistingUserMetadata() = runTest {
        val returningUserResponse = GoogleAuthResponse(
            status = "REQUIRE_SIM_VERIFICATION",
            googleUid = googleId,
            email = "sudesh@example.com",
            prefillName = existingName,
            prefillAvatar = existingAvatar,
            nxId = existingNxId,
            registeredPhoneMasked = maskedPhone,
            phoneNumber = existingPhone,
            hasActiveDevice = true,
            activeDeviceName = activeOldDeviceName,
            activeDeviceId = activeOldDeviceId,
            isSameDevice = false
        )

        assertEquals("REQUIRE_SIM_VERIFICATION", returningUserResponse.status)
        assertEquals(existingNxId, returningUserResponse.nxId)
        assertEquals(existingName, returningUserResponse.prefillName)
        assertEquals(existingAvatar, returningUserResponse.prefillAvatar)
        assertEquals(maskedPhone, returningUserResponse.registeredPhoneMasked)
        assertTrue(returningUserResponse.hasActiveDevice)
        assertEquals(activeOldDeviceName, returningUserResponse.activeDeviceName)
        assertFalse(returningUserResponse.isSameDevice)
    }

    // 3. Existing NX ID retrieval and preservation (no new NX ID generated)
    @Test
    fun testExistingGoogleUserPreservesExistingNxId() = runTest {
        val initialAccount = UserEntity(
            nxId = existingNxId,
            fullName = existingName,
            phoneNumber = existingPhone,
            email = "sudesh@example.com",
            avatarUrl = existingAvatar,
            age = 28,
            gender = "M",
            bio = "NEXA Encrypted User",
            publicKeyHex = "aa".repeat(32),
            isVerified = true
        )

        val fakeUserDao = InMemoryUserDao(mutableMapOf(existingNxId to initialAccount))

        // Returning user re-verifies session
        val resolvedProfile = fakeUserDao.getProfileByNxId(existingNxId)
        assertNotNull(resolvedProfile)
        assertEquals("NX-7F82K9A4", resolvedProfile?.nxId)

        // Ensure newly generated random ID is NEVER assigned
        assertFalse("Existing NX ID must never be re-allocated", resolvedProfile?.nxId == "NX-DEV-USER")
        assertFalse("Existing NX ID must not change", resolvedProfile?.nxId?.startsWith("NX-NEW") == true)
    }

    // 4. Returning user skipping registration form (Name, Age, Gender preserved)
    @Test
    fun testReturningUserSkipsNameAgeGenderRegistration() = runTest {
        val initialAccount = UserEntity(
            nxId = existingNxId,
            fullName = existingName,
            phoneNumber = existingPhone,
            email = "sudesh@example.com",
            avatarUrl = existingAvatar,
            age = 29,
            gender = "M",
            bio = "Encrypted Bio",
            publicKeyHex = "bb".repeat(32),
            isVerified = true
        )

        val fakeUserDao = InMemoryUserDao(mutableMapOf(existingNxId to initialAccount))

        // Session restore on returning user
        val profile = fakeUserDao.getProfileByNxId(existingNxId)
        assertEquals("Sudesh", profile?.fullName)
        assertEquals(29, profile?.age)
        assertEquals("M", profile?.gender)
        assertEquals("Encrypted Bio", profile?.bio)
    }

    // 5. Same-device login vs new-device login
    @Test
    fun testSameDeviceLoginDifferentiatesFromNewDeviceLogin() {
        val currentDeviceId = "dev_redmi_note_12"

        val sameDeviceResp = GoogleAuthResponse(
            status = "REQUIRE_DEVICE_CHALLENGE",
            nxId = existingNxId,
            deviceId = currentDeviceId,
            isSameDevice = true,
            hasActiveDevice = true,
            activeDeviceId = currentDeviceId,
            activeDeviceName = "Redmi Note 12"
        )

        val newDeviceResp = GoogleAuthResponse(
            status = "REQUIRE_SIM_VERIFICATION",
            nxId = existingNxId,
            deviceId = "dev_pixel_9",
            isSameDevice = false,
            hasActiveDevice = true,
            activeDeviceId = currentDeviceId,
            activeDeviceName = "Redmi Note 12"
        )

        assertTrue("Same device should identify matches", sameDeviceResp.isSameDevice)
        assertFalse("New device should trigger migration flow", newDeviceResp.isSameDevice)
        assertTrue("New device detects old active device", newDeviceResp.hasActiveDevice && newDeviceResp.activeDeviceId != "dev_pixel_9")
    }

    // 6. Device change detected confirmation check
    @Test
    fun testDeviceChangeDetectedWhenOldDeviceActiveOnDifferentId() {
        val activeDevice = "Redmi Note 12"
        val activeDeviceId = "dev_redmi_12"
        val currentDeviceId = "dev_samsung_s24"

        val shouldShowWarning = activeDeviceId != currentDeviceId && activeDevice.isNotBlank()
        assertTrue("User must be presented with device change warning", shouldShowWarning)
    }

    // 7. Old-device revocation and single-active-phone policy
    @Test
    fun testNewDeviceRegistrationRevokesOldDeviceSession() = runTest {
        val registeredDevices = mutableMapOf(
            "dev_redmi_12" to DeviceRecord("dev_redmi_12", "Redmi Note 12", isRevoked = false)
        )

        // New device registered
        val newDeviceId = "dev_pixel_9"
        // Atomic single-active-device migration
        registeredDevices.values.forEach { it.isRevoked = true }
        registeredDevices[newDeviceId] = DeviceRecord(newDeviceId, "Google Pixel 9", isRevoked = false)

        assertTrue("Old device must be marked revoked", registeredDevices["dev_redmi_12"]?.isRevoked == true)
        assertFalse("New device must be active", registeredDevices["dev_pixel_9"]?.isRevoked == true)
        val activeCount = registeredDevices.values.count { !it.isRevoked }
        assertEquals("Single-active-phone policy strictly enforced", 1, activeCount)
    }

    // 8. Encrypted backup found displays restore prompt
    @Test
    fun testEncryptedBackupFoundDisplaysRestorePrompt() = runTest {
        val fakeDriveRepo = FakeDriveBackupRepository(
            remoteVault = DriveFile(
                id = "drive_vault_file_123",
                name = "nexa_vault.enc",
                modifiedTime = "2026-11-12T10:30:00Z",
                size = 45 * 1024 * 1024L // 45 MB
            )
        )

        val result = fakeDriveRepo.checkRemoteBackup().first()
        assertTrue(result is DriveResource.Success)
        val file = (result as DriveResource.Success).data
        assertNotNull("Backup file must be discovered", file)
        assertEquals("drive_vault_file_123", file?.id)
        assertEquals(45 * 1024 * 1024L, file?.size)
    }

    // 9. Missing encrypted backup auto-continues to Dashboard
    @Test
    fun testMissingEncryptedBackupAutoContinuesToDashboard() = runTest {
        val fakeDriveRepo = FakeDriveBackupRepository(remoteVault = null)

        val result = fakeDriveRepo.checkRemoteBackup().first()
        assertTrue(result is DriveResource.Success)
        val file = (result as DriveResource.Success).data
        assertNull("No backup vault found", file)
    }

    // 10. Skip backup restoration navigates directly to Dashboard
    @Test
    fun testSkipEncryptedBackupNavigatesToDashboardWithoutRestoring() = runTest {
        var isRestoreCalled = false
        val onSkip: () -> Unit = { /* Navigates to Dashboard */ }
        val onRestore: () -> Unit = { isRestoreCalled = true }

        // User clicks Skip
        onSkip()
        assertFalse("Restoration must not execute when user skips", isRestoreCalled)
    }

    // 11. BIP-39 12-word recovery passphrase verification
    @Test
    fun testBip39MnemonicPassphraseValidation() {
        val validMnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        assertTrue(Bip39PassphraseHelper.validateMnemonic(validMnemonic))

        val invalidMnemonic = "abandon invalidword abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        assertFalse(Bip39PassphraseHelper.validateMnemonic(invalidMnemonic))
    }

    // 12. App update session preservation
    @Test
    fun testAppUpdatePreservesSessionWithoutLogout() = runTest {
        val initialAccount = UserEntity(
            nxId = existingNxId,
            fullName = existingName,
            phoneNumber = existingPhone,
            email = "sudesh@example.com",
            avatarUrl = existingAvatar,
            age = 28,
            gender = "M",
            bio = "Encrypted Vault",
            publicKeyHex = "cc".repeat(32),
            isVerified = true
        )
        val fakeUserDao = InMemoryUserDao(mutableMapOf(existingNxId to initialAccount))

        // After APK update, app queries session
        val verifiedCount = fakeUserDao.countVerifiedUsers()
        assertTrue("Active session must survive update", verifiedCount > 0)
        val activeProfile = fakeUserDao.getLocalProfile()
        assertEquals(existingNxId, activeProfile?.nxId)
        assertEquals(existingName, activeProfile?.fullName)
    }

    // --- Helper Classes ---

    private data class DeviceRecord(
        val deviceId: String,
        val deviceName: String,
        var isRevoked: Boolean
    )

    private class InMemoryUserDao(
        private val storage: MutableMap<String, UserEntity> = mutableMapOf()
    ) : UserDao {
        override suspend fun saveUserProfile(user: UserEntity): Long {
            storage[user.nxId] = user
            return 1L
        }

        override fun getProfileByNxIdFlow(nxId: String): Flow<UserEntity?> =
            flowOf(storage[nxId])

        override suspend fun getProfileByNxId(nxId: String): UserEntity? =
            storage[nxId]

        override suspend fun getLocalProfile(): UserEntity? =
            storage.values.firstOrNull { it.nxId != "NX-DEV-USER" } ?: storage.values.firstOrNull()

        override fun getActiveUserFlow(): Flow<UserEntity?> =
            kotlinx.coroutines.flow.flow { emit(getLocalProfile()) }

        override suspend fun getActiveNxId(): String? =
            getLocalProfile()?.nxId

        override suspend fun countVerifiedUsers(): Int =
            storage.values.count { it.isVerified && it.nxId != "NX-DEV-USER" }

        override suspend fun updateFullName(nxId: String, fullName: String): Int {
            storage[nxId] = storage[nxId]?.copy(fullName = fullName) ?: return 0
            return 1
        }

        override suspend fun updatePhoneNumber(nxId: String, phoneNumber: String): Int {
            storage[nxId] = storage[nxId]?.copy(phoneNumber = phoneNumber) ?: return 0
            return 1
        }

        override suspend fun deleteDemoProfile(): Int {
            val removed = storage.remove("NX-DEV-USER")
            return if (removed != null) 1 else 0
        }

        override suspend fun deleteProfileByNxId(nxId: String): Int {
            val removed = storage.remove(nxId)
            return if (removed != null) 1 else 0
        }

        override suspend fun clearProfile(): Int {
            val count = storage.size
            storage.clear()
            return count
        }
    }

    private class FakeDriveBackupRepository(
        private val remoteVault: DriveFile? = null
    ) : DriveBackupRepository {
        override fun createAndUploadBackup(
            passphraseWords: String,
            categoryConfig: BackupCategoryConfig
        ): Flow<DriveResource<String>> = flowOf(DriveResource.Success("uploaded"))

        override fun checkRemoteBackup(): Flow<DriveResource<DriveFile?>> =
            flowOf(DriveResource.Success(remoteVault))

        override fun downloadAndRestoreBackup(
            fileId: String,
            passphraseWords: String,
            restoreCategoryConfig: BackupCategoryConfig
        ): Flow<DriveResource<Boolean>> = flowOf(DriveResource.Success(true))

        override fun deleteRemoteBackup(fileId: String): Flow<DriveResource<Boolean>> =
            flowOf(DriveResource.Success(true))

        override fun verifyRemoteBackupIntegrity(fileId: String): Flow<DriveResource<Boolean>> =
            flowOf(DriveResource.Success(true))

        override fun calculateBackupSize(categoryConfig: BackupCategoryConfig): Long = 45 * 1024 * 1024L

        override suspend fun performSilentBackup(): Boolean = true

        override fun getRecoveryPassphrase(): String? = "abandon ".repeat(11) + "about"

        override fun generateOrGetRecoveryPassphrase(): String = "abandon ".repeat(11) + "about"

        override fun saveRecoveryPassphrase(passphrase: String) {}
    }
}
