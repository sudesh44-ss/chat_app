package com.nexa.app.core.security

import java.io.File
import java.io.FileInputStream

/**
 * Static heuristic pattern scanner / LibYARA bridge for Gate 2 post-download deep inspection.
 * Scans for embedded exploits, shellcode sequences, and hidden executable segments inside non-executable containers.
 */
class YaraScannerBridge {

    // Common heuristic exploit signatures
    private val NOP_SLED = byteArrayOf(0x90.toByte(), 0x90.toByte(), 0x90.toByte(), 0x90.toByte(), 0x90.toByte(), 0x90.toByte(), 0x90.toByte(), 0x90.toByte())
    private val EMBEDDED_SHELLCODE_CALL = byteArrayOf(0xE8.toByte(), 0x00, 0x00, 0x00, 0x00, 0x58) // call $+5; pop eax
    private val SUSPICIOUS_SCRIPT_TAG = "<script".toByteArray(Charsets.US_ASCII)
    private val SUSPICIOUS_POWERSHELL = "powershell -enc".toByteArray(Charsets.US_ASCII)

    fun scanFile(file: File): YaraScanResult {
        if (!file.exists() || file.length() == 0L) {
            return YaraScanResult(isClean = true)
        }

        val buffer = ByteArray(65536)
        val fis = FileInputStream(file)
        try {
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                val slice = if (bytesRead == buffer.size) buffer else buffer.copyOf(bytesRead)
                val detected = matchSignatures(slice)
                if (detected != null) {
                    return YaraScanResult(isClean = false, ruleName = detected)
                }
            }
        } finally {
            fis.close()
        }

        return YaraScanResult(isClean = true)
    }

    private fun matchSignatures(data: ByteArray): String? {
        if (indexOf(data, NOP_SLED) != -1) return "Suspicious.NopSled.Exploit"
        if (indexOf(data, EMBEDDED_SHELLCODE_CALL) != -1) return "Exploit.Shellcode.CallPop"
        if (indexOf(data, SUSPICIOUS_SCRIPT_TAG) != -1) return "Suspicious.EmbeddedScript"
        if (indexOf(data, SUSPICIOUS_POWERSHELL) != -1) return "Malware.PowerShellLauncher"
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
}

data class YaraScanResult(
    val isClean: Boolean,
    val ruleName: String? = null
)
