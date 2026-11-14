package com.nexa.app.core.security

/**
 * Deterministic states for file security lifecycle across sender and receiver.
 */
enum class FileSecurityState {
    OFFERED,                        // Receiver received metadata offer, transfer NOT started
    WAITING_FOR_RECEIVER,           // Sender waiting for receiver's decision
    TRANSFERRING,                   // Receiver authorized transfer; chunks actively streaming
    QUARANTINED,                    // Chunks assembled inside encrypted quarantine
    VERIFYING_INTEGRITY,            // Cryptographic whole-file hash verification in progress
    SCANNING,                       // Multi-stage deep security scan in progress
    SCAN_PASSED,                    // Scan passed, waiting for explicit receiver acceptance
    SCAN_REJECTED,                  // Scan rejected due to threat/spoofing
    WAITING_FOR_USER_ACCEPTANCE,    // Scan passed, awaiting user click "Save to NEXA"
    RELEASING,                      // Atomic promotion from quarantine to safe vault
    RELEASED,                       // Promoted and available for consumption in NEXA
    DECLINED,                       // Receiver declined offer or acceptance
    CANCELLED,                      // Sender or receiver cancelled transfer
    FAILED,                         // Network or unexpected system failure
    EXPIRED                         // Transfer request or quarantine session expired
}

/**
 * Granular Three-Layer checkpoint lifecycle states.
 */
enum class FileTransferState {
    SELECTED,
    PREPARING,
    TYPE_CHECK,
    STRUCTURE_CHECK,
    SECURITY_SCAN,
    METADATA_CHECK,
    ANOMALY_CHECK,
    SENDER_REJECTED,
    SENDER_SAFE,
    WAITING_FOR_RECEIVER,
    RECEIVER_ACCEPTED,
    ENCRYPTING,
    P2P_TRANSFER,
    CHUNK_VERIFYING,
    TRANSFER_COMPLETE,
    RECEIVER_QUARANTINED,
    INTEGRITY_CHECK,
    RECEIVER_SECURITY_SCAN,
    RECEIVER_REJECTED,
    VERIFIED,
    WAITING_FOR_USER_ACCEPTANCE,
    RELEASING,
    DELIVERED,
    READ,
    DECLINED,
    CANCELLED,
    FAILED,
    EXPIRED
}

/**
 * Standard scan outcome categories.
 */
enum class ScanResult {
    SCANNING,
    SAFE,
    SUSPICIOUS,
    MALICIOUS,
    UNSUPPORTED,
    RESOURCE_LIMIT_EXCEEDED,
    INCONCLUSIVE,
    SCANNER_UNAVAILABLE
}

/**
 * Steganography risk scoring levels.
 */
enum class StegoRiskLevel {
    NONE,
    LOW,
    MEDIUM,
    HIGH
}

/**
 * Categorized threat classifications for security reporting and audit logs.
 */
enum class ThreatCategory {
    CLEAN,
    BANNED_EXECUTABLE,
    MIME_SPOOFING,
    KNOWN_MALWARE_HASH,
    YARA_SIGNATURE_MATCH,
    ZIP_BOMB,
    ZIP_SLIP,
    NESTED_ARCHIVE,
    ARCHIVE_EXECUTABLE,
    MALFORMED_STRUCTURE,
    PDF_JAVASCRIPT_INJECTION,
    PDF_LAUNCH_ACTION,
    PDF_EMBEDDED_EXECUTABLE,
    IMAGE_DIMENSION_BOMB,
    MEDIA_CONTAINER_ANOMALY,
    STEGANOGRAPHY_TRAILING_DATA,
    STEGANOGRAPHY_EMBEDDED_CONTAINER,
    FILE_TOO_LARGE,
    RESOURCE_LIMIT_EXCEEDED,
    HASH_MISMATCH,
    CORRUPTED_STREAM,
    UNKNOWN_THREAT
}

/**
 * Structured security scan report returned by NexaSecurityEngine.
 */
data class SecurityScanReport(
    val scanResult: ScanResult,
    val threatCategory: ThreatCategory,
    val threatDetails: String? = null,
    val fileHash: String,
    val verifiedMimeType: String? = null,
    val stegoRiskLevel: StegoRiskLevel = StegoRiskLevel.NONE,
    val scanTimestamp: Long = System.currentTimeMillis(),
    val scanEngineVersion: String = "NexaSecurityEngine/2.0",
    val isDeepScanCompleted: Boolean = true
)
