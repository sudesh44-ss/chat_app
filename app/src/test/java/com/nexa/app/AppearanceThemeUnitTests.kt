package com.nexa.app

import com.nexa.app.core.theme.AppearanceMode
import com.nexa.app.core.theme.NexaCyan80
import com.nexa.app.core.theme.NexaDarkCharcoalBackground
import com.nexa.app.core.theme.NexaDarkCharcoalSurface
import com.nexa.app.core.theme.NexaDarkCharcoalTextPrimary
import com.nexa.app.core.theme.NexaDarkTextPrimary
import com.nexa.app.core.theme.NexaLightTextPrimary
import com.nexa.app.core.theme.NexaPureBlack
import com.nexa.app.core.theme.NexaPureWhite
import com.nexa.app.data.local.preferences.AppearancePreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for NEXA Appearance preferences, 4-mode theme architecture,
 * color tokens, and theme separation.
 */
class AppearanceThemeUnitTests {

    private lateinit var testPrefs: InMemorySharedPreferences
    private lateinit var appearancePrefs: AppearancePreferences

    @Before
    fun setUp() {
        testPrefs = InMemorySharedPreferences()
        appearancePrefs = AppearancePreferences(testPrefs)
    }

    // ==========================================
    // 1. AppearanceMode Enum & Preferences
    // ==========================================

    @Test
    fun testAppearanceModes_allFourModesExist() {
        val modes = AppearanceMode.values()
        assertEquals(4, modes.size)
        assertTrue(modes.contains(AppearanceMode.SYSTEM))
        assertTrue(modes.contains(AppearanceMode.DARK))
        assertTrue(modes.contains(AppearanceMode.AMOLED))
        assertTrue(modes.contains(AppearanceMode.LIGHT))
    }

    @Test
    fun testAppearancePreferences_initialDefaultIsSystem() {
        assertEquals(AppearanceMode.SYSTEM, appearancePrefs.appearanceMode.value)
    }

    @Test
    fun testAppearancePreferences_setAndGetEveryMode() {
        appearancePrefs.setAppearanceMode(AppearanceMode.DARK)
        assertEquals(AppearanceMode.DARK, appearancePrefs.appearanceMode.value)

        appearancePrefs.setAppearanceMode(AppearanceMode.AMOLED)
        assertEquals(AppearanceMode.AMOLED, appearancePrefs.appearanceMode.value)

        appearancePrefs.setAppearanceMode(AppearanceMode.LIGHT)
        assertEquals(AppearanceMode.LIGHT, appearancePrefs.appearanceMode.value)

        appearancePrefs.setAppearanceMode(AppearanceMode.SYSTEM)
        assertEquals(AppearanceMode.SYSTEM, appearancePrefs.appearanceMode.value)
    }

    @Test
    fun testAppearancePreferences_persistenceRestoration() {
        appearancePrefs.setAppearanceMode(AppearanceMode.AMOLED)

        // Simulate app restart / recreation with same underlying preferences
        val restoredPrefs = AppearancePreferences(testPrefs)
        assertEquals(AppearanceMode.AMOLED, restoredPrefs.appearanceMode.value)
    }

    @Test
    fun testAppearancePreferences_invalidModeFallbackToSystem() {
        testPrefs.edit().putString("key_appearance_mode", "UNKNOWN_LEGACY_MODE").apply()
        val restoredPrefs = AppearancePreferences(testPrefs)
        assertEquals(AppearanceMode.SYSTEM, restoredPrefs.appearanceMode.value)
    }

    @Test
    fun testAppearancePreferences_reduceAnimations_toggleAndPersist() {
        assertFalse(appearancePrefs.reduceAnimations.value)

        appearancePrefs.setReduceAnimations(true)
        assertTrue(appearancePrefs.reduceAnimations.value)

        // Restored instance preserves preference
        val restored = AppearancePreferences(testPrefs)
        assertTrue(restored.reduceAnimations.value)

        appearancePrefs.setReduceAnimations(false)
        assertFalse(appearancePrefs.reduceAnimations.value)
    }

    // ==========================================
    // 2. Color Palettes & Contrast Validation
    // ==========================================

    @Test
    fun testAmoled_isPureBlack() {
        assertEquals(0f, NexaPureBlack.red, 0.001f)
        assertEquals(0f, NexaPureBlack.green, 0.001f)
        assertEquals(0f, NexaPureBlack.blue, 0.001f)
        assertEquals(1f, NexaPureBlack.alpha, 0.001f)
    }

    @Test
    fun testLight_isPureWhite() {
        assertEquals(1f, NexaPureWhite.red, 0.001f)
        assertEquals(1f, NexaPureWhite.green, 0.001f)
        assertEquals(1f, NexaPureWhite.blue, 0.001f)
        assertEquals(1f, NexaPureWhite.alpha, 0.001f)
    }

    @Test
    fun testDarkCharcoal_isVisiblyDistinctFromBlack() {
        assertNotEquals(NexaPureBlack, NexaDarkCharcoalBackground)
        // Background #13151A has non-zero RGB components
        assertTrue(NexaDarkCharcoalBackground.red > 0f)
        assertTrue(NexaDarkCharcoalBackground.green > 0f)
        assertTrue(NexaDarkCharcoalBackground.blue > 0f)
    }

    @Test
    fun testDarkSurface_isElevatedAboveDarkBackground() {
        assertNotEquals(NexaDarkCharcoalBackground, NexaDarkCharcoalSurface)
        // Surface #1E222B is lighter than background #13151A
        assertTrue(NexaDarkCharcoalSurface.red > NexaDarkCharcoalBackground.red)
        assertTrue(NexaDarkCharcoalSurface.green > NexaDarkCharcoalBackground.green)
        assertTrue(NexaDarkCharcoalSurface.blue > NexaDarkCharcoalBackground.blue)
    }

    @Test
    fun testTextContrast_lightModeTextIsDarkSlate() {
        // Light text #0F172A is very dark (red, green, blue < 0.2f)
        assertTrue(NexaLightTextPrimary.red < 0.2f)
        assertTrue(NexaLightTextPrimary.green < 0.2f)
        assertTrue(NexaLightTextPrimary.blue < 0.25f)
    }

    @Test
    fun testTextContrast_darkModeTextIsHighBrightness() {
        // Dark & AMOLED text have high brightness (> 0.9f)
        assertTrue(NexaDarkTextPrimary.red >= 0.9f)
        assertTrue(NexaDarkCharcoalTextPrimary.red >= 0.9f)
    }

    @Test
    fun testBrandAccent_nexaMintTeal() {
        // Mint/Teal #64FFDA has high green/cyan saturation
        assertTrue(NexaCyan80.green > 0.9f)
        assertTrue(NexaCyan80.blue > 0.8f)
    }
}
