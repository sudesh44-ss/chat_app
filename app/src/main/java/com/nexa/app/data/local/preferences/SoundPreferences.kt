package com.nexa.app.data.local.preferences

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 12 Independent Sound Categories for NEXA Privacy Messenger.
 */
enum class SoundCategory(val title: String, val subtitle: String, val defaultDisplayName: String) {
    MESSAGE(
        title = "Direct Message Sound",
        subtitle = "Audio chime for private end-to-end encrypted direct chats",
        defaultDisplayName = "Default Notification Chime"
    ),
    GROUP_MESSAGE(
        title = "Group Message Sound",
        subtitle = "Audio chime for group conversations (configured independently)",
        defaultDisplayName = "Default Notification Chime"
    ),
    CALL_RINGTONE(
        title = "Call Ringtone",
        subtitle = "Ringtone loop for incoming P2P voice & video calls",
        defaultDisplayName = "Default Ringtone"
    ),
    CONNECTION_REQUEST(
        title = "Connection Request Sound",
        subtitle = "Alert when a peer sends an end-to-end encrypted connection request",
        defaultDisplayName = "Default Connection Chime"
    ),
    TRANSFER_STARTED(
        title = "Transfer Started Sound",
        subtitle = "Chime when P2P or swarm file transfer begins",
        defaultDisplayName = "Default Transfer Start"
    ),
    TRANSFER_COMPLETED(
        title = "Transfer Completed Sound",
        subtitle = "Chime when file transfer finishes and passes security scan",
        defaultDisplayName = "Default Transfer Complete"
    ),
    TRANSFER_FAILED(
        title = "Transfer Failed Sound",
        subtitle = "Alert when a file transfer fails or is blocked by security scan",
        defaultDisplayName = "Default Transfer Alert"
    ),
    NEARBY_DEVICE(
        title = "Nearby Device Detected",
        subtitle = "Chime when a new nearby peer node is detected (cooldown 30s)",
        defaultDisplayName = "Default Discovery Ping"
    ),
    MESH_MESSAGE(
        title = "Mesh Message Received",
        subtitle = "Chime when an offline DTN/store-and-forward mesh message arrives",
        defaultDisplayName = "Default Mesh Chime"
    ),
    MESH_TRANSFER_COMPLETED(
        title = "Mesh Transfer Completed",
        subtitle = "Chime when an offline bundle or relay transfer completes",
        defaultDisplayName = "Default Mesh Complete"
    ),
    NEW_STATUS(
        title = "New Status Sound",
        subtitle = "Chime when a contact publishes an encrypted 24h status story",
        defaultDisplayName = "Default Status Chime"
    ),
    STATUS_INTERACTION(
        title = "Status Interaction Sound",
        subtitle = "Chime when a contact reacts or replies to your status story",
        defaultDisplayName = "Default Status Reaction"
    )
}

/**
 * Sound selection modes per category.
 */
enum class SoundMode(val label: String) {
    DEFAULT("Default Sound"),
    LOCAL("Local Sound File"),
    SILENT("Silent")
}

/**
 * Immutable configuration for a single sound category.
 */
data class SoundConfig(
    val category: SoundCategory,
    val mode: SoundMode = SoundMode.DEFAULT,
    val localUri: String? = null,
    val displayName: String = category.defaultDisplayName,
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Local persistent store for NEXA Sound Settings.
 * Stored strictly on-device in private SharedPreferences (Zero-Knowledge, Zero Server Upload).
 */
class SoundPreferences(
    private val prefs: SharedPreferences
) {
    companion object {
        const val PREFS_NAME = "nexa_sound_preferences"
        private const val PREFIX_MODE = "sound_mode_"
        private const val PREFIX_URI = "sound_uri_"
        private const val PREFIX_NAME = "sound_name_"
        private const val PREFIX_UPDATED = "sound_updated_"
    }

    constructor(context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    private val _preferencesState = MutableStateFlow(loadAllConfigs())
    val preferencesState: StateFlow<Map<SoundCategory, SoundConfig>> = _preferencesState.asStateFlow()
    val preferencesFlow: Flow<Map<SoundCategory, SoundConfig>> = preferencesState

    fun getConfig(category: SoundCategory): SoundConfig {
        return _preferencesState.value[category] ?: loadConfigFromPrefs(category)
    }

    fun getAllConfigs(): Map<SoundCategory, SoundConfig> {
        return _preferencesState.value
    }

    fun updateConfig(config: SoundConfig) {
        val key = config.category.name.lowercase()
        prefs.edit()
            .putString(PREFIX_MODE + key, config.mode.name)
            .putString(PREFIX_URI + key, config.localUri)
            .putString(PREFIX_NAME + key, config.displayName)
            .putLong(PREFIX_UPDATED + key, config.updatedAt)
            .apply()

        val updatedMap = _preferencesState.value.toMutableMap()
        updatedMap[config.category] = config
        _preferencesState.value = updatedMap
    }

    fun resetToDefault(category: SoundCategory) {
        val defaultConfig = SoundConfig(
            category = category,
            mode = SoundMode.DEFAULT,
            localUri = null,
            displayName = category.defaultDisplayName,
            updatedAt = System.currentTimeMillis()
        )
        updateConfig(defaultConfig)
    }

    fun resetAllToDefault() {
        prefs.edit().clear().apply()
        _preferencesState.value = loadAllConfigs()
    }

    private fun loadAllConfigs(): Map<SoundCategory, SoundConfig> {
        return SoundCategory.values().associateWith { category ->
            loadConfigFromPrefs(category)
        }
    }

    private fun loadConfigFromPrefs(category: SoundCategory): SoundConfig {
        val key = category.name.lowercase()
        val modeStr = prefs.getString(PREFIX_MODE + key, SoundMode.DEFAULT.name)
        val mode = try {
            SoundMode.valueOf(modeStr ?: SoundMode.DEFAULT.name)
        } catch (_: Exception) {
            SoundMode.DEFAULT
        }
        val uri = prefs.getString(PREFIX_URI + key, null)
        val name = prefs.getString(PREFIX_NAME + key, category.defaultDisplayName) ?: category.defaultDisplayName
        val updated = prefs.getLong(PREFIX_UPDATED + key, 0L)

        return SoundConfig(
            category = category,
            mode = mode,
            localUri = uri,
            displayName = name,
            updatedAt = updated
        )
    }
}
