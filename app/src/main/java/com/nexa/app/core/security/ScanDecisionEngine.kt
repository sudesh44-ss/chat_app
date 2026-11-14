package com.nexa.app.core.security

import java.io.File

/**
 * Aggregates diagnostic signals across specialized format inspectors,
 * heuristic scanners, and cryptographic engines into a deterministic fail-closed decision.
 */
object ScanDecisionEngine {

    fun evaluate(
        file: File,
        declaredExtension: String,
        fileHash: String,
        typeDetection: DetectionResult,
        malwareResult: MalwareScanResult,
        archiveVerdict: ArchiveScanVerdict?,
        imageVerdict: ImageScanVerdict?,
        pdfVerdict: PDFScanVerdict?,
        mediaVerdict: MediaScanVerdict?,
        stegoResult: StegoAnalysisResult?
    ): SecurityScanReport {
        val now = System.currentTimeMillis()

        // 1. File Type and Extension Anti-Spoofing Checks
        if (typeDetection is DetectionResult.Blocked) {
            return SecurityScanReport(
                scanResult = ScanResult.MALICIOUS,
                threatCategory = typeDetection.threatCategory,
                threatDetails = typeDetection.reason,
                fileHash = fileHash,
                scanTimestamp = now
            )
        }

        val verifiedMime = (typeDetection as? DetectionResult.Verified)?.mimeType

        // 2. Malware & Signature Checks (Bloom filter, EICAR, LibYARA)
        if (malwareResult is MalwareScanResult.ThreatDetected) {
            return SecurityScanReport(
                scanResult = ScanResult.MALICIOUS,
                threatCategory = malwareResult.threatCategory,
                threatDetails = "${malwareResult.threatName}: ${malwareResult.details}",
                fileHash = fileHash,
                verifiedMimeType = verifiedMime,
                scanTimestamp = now
            )
        }

        // 3. Archive Safety (Zip-Bomb, Zip-Slip, Executable Inode)
        if (archiveVerdict is ArchiveScanVerdict.Blocked) {
            return SecurityScanReport(
                scanResult = ScanResult.MALICIOUS,
                threatCategory = archiveVerdict.threatCategory,
                threatDetails = archiveVerdict.reason,
                fileHash = fileHash,
                verifiedMimeType = verifiedMime,
                scanTimestamp = now
            )
        }

        // 4. Image Bomb & Dimension Exploits
        if (imageVerdict is ImageScanVerdict.Rejected) {
            return SecurityScanReport(
                scanResult = ScanResult.MALICIOUS,
                threatCategory = imageVerdict.threatCategory,
                threatDetails = imageVerdict.reason,
                fileHash = fileHash,
                verifiedMimeType = verifiedMime,
                scanTimestamp = now
            )
        }

        // 5. PDF Exploits (JavaScript, Launch Actions, Embedded Files)
        if (pdfVerdict is PDFScanVerdict.Rejected) {
            return SecurityScanReport(
                scanResult = ScanResult.MALICIOUS,
                threatCategory = pdfVerdict.threatCategory,
                threatDetails = pdfVerdict.reason,
                fileHash = fileHash,
                verifiedMimeType = verifiedMime,
                scanTimestamp = now
            )
        }

        // 6. Media Container Anomaly (Malformed atoms, invalid sync)
        if (mediaVerdict is MediaScanVerdict.Rejected) {
            return SecurityScanReport(
                scanResult = ScanResult.MALICIOUS,
                threatCategory = mediaVerdict.threatCategory,
                threatDetails = mediaVerdict.reason,
                fileHash = fileHash,
                verifiedMimeType = verifiedMime,
                scanTimestamp = now
            )
        }

        // 7. Steganography Risks
        val stegoLevel = stegoResult?.riskLevel ?: StegoRiskLevel.NONE
        if (stegoLevel == StegoRiskLevel.HIGH) {
            return SecurityScanReport(
                scanResult = ScanResult.MALICIOUS,
                threatCategory = ThreatCategory.STEGANOGRAPHY_EMBEDDED_CONTAINER,
                threatDetails = stegoResult?.details ?: "High steganographic container risk detected",
                fileHash = fileHash,
                verifiedMimeType = verifiedMime,
                stegoRiskLevel = stegoLevel,
                scanTimestamp = now
            )
        } else if (stegoLevel == StegoRiskLevel.MEDIUM) {
            return SecurityScanReport(
                scanResult = ScanResult.SUSPICIOUS,
                threatCategory = ThreatCategory.STEGANOGRAPHY_TRAILING_DATA,
                threatDetails = stegoResult?.details ?: "Trailing bytes appended past EOF marker",
                fileHash = fileHash,
                verifiedMimeType = verifiedMime,
                stegoRiskLevel = stegoLevel,
                scanTimestamp = now
            )
        }

        // 8. If all checks passed cleanly
        return SecurityScanReport(
            scanResult = ScanResult.SAFE,
            threatCategory = ThreatCategory.CLEAN,
            threatDetails = null,
            fileHash = fileHash,
            verifiedMimeType = verifiedMime,
            stegoRiskLevel = stegoLevel,
            scanTimestamp = now,
            isDeepScanCompleted = true
        )
    }
}
