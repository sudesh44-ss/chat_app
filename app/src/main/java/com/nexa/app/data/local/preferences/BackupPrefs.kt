package com.nexa.app.data.local.preferences

import android.content.Context
import android.content.SharedPreferences
import com.nexa.app.core.backup.BackupCategoryConfig
import com.nexa.app.domain.model.BackupMode
import com.nexa.app.domain.model.BackupScheduleConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class BackupFrequency {
    DAILY,
    WEEKLY,
    MONTHLY
}

data class BackupPreferencesState(
    // Mode Architecture
    val backupMode: BackupMode = BackupMode.SCHEDULED,
    val autoBackupEnabled: Boolean = true,

    // Schedule Timing
    val backupFrequency: BackupFrequency = BackupFrequency.DAILY,
    val scheduledDailyHour: Int = 2,
    val scheduledDailyMinute: Int = 0,
    val scheduledWeeklyDayOfWeek: Int = 1,
    val scheduledMonthlyDayOfMonth: Int = 1,

    // Smart Conditions & Constraints
    val backupOverWifiOnly: Boolean = true,
    val backupWhenCharging: Boolean = true,
    val backupNotificationsEnabled: Boolean = true,

    // Telemetry & Metadata
    val lastBackupTimestamp: Long = 0L,
    val lastBackupSize: Long = 0L,
    val lastBackupEpoch: Long = 0L,
    val remoteBackupFileId: String? = null,
    val googleAccountEmail: String? = null,
    val googleAccountAvatarUrl: String? = null,

    // Category Configuration
    val categoryConfig: BackupCategoryConfig = BackupCategoryConfig(),

    // Priority Chat Selections
    val priorityPersonalChatIds: Set<String> = emptySet(),
    val priorityGroupChatIds: Set<String> = emptySet()
) {
    val scheduleConfig: BackupScheduleConfig
        get() = BackupScheduleConfig(
            frequency = backupFrequency,
            dailyHour = scheduledDailyHour,
            dailyMinute = scheduledDailyMinute,
            weeklyDayOfWeek = scheduledWeeklyDayOfWeek,
            monthlyDayOfMonth = scheduledMonthlyDayOfMonth
        )

    val totalPriorityChatCount: Int
        get() = priorityPersonalChatIds.size + priorityGroupChatIds.size
}

/**
 * Manages persisted configuration and state for Google Drive AppData backups.
 * Single source of truth for Backup Modes, Schedules, Categories, and Conditions.
 */
class BackupPrefs(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(loadCurrentState())
    val state: StateFlow<BackupPreferencesState> = _state.asStateFlow()

    private fun loadCurrentState(): BackupPreferencesState {
        val freqStr = prefs.getString(KEY_FREQUENCY, BackupFrequency.DAILY.name)
        val frequency = try {
            BackupFrequency.valueOf(freqStr ?: BackupFrequency.DAILY.name)
        } catch (e: Exception) {
            BackupFrequency.DAILY
        }

        val autoEnabled = prefs.getBoolean(KEY_AUTO_BACKUP_ENABLED, true)

        // Resolve backup mode with legacy fallback
        val modeStr = prefs.getString(KEY_BACKUP_MODE, null)
        val mode = if (modeStr != null) {
            BackupMode.fromString(modeStr)
        } else {
            // Legacy fallback: if auto backup was off, resolve to MANUAL_ONLY
            if (!autoEnabled) BackupMode.MANUAL_ONLY else BackupMode.SCHEDULED
        }

        val catJson = prefs.getString(KEY_CATEGORY_CONFIG, null)
        val categoryConfig = BackupCategoryConfig.fromJson(catJson)

        val personalPriorities = prefs.getStringSet(KEY_PRIORITY_PERSONAL_CHATS, emptySet())?.toSet() ?: emptySet()
        val groupPriorities = prefs.getStringSet(KEY_PRIORITY_GROUP_CHATS, emptySet())?.toSet() ?: emptySet()

        return BackupPreferencesState(
            backupMode = mode,
            autoBackupEnabled = autoEnabled,
            backupFrequency = frequency,
            scheduledDailyHour = prefs.getInt(KEY_SCHEDULED_HOUR, 2).coerceIn(0, 23),
            scheduledDailyMinute = prefs.getInt(KEY_SCHEDULED_MINUTE, 0).coerceIn(0, 59),
            scheduledWeeklyDayOfWeek = prefs.getInt(KEY_SCHEDULED_DAY_OF_WEEK, 1).coerceIn(1, 7),
            scheduledMonthlyDayOfMonth = prefs.getInt(KEY_SCHEDULED_DAY_OF_MONTH, 1).coerceIn(1, 31),
            backupOverWifiOnly = prefs.getBoolean(KEY_WIFI_ONLY, true),
            backupWhenCharging = prefs.getBoolean(KEY_CHARGING_ONLY, true),
            backupNotificationsEnabled = prefs.getBoolean(KEY_NOTIFICATIONS_ENABLED, true),
            lastBackupTimestamp = prefs.getLong(KEY_LAST_BACKUP_TIMESTAMP, 0L),
            lastBackupSize = prefs.getLong(KEY_LAST_BACKUP_SIZE, 0L),
            lastBackupEpoch = prefs.getLong(KEY_LAST_BACKUP_EPOCH, 0L),
            remoteBackupFileId = prefs.getString(KEY_REMOTE_FILE_ID, null),
            googleAccountEmail = prefs.getString(KEY_GOOGLE_ACCOUNT_EMAIL, null),
            googleAccountAvatarUrl = prefs.getString(KEY_GOOGLE_ACCOUNT_AVATAR, null),
            categoryConfig = categoryConfig,
            priorityPersonalChatIds = personalPriorities,
            priorityGroupChatIds = groupPriorities
        )
    }

    private fun refreshState() {
        _state.value = loadCurrentState()
    }

    // --- Backup Mode ---

    var backupMode: BackupMode
        get() {
            val s = prefs.getString(KEY_BACKUP_MODE, null)
            return if (s != null) {
                BackupMode.fromString(s)
            } else {
                if (!autoBackupEnabled) BackupMode.MANUAL_ONLY else BackupMode.SCHEDULED
            }
        }
        set(value) {
            val isAuto = value != BackupMode.MANUAL_ONLY
            prefs.edit()
                .putString(KEY_BACKUP_MODE, value.name)
                .putBoolean(KEY_AUTO_BACKUP_ENABLED, isAuto)
                .apply()
            refreshState()
        }

    var autoBackupEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_BACKUP_ENABLED, true)
        set(value) {
            val editor = prefs.edit().putBoolean(KEY_AUTO_BACKUP_ENABLED, value)
            if (!value && backupMode != BackupMode.MANUAL_ONLY) {
                editor.putString(KEY_BACKUP_MODE, BackupMode.MANUAL_ONLY.name)
            } else if (value && backupMode == BackupMode.MANUAL_ONLY) {
                editor.putString(KEY_BACKUP_MODE, BackupMode.SCHEDULED.name)
            }
            editor.apply()
            refreshState()
        }

    // --- Schedule Configuration ---

    var backupFrequency: BackupFrequency
        get() {
            val s = prefs.getString(KEY_FREQUENCY, BackupFrequency.DAILY.name)
            return try { BackupFrequency.valueOf(s ?: BackupFrequency.DAILY.name) } catch (e: Exception) { BackupFrequency.DAILY }
        }
        set(value) {
            prefs.edit().putString(KEY_FREQUENCY, value.name).apply()
            refreshState()
        }

    var scheduledDailyHour: Int
        get() = prefs.getInt(KEY_SCHEDULED_HOUR, 2).coerceIn(0, 23)
        set(value) {
            prefs.edit().putInt(KEY_SCHEDULED_HOUR, value.coerceIn(0, 23)).apply()
            refreshState()
        }

    var scheduledDailyMinute: Int
        get() = prefs.getInt(KEY_SCHEDULED_MINUTE, 0).coerceIn(0, 59)
        set(value) {
            prefs.edit().putInt(KEY_SCHEDULED_MINUTE, value.coerceIn(0, 59)).apply()
            refreshState()
        }

    var scheduledWeeklyDayOfWeek: Int
        get() = prefs.getInt(KEY_SCHEDULED_DAY_OF_WEEK, 1).coerceIn(1, 7)
        set(value) {
            prefs.edit().putInt(KEY_SCHEDULED_DAY_OF_WEEK, value.coerceIn(1, 7)).apply()
            refreshState()
        }

    var scheduledMonthlyDayOfMonth: Int
        get() = prefs.getInt(KEY_SCHEDULED_DAY_OF_MONTH, 1).coerceIn(1, 31)
        set(value) {
            prefs.edit().putInt(KEY_SCHEDULED_DAY_OF_MONTH, value.coerceIn(1, 31)).apply()
            refreshState()
        }

    var scheduleConfig: BackupScheduleConfig
        get() = BackupScheduleConfig(
            frequency = backupFrequency,
            dailyHour = scheduledDailyHour,
            dailyMinute = scheduledDailyMinute,
            weeklyDayOfWeek = scheduledWeeklyDayOfWeek,
            monthlyDayOfMonth = scheduledMonthlyDayOfMonth
        )
        set(value) {
            prefs.edit()
                .putString(KEY_FREQUENCY, value.frequency.name)
                .putInt(KEY_SCHEDULED_HOUR, value.dailyHour.coerceIn(0, 23))
                .putInt(KEY_SCHEDULED_MINUTE, value.dailyMinute.coerceIn(0, 59))
                .putInt(KEY_SCHEDULED_DAY_OF_WEEK, value.weeklyDayOfWeek.coerceIn(1, 7))
                .putInt(KEY_SCHEDULED_DAY_OF_MONTH, value.monthlyDayOfMonth.coerceIn(1, 31))
                .apply()
            refreshState()
        }

    fun setDailyScheduleTime(hour: Int, minute: Int) {
        prefs.edit()
            .putInt(KEY_SCHEDULED_HOUR, hour.coerceIn(0, 23))
            .putInt(KEY_SCHEDULED_MINUTE, minute.coerceIn(0, 59))
            .apply()
        refreshState()
    }

    fun setWeeklyScheduleDay(dayOfWeek: Int) {
        prefs.edit()
            .putInt(KEY_SCHEDULED_DAY_OF_WEEK, dayOfWeek.coerceIn(1, 7))
            .apply()
        refreshState()
    }

    fun setMonthlyScheduleDate(dayOfMonth: Int) {
        prefs.edit()
            .putInt(KEY_SCHEDULED_DAY_OF_MONTH, dayOfMonth.coerceIn(1, 31))
            .apply()
        refreshState()
    }

    // --- Conditions & Network ---

    var backupOverWifiOnly: Boolean
        get() = prefs.getBoolean(KEY_WIFI_ONLY, true)
        set(value) {
            prefs.edit().putBoolean(KEY_WIFI_ONLY, value).apply()
            refreshState()
        }

    var backupWhenCharging: Boolean
        get() = prefs.getBoolean(KEY_CHARGING_ONLY, true)
        set(value) {
            prefs.edit().putBoolean(KEY_CHARGING_ONLY, value).apply()
            refreshState()
        }

    var backupNotificationsEnabled: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATIONS_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_NOTIFICATIONS_ENABLED, value).apply()
            refreshState()
        }

    // --- Metadata & Account ---

    var lastBackupTimestamp: Long
        get() = prefs.getLong(KEY_LAST_BACKUP_TIMESTAMP, 0L)
        set(value) {
            prefs.edit().putLong(KEY_LAST_BACKUP_TIMESTAMP, value).apply()
            refreshState()
        }

    var lastBackupSize: Long
        get() = prefs.getLong(KEY_LAST_BACKUP_SIZE, 0L)
        set(value) {
            prefs.edit().putLong(KEY_LAST_BACKUP_SIZE, value).apply()
            refreshState()
        }

    var lastBackupEpoch: Long
        get() = prefs.getLong(KEY_LAST_BACKUP_EPOCH, 0L)
        set(value) {
            prefs.edit().putLong(KEY_LAST_BACKUP_EPOCH, value).apply()
            refreshState()
        }

    var remoteBackupFileId: String?
        get() = prefs.getString(KEY_REMOTE_FILE_ID, null)
        set(value) {
            prefs.edit().putString(KEY_REMOTE_FILE_ID, value).apply()
            refreshState()
        }

    var googleAccountEmail: String?
        get() = prefs.getString(KEY_GOOGLE_ACCOUNT_EMAIL, null)
        set(value) {
            prefs.edit().putString(KEY_GOOGLE_ACCOUNT_EMAIL, value).apply()
            refreshState()
        }

    var googleAccountAvatarUrl: String?
        get() = prefs.getString(KEY_GOOGLE_ACCOUNT_AVATAR, null)
        set(value) {
            prefs.edit().putString(KEY_GOOGLE_ACCOUNT_AVATAR, value).apply()
            refreshState()
        }

    // --- Category Config ---

    var categoryConfig: BackupCategoryConfig
        get() = BackupCategoryConfig.fromJson(prefs.getString(KEY_CATEGORY_CONFIG, null))
        set(value) {
            prefs.edit().putString(KEY_CATEGORY_CONFIG, value.toJson()).apply()
            refreshState()
        }

    // --- Chat Priority Selections (Cache / Fast Lookup) ---

    var priorityPersonalChatIds: Set<String>
        get() = prefs.getStringSet(KEY_PRIORITY_PERSONAL_CHATS, emptySet())?.toSet() ?: emptySet()
        set(value) {
            prefs.edit().putStringSet(KEY_PRIORITY_PERSONAL_CHATS, value).apply()
            refreshState()
        }

    var priorityGroupChatIds: Set<String>
        get() = prefs.getStringSet(KEY_PRIORITY_GROUP_CHATS, emptySet())?.toSet() ?: emptySet()
        set(value) {
            prefs.edit().putStringSet(KEY_PRIORITY_GROUP_CHATS, value).apply()
            refreshState()
        }

    fun setPriorityChat(conversationId: String, isPriority: Boolean, isGroup: Boolean) {
        val key = if (isGroup) KEY_PRIORITY_GROUP_CHATS else KEY_PRIORITY_PERSONAL_CHATS
        val current = prefs.getStringSet(key, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (isPriority) {
            current.add(conversationId)
        } else {
            current.remove(conversationId)
        }
        prefs.edit().putStringSet(key, current).apply()
        refreshState()
    }

    fun isPriorityChat(conversationId: String): Boolean {
        val personal = prefs.getStringSet(KEY_PRIORITY_PERSONAL_CHATS, emptySet()) ?: emptySet()
        val groups = prefs.getStringSet(KEY_PRIORITY_GROUP_CHATS, emptySet()) ?: emptySet()
        return personal.contains(conversationId) || groups.contains(conversationId)
    }

    fun clearAllPriorityChats() {
        prefs.edit()
            .remove(KEY_PRIORITY_PERSONAL_CHATS)
            .remove(KEY_PRIORITY_GROUP_CHATS)
            .apply()
        refreshState()
    }

    // --- Actions ---

    fun updateBackupSuccess(fileId: String, size: Long, epoch: Long) {
        prefs.edit()
            .putString(KEY_REMOTE_FILE_ID, fileId)
            .putLong(KEY_LAST_BACKUP_SIZE, size)
            .putLong(KEY_LAST_BACKUP_EPOCH, epoch)
            .putLong(KEY_LAST_BACKUP_TIMESTAMP, System.currentTimeMillis())
            .apply()
        refreshState()
    }

    fun clear() {
        prefs.edit().clear().apply()
        refreshState()
    }

    companion object {
        private const val PREFS_NAME = "nexa_backup_preferences"

        // Mode Keys
        private const val KEY_BACKUP_MODE = "key_backup_mode"
        private const val KEY_AUTO_BACKUP_ENABLED = "key_auto_backup_enabled"

        // Schedule Keys
        private const val KEY_FREQUENCY = "key_frequency"
        private const val KEY_SCHEDULED_HOUR = "key_scheduled_hour"
        private const val KEY_SCHEDULED_MINUTE = "key_scheduled_minute"
        private const val KEY_SCHEDULED_DAY_OF_WEEK = "key_scheduled_day_of_week"
        private const val KEY_SCHEDULED_DAY_OF_MONTH = "key_scheduled_day_of_month"

        // Smart Condition Keys
        private const val KEY_WIFI_ONLY = "key_wifi_only"
        private const val KEY_CHARGING_ONLY = "key_charging_only"
        private const val KEY_NOTIFICATIONS_ENABLED = "key_notifications_enabled"

        // Metadata Keys
        private const val KEY_LAST_BACKUP_TIMESTAMP = "key_last_backup_timestamp"
        private const val KEY_LAST_BACKUP_SIZE = "key_last_backup_size"
        private const val KEY_LAST_BACKUP_EPOCH = "key_last_backup_epoch"
        private const val KEY_REMOTE_FILE_ID = "key_remote_file_id"
        private const val KEY_GOOGLE_ACCOUNT_EMAIL = "key_google_account_email"
        private const val KEY_GOOGLE_ACCOUNT_AVATAR = "key_google_account_avatar"

        // Category Configuration Key
        private const val KEY_CATEGORY_CONFIG = "key_category_config"

        // Priority Chat Keys
        private const val KEY_PRIORITY_PERSONAL_CHATS = "key_priority_personal_chats"
        private const val KEY_PRIORITY_GROUP_CHATS = "key_priority_group_chats"
    }
}
