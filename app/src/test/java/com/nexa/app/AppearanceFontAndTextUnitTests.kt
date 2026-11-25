package com.nexa.app

import androidx.compose.ui.text.font.FontFamily
import com.nexa.app.core.theme.AppearanceMode
import com.nexa.app.core.theme.NexaFontStyle
import com.nexa.app.core.theme.NexaTextSize
import com.nexa.app.core.theme.createNexaTypography
import com.nexa.app.data.local.preferences.AppearancePreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for Font Style selection, App Text Size scaling, Chat Text Size scaling,
 * typography generation, and preference persistence & reset behavior.
 */
class AppearanceFontAndTextUnitTests {

    private lateinit var testPrefs: InMemorySharedPreferences
    private lateinit var appearancePrefs: AppearancePreferences

    @Before
    fun setUp() {
        testPrefs = InMemorySharedPreferences()
        appearancePrefs = AppearancePreferences(testPrefs)
    }

    // ==========================================
    // 1. Font Style Enum & Resolution Tests
    // ==========================================

    @Test
    fun testNexaFontStyle_allFiveStylesExist() {
        val styles = NexaFontStyle.entries
        assertEquals(5, styles.size)
        assertTrue(styles.contains(NexaFontStyle.DEFAULT))
        assertTrue(styles.contains(NexaFontStyle.ROBOTO))
        assertTrue(styles.contains(NexaFontStyle.SERIF))
        assertTrue(styles.contains(NexaFontStyle.MONOSPACE))
        assertTrue(styles.contains(NexaFontStyle.CURSIVE))
    }

    @Test
    fun testNexaFontStyle_fontFamilyMapping() {
        assertEquals(FontFamily.Default, NexaFontStyle.DEFAULT.fontFamily)
        assertEquals(FontFamily.SansSerif, NexaFontStyle.ROBOTO.fontFamily)
        assertEquals(FontFamily.Serif, NexaFontStyle.SERIF.fontFamily)
        assertEquals(FontFamily.Monospace, NexaFontStyle.MONOSPACE.fontFamily)
        assertEquals(FontFamily.Cursive, NexaFontStyle.CURSIVE.fontFamily)
    }

    @Test
    fun testNexaFontStyle_fromNameResolution() {
        assertEquals(NexaFontStyle.DEFAULT, NexaFontStyle.fromName("DEFAULT"))
        assertEquals(NexaFontStyle.ROBOTO, NexaFontStyle.fromName("roboto"))
        assertEquals(NexaFontStyle.SERIF, NexaFontStyle.fromName("Serif"))
        assertEquals(NexaFontStyle.MONOSPACE, NexaFontStyle.fromName("MONOSPACE"))
        assertEquals(NexaFontStyle.CURSIVE, NexaFontStyle.fromName("cursive"))

        // Fallbacks for null and invalid names
        assertEquals(NexaFontStyle.DEFAULT, NexaFontStyle.fromName(null))
        assertEquals(NexaFontStyle.DEFAULT, NexaFontStyle.fromName("UNKNOWN_FONT"))
        assertEquals(NexaFontStyle.DEFAULT, NexaFontStyle.fromName(""))
    }

    // ==========================================
    // 2. Text Size Enum & Scaling Factors Tests
    // ==========================================

    @Test
    fun testNexaTextSize_allFiveSizesExist() {
        val sizes = NexaTextSize.entries
        assertEquals(5, sizes.size)
        assertTrue(sizes.contains(NexaTextSize.EXTRA_SMALL))
        assertTrue(sizes.contains(NexaTextSize.SMALL))
        assertTrue(sizes.contains(NexaTextSize.MEDIUM))
        assertTrue(sizes.contains(NexaTextSize.LARGE))
        assertTrue(sizes.contains(NexaTextSize.EXTRA_LARGE))
    }

    @Test
    fun testNexaTextSize_scaleValues() {
        // App scaling
        assertEquals(0.85f, NexaTextSize.EXTRA_SMALL.appScale, 0.001f)
        assertEquals(0.92f, NexaTextSize.SMALL.appScale, 0.001f)
        assertEquals(1.00f, NexaTextSize.MEDIUM.appScale, 0.001f)
        assertEquals(1.10f, NexaTextSize.LARGE.appScale, 0.001f)
        assertEquals(1.20f, NexaTextSize.EXTRA_LARGE.appScale, 0.001f)

        // Chat scaling (independent)
        assertEquals(0.85f, NexaTextSize.EXTRA_SMALL.chatScale, 0.001f)
        assertEquals(0.92f, NexaTextSize.SMALL.chatScale, 0.001f)
        assertEquals(1.00f, NexaTextSize.MEDIUM.chatScale, 0.001f)
        assertEquals(1.15f, NexaTextSize.LARGE.chatScale, 0.001f)
        assertEquals(1.30f, NexaTextSize.EXTRA_LARGE.chatScale, 0.001f)
    }

    @Test
    fun testNexaTextSize_fromNameResolution() {
        assertEquals(NexaTextSize.EXTRA_SMALL, NexaTextSize.fromName("EXTRA_SMALL"))
        assertEquals(NexaTextSize.SMALL, NexaTextSize.fromName("small"))
        assertEquals(NexaTextSize.MEDIUM, NexaTextSize.fromName("Medium"))
        assertEquals(NexaTextSize.LARGE, NexaTextSize.fromName("LARGE"))
        assertEquals(NexaTextSize.EXTRA_LARGE, NexaTextSize.fromName("extra_large"))

        // Fallbacks
        assertEquals(NexaTextSize.MEDIUM, NexaTextSize.fromName(null))
        assertEquals(NexaTextSize.MEDIUM, NexaTextSize.fromName("GIGANTIC"))
    }

    // ==========================================
    // 3. Dynamic Typography Generation Tests
    // ==========================================

    @Test
    fun testCreateNexaTypography_scalesProperly() {
        val defaultType = createNexaTypography(FontFamily.Default, 1.0f)
        val scaledType = createNexaTypography(FontFamily.Monospace, 1.2f)

        // Verify font family assignment
        assertEquals(FontFamily.Default, defaultType.headlineLarge.fontFamily)
        assertEquals(FontFamily.Monospace, scaledType.headlineLarge.fontFamily)
        assertEquals(FontFamily.Monospace, scaledType.bodyLarge.fontFamily)
        assertEquals(FontFamily.Monospace, scaledType.labelMedium.fontFamily)

        // Verify font size scaling
        val baseSize = defaultType.headlineLarge.fontSize.value
        val scaledSize = scaledType.headlineLarge.fontSize.value
        assertEquals(baseSize * 1.2f, scaledSize, 0.01f)

        val baseBodySize = defaultType.bodyLarge.fontSize.value
        val scaledBodySize = scaledType.bodyLarge.fontSize.value
        assertEquals(baseBodySize * 1.2f, scaledBodySize, 0.01f)
    }

    // ==========================================
    // 4. AppearancePreferences Integration Tests
    // ==========================================

    @Test
    fun testAppearancePreferences_initialDefaults() {
        assertEquals(NexaFontStyle.DEFAULT, appearancePrefs.fontStyle.value)
        assertEquals(NexaTextSize.MEDIUM, appearancePrefs.appTextSize.value)
        assertEquals(NexaTextSize.MEDIUM, appearancePrefs.chatTextSize.value)
    }

    @Test
    fun testAppearancePreferences_setAndStreamChanges() {
        appearancePrefs.setFontStyle(NexaFontStyle.SERIF)
        assertEquals(NexaFontStyle.SERIF, appearancePrefs.fontStyle.value)

        appearancePrefs.setAppTextSize(NexaTextSize.LARGE)
        assertEquals(NexaTextSize.LARGE, appearancePrefs.appTextSize.value)

        appearancePrefs.setChatTextSize(NexaTextSize.EXTRA_LARGE)
        assertEquals(NexaTextSize.EXTRA_LARGE, appearancePrefs.chatTextSize.value)
    }

    @Test
    fun testAppearancePreferences_persistenceAcrossRestarts() {
        appearancePrefs.setFontStyle(NexaFontStyle.CURSIVE)
        appearancePrefs.setAppTextSize(NexaTextSize.SMALL)
        appearancePrefs.setChatTextSize(NexaTextSize.EXTRA_SMALL)

        val restored = AppearancePreferences(testPrefs)
        assertEquals(NexaFontStyle.CURSIVE, restored.fontStyle.value)
        assertEquals(NexaTextSize.SMALL, restored.appTextSize.value)
        assertEquals(NexaTextSize.EXTRA_SMALL, restored.chatTextSize.value)
    }

    @Test
    fun testAppearancePreferences_resetFontAndText_preservesTheme() {
        // Set custom theme and custom font/sizes
        appearancePrefs.setAppearanceMode(AppearanceMode.AMOLED)
        appearancePrefs.setReduceAnimations(true)
        appearancePrefs.setFontStyle(NexaFontStyle.MONOSPACE)
        appearancePrefs.setAppTextSize(NexaTextSize.EXTRA_LARGE)
        appearancePrefs.setChatTextSize(NexaTextSize.LARGE)

        // Act: reset only font and text
        appearancePrefs.resetFontAndText()

        // Assert: Font and sizes returned to default
        assertEquals(NexaFontStyle.DEFAULT, appearancePrefs.fontStyle.value)
        assertEquals(NexaTextSize.MEDIUM, appearancePrefs.appTextSize.value)
        assertEquals(NexaTextSize.MEDIUM, appearancePrefs.chatTextSize.value)

        // CRUCIAL: Theme and animation preferences must NOT be touched!
        assertEquals(AppearanceMode.AMOLED, appearancePrefs.appearanceMode.value)
        assertTrue(appearancePrefs.reduceAnimations.value)
    }
}
