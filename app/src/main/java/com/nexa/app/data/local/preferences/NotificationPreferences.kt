package com.nexa.app.data.local.preferences

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalTime

/**
 * Android Lock Screen Privacy Modes for E2EE notifications.
 */
enum class LockScreenMode(val label: String, val description: String) {
    SHOW_ALL(
        label = "Show All Content",
        description = "Display sender name and message content on lock screen"
    ),
    HIDE_CONTENT(
        label = "Hide Sensitive Content (Recommended)",
        description = "Display 'NEXA • 1 new encrypted message' when device is locked"
    ),
    DONT_SHOW(
        label = "Don't Show on Lock Screen",
        description = "Do not display any notifications when device is locked"
    )
}

/**
 * Client-Side Notification Preferences Data Model.
 * Zero-Knowledge: Stored strictly on-device in local SharedPreferences / DataStore.
 */
data class NotificationPreferences(
    val directMessagesEnabled: Boolean = true,
    val messagePreviewEnabled: Boolean = true,
    val groupsEnabled: Boolean = true,
    val groupMentionsOnly: Boolean = false,
    val callsEnabled: Boolean = true,
    val allowCallsDuringQuietHours: Boolean = true,
    val statusUpdatesEnabled: Boolean = false,
    val connectionRequestsEnabled: Boolean = true,
    val fileTransfersEnabled: Boolean = true,
    val meshDiscoveryAlertsEnabled: Boolean = false, // Default false to conserve battery & prevent tray clutter
    val lockScreenVisibility: LockScreenMode = LockScreenMode.HIDE_CONTENT,
    val badgeCountEnabled: Boolean = true,
    val isQuietHoursEnabled: Boolean = false,
    val quietHoursStartHour: Int = 23,
    val quietHoursStartMinute: Int = 0,
    val quietHoursEndHour: Int = 7,
    val quietHoursEndMinute: Int = 0
) {
    val quietHoursStart: LocalTime
        get() = LocalTime.of(quietHoursStartHour.coerceIn(0, 23), quietHoursStartMinute.coerceIn(0, 59))

    val quietHoursEnd: LocalTime
        get() = LocalTime.of(quietHoursEndHour.coerceIn(0, 23), quietHoursEndMinute.coerceIn(0, 59))

    val quietHoursFormatted: String
        get() = String.format("%02d:%02d – %02d:%02d", quietHoursStartHour, quietHoursStartMinute, quietHoursEndHour, quietHoursEndMinute)
}

/**
 * Local persistent store for NotificationPreferences.
 */
class NotificationPrefs(
    private val prefs: SharedPreferences
) {
    constructor(context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    private val _preferencesState = MutableStateFlow(getSnapshot())
    val preferencesState: StateFlow<NotificationPreferences> = _preferencesState.asStateFlow()
    val preferencesFlow: Flow<NotificationPreferences> = preferencesState

    fun getSnapshot(): NotificationPreferences {
        val lockModeStr = prefs.getString(KEY_LOCK_SCREEN_VISIBILITY, LockScreenMode.HIDE_CONTENT.name)
        val lockMode = try {
            LockScreenMode.valueOf(lockModeStr ?: LockScreenMode.HIDE_CONTENT.name)
        } catch (e: Exception) {
            LockScreenMode.HIDE_CONTENT
        }

        return NotificationPreferences(
            directMessagesEnabled = prefs.getBoolean(KEY_DIRECT_MESSAGES_ENABLED, true),
            messagePreviewEnabled = prefs.getBoolean(KEY_MESSAGE_PREVIEW_ENABLED, true),
            groupsEnabled = prefs.getBoolean(KEY_GROUPS_ENABLED, true),
            groupMentionsOnly = prefs.getBoolean(KEY_GROUP_MENTIONS_ONLY, false),
            callsEnabled = prefs.getBoolean(KEY_CALLS_ENABLED, true),
            allowCallsDuringQuietHours = prefs.getBoolean(KEY_ALLOW_CALLS_QUIET_HOURS, true),
            statusUpdatesEnabled = prefs.getBoolean(KEY_STATUS_UPDATES_ENABLED, false),
            connectionRequestsEnabled = prefs.getBoolean(KEY_CONNECTION_REQUESTS_ENABLED, true),
            fileTransfersEnabled = prefs.getBoolean(KEY_FILE_TRANSFERS_ENABLED, true),
            meshDiscoveryAlertsEnabled = prefs.getBoolean(KEY_MESH_DISCOVERY_ALERTS_ENABLED, false),
            lockScreenVisibility = lockMode,
            badgeCountEnabled = prefs.getBoolean(KEY_BADGE_COUNT_ENABLED, true),
            isQuietHoursEnabled = prefs.getBoolean(KEY_IS_QUIET_HOURS_ENABLED, false),
            quietHoursStartHour = prefs.getInt(KEY_QUIET_HOURS_START_HOUR, 23),
            quietHoursStartMinute = prefs.getInt(KEY_QUIET_HOURS_START_MIN, 0),
            quietHoursEndHour = prefs.getInt(KEY_QUIET_HOURS_END_HOUR, 7),
            quietHoursEndMinute = prefs.getInt(KEY_QUIET_HOURS_END_MIN, 0)
        )
    }

    fun setDirectMessagesEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DIRECT_MESSAGES_ENABLED, enabled).apply()
        _preferencesState.value = getSnapshot()
    }

    fun setMessagePreviewEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MESSAGE_PREVIEW_ENABLED, enabled).apply()
        _preferencesState.value = getSnapshot()
    }

    fun setGroupsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_GROUPS_ENABLED, enabled).apply()
        _preferencesState.value = getSnapshot()
    }

    fun setGroupMentionsOnly(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_GROUP_MENTIONS_ONLY, enabled).apply()
        _preferencesState.value = getSnapshot()
    }

    fun setCallsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CALLS_ENABLED, enabled).apply()
        _preferencesState.value = getSnapshot()
    }

    fun setAllowCallsDuringQuietHours(allowed: Boolean) {
        prefs.edit().putBoolean(KEY_ALLOW_CALLS_QUIET_HOURS, allowed).apply()
        _preferencesState.value = getSnapshot()
    }

    fun setStatusUpdatesEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_STATUS_UPDATES_ENABLED, enabled).apply()
        _preferencesState.value = getSnapshot()
    }

    fun setConnectionRequestsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CONNECTION_REQUESTS_ENABLED, enabled).apply()
        _preferencesState.value = getSnapshot()
    }

    fun setFileTransfersEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_FILE_TRANSFERS_ENABLED, enabled).apply()
        _preferencesState.value = getSnapshot()
    }

    fun setMeshDiscoveryAlertsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MESH_DISCOVERY_ALERTS_ENABLED, enabled).apply()
        _preferencesState.value = getSnapshot()
    }

    fun setLockScreenVisibility(mode: LockScreenMode) {
        prefs.edit().putString(KEY_LOCK_SCREEN_VISIBILITY, mode.name).apply()
        _preferencesState.value = getSnapshot()
    }

    fun setBadgeCountEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BADGE_COUNT_ENABLED, enabled).apply()
        _preferencesState.value = getSnapshot()
    }

    fun setQuietHoursEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_IS_QUIET_HOURS_ENABLED, enabled).apply()
        _preferencesState.value = getSnapshot()
    }

    fun setQuietHoursTimeWindow(startHour: Int, startMinute: Int, endHour: Int, endMinute: Int) {
        prefs.edit()
            .putInt(KEY_QUIET_HOURS_START_HOUR, startHour)
            .putInt(KEY_QUIET_HOURS_START_MIN, startMinute)
            .putInt(KEY_QUIET_HOURS_END_HOUR, endHour)
            .putInt(KEY_QUIET_HOURS_END_MIN, endMinute)
            .apply()
        _preferencesState.value = getSnapshot()
    }

    fun reset() {
        prefs.edit().clear().apply()
        _preferencesState.value = getSnapshot()
    }

    companion object {
        private const val PREFS_NAME = "nexa_notification_prefs"
        private const val KEY_DIRECT_MESSAGES_ENABLED = "direct_messages_enabled"
        private const val KEY_MESSAGE_PREVIEW_ENABLED = "message_preview_enabled"
        private const val KEY_GROUPS_ENABLED = "groups_enabled"
        private const val KEY_GROUP_MENTIONS_ONLY = "group_mentions_only"
        private const val KEY_CALLS_ENABLED = "calls_enabled"
        private const val KEY_ALLOW_CALLS_QUIET_HOURS = "allow_calls_quiet_hours"
        private const val KEY_STATUS_UPDATES_ENABLED = "status_updates_enabled"
        private const val KEY_CONNECTION_REQUESTS_ENABLED = "connection_requests_enabled"
        private const val KEY_FILE_TRANSFERS_ENABLED = "file_transfers_enabled"
        private const val KEY_MESH_DISCOVERY_ALERTS_ENABLED = "mesh_discovery_alerts_enabled"
        private const val KEY_LOCK_SCREEN_VISIBILITY = "lock_screen_visibility"
        private const val KEY_BADGE_COUNT_ENABLED = "badge_count_enabled"
        private const val KEY_IS_QUIET_HOURS_ENABLED = "is_quiet_hours_enabled"
        private const val KEY_QUIET_HOURS_START_HOUR = "quiet_hours_start_hour"
        private const val KEY_QUIET_HOURS_START_MIN = "quiet_hours_start_min"
        private const val KEY_QUIET_HOURS_END_HOUR = "quiet_hours_end_hour"
        private const val KEY_QUIET_HOURS_END_MIN = "quiet_hours_end_min"
    }
}
