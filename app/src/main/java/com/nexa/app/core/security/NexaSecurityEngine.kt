package com.nexa.app.core.security

import android.content.Context
import android.net.Uri
import com.nexa.app.core.webrtc.FileChunk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * NexaSecurityEngine: Authoritative Three-Layer Adaptive File Security Pipeline.
 *
 * Checkpoint 1: Sender-Side Pre-Dispatch Scan (Before P2P sharing)
 * Checkpoint 2: Continuous Transfer-Time Integrity Verification (During P2P streaming)
 * Checkpoint 3: Receiver-Side Independent Security Scan (In encrypted quarantine before release)
 * Controlled Release Gate: Explicit Receiver Acceptance ([Save to NEXA] / [Open])
 */
class NexaSecurityEngine(
    private val context: Context,
    val quarantineManager: QuarantineManager = QuarantineManager(context),
    val fileIntakeManager: FileIntakeManager = FileIntakeManager(context, quarantineManager),
    val malwareScanner: MalwareScanner = MalwareScanner(),
    val chunkIntegrityVerifier: ChunkIntegrityVerifier = ChunkIntegrityVerifier()
) {

    /**
     * CHECKPOINT 1: SENDER-SIDE PRE-DISPATCH SECURITY SCAN
     * Executed locally before transfer offer metadata or chunks are dispatched.
     * Fail-closed: Any security violation halts the transfer immediately.
     */
    suspend fun checkpoint1SenderScan(
        file: File,
        declaredExtension: String
    ): SecurityScanReport = withContext(Dispatchers.IO) {
        runFullPipelineScan(file, declaredExtension, expectedHash = null)
    }

    suspend fun scanOutboundFile(file: File, declaredExtension: String): SecurityScanReport =
        checkpoint1SenderScan(file, declaredExtension)

    /**
     * CHECKPOINT 2: CONTINUOUS TRANSFER-TIME INTEGRITY VERIFICATION
     * Verifies every chunk during P2P DataChannel transmission: context binding,
     * sequence boundaries, replay/duplicate protection, byte explosion protection, MAC validation.
     */
    fun checkpoint2VerifyChunk(
        chunk: FileChunk,
        senderNxId: String? = null,
        recipientNxId: String? = null,
        chunkMacTag: ByteArray? = null
    ): ChunkVerificationResult {
        return chunkIntegrityVerifier.verifyChunk(chunk, senderNxId, recipientNxId, chunkMacTag)
    }

    /**
     * CHECKPOINT 3: RECEIVER-SIDE INDEPENDENT SECURITY SCAN
     * Executed strictly inside isolated encrypted quarantine on fully reassembled stream
     * before user prompt. Never trusts sender-reported claims.
     */
    suspend fun checkpoint3ReceiverScan(
        quarantinedFile: File,
        expectedHash: String,
        declaredExtension: String
    ): SecurityScanReport = withContext(Dispatchers.IO) {
        if (!quarantinedFile.exists() || quarantinedFile.length() == 0L) {
            return@withContext SecurityScanReport(
                scanResult = ScanResult.MALICIOUS,
                threatCategory = ThreatCategory.CORRUPTED_STREAM,
                threatDetails = "Quarantined file is missing or zero bytes",
                fileHash = "",
                isDeepScanCompleted = false
            )
        }

        // 1. Verify Whole-File Hash Integrity (Constant-time comparison)
        val actualHash = HashEngine.computeSha256(quarantinedFile)
        if (!HashEngine.constantTimeEquals(actualHash, expectedHash)) {
            return@withContext SecurityScanReport(
                scanResult = ScanResult.MALICIOUS,
                threatCategory = ThreatCategory.HASH_MISMATCH,
                threatDetails = "Whole-file hash mismatch: expected $expectedHash, got $actualHash",
                fileHash = actualHash,
                isDeepScanCompleted = false
            )
        }

        // 2. Execute deep format, structure, and malware inspection
        runFullPipelineScan(quarantinedFile, declaredExtension, expectedHash = actualHash)
    }

    suspend fun scanInboundQuarantinedFile(
        quarantinedFile: File,
        expectedHash: String,
        declaredExtension: String
    ): SecurityScanReport = checkpoint3ReceiverScan(quarantinedFile, expectedHash, declaredExtension)

    private suspend fun runFullPipelineScan(
        file: File,
        declaredExtension: String,
        expectedHash: String?
    ): SecurityScanReport = withContext(Dispatchers.IO) {
        val ext = declaredExtension.lowercase().removePrefix(".").trim()
        val fileSizeBytes = file.length()

        // Size ceiling check
        if (!ResourceLimitGuard.isWithinSizeCeiling(fileSizeBytes)) {
            return@withContext SecurityScanReport(
                scanResult = ScanResult.RESOURCE_LIMIT_EXCEEDED,
                threatCategory = ThreatCategory.FILE_TOO_LARGE,
                threatDetails = "File size (${fileSizeBytes / (1024 * 1024)} MB) exceeds 1 GB ceiling",
                fileHash = expectedHash ?: ""
            )
        }

        val fileHash = expectedHash ?: HashEngine.computeSha256(file)

        // 1. Anti-Spoofing & Magic Bytes Detection
        val typeDetection = FileTypeDetector.detectType(file, ext)
        if (typeDetection is DetectionResult.Blocked) {
            return@withContext SecurityScanReport(
                scanResult = ScanResult.MALICIOUS,
                threatCategory = typeDetection.threatCategory,
                threatDetails = typeDetection.reason,
                fileHash = fileHash
            )
        }

        // 2. Format-Specific Structural Validation (Headers, Trailers, Containers)
        val structureVerdict = StructureValidator.validateStructure(file, ext)
        if (structureVerdict is StructureValidationResult.Malformed) {
            return@withContext SecurityScanReport(
                scanResult = ScanResult.MALICIOUS,
                threatCategory = structureVerdict.threatCategory,
                threatDetails = structureVerdict.reason,
                fileHash = fileHash
            )
        }

        // 3. Malware & Heuristic Scanner (Bloom Filter, EICAR, LibYARA)
        val malwareVerdict = malwareScanner.scanFile(file)
        if (malwareVerdict is MalwareScanResult.ThreatDetected) {
            return@withContext SecurityScanReport(
                scanResult = ScanResult.MALICIOUS,
                threatCategory = malwareVerdict.threatCategory,
                threatDetails = "${malwareVerdict.threatName}: ${malwareVerdict.details}",
                fileHash = fileHash
            )
        }

        // 4. Format-Specific Deep Scanners
        val archiveVerdict = if (ext == "zip") {
            ArchiveScanner.scanArchive(file)
        } else null

        val imageVerdict = if (ext in setOf("jpg", "jpeg", "png", "webp", "gif")) {
            ImageSecurityScanner.scanImage(file)
        } else null

        val pdfVerdict = if (ext == "pdf") {
            PDFSecurityScanner.scanPdf(file)
        } else null

        val mediaVerdict = if (ext in setOf("mp4", "m4v", "mkv", "webm", "mp3", "flac", "ogg", "opus", "wav")) {
            MediaSecurityScanner.scanMediaFile(file, ext)
        } else null

        val stegoResult = if (ext in setOf("jpg", "jpeg", "png", "webp", "gif")) {
            StegoRiskAnalyzer.analyzeFile(file, ext)
        } else null

        // 5. Evaluate Composite Risk & Produce Final Decision
        ScanDecisionEngine.evaluate(
            file = file,
            declaredExtension = ext,
            fileHash = fileHash,
            typeDetection = typeDetection,
            malwareResult = malwareVerdict,
            archiveVerdict = archiveVerdict,
            imageVerdict = imageVerdict,
            pdfVerdict = pdfVerdict,
            mediaVerdict = mediaVerdict,
            stegoResult = stegoResult
        )
    }

    /**
     * CONTROLLED RELEASE GATE: EXPLICIT RECEIVER ACCEPTANCE
     * Promotes file from encrypted quarantine into NEXA's safe vault.
     */
    suspend fun releaseQuarantinedFile(
        transferId: String,
        fileName: String,
        isIncoming: Boolean
    ): Result<File> = withContext(Dispatchers.IO) {
        val promoted = quarantineManager.promoteToSafeVault(transferId, fileName, isIncoming)
        if (promoted != null && promoted.exists()) {
            Result.success(promoted)
        } else {
            Result.failure(SecurityException("Failed to promote quarantined file to safe vault"))
        }
    }

    /**
     * Purges and cryptographically shreds quarantined files.
     */
    suspend fun declineAndPurge(transferId: String, isIncoming: Boolean) = withContext(Dispatchers.IO) {
        quarantineManager.purgeQuarantine(transferId, isIncoming)
    }

    /**
     * PROFILE PICTURE SANITIZATION PIPELINE (Phase 13)
     */
    suspend fun sanitizeProfilePhoto(
        sourceFile: File,
        outputFile: File
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val ext = sourceFile.extension.lowercase()
            if (ext !in setOf("jpg", "jpeg", "png", "webp")) {
                return@withContext Result.failure(SecurityException("Only JPG, PNG, and WebP images are allowed for avatars"))
            }

            val typeVerdict = FileTypeDetector.detectType(sourceFile, ext)
            if (typeVerdict is DetectionResult.Blocked) {
                return@withContext Result.failure(SecurityException("Image security check blocked: ${typeVerdict.reason}"))
            }

            val imageVerdict = ImageSecurityScanner.scanImage(sourceFile)
            if (imageVerdict is ImageScanVerdict.Rejected) {
                return@withContext Result.failure(SecurityException("Avatar rejected: ${imageVerdict.reason}"))
            }

            val success = ImageSecurityScanner.reEncodeToWebP(
                source = sourceFile,
                destination = outputFile,
                quality = 88
            )

            if (success && outputFile.exists() && outputFile.length() > 0) {
                Result.success(outputFile)
            } else {
                Result.failure(SecurityException("Failed to re-encode avatar to secure WebP format"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
