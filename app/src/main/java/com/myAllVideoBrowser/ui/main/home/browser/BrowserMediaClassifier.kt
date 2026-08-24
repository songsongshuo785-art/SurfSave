package com.myAllVideoBrowser.ui.main.home.browser

import java.util.Locale

/** 单一媒体分类入口：统一 URL、响应 Content-Type 与清单正文特征，避免检测路径各自漂移。 */
object BrowserMediaClassifier {
    private val videoExtensions = setOf("mp4", "m4v", "webm", "mov", "flv", "3gp", "mkv")
    private val audioExtensions = setOf("mp3", "m4a", "aac", "ogg", "opus", "wav", "flac")
    private val segmentExtensions = setOf("m4s", "ts")

    fun classify(
        url: String,
        contentType: String = "",
        manifestHint: String = ""
    ): ContentType {
        val normalizedType = contentType.substringBefore(';').trim().lowercase(Locale.US)
        val normalizedHint = manifestHint.trim().lowercase(Locale.US)
        val extension = pathExtension(url)

        if (extension in segmentExtensions || url.trim().startsWith("blob:", ignoreCase = true)) {
            return ContentType.OTHER
        }
        if (normalizedHint == "hls" || looksLikeHlsManifest(normalizedHint)) return ContentType.M3U8
        if (normalizedHint == "dash" || looksLikeDashManifest(normalizedHint)) return ContentType.MPD

        return when {
            extension == "m3u8" || normalizedType.contains("mpegurl") -> ContentType.M3U8
            extension == "mpd" || normalizedType.contains("dash+xml") -> ContentType.MPD
            extension in videoExtensions || normalizedType.startsWith("video/") ||
                normalizedType.contains("application/mp4") -> ContentType.VIDEO
            extension in audioExtensions || normalizedType.startsWith("audio/") -> ContentType.AUDIO
            else -> ContentType.OTHER
        }
    }

    fun isTextPlaylistCandidate(url: String): Boolean = pathExtension(url) == "txt"

    private fun pathExtension(url: String): String {
        val path = url.substringBefore('#').substringBefore('?').trim().lowercase(Locale.US)
        return path.substringAfterLast('/', "").substringAfterLast('.', "")
    }

    private fun looksLikeHlsManifest(value: String): Boolean =
        value.removePrefix("\uFEFF").trimStart().startsWith("#extm3u", ignoreCase = true)

    private fun looksLikeDashManifest(value: String): Boolean {
        val normalized = value.removePrefix("\uFEFF").trimStart()
        return Regex("""<(?:[a-z0-9_-]+:)?mpd(?:\s|>)""", RegexOption.IGNORE_CASE)
            .containsMatchIn(normalized)
    }
}
