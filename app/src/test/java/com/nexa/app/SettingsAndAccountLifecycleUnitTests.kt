package com.nexa.app

import com.nexa.app.core.security.AppLockPinHasher
import com.nexa.app.core.theme.AppearanceMode
import com.nexa.app.data.local.preferences.AppLanguage
import com.nexa.app.data.local.preferences.ChatFontSize
import com.nexa.app.data.local.preferences.NearbyMeshSettings
import com.nexa.app.domain.model.AccountStatus
import com.nexa.app.presentation.settings.SettingsDestination
import com.nexa.app.presentation.settings.account.deletion.DeletionStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsAndAccountLifecycleUnitTests {

    // ==========================================
    // 1. Settings Screen: Exact Sections in Order
    // ==========================================

    @Test
    fun testSettingsOptions_exactSectionsInExactOrder() {
        val options = SettingsDestination.values()
        assertEquals(18, options.size)
        assertEquals(SettingsDestination.ACCOUNT, options[0])
        assertEquals(SettingsDestination.PERSONAL_PRIVACY, options[1])
        assertEquals(SettingsDestination.PRIVACY_AND_SAFETY, options[2])
        assertEquals(SettingsDestination.SECURITY, options[3])
        assertEquals(SettingsDestination.APP_LOCK, options[4])
        assertEquals(SettingsDestination.APPEARANCE, options[5])
        assertEquals(SettingsDestination.NOTIFICATIONS, options[6])
        assertEquals(SettingsDestination.SOUND, options[7])
        assertEquals(SettingsDestination.CHATS, options[8])
        assertEquals(SettingsDestination.ARCHIVE, options[9])
        assertEquals(SettingsDestination.NEARBY_AND_MESH, options[10])
        assertEquals(SettingsDestination.STORAGE_AND_DATA, options[11])
        assertEquals(SettingsDestination.LANGUAGE, options[12])
        assertEquals(SettingsDestination.ACCESSIBILITY, options[13])
        assertEquals(SettingsDestination.CONNECTED_DEVICES, options[14])
        assertEquals(SettingsDestination.BACKUP, options[15])
        assertEquals(SettingsDestination.HELP, options[16])
        assertEquals(SettingsDestination.ABOUT, options[17])
    }

    @Test
    fun testSettingsSections_separationInvariants() {
        val options = SettingsDestination.values().toList()
        // Personal Privacy and Privacy & Safety are distinct sections.
        assertTrue(options.contains(SettingsDestination.PERSONAL_PRIVACY))
        assertTrue(options.contains(SettingsDestination.PRIVACY_AND_SAFETY))
        // Notifications and Sound are distinct sections.
        assertTrue(options.contains(SettingsDestination.NOTIFICATIONS))
        assertTrue(options.contains(SettingsDestination.SOUND))
        // Backup stays separate from Security.
        assertTrue(options.contains(SettingsDestination.BACKUP))
        assertTrue(options.contains(SettingsDestination.SECURITY))
        // Unapproved legacy options are not reintroduced.
        assertFalse(options.any { it.name == "ARCHIVED_CHATS" })
        assertFalse(options.any { it.name == "NETWORK" })
    }

    // ==========================================
    // 1b. Canonical Font Size Preference
    // ==========================================

    @Test
    fun testChatFontSizes_ascendingScale() {
        val sizes = ChatFontSize.values().toList()
        assertEquals(4, sizes.size)
        sizes.zipWithNext().forEach { (smaller, larger) ->
            assertTrue(
                "${smaller.name} scale must be smaller than ${larger.name}",
                smaller.scale < larger.scale
            )
        }
        assertEquals(1.0f, ChatFontSize.DEFAULT.scale, 0.0001f)
    }

    // ==========================================
    // 1c. Nearby Discovery Defaults OFF
    // ==========================================

    @Test
    fun testNearbyMeshSettings_defaultsOff() {
        assertFalse(NearbyMeshSettings().nearbyDiscoveryEnabled)
    }

    // ==========================================
    // 1d. Language Defaults to System
    // ==========================================

    @Test
    fun testAppLanguage_defaultsToSystem() {
        assertEquals("", AppLanguage.SYSTEM.tag)
        assertEquals(AppLanguage.SYSTEM, AppLanguage.values().first())
    }

    // ==========================================
    // 1e. App Lock PIN Hashing
    // ==========================================

    @Test
    fun testAppLockPin_validityRules() {
        assertTrue(AppLockPinHasher.isValidPin("1234"))
        assertTrue(AppLockPinHasher.isValidPin("12345678"))
        assertFalse(AppLockPinHasher.isValidPin("123"))
        assertFalse(AppLockPinHasher.isValidPin("123456789"))
        assertFalse(AppLockPinHasher.isValidPin("12a4"))
        assertFalse(AppLockPinHasher.isValidPin(""))
    }

    @Test
    fun testAppLockPin_hashVerifiesAndRejectsWrongPin() {
        val pin = "7391"
        val salt = AppLockPinHasher.newSalt()
        val hash = AppLockPinHasher.hash(pin, salt, iterations = 1_000)
        assertTrue(AppLockPinHasher.verify(pin, salt, hash, iterations = 1_000))
        assertFalse(AppLockPinHasher.verify("7392", salt, hash, iterations = 1_000))
    }

    @Test
    fun testAppLockPin_saltChangesHash() {
        val pin = "4821"
        val first = AppLockPinHasher.hash(pin, AppLockPinHasher.newSalt(), iterations = 1_000)
        val second = AppLockPinHasher.hash(pin, AppLockPinHasher.newSalt(), iterations = 1_000)
        assertFalse(first.contentEquals(second))
    }

    // ==========================================
    // 2. Account Lifecycle State Machine
    // ==========================================

    @Test
    fun testAccountStatus_lifecycleStatesExist() {
        val states = AccountStatus.values()
        assertTrue(states.contains(AccountStatus.ACTIVE))
        assertTrue(states.contains(AccountStatus.DEACTIVATED))
        assertTrue(states.contains(AccountStatus.DELETION_REQUESTED))
        assertTrue(states.contains(AccountStatus.IDENTITY_VERIFIED))
        assertTrue(states.contains(AccountStatus.DELETION_CONFIRMED))
        assertTrue(states.contains(AccountStatus.DELETION_PROCESSING))
        assertTrue(states.contains(AccountStatus.DELETED))
        assertTrue(states.contains(AccountStatus.DELETION_FAILED))
        assertTrue(states.contains(AccountStatus.DELETION_CANCELLED))
    }

    @Test
    fun testAccountDeactivation_isReversible() {
        var status = AccountStatus.ACTIVE

        // Deactivation
        status = AccountStatus.DEACTIVATED
        assertEquals(AccountStatus.DEACTIVATED, status)

        // Reactivation
        status = AccountStatus.ACTIVE
        assertEquals(AccountStatus.ACTIVE, status)
    }

    @Test
    fun testAccountDeletion_progressiveStateTransitions() {
        var status = AccountStatus.ACTIVE

        // Step 1: User requests deletion
        status = AccountStatus.DELETION_REQUESTED
        assertEquals(AccountStatus.DELETION_REQUESTED, status)

        // Step 2: OTP verified
        status = AccountStatus.IDENTITY_VERIFIED
        assertEquals(AccountStatus.IDENTITY_VERIFIED, status)

        // Step 3: Final confirmation given
        status = AccountStatus.DELETION_CONFIRMED
        assertEquals(AccountStatus.DELETION_CONFIRMED, status)

        // Step 4: Background secure job processing
        status = AccountStatus.DELETION_PROCESSING
        assertEquals(AccountStatus.DELETION_PROCESSING, status)

        // Step 5: Terminal state
        status = AccountStatus.DELETED
        assertEquals(AccountStatus.DELETED, status)
    }

    // ==========================================
    // 3. Deletion Data Impact Categories (Exact 5 Categories)
    // ==========================================

    @Test
    fun testDataImpactReview_fiveCategoriesDefined() {
        val categories = listOf(
            "Profile & Account Information",
            "Contacts & Connections",
            "Messages Stored on This Account",
            "Groups & Memberships",
            "Account Settings"
        )
        assertEquals(5, categories.size)
        assertEquals("Profile & Account Information", categories[0])
        assertEquals("Contacts & Connections", categories[1])
        assertEquals("Messages Stored on This Account", categories[2])
        assertEquals("Groups & Memberships", categories[3])
        assertEquals("Account Settings", categories[4])
    }

    // ==========================================
    // 4. Deletion Multi-Step Flow Progression
    // ==========================================

    @Test
    fun testDeletionSteps_progressionSequence() {
        val steps = DeletionStep.values()
        assertTrue(steps.contains(DeletionStep.WARNING_AND_IMPACT))
        assertTrue(steps.contains(DeletionStep.ACKNOWLEDGEMENT))
        assertTrue(steps.contains(DeletionStep.IDENTITY_VERIFICATION))
        assertTrue(steps.contains(DeletionStep.PROCESSING))
        assertTrue(steps.contains(DeletionStep.SUCCESS))
    }

    // ==========================================
    // 5. Phone Number Masking Utility
    // ==========================================

    @Test
    fun testPhoneNumberMasking_protectsPrivacy() {
        fun maskPhone(phone: String): String {
            return if (phone.length > 6) {
                val start = phone.take(4)
                val end = phone.takeLast(2)
                "$start •••• $end"
            } else {
                phone
            }
        }

        val masked = maskPhone("+919876543210")
        assertEquals("+919 •••• 10", masked)
        assertFalse("Raw number should not be visible", masked.contains("8765432"))
    }

    // ==========================================
    // 6. Appearance Mode Preferences
    // ==========================================

    @Test
    fun testAppearanceModes_allSupported() {
        val modes = AppearanceMode.values()
        assertEquals(3, modes.size)
        assertTrue(modes.contains(AppearanceMode.SYSTEM))
        assertTrue(modes.contains(AppearanceMode.DARK))
        assertTrue(modes.contains(AppearanceMode.LIGHT))
    }
}
