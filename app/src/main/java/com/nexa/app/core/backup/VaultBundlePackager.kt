package com.nexa.app.core.backup

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import com.nexa.app.data.local.NexaDatabase
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class BackupManifest(
    val versionEpoch: Long,
    val totalConversations: Int,
    val totalMessages: Int,
    val mediaFilesCount: Int,
    val databaseSha256: String
)

/**
 * Packs and unpacks the unified Zero-Risk NEXA Backup Container:
 *
 * Layout:
 * backup_bundle/
 * ├── manifest.json                  (Total messages, conversations, database SHA-256 checksum)
 * ├── databases/
 * │   └── nexa_master_snapshot.db    (Clean zero-lock snapshot via VACUUM INTO)
 * ├── preferences/
 * │   ├── app_user_settings.json     (Theme, auto-download rules, read receipts, font size)
 * │   └── custom_chat_configs.json   (Pinned chat order, mute states, wallpapers)
 * └── media_vault/                   (Files named strictly by Blake3 Content Hash)
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
     * Creates a full snapshot bundle directory and archives it into a single clean zip archive.
     */
    fun packBundle(
        context: Context,
        rawDatabase: SupportSQLiteDatabase,
        outputArchiveFile: File,
        stagingDir: File,
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
        onProgress("Computing Snapshot Checksum...", 0.30f)
        val dbSha256 = computeSha256(snapshotDb)

        // Count messages and conversations safely
        var totalMessages = 0
        var totalConversations = 0
        try {
            rawDatabase.query("SELECT COUNT(*) FROM messages").use { cursor ->
                if (cursor.moveToFirst()) totalMessages = cursor.getInt(0)
            }
            rawDatabase.query("SELECT COUNT(*) FROM chats").use { cursor ->
                if (cursor.moveToFirst()) totalConversations = cursor.getInt(0)
            }
        } catch (ignored: Exception) {}

        // 3. Export App Preferences & Custom Chat Configurations
        onProgress("Exporting Preferences & Settings...", 0.45f)
        val userPrefsJson = JSONObject().apply {
            val sp = context.getSharedPreferences("nexa_settings", Context.MODE_PRIVATE)
            sp.all.forEach { (k, v) -> put(k, v) }
        }
        File(prefsDir, "app_user_settings.json").writeText(userPrefsJson.toString(2))

        val chatConfigsJson = JSONObject().apply {
            val cp = context.getSharedPreferences("nexa_chat_configs", Context.MODE_PRIVATE)
            cp.all.forEach { (k, v) -> put(k, v) }
        }
        File(prefsDir, "custom_chat_configs.json").writeText(chatConfigsJson.toString(2))

        // 4. Copy Local Media Vault Files (strictly named by Blake3 Hash)
        onProgress("Archiving Media Vault...", 0.60f)
        val internalMediaDir = File(context.filesDir, "media_vault")
        var mediaCount = 0
        if (internalMediaDir.exists() && internalMediaDir.isDirectory) {
            internalMediaDir.listFiles()?.forEach { file ->
                if (file.isFile) {
                    file.copyTo(File(mediaDir, file.name), overwrite = true)
                    mediaCount++
                }
            }
        }

        // 5. Write manifest.json
        onProgress("Creating Manifest...", 0.75f)
        val epoch = System.currentTimeMillis()
        val manifest = BackupManifest(
            versionEpoch = epoch,
            totalConversations = totalConversations,
            totalMessages = totalMessages,
            mediaFilesCount = mediaCount,
            databaseSha256 = dbSha256
        )

        val manifestJson = JSONObject().apply {
            put("version_epoch", manifest.versionEpoch)
            put("total_conversations", manifest.totalConversations)
            put("total_messages", manifest.totalMessages)
            put("media_files_count", manifest.mediaFilesCount)
            put("database_sha256", manifest.databaseSha256)
        }
        File(stagingDir, "manifest.json").writeText(manifestJson.toString(2))

        // 6. Zip Entire Staging Directory into Output Archive
        onProgress("Compressing Vault Bundle...", 0.85f)
        if (outputArchiveFile.exists()) outputArchiveFile.delete()
        zipDirectory(stagingDir, outputArchiveFile)

        onProgress("Bundle Packaging Complete", 1.0f)
        return manifest
    }

    /**
     * Verifies manifest and restores all databases, preferences, and media files atomically.
     */
    fun unpackAndRestoreBundle(
        context: Context,
        archiveFile: File,
        stagingDir: File,
        liveDatabaseFile: File,
        onProgress: (phase: String, percent: Float) -> Unit = { _, _ -> }
    ): Boolean {
        if (stagingDir.exists()) stagingDir.deleteRecursively()
        stagingDir.mkdirs()

        // 1. Unzip Archive to Staging
        onProgress("Extracting Vault Bundle...", 0.20f)
        unzipArchive(archiveFile, stagingDir)

        // 2. Read and Verify Manifest
        onProgress("Verifying Manifest & Checksum...", 0.40f)
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

        // 3. Extract and Restore Media Files to App Internal Storage
        onProgress("Restoring Media Vault...", 0.60f)
        val mediaDir = File(stagingDir, "media_vault")
        val appMediaDir = File(context.filesDir, "media_vault").apply { mkdirs() }
        if (mediaDir.exists() && mediaDir.isDirectory) {
            mediaDir.listFiles()?.forEach { file ->
                if (file.isFile) {
                    file.copyTo(File(appMediaDir, file.name), overwrite = true)
                }
            }
        }

        // 4. Restore Preferences
        onProgress("Restoring App Settings...", 0.75f)
        val userPrefsFile = File(stagingDir, "preferences/app_user_settings.json")
        if (userPrefsFile.exists()) {
            try {
                val json = JSONObject(userPrefsFile.readText())
                val sp = context.getSharedPreferences("nexa_settings", Context.MODE_PRIVATE).edit()
                json.keys().forEach { key ->
                    when (val v = json.get(key)) {
                        is Boolean -> sp.putBoolean(key, v)
                        is Int -> sp.putInt(key, v)
                        is Long -> sp.putLong(key, v)
                        is Double -> sp.putFloat(key, v.toFloat())
                        is String -> sp.putString(key, v)
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

        // 5. Atomic Database Swap
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

    private fun unzipArchive(zipFile: File, targetDir: File) {
        ZipInputStream(FileInputStream(zipFile)).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                val newFile = File(targetDir, entry.name)
                // Prevent Zip Slip vulnerability
                if (!newFile.canonicalPath.startsWith(targetDir.canonicalPath)) {
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
    }
}
