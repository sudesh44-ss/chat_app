package com.nexa.app.core.storage

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale

/**
 * Categories of storage managed in the user-accessible NEXA directory.
 */
enum class NexaStorageCategory(val subPath: String, val displayName: String) {
    IMAGES("Media/Images", "Images & Photos"),
    VIDEOS("Media/Videos", "Videos"),
    AUDIO("Media/Audio", "Audio & Voice Notes"),
    DOCUMENTS("Media/Documents", "Documents"),
    DOWNLOADS("Downloads", "Downloads"),
    EXPORTS("Exports", "Data Exports"),
    ENCRYPTED_BACKUPS("Backups/Encrypted", "Encrypted Backups"),
    AVATARS("Profile/Avatars", "Profile Avatars")
}

/**
 * Breakdown of on-device storage usage by category.
 */
data class StorageCategoryBreakdown(
    val imagesBytes: Long = 0L,
    val imagesCount: Int = 0,
    val videosBytes: Long = 0L,
    val videosCount: Int = 0,
    val audioBytes: Long = 0L,
    val audioCount: Int = 0,
    val documentsBytes: Long = 0L,
    val documentsCount: Int = 0,
    val downloadsBytes: Long = 0L,
    val downloadsCount: Int = 0,
    val exportsBytes: Long = 0L,
    val exportsCount: Int = 0,
    val encryptedBackupsBytes: Long = 0L,
    val encryptedBackupsCount: Int = 0,
    val avatarsBytes: Long = 0L,
    val avatarsCount: Int = 0,
    val cacheBytes: Long = 0L,
    val encryptedDatabaseBytes: Long = 0L,
    val totalAppBytes: Long = 0L,
    val nexaRootPath: String = "",
    val isFolderAccessible: Boolean = false
) {
    val totalMediaBytes: Long
        get() = imagesBytes + videosBytes + audioBytes + documentsBytes + downloadsBytes + exportsBytes
}

/**
 * Result of a cache clear operation.
 */
data class CacheClearResult(
    val bytesCleared: Long,
    val filesDeletedCount: Int,
    val success: Boolean
)

/**
 * Centralized Storage Manager for NEXA.
 *
 * Architecture & Privacy Guarantees:
 * 1. User-accessible root is located at:
 *    /Android/media/<packageName>/NEXA/
 *    (Compliant with Android 11+ Scoped Storage; accessible via Files app without broad storage permissions).
 * 2. Strict Internal Privacy:
 *    SQLCipher encrypted database, Keystore cryptographic keys, Signal Double Ratchet ratchets,
 *    and auth session credentials STRICTLY reside in private internal storage:
 *    /data/user/0/<packageName>/databases/ and /data/user/0/<packageName>/no_backup/
 *    They are NEVER placed in or accessible to external media directories.
 * 3. Encrypted Backups in Backups/Encrypted are AES-256-GCM encrypted bundles.
 * 4. Self-healing storage tree: all directories are automatically created and recovered if deleted.
 */
class NexaStorageManager(
    private val context: Context? = null,
    private val customRootDir: File? = null,
    private val customCacheDir: File? = null,
    private val customDataDir: File? = null
) {

    /**
     * Resolves the user-accessible NEXA root directory:
     * Priority:
     * 1. customRootDir (for testing)
     * 2. context.externalMediaDirs[0]/NEXA
     * 3. context.getExternalFilesDir(null)/NEXA
     * 4. File(context.filesDir, "media/NEXA")
     */
    val nexaRootDir: File
        get() {
            if (customRootDir != null) return customRootDir

            val mediaDirs = try {
                context?.externalMediaDirs ?: emptyArray()
            } catch (e: Exception) {
                emptyArray<File?>()
            }
            val base = mediaDirs.firstOrNull { it != null && (it.exists() || it.mkdirs()) && it.canWrite() }
                ?: context?.getExternalFilesDir(null)
                ?: File(context?.filesDir ?: File(System.getProperty("java.io.tmpdir") ?: ".", "nexa_test"), "media")
            return File(base, "NEXA")
        }

    /**
     * Initializes the full directory tree. Call during app startup.
     * Returns true if all folders are ready and accessible.
     */
    fun initStorageTree(): Boolean {
        return try {
            val root = nexaRootDir
            if (!root.exists()) {
                root.mkdirs()
            }

            NexaStorageCategory.entries.forEach { category ->
                val dir = File(root, category.subPath)
                if (!dir.exists()) {
                    dir.mkdirs()
                }

                // Protect private/internal subdirectories from Android media scanner pollution
                if (category == NexaStorageCategory.ENCRYPTED_BACKUPS || category == NexaStorageCategory.AVATARS) {
                    val noMedia = File(dir, ".nomedia")
                    if (!noMedia.exists()) {
                        runCatching { noMedia.createNewFile() }
                    }
                }
            }
            root.exists() && root.canWrite()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Retrieves the directory for a specific storage category, recreating it if needed.
     */
    fun getDirectory(category: NexaStorageCategory): File {
        val dir = File(nexaRootDir, category.subPath)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Resolves a collision-safe and traversal-safe target File inside the designated category.
     * Prevents path traversal attacks (e.g. "../../../etc/passwd").
     */
    fun resolveSafeFile(category: NexaStorageCategory, rawFileName: String): File {
        val targetDir = getDirectory(category)

        // Sanitize filename: remove directory separators and illegal control characters
        val cleanName = rawFileName
            .replace("\\", "_")
            .replace("/", "_")
            .replace("..", "_")
            .trim()
            .ifBlank { "file_${System.currentTimeMillis()}" }

        val nameWithoutExt = cleanName.substringBeforeLast('.', cleanName)
        val ext = if (cleanName.contains('.')) ".${cleanName.substringAfterLast('.')}" else ""

        var candidate = File(targetDir, cleanName)
        var index = 1
        while (candidate.exists()) {
            candidate = File(targetDir, "$nameWithoutExt ($index)$ext")
            index++
        }
        return candidate
    }

    /**
     * Saves input stream content safely into the specified category with collision prevention.
     */
    suspend fun saveMediaFile(
        category: NexaStorageCategory,
        fileName: String,
        inputStream: InputStream
    ): File = withContext(Dispatchers.IO) {
        val destination = resolveSafeFile(category, fileName)
        inputStream.use { input ->
            FileOutputStream(destination).use { output ->
                input.copyTo(output)
            }
        }
        destination
    }

    /**
     * Verifies that security-sensitive data (databases, encryption keys) is NOT in external storage.
     * Returns true if internal isolation is completely intact.
     */
    fun verifySecurityIsolation(): Boolean {
        val externalRoot = nexaRootDir
        val dbDir = if (customDataDir != null) {
            File(customDataDir, "databases")
        } else {
            File(context?.applicationInfo?.dataDir ?: "", "databases")
        }
        val internalFiles = if (customDataDir != null) {
            File(customDataDir, "files")
        } else {
            context?.filesDir ?: File("")
        }

        if (!dbDir.exists() && !internalFiles.exists()) return true
        val rootPath = externalRoot.canonicalPath
        val dbPath = dbDir.canonicalPath
        val internalPath = internalFiles.canonicalPath

        return !rootPath.startsWith(dbPath) && !rootPath.startsWith(internalPath)
    }

    /**
     * Asynchronously scans and calculates complete storage usage.
     */
    suspend fun calculateStorageUsage(): StorageCategoryBreakdown = withContext(Dispatchers.IO) {
        initStorageTree()

        fun dirStats(category: NexaStorageCategory): Pair<Long, Int> {
            val dir = File(nexaRootDir, category.subPath)
            if (!dir.exists()) return Pair(0L, 0)
            var bytes = 0L
            var count = 0
            dir.walkTopDown().filter { it.isFile && it.name != ".nomedia" }.forEach { file ->
                bytes += file.length()
                count++
            }
            return Pair(bytes, count)
        }

        val (imgBytes, imgCount) = dirStats(NexaStorageCategory.IMAGES)
        val (vidBytes, vidCount) = dirStats(NexaStorageCategory.VIDEOS)
        val (audBytes, audCount) = dirStats(NexaStorageCategory.AUDIO)
        val (docBytes, docCount) = dirStats(NexaStorageCategory.DOCUMENTS)
        val (dlBytes, dlCount) = dirStats(NexaStorageCategory.DOWNLOADS)
        val (expBytes, expCount) = dirStats(NexaStorageCategory.EXPORTS)
        val (bakBytes, bakCount) = dirStats(NexaStorageCategory.ENCRYPTED_BACKUPS)
        val (avaBytes, avaCount) = dirStats(NexaStorageCategory.AVATARS)

        // Cache size
        val cacheFolders = if (customCacheDir != null) {
            listOf(customCacheDir)
        } else {
            listOfNotNull(context?.cacheDir, context?.externalCacheDir)
        }
        var cacheBytes = 0L
        cacheFolders.forEach { cacheFolder ->
            if (cacheFolder.exists()) {
                cacheFolder.walkTopDown().filter { it.isFile }.forEach { cacheBytes += it.length() }
            }
        }

        // Encrypted database size
        val databasesDir = if (customDataDir != null) {
            File(customDataDir, "databases")
        } else {
            File(context?.applicationInfo?.dataDir ?: "", "databases")
        }
        var dbBytes = 0L
        if (databasesDir.exists()) {
            databasesDir.walkTopDown().filter { it.isFile }.forEach { dbBytes += it.length() }
        }

        val totalBytes = imgBytes + vidBytes + audBytes + docBytes + dlBytes + expBytes + bakBytes + avaBytes + cacheBytes + dbBytes

        StorageCategoryBreakdown(
            imagesBytes = imgBytes,
            imagesCount = imgCount,
            videosBytes = vidBytes,
            videosCount = vidCount,
            audioBytes = audBytes,
            audioCount = audCount,
            documentsBytes = docBytes,
            documentsCount = docCount,
            downloadsBytes = dlBytes,
            downloadsCount = dlCount,
            exportsBytes = expBytes,
            exportsCount = expCount,
            encryptedBackupsBytes = bakBytes,
            encryptedBackupsCount = bakCount,
            avatarsBytes = avaBytes,
            avatarsCount = avaCount,
            cacheBytes = cacheBytes,
            encryptedDatabaseBytes = dbBytes,
            totalAppBytes = totalBytes,
            nexaRootPath = nexaRootDir.absolutePath,
            isFolderAccessible = nexaRootDir.exists() && nexaRootDir.canWrite()
        )
    }

    /**
     * Safely clears temporary cache files without touching user messages, media, or database.
     */
    suspend fun clearCache(): CacheClearResult = withContext(Dispatchers.IO) {
        val dirs = if (customCacheDir != null) {
            listOf(customCacheDir)
        } else {
            listOfNotNull(context?.cacheDir, context?.externalCacheDir)
        }
        var deletedBytes = 0L
        var deletedFiles = 0

        for (dir in dirs) {
            if (!dir.exists()) continue
            dir.listFiles()?.forEach { child ->
                val size = if (child.isFile) child.length() else {
                    child.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                }
                val count = if (child.isFile) 1 else child.walkTopDown().filter { it.isFile }.count()
                if (child.deleteRecursively()) {
                    deletedBytes += size
                    deletedFiles += count
                }
            }
        }

        CacheClearResult(
            bytesCleared = deletedBytes,
            filesDeletedCount = deletedFiles,
            success = true
        )
    }

    /**
     * Creates an Intent to launch the system file manager pointed at the NEXA folder.
     */
    fun createOpenFolderIntent(): Intent {
        val root = nexaRootDir
        if (!root.exists()) {
            root.mkdirs()
        }

        val ctx = context ?: return Intent(Intent.ACTION_VIEW)

        // Try DocumentsContract / SAF View intent
        val intent = Intent(Intent.ACTION_VIEW).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION

            // Primary strategy: FileProvider content URI with resource/folder type
            try {
                val uri = FileProvider.getUriForFile(
                    ctx,
                    "${ctx.packageName}.fileprovider",
                    root
                )
                setDataAndType(uri, "resource/folder")
            } catch (e: Exception) {
                // Fallback: Documents provider URI
                val escapedPath = "primary:Android/media/${ctx.packageName}/NEXA"
                val uri = Uri.parse("content://com.android.externalstorage.documents/document/$escapedPath")
                setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
            }
        }

        return intent
    }

    /**
     * Copies the absolute path of the NEXA directory to the device clipboard.
     */
    fun copyPathToClipboard(context: Context? = null): String {
        val path = nexaRootDir.absolutePath
        val ctx = context ?: this.context
        if (ctx != null) {
            val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = ClipData.newPlainText("NEXA Storage Path", path)
            clipboard?.setPrimaryClip(clip)
        }
        return path
    }

    companion object {
        fun formatBytes(bytes: Long): String {
            if (bytes <= 0L) return "0 B"
            if (bytes < 1024L) return "$bytes B"
            val kb = bytes / 1024.0
            if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
            val mb = kb / 1024.0
            if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
            val gb = mb / 1024.0
            return String.format(Locale.US, "%.2f GB", gb)
        }
    }
}
