package com.example.storage

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.model.SharedFile
import com.example.utils.MimeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

class StorageManager(private val context: Context) {

    val sharedDirectory: File by lazy {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "shared_files")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        dir
    }

    /**
     * Sanitizes filename: removes path traversal tokens while keeping Unicode characters intact.
     */
    fun sanitizeFilename(rawName: String?): String {
        if (rawName.isNullOrBlank()) {
            return "shared_file_${System.currentTimeMillis()}"
        }
        var cleaned = rawName.trim()
            .replace("\\", "/")
            .substringAfterLast("/")
            .replace(Regex("[\\r\\n\\t\\x00]"), "")

        // Remove leading dots or slashes
        cleaned = cleaned.trimStart('.', ' ')
        if (cleaned.isBlank()) {
            cleaned = "file_${System.currentTimeMillis()}"
        }
        return cleaned
    }

    /**
     * Generates a safe unique file destination, avoiding overwriting existing files:
     * photo.jpg -> photo (1).jpg -> photo (2).jpg
     */
    fun getUniqueDestinationFile(desiredName: String): File {
        val sanitized = sanitizeFilename(desiredName)
        val baseName = sanitized.substringBeforeLast('.', sanitized)
        val extension = if (sanitized.contains('.')) "." + sanitized.substringAfterLast('.') else ""

        var candidate = File(sharedDirectory, sanitized)
        var counter = 1
        while (candidate.exists()) {
            candidate = File(sharedDirectory, "$baseName ($counter)$extension")
            counter++
        }
        return candidate
    }

    /**
     * Verifies that the file path is strictly within the shared directory (Path Traversal Protection).
     */
    fun isPathSafe(file: File): Boolean {
        return try {
            val canonicalTarget = file.canonicalPath
            val canonicalShared = sharedDirectory.canonicalPath
            canonicalTarget.startsWith(canonicalShared)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Saves an incoming InputStream to disk in chunks without loading entire content into RAM.
     */
    suspend fun saveStreamToDisk(
        inputStream: InputStream,
        originalName: String,
        isUploadedFromWeb: Boolean = true,
        onProgress: ((bytesWritten: Long, speedBps: Long) -> Unit)? = null
    ): SharedFile = withContext(Dispatchers.IO) {
        val destFile = getUniqueDestinationFile(originalName)
        if (!isPathSafe(destFile)) {
            throw SecurityException("Path traversal attempt detected")
        }

        var bytesWritten = 0L
        val buffer = ByteArray(64 * 1024) // 64 KB buffer for streaming
        var lastTime = System.currentTimeMillis()
        var lastBytes = 0L

        FileOutputStream(destFile).use { outputStream ->
            var read: Int
            while (inputStream.read(buffer).also { read = it } != -1) {
                outputStream.write(buffer, 0, read)
                bytesWritten += read

                val now = System.currentTimeMillis()
                if (now - lastTime >= 300) {
                    val durationSec = (now - lastTime) / 1000.0
                    val speed = if (durationSec > 0) ((bytesWritten - lastBytes) / durationSec).toLong() else 0L
                    onProgress?.invoke(bytesWritten, speed)
                    lastTime = now
                    lastBytes = bytesWritten
                }
            }
            outputStream.flush()
        }

        val fileId = UUID.randomUUID().toString()
        val mimeType = MimeUtils.getMimeType(destFile.name)

        SharedFile(
            id = fileId,
            name = destFile.name,
            size = destFile.length(),
            mimeType = mimeType,
            addedTime = System.currentTimeMillis(),
            localPath = destFile.absolutePath,
            isUploadedFromWeb = isUploadedFromWeb
        )
    }

    /**
     * Imports a file selected on Android via ContentResolver URI into the shared storage directory.
     */
    suspend fun importFromUri(uri: Uri): SharedFile? = withContext(Dispatchers.IO) {
        try {
            var fileName: String? = null
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1 && cursor.moveToFirst()) {
                        fileName = cursor.getString(nameIndex)
                    }
                }
            } catch (e: Exception) {
                // Ignore query failure, will resolve from URI path or MIME type
            }

            if (fileName.isNullOrBlank()) {
                val pathSegment = uri.lastPathSegment
                if (!pathSegment.isNullOrBlank()) {
                    fileName = pathSegment.substringAfterLast('/')
                }
            }

            val mimeType = try { context.contentResolver.getType(uri) } catch (e: Exception) { null }
            val extensionFromMime = mimeType?.let { android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }

            if (fileName.isNullOrBlank()) {
                val ext = if (!extensionFromMime.isNullOrBlank()) ".$extensionFromMime" else ""
                fileName = "shared_${System.currentTimeMillis()}$ext"
            } else if (!fileName!!.contains('.') && !extensionFromMime.isNullOrBlank()) {
                fileName = "$fileName.$extensionFromMime"
            }

            val inputStream = context.contentResolver.openInputStream(uri) ?: return@withContext null
            inputStream.use { stream ->
                saveStreamToDisk(
                    inputStream = stream,
                    originalName = fileName!!,
                    isUploadedFromWeb = false
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Safely deletes a file from disk if it is located inside the shared directory.
     */
    suspend fun deleteFile(sharedFile: SharedFile): Boolean = withContext(Dispatchers.IO) {
        try {
            val file = File(sharedFile.localPath)
            if (isPathSafe(file) && file.exists()) {
                file.delete()
            } else {
                false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Obtains a secure content:// Uri for opening or sharing a file.
     */
    fun getContentUri(sharedFile: SharedFile): Uri? {
        return try {
            val file = File(sharedFile.localPath)
            if (file.exists() && isPathSafe(file)) {
                androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Exports a text snippet to a .txt file in the shared directory.
     */
    suspend fun exportTextToFile(content: String): SharedFile = withContext(Dispatchers.IO) {
        val fileName = "Text_${System.currentTimeMillis()}.txt"
        val bytes = content.toByteArray(Charsets.UTF_8)
        val destFile = getUniqueDestinationFile(fileName)
        destFile.writeBytes(bytes)
        SharedFile(
            id = UUID.randomUUID().toString(),
            name = destFile.name,
            size = destFile.length(),
            mimeType = "text/plain",
            addedTime = System.currentTimeMillis(),
            localPath = destFile.absolutePath,
            isUploadedFromWeb = false
        )
    }

    /**
     * Streams a file or partial byte range to an output stream.
     */
    suspend fun streamFileRange(
        file: File,
        outputStream: OutputStream,
        startOffset: Long,
        length: Long,
        onProgress: ((bytesRead: Long) -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        if (!isPathSafe(file) || !file.exists()) {
            throw SecurityException("Invalid file access")
        }

        FileInputStream(file).use { fis ->
            if (startOffset > 0) {
                var skipped = 0L
                while (skipped < startOffset) {
                    val s = fis.skip(startOffset - skipped)
                    if (s <= 0) break
                    skipped += s
                }
            }

            val buffer = ByteArray(64 * 1024)
            var remaining = length
            var totalSent = 0L

            while (remaining > 0) {
                val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                val bytesRead = fis.read(buffer, 0, toRead)
                if (bytesRead == -1) break

                outputStream.write(buffer, 0, bytesRead)
                remaining -= bytesRead
                totalSent += bytesRead
                onProgress?.invoke(totalSent)
            }
            outputStream.flush()
        }
    }
}
