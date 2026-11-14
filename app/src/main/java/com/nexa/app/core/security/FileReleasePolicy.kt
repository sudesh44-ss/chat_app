package com.nexa.app.core.security

enum class ReleaseAuthorization {
    CLEAN_NORMAL,
    FAILED_NORMAL_RISK,
    CLEAN_APK,
    FAILED_APK_RISK,
    CLEAN_EXECUTABLE,
    EXECUTABLE_RISK
}

object FileReleasePolicy {

    fun isAuthorized(
        classification: FileClassification,
        status: String,
        scanResult: String,
        authorization: ReleaseAuthorization
    ): Boolean {
        val cleanScan = status == "SCAN_PASSED" && scanResult == "SAFE"
        val failedScan = status == "REJECTED_MALICIOUS" && scanResult == "MALICIOUS"
        return when (authorization) {
            ReleaseAuthorization.CLEAN_NORMAL ->
                classification == FileClassification.NORMAL && cleanScan
            ReleaseAuthorization.CLEAN_APK ->
                classification == FileClassification.APK && cleanScan
            ReleaseAuthorization.FAILED_NORMAL_RISK ->
                classification == FileClassification.NORMAL && failedScan
            ReleaseAuthorization.FAILED_APK_RISK ->
                classification == FileClassification.APK && failedScan
            ReleaseAuthorization.CLEAN_EXECUTABLE ->
                classification == FileClassification.EXECUTABLE && cleanScan
            ReleaseAuthorization.EXECUTABLE_RISK ->
                classification == FileClassification.EXECUTABLE && failedScan
        }
    }
}
