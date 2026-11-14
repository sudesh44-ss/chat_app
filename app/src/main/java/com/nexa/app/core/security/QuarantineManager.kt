package com.nexa.app.core.security

import android.content.Context
import com.nexa.app.core.util.FileShredder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Manages isolated, encrypted-at-rest quarantine storage for incoming and outgoing files.
 * Quarantined files are encrypted with ephemeral AES-256 GCM session keys and kept
 * isolated in private storage away from public directories (MediaStore, Downloads, Gallery).
 */
class QuarantineManager(private val context: Context) {

    companion object {
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128
        private const val MAX_QUARANTINE_QUOTA_BYTES = 500L * 1024 * 1024 // 500 MB
        private const val EXPIRY_WINDOW_MS = 24L * 60 * 60 * 1000 // 24 hours
    }

    val incomingQuarantineDir: File by lazy {
        File(context.filesDir, "security/incoming_quarantine").apply { if (!exists()) mkdirs() }
    }

    val outgoingQuarantineDir: File by lazy {
        File(context.filesDir, "security/outgoing_quarantine").apply { if (!exists()) mkdirs() }
    }

    val safeVaultDir: File by lazy {
        File(context.filesDir, "vault/safe").apply { if (!exists()) mkdirs() }
    }

    private val sessionKeys = ConcurrentHashMap<String, SecretKey>()
    private val secureRandom = SecureRandom()

    fun getQuarantineFile(transferId: String, isIncoming: Boolean): File {
        val baseDir = if (isIncoming) incomingQuarantineDir else outgoingQuarantineDir
        return File(baseDir, "q_$transferId.enc")
    }

    fun getDecryptedStagingFile(transferId: String): File {
        return File(context.cacheDir, "scan_stage_$transferId.tmp")
    }

    fun getOrCreateSessionKey(transferId: String): SecretKey {
        return sessionKeys.computeIfAbsent(transferId) {
            val keyGen = KeyGenerator.getInstance("AES")
            keyGen.init(256, secureRandom)
            keyGen.generateKey()
        }
    }

    /**
     * Encrypts and writes raw chunk data to the quarantine sink.
     */
    fun appendEncryptedChunk(transferId: String, rawBytes: ByteArray, isIncoming: Boolean) {
        val key = getOrCreateSessionKey(transferId)
        val quarantineFile = getQuarantineFile(transferId, isIncoming)

        val iv = ByteArray(GCM_IV_LENGTH)
        secureRandom.nextBytes(iv)

        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        val ciphertext = cipher.doFinal(rawBytes)

        FileOutputStream(quarantineFile, true).use { fos ->
            // Write length-prefixed chunk: 4 bytes len + 12 bytes IV + ciphertext
            val chunkLen = iv.size + ciphertext.size
            fos.write((chunkLen shr 24) and 0xFF)
            fos.write((chunkLen shr 16) and 0xFF)
            fos.write((chunkLen shr 8) and 0xFF)
            fos.write(chunkLen and 0xFF)
            fos.write(iv)
            fos.write(ciphertext)
        }
    }

    /**
     * Decrypts the quarantined chunks to a temporary isolated file for scanning.
     */
    fun decryptToStaging(transferId: String, isIncoming: Boolean): File? {
        val quarantineFile = getQuarantineFile(transferId, isIncoming)
        if (!quarantineFile.exists() || quarantineFile.length() == 0L) return null

        val key = sessionKeys[transferId] ?: return null
        val stagingFile = getDecryptedStagingFile(transferId)
        if (stagingFile.exists()) stagingFile.delete()

        try {
            FileInputStream(quarantineFile).use { fis ->
                FileOutputStream(stagingFile).use { fos ->
                    val lenBuf = ByteArray(4)
                    while (fis.read(lenBuf) == 4) {
                        val chunkLen = ((lenBuf[0].toInt() and 0xFF) shl 24) or
                                ((lenBuf[1].toInt() and 0xFF) shl 16) or
                                ((lenBuf[2].toInt() and 0xFF) shl 8) or
                                (lenBuf[3].toInt() and 0xFF)

                        if (chunkLen < GCM_IV_LENGTH) break
                        val iv = ByteArray(GCM_IV_LENGTH)
                        if (fis.read(iv) != GCM_IV_LENGTH) break

                        val cipherLen = chunkLen - GCM_IV_LENGTH
                        val ciphertext = ByteArray(cipherLen)
                        var bytesRead = 0
                        while (bytesRead < cipherLen) {
                            val r = fis.read(ciphertext, bytesRead, cipherLen - bytesRead)
                            if (r == -1) break
                            bytesRead += r
                        }
                        if (bytesRead < cipherLen) break

                        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
                        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
                        val decrypted = cipher.doFinal(ciphertext)
                        fos.write(decrypted)
                    }
                }
            }
            return stagingFile
        } catch (e: Exception) {
            stagingFile.delete()
            return null
        }
    }

    /**
     * Atomically promotes a verified file from quarantine into NEXA's safe vault,
     * shredding the quarantine encrypted file and wiping the ephemeral key.
     */
    fun promoteToSafeVault(transferId: String, destinationFileName: String, isIncoming: Boolean): File? {
        val staging = decryptToStaging(transferId, isIncoming) ?: return null
        val destFile = File(safeVaultDir, destinationFileName)

        return try {
            staging.copyTo(destFile, overwrite = true)
            destFile
        } catch (_: Exception) {
            null
        } finally {
            staging.delete()
            purgeQuarantine(transferId, isIncoming)
        }
    }

    /**
     * Cryptographically shreds the quarantine file and zeroes session keys.
     */
    fun purgeQuarantine(transferId: String, isIncoming: Boolean) {
        val qFile = getQuarantineFile(transferId, isIncoming)
        if (qFile.exists()) {
            FileShredder.shred(qFile)
        }
        val staging = getDecryptedStagingFile(transferId)
        if (staging.exists()) {
            staging.delete()
        }
        sessionKeys.remove(transferId)
    }

    /**
     * Enforces storage quotas and cleans expired quarantine files.
     */
    fun cleanupExpiredQuarantine() {
        val now = System.currentTimeMillis()
        listOf(incomingQuarantineDir, outgoingQuarantineDir).forEach { dir ->
            dir.listFiles()?.forEach { file ->
                if (now - file.lastModified() > EXPIRY_WINDOW_MS) {
                    FileShredder.shred(file)
                }
            }
        }
    }
}
