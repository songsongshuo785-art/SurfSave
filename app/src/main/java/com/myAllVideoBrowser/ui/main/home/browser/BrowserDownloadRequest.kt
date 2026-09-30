package com.myAllVideoBrowser.ui.main.home.browser

import com.myAllVideoBrowser.data.local.room.entity.DownloadRequestData
import com.myAllVideoBrowser.data.local.room.entity.VideFormatEntityList
import com.myAllVideoBrowser.data.local.room.entity.VideoFormatEntity
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.util.FileNameCleaner
import java.net.URI
import java.util.Locale

/** WebView 明确交给宿主处理的下载请求；媒体与普通附件由协调器分流。 */
data class BrowserDownloadRequest(
    val url: String,
    val pageUrl: String?,
    val headers: Map<String, String>,
    val contentDisposition: String?,
    val mimeType: String?,
    val contentLength: Long,
    val suggestedFileName: String?
) {
    fun mediaType(): ContentType = BrowserMediaClassifier.classify(
        url = url,
        contentType = mimeType.orEmpty(),
        contentDisposition = contentDisposition.orEmpty()
    )

    fun isSupportedMedia(): Boolean = mediaType() != ContentType.OTHER

    fun isHttpRequest(): Boolean =
        url.startsWith("http://", ignoreCase = true) ||
            url.startsWith("https://", ignoreCase = true)

    fun suggestedTitle(): String? {
        val fileName = preferredFileName().orEmpty()
        if (fileName.isBlank()) return null
        return fileName.substringBeforeLast('.', fileName).trim().takeIf { it.isNotBlank() }
    }

    fun suggestedExtension(): String? {
        val expectedType = mediaType()
        val declaredMimeExtension = EXTENSION_BY_MIME[declaredMimeType()]
        if (declaredMimeExtension != null &&
            BrowserMediaClassifier.classify("https://download.invalid/file.$declaredMimeExtension") == expectedType
        ) {
            return declaredMimeExtension
        }

        val extension = preferredFileName()
            ?.substringAfterLast('.', "")
            ?.trim()
            ?.lowercase(Locale.US)
            .orEmpty()
        if (extension.isBlank()) return null

        return extension.takeIf {
            BrowserMediaClassifier.classify("https://download.invalid/file.$extension") == expectedType
        }
    }

    fun safeFileName(): String {
        val rawName = preferredFileName() ?: "download"

        val cleaned = rawName
            .replace(UNSAFE_FILE_NAME_CHARS, "_")
            .filterNot(Char::isISOControl)
            .trim()
            .trim('.', ' ')
            .ifBlank { "download" }
            .let(::appendDeclaredMimeExtensionWhenMissing)

        if (cleaned.length <= MAX_ATTACHMENT_FILE_NAME_LENGTH) return cleaned

        val extension = cleaned.substringAfterLast('.', "")
            .takeIf { it.length in 1..MAX_PRESERVED_EXTENSION_LENGTH }
        if (extension == null) return cleaned.take(MAX_ATTACHMENT_FILE_NAME_LENGTH)

        val suffix = ".$extension"
        return cleaned.substringBeforeLast('.')
            .take((MAX_ATTACHMENT_FILE_NAME_LENGTH - suffix.length).coerceAtLeast(1)) + suffix
    }

    fun normalizedMimeType(): String? {
        val declared = declaredMimeType()
        if (declared != null && declared != "application/octet-stream") return declared

        return MIME_BY_EXTENSION[safeFileName().substringAfterLast('.', "").lowercase(Locale.US)]
            ?: declared
    }

    fun allowedDownloadHeaders(): Map<String, String> {
        return headers.entries.mapNotNull { (name, value) ->
            val canonicalName = SYSTEM_DOWNLOAD_HEADERS[name.lowercase(Locale.US)] ?: return@mapNotNull null
            val safeValue = value.trim().takeIf {
                it.isNotBlank() && !it.contains('\r') && !it.contains('\n')
            } ?: return@mapNotNull null
            canonicalName to safeValue
        }.toMap(linkedMapOf())
    }

    /**
     * Applies authoritative response metadata discovered after the WebView
     * reported a candidate. The original URL and browser headers are retained
     * so signed URLs and referrer-gated downloads continue to work.
     */
    fun withResponseMetadata(
        responseMimeType: String?,
        responseContentDisposition: String?,
        responseContentLength: Long
    ): BrowserDownloadRequest {
        val normalizedMime = responseMimeType
            ?.substringBefore(';')
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        return copy(
            mimeType = normalizedMime ?: mimeType,
            contentDisposition = responseContentDisposition
                ?.takeIf { it.isNotBlank() }
                ?: contentDisposition,
            contentLength = responseContentLength.takeIf { it > 0L } ?: contentLength
        )
    }

    fun toDirectMediaVideoInfo(fallbackTitle: String? = null): VideoInfo? {
        val type = mediaType()
        if (!isHttpRequest() ||
            (type != ContentType.VIDEO && type != ContentType.AUDIO && type != ContentType.IMAGE)
        ) {
            return null
        }

        val extension = suggestedExtension() ?: defaultMediaExtension(type)
        val rawTitle = suggestedTitle()
            ?: fallbackTitle?.trim()?.takeIf { it.isNotBlank() }
            ?: runCatching { URI(url).host }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: "download"
        val safeTitle = FileNameCleaner.cleanFileName(rawTitle).ifBlank { "download" }
        val downloadHeaders = allowedDownloadHeaders()
        val requestData = DownloadRequestData(url = url, headers = downloadHeaders)
        val format = VideoFormatEntity(
            formatId = "direct",
            format = when (type) {
                ContentType.AUDIO -> "audio"
                ContentType.IMAGE -> "image"
                else -> "video"
            },
            ext = extension,
            url = url,
            httpHeaders = downloadHeaders,
            fileSize = contentLength.coerceAtLeast(0L)
        )

        return VideoInfo(
            downloadUrls = listOf(requestData),
            title = safeTitle,
            ext = extension,
            originalUrl = pageUrl?.takeIf { it.isNotBlank() } ?: url,
            formats = VideFormatEntityList(listOf(format)),
            isRegularDownload = true
        )
    }

    private fun defaultMediaExtension(type: ContentType): String {
        val normalizedMime = normalizedMimeType().orEmpty()
        return when {
            normalizedMime.contains("webm") -> "webm"
            normalizedMime.contains("quicktime") -> "mov"
            normalizedMime.contains("audio/mp4") -> "m4a"
            normalizedMime.contains("mpeg") && type == ContentType.AUDIO -> "mp3"
            type == ContentType.AUDIO -> "m4a"
            type == ContentType.IMAGE && normalizedMime.contains("png") -> "png"
            type == ContentType.IMAGE && normalizedMime.contains("gif") -> "gif"
            type == ContentType.IMAGE && normalizedMime.contains("webp") -> "webp"
            type == ContentType.IMAGE && normalizedMime.contains("avif") -> "avif"
            type == ContentType.IMAGE && normalizedMime.contains("heic") -> "heic"
            type == ContentType.IMAGE && normalizedMime.contains("heif") -> "heif"
            type == ContentType.IMAGE && normalizedMime.contains("bmp") -> "bmp"
            type == ContentType.IMAGE && normalizedMime.contains("svg") -> "svg"
            type == ContentType.IMAGE -> "jpg"
            else -> "mp4"
        }
    }

    private fun preferredFileName(): String? {
        return BrowserContentDisposition.fileName(contentDisposition)
            ?.takeIf { it.isNotBlank() }
            ?: suggestedFileName?.trim()?.takeIf { it.isNotBlank() }
            ?: url.substringBefore('#')
                .substringBefore('?')
                .substringAfterLast('/')
                .trim()
                .takeIf { it.isNotBlank() }
    }

    private fun appendDeclaredMimeExtensionWhenMissing(fileName: String): String {
        val currentExtension = fileName.substringAfterLast('.', "").lowercase(Locale.US)
        if (currentExtension in MIME_BY_EXTENSION) return fileName

        val expectedExtension = EXTENSION_BY_MIME[declaredMimeType()] ?: return fileName
        return "$fileName.$expectedExtension"
    }

    private fun declaredMimeType(): String? = mimeType
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase(Locale.US)
        ?.takeIf { it.isNotBlank() }

    companion object {
        private const val MAX_ATTACHMENT_FILE_NAME_LENGTH = 180
        private const val MAX_PRESERVED_EXTENSION_LENGTH = 16
        private val UNSAFE_FILE_NAME_CHARS = Regex("""[\\/:*?\"<>|]""")
        private val SYSTEM_DOWNLOAD_HEADERS = mapOf(
            "user-agent" to "User-Agent",
            "referer" to "Referer",
            "cookie" to "Cookie"
        )
        private val MIME_BY_EXTENSION = mapOf(
            "apk" to "application/vnd.android.package-archive",
            "pdf" to "application/pdf",
            "zip" to "application/zip",
            "rar" to "application/vnd.rar",
            "7z" to "application/x-7z-compressed",
            "txt" to "text/plain",
            "mp4" to "video/mp4",
            "webm" to "video/webm",
            "mp3" to "audio/mpeg",
            "m4a" to "audio/mp4",
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "png" to "image/png",
            "gif" to "image/gif",
            "webp" to "image/webp",
            "avif" to "image/avif",
            "heic" to "image/heic",
            "heif" to "image/heif",
            "bmp" to "image/bmp",
            "svg" to "image/svg+xml"
        )
        // Several extensions can share one MIME type (image/jpeg -> jpg|jpeg).
        // Prefer the shortest canonical extension ("jpg", not "jpeg") so the
        // generated name stays consistent with defaultMediaExtension().
        private val EXTENSION_BY_MIME: Map<String, String> =
            linkedMapOf<String, String>().apply {
                MIME_BY_EXTENSION.entries
                    .sortedBy { (extension) -> extension.length }
                    .forEach { (extension, mimeType) -> putIfAbsent(mimeType, extension) }
            }
    }
}
