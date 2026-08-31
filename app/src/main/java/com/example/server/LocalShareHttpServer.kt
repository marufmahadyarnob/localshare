package com.example.server

import android.content.Context
import com.example.model.SharedFile
import com.example.model.SharedText
import com.example.model.TransferProgress
import com.example.storage.FileDao
import com.example.storage.StorageManager
import com.example.storage.TextDao
import com.example.utils.MimeUtils
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.URLEncoder
import java.util.UUID

class LocalShareHttpServer(
    private val context: Context,
    private val fileDao: FileDao,
    private val textDao: TextDao,
    private val storageManager: StorageManager,
    port: Int = 8080
) : NanoHTTPD("0.0.0.0", port) {

    private val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _activeTransfer = MutableStateFlow<TransferProgress?>(null)
    val activeTransfer: StateFlow<TransferProgress?> = _activeTransfer.asStateFlow()

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        return try {
            when {
                // Serve Web Interface
                (uri == "/" || uri == "/index.html") && method == Method.GET -> {
                    serveWebPage()
                }

                // File API: List
                uri == "/api/files" && method == Method.GET -> {
                    serveFileList()
                }

                // File API: Upload
                uri == "/api/upload" && method == Method.POST -> {
                    handleFileUpload(session)
                }

                // File API: Download
                uri.startsWith("/api/files/") && uri.endsWith("/download") && method == Method.GET -> {
                    val fileId = uri.removePrefix("/api/files/").removeSuffix("/download")
                    serveFileContent(session, fileId, asAttachment = true)
                }

                // File API: Preview
                uri.startsWith("/api/files/") && uri.endsWith("/preview") && method == Method.GET -> {
                    val fileId = uri.removePrefix("/api/files/").removeSuffix("/preview")
                    serveFileContent(session, fileId, asAttachment = false)
                }

                // File API: Delete
                uri.startsWith("/api/files/") && method == Method.DELETE -> {
                    val fileId = uri.removePrefix("/api/files/")
                    handleFileDelete(fileId)
                }

                // Text API: List
                uri == "/api/text" && method == Method.GET -> {
                    serveTextList()
                }

                // Text API: Add
                uri == "/api/text" && method == Method.POST -> {
                    handleTextAdd(session)
                }

                // Text API: Download as .txt
                uri.startsWith("/api/text/") && uri.endsWith("/download") && method == Method.GET -> {
                    val textId = uri.removePrefix("/api/text/").removeSuffix("/download")
                    serveTextDownload(textId)
                }

                // Text API: Delete
                uri.startsWith("/api/text/") && method == Method.DELETE -> {
                    val textId = uri.removePrefix("/api/text/")
                    handleTextDelete(textId)
                }

                else -> {
                    newFixedLengthResponse(
                        Response.Status.NOT_FOUND,
                        "application/json",
                        """{"error":"Endpoint not found"}"""
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                """{"error":"${e.message?.replace("\"", "\\\"") ?: "Internal server error"}"}"""
            )
        }
    }

    private fun serveWebPage(): Response {
        return try {
            val htmlStream = context.assets.open("web/index.html")
            newChunkedResponse(Response.Status.OK, "text/html; charset=UTF-8", htmlStream)
        } catch (e: Exception) {
            e.printStackTrace()
            newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                "text/plain",
                "Web interface not found"
            )
        }
    }

    private fun serveFileList(): Response {
        val files = runBlocking { fileDao.getAllFilesList() }
        val jsonArray = JSONArray()
        for (f in files) {
            val obj = JSONObject().apply {
                put("id", f.id)
                put("name", f.name)
                put("size", f.size)
                put("mimeType", f.mimeType)
                put("addedTime", f.addedTime)
                put("isUploadedFromWeb", f.isUploadedFromWeb)
            }
            jsonArray.put(obj)
        }
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json; charset=UTF-8",
            jsonArray.toString()
        )
    }

    private fun handleFileUpload(session: IHTTPSession): Response {
        return try {
            val files = HashMap<String, String>()
            // NanoHTTPD streams multipart payload into temporary files on disk
            session.parseBody(files)

            val uploadedFiles = mutableListOf<SharedFile>()
            val params = session.parameters

            for ((key, tempPath) in files) {
                if (key == "postData") continue // Skip non-file form bodies
                
                val tempFile = File(tempPath)
                if (tempFile.exists() && tempFile.length() > 0) {
                    // Extract original filename from session parameters or query
                    var originalName: String? = params[key]?.firstOrNull()
                    if (originalName.isNullOrBlank()) {
                        originalName = params["filename"]?.firstOrNull()
                    }
                    if (originalName.isNullOrBlank()) {
                        originalName = session.parameters["filename"]?.firstOrNull() ?: session.parameters[key]?.firstOrNull()
                    }
                    if (originalName.isNullOrBlank()) {
                        originalName = "upload_${System.currentTimeMillis()}"
                    }

                    // Stream from temp file to dedicated LocalShare storage
                    val finalSharedFile = runBlocking {
                        FileInputStream(tempFile).use { fis ->
                            storageManager.saveStreamToDisk(
                                inputStream = fis,
                                originalName = originalName!!,
                                isUploadedFromWeb = true,
                                onProgress = { bytes, speed ->
                                    _activeTransfer.value = TransferProgress(
                                        id = "upload",
                                        fileName = originalName!!,
                                        bytesTransferred = bytes,
                                        totalBytes = tempFile.length(),
                                        isUpload = true,
                                        speedBytesPerSec = speed
                                    )
                                }
                            )
                        }
                    }

                    // Persist in Room DB
                    runBlocking { fileDao.insertFile(finalSharedFile) }
                    uploadedFiles.add(finalSharedFile)

                    // Clean up temporary file
                    tempFile.delete()
                }
            }

            _activeTransfer.value = null

            val resObj = JSONObject().apply {
                put("success", true)
                put("count", uploadedFiles.size)
                if (uploadedFiles.isNotEmpty()) {
                    val first = uploadedFiles.first()
                    put("file", JSONObject().apply {
                        put("id", first.id)
                        put("name", first.name)
                        put("size", first.size)
                    })
                }
            }

            newFixedLengthResponse(
                Response.Status.OK,
                "application/json; charset=UTF-8",
                resObj.toString()
            )
        } catch (e: Exception) {
            _activeTransfer.value = null
            e.printStackTrace()
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json; charset=UTF-8",
                """{"error":"Upload failed: ${e.message?.replace("\"", "\\\"")}"}"""
            )
        }
    }

    private fun serveFileContent(session: IHTTPSession, fileId: String, asAttachment: Boolean): Response {
        val sharedFile = runBlocking { fileDao.getFileById(fileId) }
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", """{"error":"File not found"}""")

        val file = File(sharedFile.localPath)
        if (!file.exists() || !storageManager.isPathSafe(file)) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", """{"error":"File not available"}""")
        }

        val fileLength = file.length()
        val mimeType = sharedFile.mimeType.ifBlank { "application/octet-stream" }
        val rangeHeader = session.headers["range"]

        // Encode filename for Content-Disposition (RFC 5987 UTF-8 support)
        val encodedName = URLEncoder.encode(sharedFile.name, "UTF-8").replace("+", "%20")
        val dispositionType = if (asAttachment) "attachment" else "inline"
        val contentDisposition = "$dispositionType; filename=\"${sharedFile.name.replace("\"", "")}\"; filename*=UTF-8''$encodedName"

        return if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            // Handle HTTP Range request (206 Partial Content)
            var startFrom = 0L
            var endAt = fileLength - 1
            val rangeSpec = rangeHeader.substring(6).trim()
            val minusIndex = rangeSpec.indexOf('-')
            if (minusIndex > 0) {
                try {
                    startFrom = rangeSpec.substring(0, minusIndex).toLong()
                    val endPart = rangeSpec.substring(minusIndex + 1)
                    if (endPart.isNotBlank()) {
                        endAt = endPart.toLong()
                    }
                } catch (e: NumberFormatException) {
                    // fall back
                }
            }

            if (startFrom >= fileLength) {
                val res = newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "")
                res.addHeader("Content-Range", "bytes */$fileLength")
                return res
            }

            if (endAt >= fileLength) {
                endAt = fileLength - 1
            }

            val contentLength = endAt - startFrom + 1
            val fis = FileInputStream(file)
            fis.skip(startFrom)

            val boundedStream = object : InputStream() {
                private var bytesLeft = contentLength
                override fun read(): Int {
                    if (bytesLeft <= 0) return -1
                    val byte = fis.read()
                    if (byte != -1) bytesLeft--
                    return byte
                }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (bytesLeft <= 0) return -1
                    val toRead = minOf(len.toLong(), bytesLeft).toInt()
                    val read = fis.read(b, off, toRead)
                    if (read > 0) bytesLeft -= read
                    return read
                }

                override fun close() {
                    fis.close()
                }
            }

            val response = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mimeType, boundedStream, contentLength)
            response.addHeader("Content-Range", "bytes $startFrom-$endAt/$fileLength")
            response.addHeader("Content-Disposition", contentDisposition)
            response.addHeader("Accept-Ranges", "bytes")
            response
        } else {
            // Full file stream (200 OK)
            val fis = FileInputStream(file)
            val response = newFixedLengthResponse(Response.Status.OK, mimeType, fis, fileLength)
            response.addHeader("Content-Disposition", contentDisposition)
            response.addHeader("Accept-Ranges", "bytes")
            response
        }
    }

    private fun handleFileDelete(fileId: String): Response {
        val sharedFile = runBlocking { fileDao.getFileById(fileId) }
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", """{"error":"File not found"}""")

        runBlocking {
            storageManager.deleteFile(sharedFile)
            fileDao.deleteFileById(fileId)
        }

        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=UTF-8", """{"success":true}""")
    }

    private fun serveTextList(): Response {
        val texts = runBlocking { textDao.getAllTextsList() }
        val jsonArray = JSONArray()
        for (t in texts) {
            val obj = JSONObject().apply {
                put("id", t.id)
                put("content", t.content)
                put("addedTime", t.addedTime)
                put("senderDevice", t.senderDevice)
            }
            jsonArray.put(obj)
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=UTF-8", jsonArray.toString())
    }

    private fun handleTextAdd(session: IHTTPSession): Response {
        val map = HashMap<String, String>()
        session.parseBody(map)
        val postData = map["postData"] ?: ""
        if (postData.isEmpty()) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", """{"error":"Empty payload"}""")
        }

        val json = JSONObject(postData)
        val content = json.optString("content", "").trim()
        if (content.isEmpty()) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", """{"error":"Text content is empty"}""")
        }

        val textItem = SharedText(
            id = UUID.randomUUID().toString(),
            content = content,
            addedTime = System.currentTimeMillis(),
            senderDevice = "Web"
        )

        runBlocking { textDao.insertText(textItem) }

        val res = JSONObject().apply {
            put("success", true)
            put("text", JSONObject().apply {
                put("id", textItem.id)
                put("content", textItem.content)
                put("addedTime", textItem.addedTime)
            })
        }

        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=UTF-8", res.toString())
    }

    private fun serveTextDownload(textId: String): Response {
        val textItem = runBlocking { textDao.getTextById(textId) }
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", """{"error":"Text not found"}""")

        val bytes = textItem.content.toByteArray(Charsets.UTF_8)
        val fileName = "Text_${textItem.addedTime}.txt"
        val response = newFixedLengthResponse(
            Response.Status.OK,
            "text/plain; charset=UTF-8",
            ByteArrayInputStream(bytes),
            bytes.size.toLong()
        )
        response.addHeader("Content-Disposition", "attachment; filename=\"$fileName\"")
        return response
    }

    private fun handleTextDelete(textId: String): Response {
        runBlocking { textDao.deleteTextById(textId) }
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=UTF-8", """{"success":true}""")
    }
}
