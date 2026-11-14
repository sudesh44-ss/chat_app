package com.nexa.app.data.local.preferences

import android.content.Context
import android.content.SharedPreferences
import com.nexa.app.core.theme.AppearanceMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persists and streams the user's selected AppearanceMode (SYSTEM, DARK, LIGHT).
 * Changes are observed in real-time by MainActivity and all Composable screens.
 */
class AppearancePreferences(
    private val prefs: SharedPreferences
) {
    constructor(context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    private val _appearanceMode = MutableStateFlow(getStoredMode())
    val appearanceMode: StateFlow<AppearanceMode> = _appearanceMode.asStateFlow()

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

    companion object {
        private const val PREFS_NAME = "nexa_appearance_prefs"
        private const val KEY_APPEARANCE_MODE = "key_appearance_mode"
    }
}
