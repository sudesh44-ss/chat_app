package com.nexa.app.data.drive

/**
 * Immutable metadata representing a file inside the isolated Google Drive `appDataFolder`.
 */
data class DriveFile(
    val id: String,
    val name: String,
    val modifiedTime: String? = null,
    val size: Long = 0L,
    val appProperties: Map<String, String> = emptyMap()
)
