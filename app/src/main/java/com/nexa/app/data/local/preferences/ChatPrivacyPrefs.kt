package com.nexa.app.data.local.preferences

import android.content.Context
import android.content.SharedPreferences

enum class TypingPrivacyMode {
    EVERYONE,
    NOBODY
}

enum class OnlinePrivacyMode {
    EVERYONE,
    MY_CONTACTS,
    NOBODY
}

enum class OfflinePresenceMode(val label: String) {
    SHOW_OFFLINE("Show Offline"),
    LAST_SEEN_RECENTLY("Show Last Seen Recently"),
    TAKING_A_BREAK("Taking a Break");

    fun statusText(contactName: String): String = when (this) {
        SHOW_OFFLINE -> "Offline"
        LAST_SEEN_RECENTLY -> "Last seen recently"
        TAKING_A_BREAK -> "$contactName is taking a break — back soon"
    }
}

enum class TransportRouteMode(val label: String, val iconEmoji: String) {
    AUTO("Auto (Recommended)", "⚡"),
    INTERNET("Internet Only", "🌐"),
    MESH("Local Mesh Only", "📡"),
    SMS("Binary Data SMS", "✉️")
}

/**
 * Persists chat privacy gatekeeper configurations and multi-transport preferences.
 * Enforces Zero Server Knowledge: suppression of read receipts, typing pulses, and
 * presence happens 100% on the client before packets are formed.
 */
class ChatPrivacyPrefs(
    private val prefs: SharedPreferences
) {
    constructor(context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    var readReceiptsEnabled: Boolean
        get() = prefs.getBoolean(KEY_READ_RECEIPTS, true)
        set(value) = prefs.edit().putBoolean(KEY_READ_RECEIPTS, value).apply()

    var typingIndicatorMode: TypingPrivacyMode
        get() {
            val name = prefs.getString(KEY_TYPING_MODE, TypingPrivacyMode.EVERYONE.name)
            return try {
                TypingPrivacyMode.valueOf(name ?: TypingPrivacyMode.EVERYONE.name)
            } catch (e: Exception) {
                TypingPrivacyMode.EVERYONE
            }
        }
        set(value) = prefs.edit().putString(KEY_TYPING_MODE, value.name).apply()

    var showOnlineStatus: Boolean
        get() = prefs.getBoolean(KEY_SHOW_ONLINE_STATUS, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_ONLINE_STATUS, value).apply()

    var onlinePresenceMode: OnlinePrivacyMode
        get() {
            val name = prefs.getString(KEY_ONLINE_MODE, OnlinePrivacyMode.EVERYONE.name)
            return try {
                OnlinePrivacyMode.valueOf(name ?: OnlinePrivacyMode.EVERYONE.name)
            } catch (e: Exception) {
                OnlinePrivacyMode.EVERYONE
            }
        }
        set(value) = prefs.edit().putString(KEY_ONLINE_MODE, value.name).apply()

    var offlinePresenceMode: OfflinePresenceMode
        get() {
            val name = prefs.getString(KEY_OFFLINE_STATUS_MODE, OfflinePresenceMode.TAKING_A_BREAK.name)
            return try {
                OfflinePresenceMode.valueOf(name ?: OfflinePresenceMode.TAKING_A_BREAK.name)
            } catch (e: Exception) {
                OfflinePresenceMode.TAKING_A_BREAK
            }
        }
        set(value) = prefs.edit().putString(KEY_OFFLINE_STATUS_MODE, value.name).apply()

    var selectedOnlineStatusContacts: Set<String>
        get() = prefs.getStringSet(KEY_SELECTED_ONLINE_STATUS_CONTACTS, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_SELECTED_ONLINE_STATUS_CONTACTS, value).apply()

    fun addSelectedOnlineStatusContact(contactId: String) {
        if (contactId.isBlank()) return
        val updated = LinkedHashSet(selectedOnlineStatusContacts)
        updated.add(contactId)
        selectedOnlineStatusContacts = updated
    }

    fun removeSelectedOnlineStatusContact(contactId: String) {
        val updated = LinkedHashSet(selectedOnlineStatusContacts)
        updated.remove(contactId)
        selectedOnlineStatusContacts = updated
    }

    var showTransportBadges: Boolean
        get() = prefs.getBoolean(KEY_SHOW_TRANSPORT_BADGES, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_TRANSPORT_BADGES, value).apply()

    var preferredTransportRoute: TransportRouteMode
        get() {
            val name = prefs.getString(KEY_PREFERRED_ROUTE, TransportRouteMode.AUTO.name)
            return try {
                TransportRouteMode.valueOf(name ?: TransportRouteMode.AUTO.name)
            } catch (e: Exception) {
                TransportRouteMode.AUTO
            }
        }
        set(value) = prefs.edit().putString(KEY_PREFERRED_ROUTE, value.name).apply()

    fun reset() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "nexa_chat_privacy_prefs"
        private const val KEY_READ_RECEIPTS = "read_receipts_enabled"
        private const val KEY_TYPING_MODE = "typing_indicator_mode"
        private const val KEY_ONLINE_MODE = "online_presence_mode"
        private const val KEY_SHOW_ONLINE_STATUS = "show_online_status"
        private const val KEY_OFFLINE_STATUS_MODE = "offline_status_mode"
        private const val KEY_SELECTED_ONLINE_STATUS_CONTACTS = "selected_online_status_contacts"
        private const val KEY_SHOW_TRANSPORT_BADGES = "show_transport_badges"
        private const val KEY_PREFERRED_ROUTE = "preferred_transport_route"
    }
}
