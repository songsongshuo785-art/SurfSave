package com.myAllVideoBrowser.util

import android.webkit.MimeTypeMap
import java.util.Locale

/** 统一的 MIME 解析：先信已声明的类型，再按扩展名兜底。 */
object MediaMimeTypes {
    // 部分设备/自定义 ROM 的 MimeTypeMap 缺少常见扩展名，内置一份最小兜底。
    private val FALLBACKS = mapOf(
        "mp4" to "video/mp4",
        "m4v" to "video/mp4",
        "webm" to "video/webm",
        "mkv" to "video/x-matroska",
        "mov" to "video/quicktime",
        "mp3" to "audio/mpeg",
        "m4a" to "audio/mp4",
        "aac" to "audio/aac",
        "opus" to "audio/opus",
        "ogg" to "audio/ogg",
        "wav" to "audio/wav",
        "flac" to "audio/flac",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
        "webp" to "image/webp",
        "gif" to "image/gif",
    )

    fun resolve(declaredMimeType: String, fileName: String): String {
        if (declaredMimeType.isNotBlank()) return declaredMimeType
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.US)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: FALLBACKS[extension]
            ?: "*/*"
    }
}
