package com.nexa.app.data.local.preferences

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Canonical chat font size. There is exactly one persisted value for message text size;
 * the Chats settings screen and the Accessibility screen both read and write this store.
 */
enum class ChatFontSize(val label: String, val scale: Float) {
    SMALL("Small", 0.86f),
    DEFAULT("Default", 1.0f),
    LARGE("Large", 1.15f),
    EXTRA_LARGE("Extra Large", 1.30f)
}

data class ChatsSettings(
    val fontSize: ChatFontSize = ChatFontSize.DEFAULT,
    val enterToSend: Boolean = true,
    val mediaVisibilityEnabled: Boolean = true
)

/**
 * Chat presentation preferences: message font size, Enter-to-send, and media visibility.
 * Stored strictly on-device (Zero-Knowledge).
 */
class ChatsPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(load())
    val state: StateFlow<ChatsSettings> = _state.asStateFlow()

    private fun load(): ChatsSettings {
        val sizeName = prefs.getString(KEY_FONT_SIZE, ChatFontSize.DEFAULT.name)
        val size = try {
            ChatFontSize.valueOf(sizeName ?: ChatFontSize.DEFAULT.name)
        } catch (_: Exception) {
            ChatFontSize.DEFAULT
        }
        return ChatsSettings(
            fontSize = size,
            enterToSend = prefs.getBoolean(KEY_ENTER_TO_SEND, true),
            mediaVisibilityEnabled = prefs.getBoolean(KEY_MEDIA_VISIBILITY, true)
        )
    }

    fun setFontSize(size: ChatFontSize) {
        prefs.edit().putString(KEY_FONT_SIZE, size.name).apply()
        _state.value = load()
    }

    fun setEnterToSend(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENTER_TO_SEND, enabled).apply()
        _state.value = load()
    }

    fun setMediaVisibilityEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MEDIA_VISIBILITY, enabled).apply()
        _state.value = load()
    }

    companion object {
        private const val PREFS_NAME = "nexa_chats_preferences"
        private const val KEY_FONT_SIZE = "message_font_size"
        private const val KEY_ENTER_TO_SEND = "enter_to_send"
        private const val KEY_MEDIA_VISIBILITY = "media_visibility"
    }
}

data class AccessibilitySettings(
    val highContrastMessages: Boolean = false
)

/**
 * Accessibility presentation flags. Message text size is intentionally NOT duplicated here;
 * it lives in [ChatsPreferences] as the single canonical font-size preference.
 */
class AccessibilityPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(load())
    val state: StateFlow<AccessibilitySettings> = _state.asStateFlow()

    private fun load(): AccessibilitySettings = AccessibilitySettings(
        highContrastMessages = prefs.getBoolean(KEY_HIGH_CONTRAST, false)
    )

    fun setHighContrastMessages(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_HIGH_CONTRAST, enabled).apply()
        _state.value = load()
    }

    companion object {
        private const val PREFS_NAME = "nexa_accessibility_preferences"
        private const val KEY_HIGH_CONTRAST = "high_contrast_messages"
    }
}

data class NearbyMeshSettings(
    val nearbyDiscoveryEnabled: Boolean = false
)

/**
 * Nearby Discovery is OFF by default. Enabling it is the only thing that starts
 * Google Nearby Connections advertising/discovery (see NearbyMeshScreen).
 */
class NearbyMeshPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(load())
    val state: StateFlow<NearbyMeshSettings> = _state.asStateFlow()

    private fun load(): NearbyMeshSettings = NearbyMeshSettings(
        nearbyDiscoveryEnabled = prefs.getBoolean(KEY_NEARBY_ENABLED, false)
    )

    fun setNearbyDiscoveryEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NEARBY_ENABLED, enabled).apply()
        _state.value = load()
    }

    companion object {
        private const val PREFS_NAME = "nexa_nearby_mesh_preferences"
        private const val KEY_NEARBY_ENABLED = "nearby_discovery_enabled"
    }
}

enum class AppLanguage(
    val tag: String,
    val label: String,
    val nativeLabel: String,
    val isBeta: Boolean = false
) {
    SYSTEM("", "System Default", "Follow device language"),
    ENGLISH("en", "English", "English"),
    HINDI("hi", "Hindi", "हिन्दी", isBeta = true),
    BENGALI("bn", "Bengali", "বাংলা", isBeta = true),
    SPANISH("es", "Spanish", "Español", isBeta = true),
    FRENCH("fr", "French", "Français", isBeta = true),
    GERMAN("de", "German", "Deutsch", isBeta = true),
    PORTUGUESE("pt", "Portuguese", "Português", isBeta = true),
    ARABIC("ar", "Arabic", "العربية", isBeta = true)
}

/**
 * Persists the selected application locale. The locale is applied in
 * NexaApplication.attachBaseContext so it survives process restarts.
 */
class LanguagePreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _selectedLanguage = MutableStateFlow(load())
    val selectedLanguage: StateFlow<AppLanguage> = _selectedLanguage.asStateFlow()

    private fun load(): AppLanguage {
        val tag = prefs.getString(KEY_SELECTED_LANGUAGE, AppLanguage.SYSTEM.tag) ?: AppLanguage.SYSTEM.tag
        return AppLanguage.values().firstOrNull { it.tag == tag } ?: AppLanguage.SYSTEM
    }

    fun setLanguage(language: AppLanguage) {
        prefs.edit().putString(KEY_SELECTED_LANGUAGE, language.tag).apply()
        _selectedLanguage.value = language
    }

    companion object {
        private const val PREFS_NAME = "nexa_language_preferences"
        private const val KEY_SELECTED_LANGUAGE = "selected_language"
        const val KEY_SELECTED_LANGUAGE_FOR_BASE_CONTEXT = "selected_language"
    }
}
