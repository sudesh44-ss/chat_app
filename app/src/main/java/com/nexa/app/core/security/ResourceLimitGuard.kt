package com.nexa.app.core.security

import kotlinx.coroutines.withTimeout
import java.io.File

enum class ScanIntensity {
    FULL_DEEP,        // <= 10 MB: In-depth heuristic, full structure, metadata, stego
    STREAMING_DEEP,   // 10 MB - 100 MB: Streaming malware, bytecode, container bounds
    CHUNKED_STREAMING,// 100 MB - 1 GB: Chunk-by-chunk container and signature verification
    REJECTED_TOO_LARGE// > 1 GB: Blocked by size policy ceiling
}

/**
 * Enforces adaptive resource policies based on file sizes to protect device
 * memory and CPU from exhaustion (Denial-of-Service / Zip-Bomb attacks).
 */
object ResourceLimitGuard {

    const val MAX_FILE_SIZE_LIMIT = 1024L * 1024 * 1024 // 1 GB hard transfer ceiling
    const val THRESHOLD_SMALL = 10L * 1024 * 1024       // 10 MB
    const val THRESHOLD_MEDIUM = 100L * 1024 * 1024     // 100 MB
    const val DEFAULT_SCAN_TIMEOUT_MS = 30_000L         // 30 seconds scan timeout

    fun determineScanIntensity(fileSizeBytes: Long): ScanIntensity {
        return when {
            fileSizeBytes <= 0 -> ScanIntensity.FULL_DEEP
            fileSizeBytes <= THRESHOLD_SMALL -> ScanIntensity.FULL_DEEP
            fileSizeBytes <= THRESHOLD_MEDIUM -> ScanIntensity.STREAMING_DEEP
            fileSizeBytes <= MAX_FILE_SIZE_LIMIT -> ScanIntensity.CHUNKED_STREAMING
            else -> ScanIntensity.REJECTED_TOO_LARGE
        }
    }

    fun isWithinSizeCeiling(fileSizeBytes: Long): Boolean {
        return fileSizeBytes in 1..MAX_FILE_SIZE_LIMIT
    }

    suspend fun <T> withScanTimeout(
        timeoutMs: Long = DEFAULT_SCAN_TIMEOUT_MS,
        block: suspend () -> T
    ): T {
        return withTimeout(timeoutMs) {
            block()
        }
    }
}
