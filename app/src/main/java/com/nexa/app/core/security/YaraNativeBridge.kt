package com.nexa.app.core.security

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * JNI wrapper and fallback static heuristic engine interfacing with LibYARA.
 * Detects embedded shellcode, malicious PDF JavaScript, weaponized macros, and exploit payloads.
 */
class YaraNativeBridge {

    private var isNativeLibLoaded = false

    init {
        try {
            System.loadLibrary("yara_bridge")
            isNativeLibLoaded = nativeInit()
        } catch (e: Throwable) {
            isNativeLibLoaded = false
        }
    }

    // JNI Native Methods
    private external fun nativeInit(): Boolean
    private external fun nativeLoadRules(compiledRulesPath: String): Boolean
    private external fun nativeScanFile(filePath: String): String?
    private external fun nativeScanBuffer(buffer: ByteArray, length: Int): String?

    fun loadRules(compiledRulesFile: File): Boolean {
        if (isNativeLibLoaded && compiledRulesFile.exists()) {
            return nativeLoadRules(compiledRulesFile.absolutePath)
        }
        return false
    }

    fun scanFile(filePath: String): String? {
        val file = File(filePath)
        if (!file.exists() || file.length() == 0L) return null

        if (isNativeLibLoaded) {
            try {
                val nativeVerdict = nativeScanFile(filePath)
                if (nativeVerdict != null) return nativeVerdict
            } catch (e: Throwable) {
                // Fall back to built-in heuristic pattern engine
            }
        }

        return fallbackScanFile(file)
    }

    fun scanUri(context: Context, uri: Uri): String? {
        val stream = context.contentResolver.openInputStream(uri) ?: return null
        return stream.use { scanInputStream(it) }
    }

    fun scanInputStream(inputStream: InputStream): String? {
        val buffer = ByteArray(65536)
        var bytesRead: Int
        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            val chunk = if (bytesRead == buffer.size) buffer else buffer.copyOf(bytesRead)
            val threat = matchByteSignatures(chunk)
            if (threat != null) return threat
        }
        return null
    }

    private fun fallbackScanFile(file: File): String? {
        FileInputStream(file).use { fis ->
            return scanInputStream(fis)
        }
    }

    /**
     * Built-in bytecode pattern inspection rules mirroring LibYARA compiled heuristics.
     */
    private fun matchByteSignatures(data: ByteArray): String? {
        // 1. EICAR Anti-Virus Standard Test Pattern
        if (indexOf(data, EICAR_PATTERN) != -1) return "Test.EICAR.StandardVector"

        // 2. NOP Sled (8 consecutive 0x90 NOP instructions common in buffer overflow exploits)
        if (indexOf(data, NOP_SLED) != -1) return "Exploit.NopSled.BufferOverflow"

        // 3. Embedded Shellcode Call/Pop Sequence (call $+5; pop eax)
        if (indexOf(data, SHELLCODE_CALL_POP) != -1) return "Exploit.Shellcode.CallPop"

        // 4. Malicious PDF JavaScript Stream & Launch Actions
        if (indexOf(data, PDF_JAVASCRIPT_1) != -1 || indexOf(data, PDF_JAVASCRIPT_2) != -1) {
            if (indexOf(data, PDF_EMBEDDED_STREAM) != -1 || indexOf(data, SCRIPT_TAG) != -1) {
                return "Exploit.PDF.MaliciousJavaScript"
            }
        }
        if (indexOf(data, PDF_LAUNCH_ACTION) != -1) return "Exploit.PDF.SuspiciousLaunchAction"

        // 5. Weaponized Office Macros & VBA Project Binaries
        if (indexOf(data, VBA_PROJECT_BIN) != -1 || indexOf(data, MACRO_AUTO_OPEN) != -1 || indexOf(data, MACRO_DOC_OPEN) != -1) {
            return "Exploit.Office.WeaponizedMacro"
        }

        // 6. Malicious Encoded PowerShell or Reverse Shell Strings
        if (indexOf(data, POWERSHELL_ENC) != -1 || indexOf(data, POWERSHELL_HIDDEN) != -1) {
            return "Malware.PowerShellLauncher"
        }
        if (indexOf(data, BASH_TCP_REVERSE) != -1) {
            return "Malware.BashReverseShell"
        }

        return null
    }

    private fun indexOf(source: ByteArray, target: ByteArray): Int {
        if (target.isEmpty() || source.size < target.size) return -1
        val max = source.size - target.size
        for (i in 0..max) {
            var match = true
            for (j in target.indices) {
                if (source[i + j] != target[j]) {
                    match = false
                    break
                }
            }
            if (match) return i
        }
        return -1
    }

    companion object {
        // Masked with XOR 0x5A to prevent static false-positive AV & Play Protect installation blocks on the APK
        private val EICAR_MASKED = byteArrayOf(
            0x02.toByte(), 0x6F.toByte(), 0x15.toByte(), 0x7B.toByte(), 0x0A.toByte(), 0x7F.toByte(), 0x1A.toByte(), 0x1B.toByte(),
            0x0A.toByte(), 0x01.toByte(), 0x6E.toByte(), 0x06.toByte(), 0x0A.toByte(), 0x00.toByte(), 0x02.toByte(), 0x6F.toByte(),
            0x6E.toByte(), 0x72.toByte(), 0x0A.toByte(), 0x04.toByte(), 0x73.toByte(), 0x6D.toByte(), 0x19.toByte(), 0x19.toByte(),
            0x73.toByte(), 0x6D.toByte(), 0x27.toByte(), 0x7E.toByte(), 0x1F.toByte(), 0x13.toByte(), 0x19.toByte(), 0x1B.toByte(),
            0x08.toByte(), 0x77.toByte(), 0x09.toByte(), 0x0E.toByte(), 0x1B.toByte(), 0x14.toByte(), 0x1E.toByte(), 0x1B.toByte(),
            0x08.toByte(), 0x1E.toByte(), 0x77.toByte(), 0x1B.toByte(), 0x14.toByte(), 0x0E.toByte(), 0x13.toByte(), 0x0C.toByte(),
            0x13.toByte(), 0x08.toByte(), 0x0F.toByte(), 0x09.toByte(), 0x77.toByte(), 0x0E.toByte(), 0x1F.toByte(), 0x09.toByte(),
            0x0E.toByte(), 0x77.toByte(), 0x1C.toByte(), 0x13.toByte(), 0x16.toByte(), 0x1F.toByte(), 0x7B.toByte(), 0x7E.toByte(),
            0x12.toByte(), 0x71.toByte(), 0x12.toByte(), 0x70.toByte()
        )
        private val EICAR_PATTERN: ByteArray by lazy {
            ByteArray(EICAR_MASKED.size) { i -> (EICAR_MASKED[i].toInt() xor 0x5A).toByte() }
        }
        private val NOP_SLED = byteArrayOf(0x90.toByte(), 0x90.toByte(), 0x90.toByte(), 0x90.toByte(), 0x90.toByte(), 0x90.toByte(), 0x90.toByte(), 0x90.toByte())
        private val SHELLCODE_CALL_POP = byteArrayOf(0xE8.toByte(), 0x00, 0x00, 0x00, 0x00, 0x58)
        private val SCRIPT_TAG = "<script".toByteArray(Charsets.US_ASCII)
        private val PDF_JAVASCRIPT_1 = "/JavaScript".toByteArray(Charsets.US_ASCII)
        private val PDF_JAVASCRIPT_2 = "/JS".toByteArray(Charsets.US_ASCII)
        private val PDF_EMBEDDED_STREAM = "/EmbeddedFiles".toByteArray(Charsets.US_ASCII)
        private val PDF_LAUNCH_ACTION = "/Launch".toByteArray(Charsets.US_ASCII)
        private val VBA_PROJECT_BIN = "vbaProject.bin".toByteArray(Charsets.US_ASCII)
        private val MACRO_AUTO_OPEN = "AutoOpen".toByteArray(Charsets.US_ASCII)
        private val MACRO_DOC_OPEN = "Document_Open".toByteArray(Charsets.US_ASCII)
        private val POWERSHELL_ENC = "powershell -enc".toByteArray(Charsets.US_ASCII)
        private val POWERSHELL_HIDDEN = "powershell -w hidden".toByteArray(Charsets.US_ASCII)
        private val BASH_TCP_REVERSE = "/dev/tcp/".toByteArray(Charsets.US_ASCII)
    }
}
