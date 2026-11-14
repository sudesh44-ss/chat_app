package com.nexa.app

import com.nexa.app.core.security.ArchiveScanVerdict
import com.nexa.app.core.security.ArchiveScanner
import com.nexa.app.core.security.FileClassification
import com.nexa.app.core.security.FileReleasePolicy
import com.nexa.app.core.security.FileReleaseClassifier
import com.nexa.app.core.security.FileSecurityManager
import com.nexa.app.core.security.ReleaseAuthorization
import com.nexa.app.core.security.SecurityScanResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking

class FileReleaseClassifierTests {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun apkIsClassifiedFromPackageStructureAndScannedWithinLimits() {
        val apk = temporaryFolder.newFile("received.apk")
        ZipOutputStream(FileOutputStream(apk)).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write(byteArrayOf(1, 2, 3))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("classes.dex"))
            zip.write(byteArrayOf(4, 5, 6))
            zip.closeEntry()
        }

        assertEquals(FileClassification.APK, FileReleaseClassifier.classify(apk, "received.apk"))
        assertTrue(ArchiveScanner.scanAndroidPackage(apk) is ArchiveScanVerdict.Safe)
    }

    @Test
    fun apkPackageWithoutManifestIsNotAcceptedAsStructurallyValid() {
        val apk = temporaryFolder.newFile("bad.apk")
        ZipOutputStream(FileOutputStream(apk)).use { zip ->
            zip.putNextEntry(ZipEntry("classes.dex"))
            zip.write(byteArrayOf(1, 2, 3))
            zip.closeEntry()
        }

        assertTrue(ArchiveScanner.scanAndroidPackage(apk) is ArchiveScanVerdict.Blocked)
    }

    @Test
    fun executableSignatureOverridesBenignFileExtension() {
        val disguised = temporaryFolder.newFile("photo.jpg")
        FileOutputStream(disguised).use {
            it.write(byteArrayOf(0x4D, 0x5A, 0x90.toByte(), 0x00))
        }

        assertEquals(FileClassification.EXECUTABLE, FileReleaseClassifier.classify(disguised, "photo.jpg"))
    }

    @Test
    fun unsupportedArchiveFormatDoesNotBecomeNormalByExtension() {
        val unsupported = temporaryFolder.newFile("archive.rar")
        FileOutputStream(unsupported).use { it.write(byteArrayOf(1, 2, 3, 4)) }

        assertEquals(FileClassification.UNSUPPORTED, FileReleaseClassifier.classify(unsupported, "archive.rar"))
    }

    @Test
    fun archiveContainingAnotherArchiveIsBlocked() {
        val archive = temporaryFolder.newFile("nested.zip")
        ZipOutputStream(FileOutputStream(archive)).use { zip ->
            zip.putNextEntry(ZipEntry("payload.apk"))
            zip.write(byteArrayOf(1, 2, 3))
            zip.closeEntry()
        }

        assertTrue(ArchiveScanner.scanArchive(archive) is ArchiveScanVerdict.Blocked)
    }

    @Test
    fun releasePolicyRequiresScanOutcomeAndMatchingFileClass() {
        assertTrue(
            FileReleasePolicy.isAuthorized(
                FileClassification.NORMAL,
                "SCAN_PASSED",
                "SAFE",
                ReleaseAuthorization.CLEAN_NORMAL
            )
        )
        assertTrue(
            FileReleasePolicy.isAuthorized(
                FileClassification.NORMAL,
                "REJECTED_MALICIOUS",
                "MALICIOUS",
                ReleaseAuthorization.FAILED_NORMAL_RISK
            )
        )
        assertTrue(
            FileReleasePolicy.isAuthorized(
                FileClassification.APK,
                "SCAN_PASSED",
                "SAFE",
                ReleaseAuthorization.CLEAN_APK
            )
        )
        assertTrue(
            FileReleasePolicy.isAuthorized(
                FileClassification.APK,
                "REJECTED_MALICIOUS",
                "MALICIOUS",
                ReleaseAuthorization.FAILED_APK_RISK
            )
        )
        assertTrue(
            FileReleasePolicy.isAuthorized(
                FileClassification.EXECUTABLE,
                "REJECTED_MALICIOUS",
                "MALICIOUS",
                ReleaseAuthorization.EXECUTABLE_RISK
            )
        )
        assertTrue(
            !FileReleasePolicy.isAuthorized(
                FileClassification.NORMAL,
                "SCAN_ERROR",
                "INCONCLUSIVE",
                ReleaseAuthorization.CLEAN_NORMAL
            )
        )
        assertTrue(
            !FileReleasePolicy.isAuthorized(
                FileClassification.APK,
                "SCAN_PASSED",
                "SAFE",
                ReleaseAuthorization.CLEAN_NORMAL
            )
        )
    }

    @Test
    fun receiverScanCanPassApkButNeverPassesUnsupportedFormats() = runBlocking {
        val apk = temporaryFolder.newFile("valid.apk")
        ZipOutputStream(FileOutputStream(apk)).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write(byteArrayOf(1, 2, 3))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("classes.dex"))
            zip.write(byteArrayOf(4, 5, 6))
            zip.closeEntry()
        }
        val manager = FileSecurityManager()

        val apkResult = manager.scanInboundQuarantine(
            apk,
            manager.computeSha256(apk),
            "valid.apk"
        )
        assertTrue(apkResult is SecurityScanResult.Safe)

        val unsupported = temporaryFolder.newFile("unknown.rar")
        FileOutputStream(unsupported).use { it.write(byteArrayOf(1, 2, 3, 4)) }
        val unsupportedResult = manager.scanInboundQuarantine(
            unsupported,
            manager.computeSha256(unsupported),
            "unknown.rar"
        )
        assertTrue(unsupportedResult is SecurityScanResult.Inconclusive)
    }
}
