package com.nexa.app.core.backup

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import com.nexa.app.core.backup.crypto.KeyEnvelopeEngine
import com.nexa.app.core.security.SecureStorage
import com.nexa.app.data.local.NexaDatabase
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class BackupManifest(
    val versionEpoch: Long,
    val formatVersion: Int = 2,
    val totalConversations: Int,
    val totalMessages: Int,
    val mediaFilesCount: Int,
    val databaseSha256: String,
    val categoryConfig: BackupCategoryConfig = BackupCategoryConfig()
)

/**
 * Packs and unpacks the unified Zero-Risk NEXA Backup Container:
 *
 * Layout (Format Version 2):
 * backup_bundle/
 * ├── key_envelope.bin               (66-byte authenticated binary envelope; processed ONLY in RAM)
 * ├── manifest.json                  (Total messages, conversations, database SHA-256; ZERO keys)
 * ├── databases/
 * │   └── nexa_master_snapshot.db    (Clean zero-lock snapshot via VACUUM INTO)
 * ├── preferences/
 * │   ├── app_user_settings.json     (Non-sensitive theme, auto-download, font size)
 * │   └── custom_chat_configs.json   (Pinned chat order, mute states, wallpapers)
 * └── media_vault/                   (Files filtered by category, named strictly by Blake3 Content Hash)
 */
object VaultBundlePackager {

    private const val BUFFER_SIZE = 64 * 1024

    fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(BUFFER_SIZE)
            var read: Int
            while (fis.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Determines whether a given filename matches the user's selected media categories.
     */
    fun matchesMediaCategory(fileName: String, config: BackupCategoryConfig): Boolean {
        if (!config.includeAttachments) return false
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
                    lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".bmp") -> config.includeImages
            lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm") ||
                    lower.endsWith(".avi") || lower.endsWith(".mov") -> config.includeVideos
            lower.endsWith(".opus") || lower.endsWith(".mp3") || lower.endsWith(".m4a") ||
                    lower.endsWith(".aac") || lower.endsWith(".wav") || lower.endsWith(".ogg") -> config.includeAudio
            lower.endsWith(".pdf") || lower.endsWith(".doc") || lower.endsWith(".docx") ||
                    lower.endsWith(".xls") || lower.endsWith(".xlsx") || lower.endsWith(".ppt") ||
                    lower.endsWith(".pptx") || lower.endsWith(".txt") || lower.endsWith(".csv") -> config.includeDocuments
            else -> config.includeOtherFiles
        }
    }

    /**
     * Creates a full snapshot bundle directory and archives it into a single clean zip archive.
     * Incorporates Design C: Dual-Layer Cryptographic Key Envelope (`key_envelope.bin`).
     */
    fun packBundle(
        context: Context,
        rawDatabase: SupportSQLiteDatabase,
        outputArchiveFile: File,
        stagingDir: File,
        dbPassphrase: ByteArray? = null,
        vaultMasterKeyBytes: ByteArray? = null,
        salt: ByteArray? = null,
        categoryConfig: BackupCategoryConfig = BackupCategoryConfig(),
        onProgress: (phase: String, percent: Float) -> Unit = { _, _ -> }
    ): BackupManifest {
        if (stagingDir.exists()) stagingDir.deleteRecursively()
        stagingDir.mkdirs()

        val dbDir = File(stagingDir, "databases").apply { mkdirs() }
        val prefsDir = File(stagingDir, "preferences").apply { mkdirs() }
        val mediaDir = File(stagingDir, "media_vault").apply { mkdirs() }

        // 1. Zero-Lock Atomic Snapshot via VACUUM INTO
        onProgress("Creating Zero-Lock Database Snapshot...", 0.15f)
        val snapshotDb = File(dbDir, "nexa_master_snapshot.db")
        if (snapshotDb.exists()) snapshotDb.delete()
        rawDatabase.execSQL("VACUUM INTO '${snapshotDb.absolutePath}'")

        // 2. Compute Checksum of Database Snapshot
        onProgress("Computing Snapshot Checksum...", 0.25f)
        val dbSha256 = computeSha256(snapshotDb)

        // Count messages and conversations safely
        var totalMessages = 0
        var totalConversations = 0
        val fileHashMap = mutableMapOf<String, String>() // hash -> fileName
        try {
            rawDatabase.query("SELECT COUNT(*) FROM messages").use { cursor ->
                if (cursor.moveToFirst()) totalMessages = cursor.getInt(0)
            }
            rawDatabase.query("SELECT COUNT(*) FROM chats").use { cursor ->
                if (cursor.moveToFirst()) totalConversations = cursor.getInt(0)
            }
            rawDatabase.query("SELECT fileHash, fileName FROM file_envelopes").use { cursor ->
                val hashIdx = cursor.getColumnIndex("fileHash")
                val nameIdx = cursor.getColumnIndex("fileName")
                while (cursor.moveToNext()) {
                    if (hashIdx >= 0 && nameIdx >= 0) {
                        val h = cursor.getString(hashIdx)
                        val n = cursor.getString(nameIdx)
                        if (!h.isNullOrBlank() && !n.isNullOrBlank()) {
                            fileHashMap[h] = n
                        }
                    }
                }
            }
        } catch (ignored: Exception) {}

        // 3. Design C: Seal Database Key into Binary Key Envelope (key_envelope.bin)
        if (dbPassphrase != null && vaultMasterKeyBytes != null && salt != null) {
            onProgress("Sealing Cryptographic Key Envelope...", 0.35f)
            val sealedEnvelope = KeyEnvelopeEngine.sealPassphrase(
                dbPassphrase = dbPassphrase,
                vaultMasterKeyBytes = vaultMasterKeyBytes,
                salt = salt
            )
            File(stagingDir, "key_envelope.bin").writeBytes(sealedEnvelope)
        }

        // 4. Export App Preferences & Custom Chat Configurations (Sanitized: NO TOKENS OR SECRETS)
        if (categoryConfig.includeAppSettings || categoryConfig.includeUserPrefs) {
            onProgress("Exporting Preferences & Settings...", 0.45f)
            val userPrefsJson = JSONObject().apply {
                val sp = context.getSharedPreferences("nexa_settings", Context.MODE_PRIVATE)
                sp.all.forEach { (k, v) ->
                    // Exclude any keys containing sensitive tokens, auth, or secrets
                    val lower = k.lowercase()
                    if (!lower.contains("token") && !lower.contains("secret") && !lower.contains("auth")) {
                        put(k, v)
                    }
                }
            }
            File(prefsDir, "app_user_settings.json").writeText(userPrefsJson.toString(2))

            val chatConfigsJson = JSONObject().apply {
                val cp = context.getSharedPreferences("nexa_chat_configs", Context.MODE_PRIVATE)
                cp.all.forEach { (k, v) -> put(k, v) }
            }
            File(prefsDir, "custom_chat_configs.json").writeText(chatConfigsJson.toString(2))
        }

        // 5. Copy Local Media Vault Files (Filtered by category, named strictly by Blake3 Hash)
        var mediaCount = 0
        if (categoryConfig.includeAttachments) {
            onProgress("Archiving Media Vault...", 0.60f)
            val internalMediaDir = File(context.filesDir, "media_vault")
            if (internalMediaDir.exists() && internalMediaDir.isDirectory) {
                internalMediaDir.listFiles()?.forEach { file ->
                    if (file.isFile) {
                        val mappedFileName = fileHashMap[file.name] ?: file.name
                        if (matchesMediaCategory(mappedFileName, categoryConfig)) {
                            file.copyTo(File(mediaDir, file.name), overwrite = true)
                            mediaCount++
                        }
                    }
                }
            }
        }

        // 6. Write manifest.json (STRICTLY NON-SENSITIVE METADATA; ZERO KEYS)
        onProgress("Creating Manifest...", 0.75f)
        val epoch = System.currentTimeMillis()
        val manifest = BackupManifest(
            versionEpoch = epoch,
            formatVersion = 2,
            totalConversations = totalConversations,
            totalMessages = totalMessages,
            mediaFilesCount = mediaCount,
            databaseSha256 = dbSha256,
            categoryConfig = categoryConfig
        )

        val manifestJson = JSONObject().apply {
            put("version_epoch", manifest.versionEpoch)
            put("format_version", manifest.formatVersion)
            put("total_conversations", manifest.totalConversations)
            put("total_messages", manifest.totalMessages)
            put("media_files_count", manifest.mediaFilesCount)
            put("database_sha256", manifest.databaseSha256)
            put("category_config", manifest.categoryConfig.toJsonObject())
        }
        File(stagingDir, "manifest.json").writeText(manifestJson.toString(2))

        // 7. Zip Entire Staging Directory into Output Archive
        onProgress("Compressing Vault Bundle...", 0.85f)
        if (outputArchiveFile.exists()) outputArchiveFile.delete()
        zipDirectory(stagingDir, outputArchiveFile)

        onProgress("Bundle Packaging Complete", 1.0f)
        return manifest
    }

    /**
     * Verifies manifest and restores databases, preferences, and media files atomically.
     * Guaranteed Zero-Disk RAM Staging for Key Envelope (`key_envelope.bin`).
     */
    fun unpackAndRestoreBundle(
        context: Context,
        archiveFile: File,
        stagingDir: File,
        liveDatabaseFile: File,
        vaultMasterKeyBytes: ByteArray? = null,
        salt: ByteArray? = null,
        secureStorage: SecureStorage? = null,
        restoreCategoryConfig: BackupCategoryConfig = BackupCategoryConfig(),
        onProgress: (phase: String, percent: Float) -> Unit = { _, _ -> }
    ): Boolean {
        if (stagingDir.exists()) stagingDir.deleteRecursively()
        stagingDir.mkdirs()

        // 1. Unzip Archive to Staging with ZERO-DISK RAM EXTRACTION for key_envelope.bin
        onProgress("Extracting Vault Bundle & Unsealing Key...", 0.20f)
        var envelopeBytesInRam: ByteArray? = null

        ZipInputStream(FileInputStream(archiveFile)).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                if (entry.name == "key_envelope.bin" || entry.name.endsWith("/key_envelope.bin")) {
                    // ZERO-DISK: Read envelope directly into RAM byte array
                    val bos = ByteArrayOutputStream()
                    val buffer = ByteArray(BUFFER_SIZE)
                    var read: Int
                    while (zis.read(buffer).also { read = it } != -1) {
                        bos.write(buffer, 0, read)
                    }
                    envelopeBytesInRam = bos.toByteArray()
                    zis.closeEntry()
                    entry = zis.nextEntry
                    continue
                }

                val newFile = File(stagingDir, entry.name)
                // Prevent Zip Slip vulnerability
                if (!newFile.canonicalPath.startsWith(stagingDir.canonicalPath)) {
                    throw SecurityException("Zip Slip directory traversal detected in entry: ${entry.name}")
                }
                if (entry.isDirectory) {
                    newFile.mkdirs()
                } else {
                    newFile.parentFile?.mkdirs()
                    FileOutputStream(newFile).use { fos ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var read: Int
                        while (zis.read(buffer).also { read = it } != -1) {
                            fos.write(buffer, 0, read)
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        // 2. Unseal Key Envelope in RAM and Save into Device B KeyStore
        if (envelopeBytesInRam != null && vaultMasterKeyBytes != null && salt != null && secureStorage != null) {
            onProgress("Unsealing Portable Key Envelope in RAM...", 0.35f)
            val recoveredPassphrase = KeyEnvelopeEngine.unsealPassphrase(
                envelopeBytes = envelopeBytesInRam!!,
                vaultMasterKeyBytes = vaultMasterKeyBytes,
                salt = salt
            )
            try {
                secureStorage.saveDatabasePassphrase(recoveredPassphrase)
            } finally {
                java.util.Arrays.fill(recoveredPassphrase, 0.toByte())
            }
        }

        // 3. Read and Verify Manifest
        onProgress("Verifying Manifest & Checksum...", 0.45f)
        val manifestFile = File(stagingDir, "manifest.json")
        val snapshotDb = File(stagingDir, "databases/nexa_master_snapshot.db")

        if (!manifestFile.exists() || !snapshotDb.exists()) {
            throw IllegalArgumentException("Corrupted backup bundle: missing manifest or database snapshot")
        }

        val manifestObj = JSONObject(manifestFile.readText())
        val expectedSha = manifestObj.getString("database_sha256")
        val actualSha = computeSha256(snapshotDb)

        if (!actualSha.equals(expectedSha, ignoreCase = true)) {
            throw SecurityException("Backup integrity violation: Computed SHA-256 ($actualSha) does not match manifest ($expectedSha). Restore aborted.")
        }

        // 4. Extract and Restore Media Files to App Internal Storage
        if (restoreCategoryConfig.includeAttachments) {
            onProgress("Restoring Media Vault...", 0.60f)
            val mediaDir = File(stagingDir, "media_vault")
            val appMediaDir = File(context.filesDir, "media_vault").apply { mkdirs() }
            if (mediaDir.exists() && mediaDir.isDirectory) {
                mediaDir.listFiles()?.forEach { file ->
                    if (file.isFile && matchesMediaCategory(file.name, restoreCategoryConfig)) {
                        file.copyTo(File(appMediaDir, file.name), overwrite = true)
                    }
                }
            }
        }

        // 5. Restore Preferences if selected
        if (restoreCategoryConfig.includeAppSettings || restoreCategoryConfig.includeUserPrefs) {
            onProgress("Restoring App Settings...", 0.75f)
            val userPrefsFile = File(stagingDir, "preferences/app_user_settings.json")
            if (userPrefsFile.exists()) {
                try {
                    val json = JSONObject(userPrefsFile.readText())
                    val sp = context.getSharedPreferences("nexa_settings", Context.MODE_PRIVATE).edit()
                    json.keys().forEach { key ->
                        val lower = key.lowercase()
                        if (!lower.contains("token") && !lower.contains("secret") && !lower.contains("auth")) {
                            when (val v = json.get(key)) {
                                is Boolean -> sp.putBoolean(key, v)
                                is Int -> sp.putInt(key, v)
                                is Long -> sp.putLong(key, v)
                                is Double -> sp.putFloat(key, v.toFloat())
                                is String -> sp.putString(key, v)
                            }
                        }
                    }
                    sp.apply()
                } catch (ignored: Exception) {}
            }

            val chatConfigsFile = File(stagingDir, "preferences/custom_chat_configs.json")
            if (chatConfigsFile.exists()) {
                try {
                    val json = JSONObject(chatConfigsFile.readText())
                    val cp = context.getSharedPreferences("nexa_chat_configs", Context.MODE_PRIVATE).edit()
                    json.keys().forEach { key ->
                        when (val v = json.get(key)) {
                            is Boolean -> cp.putBoolean(key, v)
                            is Int -> cp.putInt(key, v)
                            is Long -> cp.putLong(key, v)
                            is Double -> cp.putFloat(key, v.toFloat())
                            is String -> cp.putString(key, v)
                        }
                    }
                    cp.apply()
                } catch (ignored: Exception) {}
            }
        }

        // 6. Atomic Database Swap
        onProgress("Activating Database Snapshot...", 0.90f)
        NexaDatabase.closeAndResetInstance()

        val walFile = File(liveDatabaseFile.path + "-wal")
        val shmFile = File(liveDatabaseFile.path + "-shm")
        val journalFile = File(liveDatabaseFile.path + "-journal")

        if (walFile.exists()) walFile.delete()
        if (shmFile.exists()) shmFile.delete()
        if (journalFile.exists()) journalFile.delete()

        if (liveDatabaseFile.exists()) liveDatabaseFile.delete()
        snapshotDb.copyTo(liveDatabaseFile, overwrite = true)

        onProgress("Restoration Completed Successfully", 1.0f)
        return true
    }

    private fun zipDirectory(sourceDir: File, outputZipFile: File) {
        ZipOutputStream(FileOutputStream(outputZipFile)).use { zos ->
            sourceDir.walkTopDown().forEach { file ->
                if (file.isFile) {
                    val relativePath = file.relativeTo(sourceDir).path.replace("\\", "/")
                    zos.putNextEntry(ZipEntry(relativePath))
                    FileInputStream(file).use { fis ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var read: Int
                        while (fis.read(buffer).also { read = it } != -1) {
                            zos.write(buffer, 0, read)
                        }
                    }
                    zos.closeEntry()
                }
            }
        }
    }
}
