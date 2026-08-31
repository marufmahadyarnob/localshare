package com.example.utils

import android.webkit.MimeTypeMap
import java.util.Locale

object MimeUtils {

    fun getMimeType(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (extension.isEmpty()) return "application/octet-stream"

        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        if (!mime.isNullOrBlank()) return mime

        return when (extension) {
            "apk" -> "application/vnd.android.package-archive"
            "mkv" -> "video/x-matroska"
            "mov" -> "video/quicktime"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            "json" -> "application/json"
            "csv" -> "text/csv"
            "txt" -> "text/plain"
            "md" -> "text/markdown"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "rar" -> "application/x-rar-compressed"
            "7z" -> "application/x-7z-compressed"
            "tar" -> "application/x-tar"
            "gz" -> "application/gzip"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            "flac" -> "audio/flac"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "ppt" -> "application/vnd.ms-powerpoint"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            else -> "application/octet-stream"
        }
    }

    fun getCategory(mimeType: String): FileCategory {
        return when {
            mimeType.startsWith("image/") -> FileCategory.IMAGE
            mimeType.startsWith("video/") -> FileCategory.VIDEO
            mimeType.startsWith("audio/") -> FileCategory.AUDIO
            mimeType == "application/pdf" || mimeType.contains("document") || mimeType.contains("sheet") || mimeType.contains("presentation") || mimeType.startsWith("text/") -> FileCategory.DOCUMENT
            else -> FileCategory.GENERIC
        }
    }
}

enum class FileCategory {
    IMAGE,
    VIDEO,
    AUDIO,
    DOCUMENT,
    GENERIC
}
