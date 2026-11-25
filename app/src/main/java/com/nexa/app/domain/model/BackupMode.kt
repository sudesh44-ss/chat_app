package com.nexa.app.domain.model

/**
 * Authoritative Backup Modes for NEXA.
 *
 * - SCHEDULED: Automated backup execution at configured frequency (Daily, Weekly, Monthly)
 *              with customizable schedule times.
 * - CHAT_PRIORITY: Automated backup restricted strictly to user-designated priority
 *                  personal and group conversations.
 * - MANUAL_ONLY: Automated background execution is completely disabled; backups occur
 *                exclusively when explicitly triggered by the user.
 */
enum class BackupMode {
    SCHEDULED,
    CHAT_PRIORITY,
    MANUAL_ONLY;

    val isAutomatic: Boolean
        get() = this != MANUAL_ONLY

    companion object {
        fun fromString(value: String?): BackupMode {
            if (value.isNullOrBlank()) return SCHEDULED
            return try {
                valueOf(value.trim().uppercase())
            } catch (e: Exception) {
                // Backward-compatible safe mapping for legacy or alternative names
                when (value.trim().lowercase()) {
                    "scheduled", "auto", "automatic", "smart", "near_live" -> SCHEDULED
                    "priority", "chat_priority" -> CHAT_PRIORITY
                    "manual", "manual_only", "off", "disabled" -> MANUAL_ONLY
                    else -> SCHEDULED
                }
            }
        }
    }
}
