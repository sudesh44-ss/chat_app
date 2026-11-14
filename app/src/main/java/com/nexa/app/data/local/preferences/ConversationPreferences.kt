package com.nexa.app.data.local.preferences

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists scoped conversation preferences:
 * - Scoped Mute (Indefinite or timed duration)
 * - Per-chat Wallpaper customization
 * - Custom notification ringtone/sound
 * - Vibration toggle
 *
 * Scoped strictly to the conversationId without polluting global preferences.
 */
class ConversationPreferences(
    private val prefs: SharedPreferences
) {
    constructor(context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    fun isMuted(conversationId: String): Boolean {
        val until = prefs.getLong("mute_until_$conversationId", 0L)
        return when {
            until == -1L -> true // Indefinite mute
            until > 0L -> System.currentTimeMillis() < until
            else -> false
        }
    }

    fun setMuted(conversationId: String, muted: Boolean, untilMillis: Long = -1L) {
        val value = if (muted) untilMillis else 0L
        prefs.edit().putLong("mute_until_$conversationId", value).apply()
    }

    fun getMuteUntil(conversationId: String): Long {
        return prefs.getLong("mute_until_$conversationId", 0L)
    }

    fun getWallpaper(conversationId: String): String? {
        return prefs.getString("wallpaper_$conversationId", null)
    }

    fun setWallpaper(conversationId: String, wallpaperUriOrKey: String?) {
        if (wallpaperUriOrKey == null) {
            prefs.edit().remove("wallpaper_$conversationId").apply()
        } else {
            prefs.edit().putString("wallpaper_$conversationId", wallpaperUriOrKey).apply()
        }
    }

    fun getCustomTone(conversationId: String): String? {
        return prefs.getString("tone_$conversationId", null)
    }

    fun setCustomTone(conversationId: String, toneUriOrName: String?) {
        if (toneUriOrName == null) {
            prefs.edit().remove("tone_$conversationId").apply()
        } else {
            prefs.edit().putString("tone_$conversationId", toneUriOrName).apply()
        }
    }

    fun isVibrationEnabled(conversationId: String): Boolean {
        return prefs.getBoolean("vib_$conversationId", true)
    }

    fun setVibrationEnabled(conversationId: String, enabled: Boolean) {
        prefs.edit().putBoolean("vib_$conversationId", enabled).apply()
    }

    companion object {
        private const val PREFS_NAME = "nexa_conversation_preferences"
    }
}
