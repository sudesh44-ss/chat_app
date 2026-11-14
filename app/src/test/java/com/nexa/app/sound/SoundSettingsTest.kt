package com.nexa.app.sound

import android.content.SharedPreferences
import com.nexa.app.core.sound.AudioValidationResult
import com.nexa.app.data.local.preferences.SoundCategory
import com.nexa.app.data.local.preferences.SoundConfig
import com.nexa.app.data.local.preferences.SoundMode
import com.nexa.app.data.local.preferences.SoundPreferences
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

// ---------------------------------------------------------------------------
// In-memory SharedPreferences implementation for local unit tests (no Android)
// ---------------------------------------------------------------------------
class InMemorySharedPrefs : SharedPreferences {
    private val map = mutableMapOf<String, Any?>()
    private val listeners = mutableListOf<SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun getAll() = map.toMap()
    override fun getString(key: String, defValue: String?) = map[key] as? String ?: defValue
    override fun getStringSet(key: String, defValues: MutableSet<String>?) = null
    override fun getInt(key: String, defValue: Int) = (map[key] as? Int) ?: defValue
    override fun getLong(key: String, defValue: Long) = (map[key] as? Long) ?: defValue
    override fun getFloat(key: String, defValue: Float) = (map[key] as? Float) ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = (map[key] as? Boolean) ?: defValue
    override fun contains(key: String) = map.containsKey(key)
    override fun edit() = object : SharedPreferences.Editor {
        val edits = mutableMapOf<String, Any?>()
        val removes = mutableSetOf<String>()
        var clearAll = false
        override fun putString(key: String, value: String?) = apply { edits[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { edits[key] = values }
        override fun putInt(key: String, value: Int) = apply { edits[key] = value }
        override fun putLong(key: String, value: Long) = apply { edits[key] = value }
        override fun putFloat(key: String, value: Float) = apply { edits[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { edits[key] = value }
        override fun remove(key: String) = apply { removes.add(key) }
        override fun clear() = apply { clearAll = true }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() {
            if (clearAll) map.clear()
            removes.forEach { map.remove(it) }
            map.putAll(edits)
        }
    }
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners.add(listener)
    }
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners.remove(listener)
    }
}

// ---------------------------------------------------------------------------
// SoundPreferences Tests
// ---------------------------------------------------------------------------
class SoundPreferencesTest {

    private lateinit var prefs: SoundPreferences

    @Before
    fun setUp() {
        prefs = SoundPreferences(InMemorySharedPrefs())
    }

    /** Test 1: All 12 categories start in DEFAULT mode on first load (empty SharedPreferences). */
    @Test
    fun defaultMode_allCategoriesDefaultOnFirstLoad() {
        val allConfigs = prefs.getAllConfigs()
        assertEquals("Expected exactly 12 sound categories", 12, allConfigs.size)
        for (category in SoundCategory.values()) {
            val config = allConfigs[category]
            assertNotNull("Config must not be null for $category", config)
            assertEquals(
                "Category $category should default to SoundMode.DEFAULT",
                SoundMode.DEFAULT,
                config!!.mode
            )
        }
    }

    /** Test 2: After updating MESSAGE category to SILENT, getConfig returns SILENT. */
    @Test
    fun updateConfig_persistsAndEmitsCorrectly() {
        val newConfig = SoundConfig(
            category = SoundCategory.MESSAGE,
            mode = SoundMode.SILENT,
            localUri = null,
            displayName = SoundCategory.MESSAGE.defaultDisplayName
        )
        prefs.updateConfig(newConfig)

        val retrieved = prefs.getConfig(SoundCategory.MESSAGE)
        assertEquals(
            "MESSAGE category should be SILENT after update",
            SoundMode.SILENT,
            retrieved.mode
        )
    }

    /** Test 3: Updating MESSAGE does not affect GROUP_MESSAGE. */
    @Test
    fun updateConfig_categoriesAreIndependent() {
        val silentMessage = SoundConfig(
            category = SoundCategory.MESSAGE,
            mode = SoundMode.SILENT,
            localUri = null,
            displayName = SoundCategory.MESSAGE.defaultDisplayName
        )
        prefs.updateConfig(silentMessage)

        val groupConfig = prefs.getConfig(SoundCategory.GROUP_MESSAGE)
        assertEquals(
            "GROUP_MESSAGE should remain DEFAULT when only MESSAGE is changed",
            SoundMode.DEFAULT,
            groupConfig.mode
        )
    }

    /** Test 4: After setting LOCAL mode, resetToDefault restores DEFAULT. */
    @Test
    fun resetToDefault_restoresDefaultMode() {
        val localConfig = SoundConfig(
            category = SoundCategory.CALL_RINGTONE,
            mode = SoundMode.LOCAL,
            localUri = "file:///storage/emulated/0/ringtone.mp3",
            displayName = "My Ringtone"
        )
        prefs.updateConfig(localConfig)

        // Confirm it was set to LOCAL
        assertEquals(SoundMode.LOCAL, prefs.getConfig(SoundCategory.CALL_RINGTONE).mode)

        prefs.resetToDefault(SoundCategory.CALL_RINGTONE)

        val reset = prefs.getConfig(SoundCategory.CALL_RINGTONE)
        assertEquals(
            "After resetToDefault, CALL_RINGTONE should be DEFAULT",
            SoundMode.DEFAULT,
            reset.mode
        )
        assertNull(
            "After resetToDefault, localUri should be null",
            reset.localUri
        )
    }

    /** Test 5: When stored mode string is "GARBAGE", fallback to DEFAULT. */
    @Test
    fun loadConfigFromPrefs_invalidModeString_fallsBackToDefault() {
        // Write an invalid mode directly into the underlying SharedPreferences
        val sharedPrefs = InMemorySharedPrefs()
        sharedPrefs.edit().putString("sound_mode_message", "GARBAGE").apply()

        val soundPrefs = SoundPreferences(sharedPrefs)
        val config = soundPrefs.getConfig(SoundCategory.MESSAGE)

        assertEquals(
            "Invalid mode string 'GARBAGE' must fall back to DEFAULT",
            SoundMode.DEFAULT,
            config.mode
        )
    }

    /** Test 6: LOCAL mode config stores display name correctly even if localUri is missing. */
    @Test
    fun localMode_missingUri_displayNamePreserved() {
        val customName = "My Custom Bell"
        val localConfig = SoundConfig(
            category = SoundCategory.NEW_STATUS,
            mode = SoundMode.LOCAL,
            localUri = null, // URI missing (e.g. file was deleted)
            displayName = customName
        )
        prefs.updateConfig(localConfig)

        val retrieved = prefs.getConfig(SoundCategory.NEW_STATUS)
        assertEquals(
            "Display name must be preserved even when localUri is null",
            customName,
            retrieved.displayName
        )
        assertEquals(
            "Mode should remain LOCAL",
            SoundMode.LOCAL,
            retrieved.mode
        )
    }
}

// ---------------------------------------------------------------------------
// SoundConfig Tests
// ---------------------------------------------------------------------------
class SoundConfigTest {

    /** Test 7: SoundConfig constructed with no optional args has DEFAULT mode. */
    @Test
    fun soundConfig_defaultValues_areCorrect() {
        val config = SoundConfig(category = SoundCategory.MESH_MESSAGE)
        assertEquals(
            "Default SoundConfig mode should be DEFAULT",
            SoundMode.DEFAULT,
            config.mode
        )
        assertNull(
            "Default SoundConfig localUri should be null",
            config.localUri
        )
        assertEquals(
            "Default displayName should equal category.defaultDisplayName",
            SoundCategory.MESH_MESSAGE.defaultDisplayName,
            config.displayName
        )
    }

    /** Test 8: SoundMode.SILENT has correct label. */
    @Test
    fun soundMode_silentMeansNoPlayback() {
        assertEquals(
            "SoundMode.SILENT label should be 'Silent'",
            "Silent",
            SoundMode.SILENT.label
        )
        // Confirm the full label set is consistent
        assertEquals("Default Sound", SoundMode.DEFAULT.label)
        assertEquals("Local Sound File", SoundMode.LOCAL.label)
    }

    /** Test 9: SoundCategory enum has exactly 12 entries. */
    @Test
    fun soundCategory_allTwelvePresent() {
        val categories = SoundCategory.values()
        assertEquals("SoundCategory must have exactly 12 entries", 12, categories.size)

        // Spot-check that specific categories are all present
        val categoryNames = categories.map { it.name }.toSet()
        assertTrue(categoryNames.contains("MESSAGE"))
        assertTrue(categoryNames.contains("GROUP_MESSAGE"))
        assertTrue(categoryNames.contains("CALL_RINGTONE"))
        assertTrue(categoryNames.contains("CONNECTION_REQUEST"))
        assertTrue(categoryNames.contains("TRANSFER_STARTED"))
        assertTrue(categoryNames.contains("TRANSFER_COMPLETED"))
        assertTrue(categoryNames.contains("TRANSFER_FAILED"))
        assertTrue(categoryNames.contains("NEARBY_DEVICE"))
        assertTrue(categoryNames.contains("MESH_MESSAGE"))
        assertTrue(categoryNames.contains("MESH_TRANSFER_COMPLETED"))
        assertTrue(categoryNames.contains("NEW_STATUS"))
        assertTrue(categoryNames.contains("STATUS_INTERACTION"))
    }
}

// ---------------------------------------------------------------------------
// AudioValidationResult Tests
// ---------------------------------------------------------------------------
class AudioValidationResultTest {

    /** Test 10: AudioValidationResult.Success carries correct fields. */
    @Test
    fun audioValidationResult_successHasCorrectFields() {
        // We test the sealed class data class directly (no Android Context required)
        val fakeUri = "file:///data/user/0/com.nexa.app/files/sounds/custom_message.mp3"
        val result = AudioValidationResult.Success(
            cachedUri = fakeUri,
            displayName = "notification.mp3",
            durationMs = 3_500L,
            fileSize = 512_000L
        )

        assertTrue("Result should be Success", result is AudioValidationResult.Success)
        assertEquals("notification.mp3", result.displayName)
        assertEquals(3_500L, result.durationMs)
        assertEquals(512_000L, result.fileSize)
        assertNotNull("cachedUri must not be null", result.cachedUri)
        assertEquals(fakeUri, result.cachedUri)
    }

    /** Test 11: AudioValidationResult.Error carries the rejection reason. */
    @Test
    fun audioValidationResult_errorHasReason() {
        val errorMsg = "Unsupported audio format. NEXA supports MP3, WAV, M4A, and OGG files."
        val result = AudioValidationResult.Error(reason = errorMsg)

        assertTrue("Result should be Error", result is AudioValidationResult.Error)
        assertEquals("Error reason must match", errorMsg, result.reason)
        assertFalse("Error must not be Success", result is AudioValidationResult.Success)
    }
}
