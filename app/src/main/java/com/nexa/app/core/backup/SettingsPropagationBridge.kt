package com.nexa.app.core.backup

import android.content.Context
import android.util.Log

/**
 * Migration & propagation bridge that applies restored allowlisted settings
 * into the canonical partitioned SharedPreferences stores used by NEXA:
 * - `nexa_appearance_prefs` (AppearancePreferences)
 * - `nexa_chats_preferences` (ChatsPreferences)
 * - `nexa_accessibility_preferences` (AccessibilityPreferences)
 * - `nexa_language_preferences` (LanguagePreferences)
 * - `nexa_notification_prefs` (NotificationPrefs)
 * - `nexa_sound_preferences` (SoundPreferences)
 * - `backup_prefs` (BackupPrefs)
 * - `nexa_media_preferences` (MediaPrefs)
 * - `nexa_conversation_preferences` (ConversationPreferences)
 *
 * Security Guarantee:
 * - Only processes settings explicitly in [RestoreEngine.APPROVED_SETTINGS_ALLOWLIST]
 *   and chat configurations matching [RestoreEngine.isApprovedChatConfigKey].
 * - Unknown keys, tokens, session IDs, credentials, and encryption keys are strictly ignored.
 * - Idempotent: repeated application produces the same deterministic state.
 */
object SettingsPropagationBridge {

    private const val TAG = "SettingsPropBridge"

    const val PREFS_APPEARANCE = "nexa_appearance_prefs"
    const val PREFS_CHATS = "nexa_chats_preferences"
    const val PREFS_ACCESSIBILITY = "nexa_accessibility_preferences"
    const val PREFS_LANGUAGE = "nexa_language_preferences"
    const val PREFS_NOTIFICATIONS = "nexa_notification_prefs"
    const val PREFS_SOUND = "nexa_sound_preferences"
    const val PREFS_BACKUP = "backup_prefs"
    const val PREFS_MEDIA = "nexa_media_preferences"
    const val PREFS_CONVERSATIONS = "nexa_conversation_preferences"

    /**
     * Propagates a map of sanitized settings to their respective partitioned stores.
     * Returns the total count of properties updated across partitioned stores.
     */
    fun propagateSettings(
        context: Context,
        sanitizedSettings: Map<String, Any>
    ): Int {
        var propagatedCount = 0

        val appearanceEdit = context.getSharedPreferences(PREFS_APPEARANCE, Context.MODE_PRIVATE).edit()
        var appearanceChanged = false

        val chatsEdit = context.getSharedPreferences(PREFS_CHATS, Context.MODE_PRIVATE).edit()
        var chatsChanged = false

        val accessEdit = context.getSharedPreferences(PREFS_ACCESSIBILITY, Context.MODE_PRIVATE).edit()
        var accessChanged = false

        val langEdit = context.getSharedPreferences(PREFS_LANGUAGE, Context.MODE_PRIVATE).edit()
        var langChanged = false

        val notifEdit = context.getSharedPreferences(PREFS_NOTIFICATIONS, Context.MODE_PRIVATE).edit()
        var notifChanged = false

        val soundEdit = context.getSharedPreferences(PREFS_SOUND, Context.MODE_PRIVATE).edit()
        var soundChanged = false

        val backupEdit = context.getSharedPreferences(PREFS_BACKUP, Context.MODE_PRIVATE).edit()
        var backupChanged = false

        val mediaEdit = context.getSharedPreferences(PREFS_MEDIA, Context.MODE_PRIVATE).edit()
        var mediaChanged = false

        sanitizedSettings.forEach { (key, value) ->
            if (!RestoreEngine.APPROVED_SETTINGS_ALLOWLIST.contains(key)) {
                return@forEach
            }

            when (key) {
                // --- Appearance Store ---
                "app_theme_mode", "appearance_mode", "key_appearance_mode" -> {
                    appearanceEdit.putString("key_appearance_mode", value.toString())
                    appearanceChanged = true
                    propagatedCount++
                }
                "reduce_animations", "key_reduce_animations" -> {
                    if (value is Boolean) {
                        appearanceEdit.putBoolean("key_reduce_animations", value)
                        appearanceChanged = true
                        propagatedCount++
                    }
                }
                "font_style", "key_font_style" -> {
                    appearanceEdit.putString("key_font_style", value.toString())
                    appearanceChanged = true
                    propagatedCount++
                }
                "app_text_size", "key_app_text_size" -> {
                    appearanceEdit.putString("key_app_text_size", value.toString())
                    appearanceChanged = true
                    propagatedCount++
                }
                "chat_text_size", "key_chat_text_size" -> {
                    appearanceEdit.putString("key_chat_text_size", value.toString())
                    appearanceChanged = true
                    propagatedCount++
                }

                // --- Chats Store ---
                "message_font_size", "font_size_scale" -> {
                    chatsEdit.putString("message_font_size", value.toString())
                    chatsChanged = true
                    propagatedCount++
                }
                "enter_to_send" -> {
                    if (value is Boolean) {
                        chatsEdit.putBoolean("enter_to_send", value)
                        chatsChanged = true
                        propagatedCount++
                    }
                }
                "media_visibility", "media_visibility_enabled" -> {
                    if (value is Boolean) {
                        chatsEdit.putBoolean("media_visibility", value)
                        chatsChanged = true
                        propagatedCount++
                    }
                }
                "chat_bubble_style" -> {
                    chatsEdit.putString("chat_bubble_style", value.toString())
                    chatsChanged = true
                    propagatedCount++
                }
                "chat_wallpaper" -> {
                    chatsEdit.putString("chat_wallpaper", value.toString())
                    chatsChanged = true
                    propagatedCount++
                }

                // --- Accessibility Store ---
                "high_contrast_messages", "high_contrast" -> {
                    if (value is Boolean) {
                        accessEdit.putBoolean("high_contrast_messages", value)
                        accessChanged = true
                        propagatedCount++
                    }
                }

                // --- Language Store ---
                "selected_language" -> {
                    langEdit.putString("selected_language", value.toString())
                    langChanged = true
                    propagatedCount++
                }

                // --- Notification & Sound Store ---
                "notifications_enabled" -> {
                    if (value is Boolean) {
                        notifEdit.putBoolean("direct_messages_enabled", value)
                        notifEdit.putBoolean("groups_enabled", value)
                        notifChanged = true
                        propagatedCount++
                    }
                }
                "vibration_enabled" -> {
                    if (value is Boolean) {
                        notifEdit.putBoolean("vibration_enabled", value)
                        notifChanged = true
                        propagatedCount++
                    }
                }
                "notification_sound" -> {
                    soundEdit.putString("sound_uri_message", value.toString())
                    soundChanged = true
                    propagatedCount++
                }

                // --- Backup Store ---
                "backup_frequency" -> {
                    backupEdit.putString("backup_frequency", value.toString())
                    backupChanged = true
                    propagatedCount++
                }
                "backup_over_wifi_only" -> {
                    if (value is Boolean) {
                        backupEdit.putBoolean("backup_over_wifi_only", value)
                        backupChanged = true
                        propagatedCount++
                    }
                }
                "backup_when_charging" -> {
                    if (value is Boolean) {
                        backupEdit.putBoolean("backup_when_charging", value)
                        backupChanged = true
                        propagatedCount++
                    }
                }
                "backup_notifications_enabled" -> {
                    if (value is Boolean) {
                        backupEdit.putBoolean("backup_notifications_enabled", value)
                        backupChanged = true
                        propagatedCount++
                    }
                }
                "backup_mode" -> {
                    backupEdit.putString("backup_mode", value.toString())
                    backupChanged = true
                    propagatedCount++
                }

                // --- Media Auto-Download Store ---
                "auto_download_wifi_images", "auto_download_wifi_videos",
                "auto_download_wifi_documents", "auto_download_wifi_audio",
                "auto_download_cellular_images", "auto_download_cellular_videos",
                "auto_download_cellular_documents", "auto_download_cellular_audio" -> {
                    if (value is Boolean) {
                        mediaEdit.putBoolean(key, value)
                        mediaChanged = true
                        propagatedCount++
                    }
                }
            }
        }

        if (appearanceChanged) appearanceEdit.apply()
        if (chatsChanged) chatsEdit.apply()
        if (accessChanged) accessEdit.apply()
        if (langChanged) langEdit.apply()
        if (notifChanged) notifEdit.apply()
        if (soundChanged) soundEdit.apply()
        if (backupChanged) backupEdit.apply()
        if (mediaChanged) mediaEdit.apply()

        Log.i(TAG, "Propagated $propagatedCount settings to partitioned stores")
        return propagatedCount
    }

    /**
     * Propagates per-conversation configurations into [ConversationPreferences] store (`nexa_conversation_preferences`).
     * Maps canonical keys `<convId>_wallpaper` -> `wallpaper_<convId>` and `<convId>_muted` -> `mute_until_<convId>`.
     */
    fun propagateChatConfigs(
        context: Context,
        sanitizedConfigs: Map<String, Any>
    ): Int {
        var propagatedCount = 0
        val convEdit = context.getSharedPreferences(PREFS_CONVERSATIONS, Context.MODE_PRIVATE).edit()
        var changed = false

        sanitizedConfigs.forEach { (key, value) ->
            if (!RestoreEngine.isApprovedChatConfigKey(key)) return@forEach

            val lower = key.lowercase()
            when {
                lower.endsWith("_wallpaper") -> {
                    val convId = key.substring(0, key.length - "_wallpaper".length)
                    convEdit.putString("wallpaper_$convId", value.toString())
                    changed = true
                    propagatedCount++
                }
                lower.endsWith("_muted") -> {
                    val convId = key.substring(0, key.length - "_muted".length)
                    val muteUntil = when (value) {
                        is Boolean -> if (value) -1L else 0L
                        is Long -> value
                        is Int -> value.toLong()
                        else -> 0L
                    }
                    convEdit.putLong("mute_until_$convId", muteUntil)
                    changed = true
                    propagatedCount++
                }
                lower.endsWith("_custom_theme") -> {
                    val convId = key.substring(0, key.length - "_custom_theme".length)
                    convEdit.putString("theme_$convId", value.toString())
                    changed = true
                    propagatedCount++
                }
                lower.endsWith("_custom_nickname") -> {
                    val convId = key.substring(0, key.length - "_custom_nickname".length)
                    convEdit.putString("nickname_$convId", value.toString())
                    changed = true
                    propagatedCount++
                }
            }
        }

        if (changed) convEdit.apply()
        return propagatedCount
    }
}
