package com.nexa.app.domain.model

/**
 * Individual conversation backup policy options.
 * Each conversation can independently follow global defaults, be prioritized,
 * specify custom category overrides, be fully excluded, or be eligible for manual backups only.
 */
enum class ConversationBackupPolicy {
    DEFAULT,      // Follows global backup configuration
    PRIORITY,     // Processed first before normal conversations
    CUSTOM,       // Granular individual category selection
    EXCLUDED,     // Completely omitted from backup
    MANUAL_ONLY   // Included only during user-initiated manual backups
}
