package com.nexa.app.data.local.preferences

import android.content.Context
import android.content.SharedPreferences
import com.nexa.app.core.network.NetworkTypeState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DefaultVideoQuality {
    ALWAYS_ASK,
    COMPRESSED_HD,
    ORIGINAL_RAW
}

enum class MediaAutoReceiveMode(val label: String) {
    NEVER("Never"),
    WIFI_ONLY("Wi‑Fi only"),
    WIFI_AND_MOBILE_DATA("Wi‑Fi + Mobile Data")
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

/**
 * Storage & Data -> Media Auto-Receive policy for inbound P2P payloads.
 *
 * Known contacts may auto-receive when the current connection type is permitted. Unknown
 * senders never auto-accept merely because the global preference is enabled. Blocked contacts
 * are always denied.
 */
class MediaAutoReceivePreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(load())
    val state: StateFlow<MediaAutoReceiveMode> = _state.asStateFlow()

    private fun load(): MediaAutoReceiveMode {
        val raw = prefs.getString(KEY_AUTO_RECEIVE_MODE, MediaAutoReceiveMode.NEVER.name)
        return try {
            MediaAutoReceiveMode.valueOf(raw ?: MediaAutoReceiveMode.NEVER.name)
        } catch (_: Exception) {
            MediaAutoReceiveMode.NEVER
        }
    }

    fun getMode(): MediaAutoReceiveMode = _state.value

    fun setMode(mode: MediaAutoReceiveMode) {
        prefs.edit().putString(KEY_AUTO_RECEIVE_MODE, mode.name).apply()
        _state.value = mode
    }

    fun shouldAutoReceive(
        isKnownContact: Boolean,
        isBlocked: Boolean = false,
        networkState: NetworkTypeState? = null
    ): Boolean {
        if (isBlocked || !isKnownContact) return false

        val mode = _state.value
        return when (networkState) {
            is NetworkTypeState.Wifi -> mode == MediaAutoReceiveMode.WIFI_ONLY ||
                mode == MediaAutoReceiveMode.WIFI_AND_MOBILE_DATA
            is NetworkTypeState.Cellular -> mode == MediaAutoReceiveMode.WIFI_AND_MOBILE_DATA
            else -> false
        }
    }

    fun requiresExplicitUserApproval(
        isKnownContact: Boolean,
        isBlocked: Boolean = false,
        networkState: NetworkTypeState? = null
    ): Boolean = !shouldAutoReceive(isKnownContact, isBlocked, networkState)

    companion object {
        private const val PREFS_NAME = "nexa_media_auto_receive_preferences"
        private const val KEY_AUTO_RECEIVE_MODE = "media_auto_receive_mode"
    }
}

object MediaAutoReceivePolicy {
    fun shouldAutoReceive(
        mode: MediaAutoReceiveMode,
        isKnownContact: Boolean,
        isBlocked: Boolean = false,
        networkState: NetworkTypeState? = null
    ): Boolean {
        if (isBlocked || !isKnownContact) return false
        return when (networkState) {
            is NetworkTypeState.Wifi -> mode == MediaAutoReceiveMode.WIFI_ONLY ||
                mode == MediaAutoReceiveMode.WIFI_AND_MOBILE_DATA
            is NetworkTypeState.Cellular -> mode == MediaAutoReceiveMode.WIFI_AND_MOBILE_DATA
            else -> false
        }
    }

    fun requiresExplicitUserApproval(
        mode: MediaAutoReceiveMode,
        isKnownContact: Boolean,
        isBlocked: Boolean = false,
        networkState: NetworkTypeState? = null
    ): Boolean = !shouldAutoReceive(mode, isKnownContact, isBlocked, networkState)
}

