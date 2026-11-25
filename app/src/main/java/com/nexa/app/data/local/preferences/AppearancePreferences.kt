package com.nexa.app.data.local.preferences

import android.content.Context
import android.content.SharedPreferences
import com.nexa.app.core.theme.AppearanceMode
import com.nexa.app.core.theme.NexaFontStyle
import com.nexa.app.core.theme.NexaTextSize
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persists and streams the user's selected AppearanceMode (SYSTEM, DARK, AMOLED, LIGHT),
 * Reduce Animations preference, Font Style, App Text Size, and Chat Text Size.
 * Changes are observed in real-time across the app.
 */
class AppearancePreferences(
    private val prefs: SharedPreferences
) {
    constructor(context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    private val _appearanceMode = MutableStateFlow(getStoredMode())
    val appearanceMode: StateFlow<AppearanceMode> = _appearanceMode.asStateFlow()

    private val _reduceAnimations = MutableStateFlow(getStoredReduceAnimations())
    val reduceAnimations: StateFlow<Boolean> = _reduceAnimations.asStateFlow()

    private val _fontStyle = MutableStateFlow(getStoredFontStyle())
    val fontStyle: StateFlow<NexaFontStyle> = _fontStyle.asStateFlow()

    private val _appTextSize = MutableStateFlow(getStoredAppTextSize())
    val appTextSize: StateFlow<NexaTextSize> = _appTextSize.asStateFlow()

    private val _chatTextSize = MutableStateFlow(getStoredChatTextSize())
    val chatTextSize: StateFlow<NexaTextSize> = _chatTextSize.asStateFlow()

    private fun getStoredMode(): AppearanceMode {
        val name = prefs.getString(KEY_APPEARANCE_MODE, AppearanceMode.SYSTEM.name)
        return try {
            AppearanceMode.valueOf(name ?: AppearanceMode.SYSTEM.name)
        } catch (e: Exception) {
            AppearanceMode.SYSTEM
        }
    }

    fun setAppearanceMode(mode: AppearanceMode) {
        prefs.edit().putString(KEY_APPEARANCE_MODE, mode.name).apply()
        _appearanceMode.value = mode
    }

    private fun getStoredReduceAnimations(): Boolean =
        prefs.getBoolean(KEY_REDUCE_ANIMATIONS, false)

    fun setReduceAnimations(reduce: Boolean) {
        prefs.edit().putBoolean(KEY_REDUCE_ANIMATIONS, reduce).apply()
        _reduceAnimations.value = reduce
    }

    private fun getStoredFontStyle(): NexaFontStyle {
        val name = prefs.getString(KEY_FONT_STYLE, NexaFontStyle.DEFAULT.name)
        return NexaFontStyle.fromName(name)
    }

    fun setFontStyle(style: NexaFontStyle) {
        prefs.edit().putString(KEY_FONT_STYLE, style.name).apply()
        _fontStyle.value = style
    }

    private fun getStoredAppTextSize(): NexaTextSize {
        val name = prefs.getString(KEY_APP_TEXT_SIZE, NexaTextSize.MEDIUM.name)
        return NexaTextSize.fromName(name)
    }

    fun setAppTextSize(size: NexaTextSize) {
        prefs.edit().putString(KEY_APP_TEXT_SIZE, size.name).apply()
        _appTextSize.value = size
    }

    private fun getStoredChatTextSize(): NexaTextSize {
        val name = prefs.getString(KEY_CHAT_TEXT_SIZE, NexaTextSize.MEDIUM.name)
        return NexaTextSize.fromName(name)
    }

    fun setChatTextSize(size: NexaTextSize) {
        prefs.edit().putString(KEY_CHAT_TEXT_SIZE, size.name).apply()
        _chatTextSize.value = size
    }

    /**
     * Resets Font Style to Default NEXA, App Text Size to Medium, and Chat Text Size to Medium.
     * Does NOT modify appearanceMode or reduceAnimations.
     */
    fun resetFontAndText() {
        prefs.edit()
            .putString(KEY_FONT_STYLE, NexaFontStyle.DEFAULT.name)
            .putString(KEY_APP_TEXT_SIZE, NexaTextSize.MEDIUM.name)
            .putString(KEY_CHAT_TEXT_SIZE, NexaTextSize.MEDIUM.name)
            .apply()
        _fontStyle.value = NexaFontStyle.DEFAULT
        _appTextSize.value = NexaTextSize.MEDIUM
        _chatTextSize.value = NexaTextSize.MEDIUM
    }

    companion object {
        private const val PREFS_NAME = "nexa_appearance_prefs"
        private const val KEY_APPEARANCE_MODE = "key_appearance_mode"
        private const val KEY_REDUCE_ANIMATIONS = "key_reduce_animations"
        private const val KEY_FONT_STYLE = "key_font_style"
        private const val KEY_APP_TEXT_SIZE = "key_app_text_size"
        private const val KEY_CHAT_TEXT_SIZE = "key_chat_text_size"
    }
}
