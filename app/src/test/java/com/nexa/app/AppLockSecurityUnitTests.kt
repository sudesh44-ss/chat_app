package com.nexa.app

import android.content.SharedPreferences
import com.nexa.app.core.security.AppLockPinHasher
import com.nexa.app.core.security.AppLockSecurityPolicy
import com.nexa.app.core.security.AutoLockTimeout
import com.nexa.app.core.security.SecureStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for NEXA App Lock security, PIN cryptography, brute-force mitigation,
 * lifecycle Auto-Lock policies, and secure-window flags.
 */
class AppLockSecurityUnitTests {

    private lateinit var testPrefs: InMemorySharedPreferences
    private lateinit var secureStorage: SecureStorage

    @Before
    fun setUp() {
        testPrefs = InMemorySharedPreferences()
        secureStorage = SecureStorage(context = null, testPrefs = testPrefs)
    }

    // ==========================================
    // 1. PIN Validity & PBKDF2 Cryptography
    // ==========================================

    @Test
    fun testPinValidation_policyRules() {
        // Valid PINs: 4 to 8 digits
        assertTrue(AppLockPinHasher.isValidPin("1234"))
        assertTrue(AppLockPinHasher.isValidPin("123456"))
        assertTrue(AppLockPinHasher.isValidPin("12345678"))

        // Invalid: too short, too long, non-digit, empty
        assertFalse(AppLockPinHasher.isValidPin("123"))
        assertFalse(AppLockPinHasher.isValidPin("123456789"))
        assertFalse(AppLockPinHasher.isValidPin("12a4"))
        assertFalse(AppLockPinHasher.isValidPin(""))
        assertFalse(AppLockPinHasher.isValidPin("abcdef"))
    }

    @Test
    fun testPinHashing_verifiesCorrectPin_rejectsWrongPin() {
        val pin = "982341"
        val salt = AppLockPinHasher.newSalt()
        val hash = AppLockPinHasher.hash(pin, salt, iterations = 1000)

        assertTrue(AppLockPinHasher.verify(pin, salt, hash, iterations = 1000))
        assertFalse(AppLockPinHasher.verify("982340", salt, hash, iterations = 1000))
        assertFalse(AppLockPinHasher.verify("123456", salt, hash, iterations = 1000))
    }

    @Test
    fun testPinHashing_uniqueSaltsProduceDifferentHashes() {
        val pin = "654321"
        val salt1 = AppLockPinHasher.newSalt()
        val salt2 = AppLockPinHasher.newSalt()

        val hash1 = AppLockPinHasher.hash(pin, salt1, iterations = 1000)
        val hash2 = AppLockPinHasher.hash(pin, salt2, iterations = 1000)

        assertFalse(hash1.contentEquals(hash2))
    }

    // ==========================================
    // 2. SecureStorage PIN Lifecycle & Verification
    // ==========================================

    @Test
    fun testSecureStorage_pinSetupAndVerification() {
        assertFalse(secureStorage.isAppLockEnabled())
        assertFalse(secureStorage.hasAppLockPin())

        secureStorage.setAppLockPin("4321")

        assertTrue(secureStorage.isAppLockEnabled())
        assertTrue(secureStorage.hasAppLockPin())
        assertTrue(secureStorage.verifyAppLockPin("4321"))
        assertFalse(secureStorage.verifyAppLockPin("0000"))
    }

    @Test
    fun testSecureStorage_changePin_success() {
        secureStorage.setAppLockPin("1122")
        assertTrue(secureStorage.verifyAppLockPin("1122"))

        val changed = secureStorage.changeAppLockPin(currentPin = "1122", newPin = "9988")
        assertTrue(changed)

        // New PIN works, old PIN fails
        assertTrue(secureStorage.verifyAppLockPin("9988"))
        assertFalse(secureStorage.verifyAppLockPin("1122"))
    }

    @Test
    fun testSecureStorage_changePin_failsWithWrongCurrentPin() {
        secureStorage.setAppLockPin("5566")

        val changed = secureStorage.changeAppLockPin(currentPin = "0000", newPin = "9988")
        assertFalse(changed)

        // Old PIN remains untouched
        assertTrue(secureStorage.verifyAppLockPin("5566"))
        assertFalse(secureStorage.verifyAppLockPin("9988"))
    }

    @Test
    fun testSecureStorage_clearAppLock_wipesAllAppLockMaterial() {
        secureStorage.setAppLockPin("7788")
        secureStorage.setBiometricUnlockEnabled(true)
        secureStorage.setAutoLockTimeout(AutoLockTimeout.FIVE_MINUTES)
        secureStorage.setHideAppPreviewEnabled(true)
        secureStorage.setBlockScreenshotsEnabled(true)

        secureStorage.clearAppLock()

        assertFalse(secureStorage.isAppLockEnabled())
        assertFalse(secureStorage.hasAppLockPin())
        assertFalse(secureStorage.isBiometricUnlockEnabled())
        assertEquals(AutoLockTimeout.IMMEDIATELY, secureStorage.getAutoLockTimeout())
        assertFalse(secureStorage.isHideAppPreviewEnabled())
        assertFalse(secureStorage.isBlockScreenshotsEnabled())
    }

    // ==========================================
    // 3. Preferences Persistence & Restoration
    // ==========================================

    @Test
    fun testSecureStorage_autoLockTimeout_persistence() {
        assertEquals(AutoLockTimeout.IMMEDIATELY, secureStorage.getAutoLockTimeout())

        secureStorage.setAutoLockTimeout(AutoLockTimeout.ONE_MINUTE)
        assertEquals(AutoLockTimeout.ONE_MINUTE, secureStorage.getAutoLockTimeout())

        secureStorage.setAutoLockTimeout(AutoLockTimeout.FIVE_MINUTES)
        assertEquals(AutoLockTimeout.FIVE_MINUTES, secureStorage.getAutoLockTimeout())

        secureStorage.setAutoLockTimeout(AutoLockTimeout.FIFTEEN_MINUTES)
        assertEquals(AutoLockTimeout.FIFTEEN_MINUTES, secureStorage.getAutoLockTimeout())
    }

    @Test
    fun testSecureStorage_lockOnLeave_persistence() {
        // Defaults to true
        assertTrue(secureStorage.isLockOnLeaveEnabled())

        secureStorage.setLockOnLeaveEnabled(false)
        assertFalse(secureStorage.isLockOnLeaveEnabled())

        secureStorage.setLockOnLeaveEnabled(true)
        assertTrue(secureStorage.isLockOnLeaveEnabled())
    }

    @Test
    fun testSecureStorage_hideAppPreview_persistence() {
        assertFalse(secureStorage.isHideAppPreviewEnabled())

        secureStorage.setHideAppPreviewEnabled(true)
        assertTrue(secureStorage.isHideAppPreviewEnabled())

        secureStorage.setHideAppPreviewEnabled(false)
        assertFalse(secureStorage.isHideAppPreviewEnabled())
    }

    @Test
    fun testSecureStorage_blockScreenshots_persistence() {
        assertFalse(secureStorage.isBlockScreenshotsEnabled())

        secureStorage.setBlockScreenshotsEnabled(true)
        assertTrue(secureStorage.isBlockScreenshotsEnabled())

        secureStorage.setBlockScreenshotsEnabled(false)
        assertFalse(secureStorage.isBlockScreenshotsEnabled())
    }

    @Test
    fun testSecureStorage_biometricToggle_persistence() {
        secureStorage.setAppLockPin("1234")
        assertFalse(secureStorage.isBiometricUnlockEnabled())

        secureStorage.setBiometricUnlockEnabled(true)
        assertTrue(secureStorage.isBiometricUnlockEnabled())

        secureStorage.setBiometricUnlockEnabled(false)
        assertFalse(secureStorage.isBiometricUnlockEnabled())
    }

    // ==========================================
    // 4. Brute-Force Throttling & Lockout
    // ==========================================

    @Test
    fun testSecureStorage_bruteForceMitigation() {
        assertEquals(0, secureStorage.getAppLockFailedAttempts())
        assertEquals(0L, secureStorage.getAppLockLockoutRemainingSeconds())

        // 4 failed attempts
        repeat(4) { secureStorage.incrementAppLockFailedAttempts() }
        assertEquals(4, secureStorage.getAppLockFailedAttempts())
        assertEquals(0L, secureStorage.getAppLockLockoutRemainingSeconds())

        // 5th failed attempt triggers lockout
        val attempts = secureStorage.incrementAppLockFailedAttempts()
        assertEquals(5, attempts)
        assertTrue(secureStorage.getAppLockLockoutRemainingSeconds() > 0)

        // Reset clears lockout
        secureStorage.resetAppLockFailedAttempts()
        assertEquals(0, secureStorage.getAppLockFailedAttempts())
        assertEquals(0L, secureStorage.getAppLockLockoutRemainingSeconds())
    }

    // ==========================================
    // 5. Lifecycle Lock Policy Enforcement
    // ==========================================

    @Test
    fun testAppLockSecurityPolicy_coldStartAlwaysLocks() {
        // Cold start (lastBackgroundTimestamp = 0)
        assertTrue(
            AppLockSecurityPolicy.shouldLockOnReturn(
                lastBackgroundTimestamp = 0L,
                currentTimestamp = 100_000L,
                lockOnLeave = true,
                timeoutSeconds = 60L
            )
        )
    }

    @Test
    fun testAppLockSecurityPolicy_immediateLock_locksImmediately() {
        val bgTime = 50_000L
        val returnTime = 50_100L // 100ms later

        assertTrue(
            AppLockSecurityPolicy.shouldLockOnReturn(
                lastBackgroundTimestamp = bgTime,
                currentTimestamp = returnTime,
                lockOnLeave = true,
                timeoutSeconds = 0L // Immediately
            )
        )
    }

    @Test
    fun testAppLockSecurityPolicy_timedLock_returnsBeforeTimeout_doesNotLock() {
        val bgTime = 10_000L
        val returnTime = 40_000L // 30s later (timeout is 60s)

        assertFalse(
            AppLockSecurityPolicy.shouldLockOnReturn(
                lastBackgroundTimestamp = bgTime,
                currentTimestamp = returnTime,
                lockOnLeave = true,
                timeoutSeconds = 60L
            )
        )
    }

    @Test
    fun testAppLockSecurityPolicy_timedLock_returnsAfterTimeout_locks() {
        val bgTime = 10_000L
        val returnTime = 75_000L // 65s later (timeout is 60s)

        assertTrue(
            AppLockSecurityPolicy.shouldLockOnReturn(
                lastBackgroundTimestamp = bgTime,
                currentTimestamp = returnTime,
                lockOnLeave = true,
                timeoutSeconds = 60L
            )
        )
    }

    @Test
    fun testAppLockSecurityPolicy_lockOnLeaveFalse_immediateNotTriggered() {
        val bgTime = 10_000L
        val returnTime = 15_000L // 5s later

        assertFalse(
            AppLockSecurityPolicy.shouldLockOnReturn(
                lastBackgroundTimestamp = bgTime,
                currentTimestamp = returnTime,
                lockOnLeave = false,
                timeoutSeconds = 0L
            )
        )
    }

    @Test
    fun testAppLockSecurityPolicy_lockOnLeaveFalse_timedExpired_triggersLock() {
        val bgTime = 10_000L
        val returnTime = 80_000L // 70s later (timeout is 60s)

        assertTrue(
            AppLockSecurityPolicy.shouldLockOnReturn(
                lastBackgroundTimestamp = bgTime,
                currentTimestamp = returnTime,
                lockOnLeave = false,
                timeoutSeconds = 60L
            )
        )
    }

    // ==========================================
    // 6. Window Security (FLAG_SECURE) Computation
    // ==========================================

    @Test
    fun testAppLockSecurityPolicy_computeSecureWindowFlag() {
        // App Lock OFF: never secure window
        assertFalse(
            AppLockSecurityPolicy.computeSecureWindowFlag(
                isAppLockEnabled = false,
                blockScreenshots = true,
                hideAppPreview = true,
                isForeground = true
            )
        )

        // Block Screenshots ON: always secure window
        assertTrue(
            AppLockSecurityPolicy.computeSecureWindowFlag(
                isAppLockEnabled = true,
                blockScreenshots = true,
                hideAppPreview = false,
                isForeground = true
            )
        )
        assertTrue(
            AppLockSecurityPolicy.computeSecureWindowFlag(
                isAppLockEnabled = true,
                blockScreenshots = true,
                hideAppPreview = false,
                isForeground = false
            )
        )

        // Hide App Preview ON, Block Screenshots OFF:
        // Secure in background (prevents Recent Apps snapshot leak), unsecure in foreground
        assertFalse(
            AppLockSecurityPolicy.computeSecureWindowFlag(
                isAppLockEnabled = true,
                blockScreenshots = false,
                hideAppPreview = true,
                isForeground = true
            )
        )
        assertTrue(
            AppLockSecurityPolicy.computeSecureWindowFlag(
                isAppLockEnabled = true,
                blockScreenshots = false,
                hideAppPreview = true,
                isForeground = false
            )
        )

        // Both OFF: never secure window
        assertFalse(
            AppLockSecurityPolicy.computeSecureWindowFlag(
                isAppLockEnabled = true,
                blockScreenshots = false,
                hideAppPreview = false,
                isForeground = true
            )
        )
        assertFalse(
            AppLockSecurityPolicy.computeSecureWindowFlag(
                isAppLockEnabled = true,
                blockScreenshots = false,
                hideAppPreview = false,
                isForeground = false
            )
        )
    }
}

/**
 * Clean, lightweight in-memory [SharedPreferences] implementation for high-speed unit tests.
 */
class InMemorySharedPreferences : SharedPreferences {

    private val map = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = map

    override fun getString(key: String?, defValue: String?): String? =
        (map[key] as? String) ?: defValue

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        (map[key] as? MutableSet<String>) ?: defValues

    override fun getInt(key: String?, defValue: Int): Int =
        (map[key] as? Int) ?: defValue

    override fun getLong(key: String?, defValue: Long): Long =
        (map[key] as? Long) ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float =
        (map[key] as? Float) ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        (map[key] as? Boolean) ?: defValue

    override fun contains(key: String?): Boolean = map.containsKey(key)

    override fun edit(): SharedPreferences.Editor = EditorImpl()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    private inner class EditorImpl : SharedPreferences.Editor {
        private val tempMap = mutableMapOf<String, Any?>()
        private val removeKeys = mutableSetOf<String>()
        private var clearFlag = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            if (key != null) tempMap[key] = value
            return this
        }

        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
            if (key != null) tempMap[key] = values
            return this
        }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
            if (key != null) tempMap[key] = value
            return this
        }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
            if (key != null) tempMap[key] = value
            return this
        }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
            if (key != null) tempMap[key] = value
            return this
        }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
            if (key != null) tempMap[key] = value
            return this
        }

        override fun remove(key: String?): SharedPreferences.Editor {
            if (key != null) removeKeys.add(key)
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clearFlag = true
            return this
        }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            if (clearFlag) map.clear()
            removeKeys.forEach { map.remove(it) }
            map.putAll(tempMap)
        }
    }
}
