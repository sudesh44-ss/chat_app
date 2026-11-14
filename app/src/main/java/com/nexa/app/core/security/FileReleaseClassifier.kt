package com.nexa.app.core.security

import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipFile

enum class FileClassification {
    NORMAL,
    APK,
    EXECUTABLE,
    UNSUPPORTED
}

object FileReleaseClassifier {

    fun classify(file: File, fileName: String): FileClassification {
        val extension = fileName.substringAfterLast('.', "").lowercase().removePrefix(".")
        if (extension == "apk") return FileClassification.APK

        val header = ByteArray(8)
        val count = FileInputStream(file).use { it.read(header) }
        if (hasExecutableSignature(header, count)) {
            return FileClassification.EXECUTABLE
        }

        if (SecurityPolicyManager.isExtensionBanned(extension)) return FileClassification.EXECUTABLE
        val isZip = count >= 4 &&
            header[0] == 0x50.toByte() &&
            header[1] == 0x4B.toByte()
        if (isZip && isApkContainer(file)) return FileClassification.APK
        if (isZip && isJarContainer(file)) return FileClassification.EXECUTABLE

        return if (SecurityPolicyManager.isFormatSupported(extension)) {
            FileClassification.NORMAL
        } else {
            FileClassification.UNSUPPORTED
        }
    }

    fun isApkContainer(file: File): Boolean {
        if (!file.isFile || file.length() < 4) return false
        return try {
            ZipFile(file).use { zip -> zip.getEntry("AndroidManifest.xml") != null }
        } catch (_: java.io.IOException) {
            false
        }
    }

    private fun hasExecutableSignature(header: ByteArray, count: Int): Boolean {
        if (count >= 2 && header[0] == 0x4D.toByte() && header[1] == 0x5A.toByte()) return true
        if (count >= 4 && (
                header.copyOfRange(0, 4).contentEquals(NexaFileSecurityEngine.DEX_HEADER) ||
                    header.copyOfRange(0, 4).contentEquals(NexaFileSecurityEngine.ELF_HEADER) ||
                    header.copyOfRange(0, 2).contentEquals(NexaFileSecurityEngine.SHEBANG_HEADER) ||
                    header.copyOfRange(0, 4).contentEquals(byteArrayOf(0xFE.toByte(), 0xED.toByte(), 0xFA.toByte(), 0xCE.toByte())) ||
                    header.copyOfRange(0, 4).contentEquals(byteArrayOf(0xFE.toByte(), 0xED.toByte(), 0xFA.toByte(), 0xCF.toByte())) ||
                    header.copyOfRange(0, 4).contentEquals(byteArrayOf(0xCE.toByte(), 0xFA.toByte(), 0xED.toByte(), 0xFE.toByte())) ||
                    header.copyOfRange(0, 4).contentEquals(byteArrayOf(0xCF.toByte(), 0xFA.toByte(), 0xED.toByte(), 0xFE.toByte())) ||
                    header.copyOfRange(0, 4).contentEquals(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte())) ||
                    header.copyOfRange(0, 4).contentEquals(byteArrayOf(0xBE.toByte(), 0xBA.toByte(), 0xFE.toByte(), 0xCA.toByte()))
                )
        ) return true
        return false
    }

    private fun isJarContainer(file: File): Boolean {
        if (!file.isFile || file.length() < 4) return false
        return try {
            ZipFile(file).use { zip ->
                if (zip.getEntry("META-INF/MANIFEST.MF") == null) return@use false
                val entries = zip.entries()
                var inspected = 0
                while (entries.hasMoreElements() && inspected++ < ArchiveScanner.MAX_ENTRY_COUNT) {
                    if (entries.nextElement().name.endsWith(".class", ignoreCase = true)) return@use true
                }
                false
            }
        } catch (_: java.io.IOException) {
            false
        }
    }
}
