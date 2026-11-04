package com.nexa.app.data.local.preferences

import android.content.Context
import android.content.SharedPreferences

enum class DefaultVideoQuality {
    ALWAYS_ASK,
    COMPRESSED_HD,
    ORIGINAL_RAW
}

/**
 * Persists user media preferences such as default video transmission mode.
 */
class MediaPrefs(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var defaultVideoQuality: DefaultVideoQuality
        get() {
            val name = prefs.getString(KEY_DEFAULT_QUALITY, DefaultVideoQuality.ALWAYS_ASK.name)
            return try {
                DefaultVideoQuality.valueOf(name ?: DefaultVideoQuality.ALWAYS_ASK.name)
            } catch (e: Exception) {
                DefaultVideoQuality.ALWAYS_ASK
            }
        }
        set(value) {
            prefs.edit().putString(KEY_DEFAULT_QUALITY, value.name).apply()
        }

    fun reset() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "nexa_media_prefs"
        private const val KEY_DEFAULT_QUALITY = "default_video_quality"
    }
}

