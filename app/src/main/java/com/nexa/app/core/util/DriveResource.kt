package com.nexa.app.core.util

/**
 * Universal state wrapper for Google Drive backup, upload, download, and recovery pipelines.
 */
sealed interface DriveResource<out T> {
    data object Idle : DriveResource<Nothing>
    data class Progress(val phase: String, val percent: Float) : DriveResource<Nothing>
    data class Success<out T>(val data: T) : DriveResource<T>
    data class Error(val message: String, val throwable: Throwable? = null) : DriveResource<Nothing>
}
