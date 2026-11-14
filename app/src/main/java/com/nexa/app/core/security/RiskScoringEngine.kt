package com.nexa.app.core.security

enum class CompositeRiskLevel {
    CLEAN,
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}

data class RiskAssessment(
    val score: Int, // 0 to 100
    val riskLevel: CompositeRiskLevel,
    val findings: List<String>
)

/**
 * Computes a weighted, multi-vector risk score combining heuristic signals,
 * metadata privacy leaks, format anomalies, and steganography risks.
 */
object RiskScoringEngine {

    fun computeRisk(
        typeDetection: DetectionResult,
        malwareResult: MalwareScanResult,
        structureResult: StructureValidationResult,
        stegoResult: StegoAnalysisResult?,
        metadataReport: MetadataReport?
    ): RiskAssessment {
        val findings = mutableListOf<String>()
        var score = 0

        // 1. Malware or Hash Matches (Weight: 100)
        if (malwareResult is MalwareScanResult.ThreatDetected) {
            findings.add("Malware/Exploit detected: ${malwareResult.threatName}")
            score += 100
        }

        // 2. File Type / MIME Spoofing (Weight: 100)
        if (typeDetection is DetectionResult.Blocked) {
            findings.add("Anti-Spoofing blocked: ${typeDetection.reason}")
            score += 100
        }

        // 3. Structural Malformations (Weight: 80)
        if (structureResult is StructureValidationResult.Malformed) {
            findings.add("Structural anomaly: ${structureResult.reason}")
            score += 80
        }

        // 4. Steganography Signals (Weight: 30 to 85)
        if (stegoResult != null) {
            when (stegoResult.riskLevel) {
                StegoRiskLevel.HIGH -> {
                    findings.add("High steganography risk: ${stegoResult.details}")
                    score += 85
                }
                StegoRiskLevel.MEDIUM -> {
                    findings.add("Medium steganography risk: ${stegoResult.details}")
                    score += 50
                }
                StegoRiskLevel.LOW -> {
                    findings.add("Low steganography risk: ${stegoResult.details}")
                    score += 20
                }
                StegoRiskLevel.NONE -> Unit
            }
        }

        // 5. Privacy Leaks (GPS, Camera Serial) (Weight: 15)
        if (metadataReport != null && metadataReport.hasGpsLocation) {
            findings.add("EXIF GPS location coordinates embedded")
            score += 25
        }

        val finalScore = score.coerceIn(0, 100)
        val level = when {
            finalScore >= 90 -> CompositeRiskLevel.CRITICAL
            finalScore >= 70 -> CompositeRiskLevel.HIGH
            finalScore >= 40 -> CompositeRiskLevel.MEDIUM
            finalScore > 0 -> CompositeRiskLevel.LOW
            else -> CompositeRiskLevel.CLEAN
        }

        return RiskAssessment(
            score = finalScore,
            riskLevel = level,
            findings = findings
        )
    }
}
