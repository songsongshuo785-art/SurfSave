package com.myAllVideoBrowser.contentblock

import java.net.URI
import java.util.Locale

object BrowserResourceTypeResolver {
    private val mediaExtensions = setOf(
        "mp4", "m4v", "webm", "mov", "flv", "3gp", "mkv", "mp3", "m4a", "aac",
        "ogg", "opus", "wav", "flac", "m3u8", "mpd", "m4s", "ts", "cmfv", "cmfa"
    )

    fun resolve(
        url: String,
        headers: Map<String, String>,
        isMainFrame: Boolean
    ): BrowserResourceType {
        if (isMainFrame) return BrowserResourceType.DOCUMENT

        val fetchDestination = header(headers, "Sec-Fetch-Dest").lowercase(Locale.US)
        when (fetchDestination) {
            "iframe", "frame" -> return BrowserResourceType.SUBDOCUMENT
            "script", "worker", "sharedworker", "serviceworker" -> return BrowserResourceType.SCRIPT
            "style" -> return BrowserResourceType.STYLESHEET
            "image" -> return BrowserResourceType.IMAGE
            "font" -> return BrowserResourceType.FONT
            "audio", "video", "track" -> return BrowserResourceType.MEDIA
            "empty" -> Unit
            else -> if (fetchDestination.isNotEmpty()) return BrowserResourceType.OTHER
        }

        val accept = header(headers, "Accept").lowercase(Locale.US)
        when {
            accept.contains("video/") || accept.contains("audio/") ||
                accept.contains("mpegurl") || accept.contains("dash+xml") ->
                return BrowserResourceType.MEDIA
            accept.contains("text/css") -> return BrowserResourceType.STYLESHEET
            accept.contains("javascript") -> return BrowserResourceType.SCRIPT
            accept.contains("image/") -> return BrowserResourceType.IMAGE
            accept.contains("font/") -> return BrowserResourceType.FONT
        }

        // Chromium uses `empty` for fetch(), XMLHttpRequest and API-style requests. Keep the
        // explicit Accept checks above this branch so media and other known types still win.
        if (fetchDestination == "empty") return BrowserResourceType.XML_HTTP_REQUEST

        val extension = runCatching {
            URI(url).path.orEmpty().substringAfterLast('.', "").lowercase(Locale.US)
        }.getOrDefault("")
        return when (extension) {
            in mediaExtensions -> BrowserResourceType.MEDIA
            "js", "mjs" -> BrowserResourceType.SCRIPT
            "css" -> BrowserResourceType.STYLESHEET
            "jpg", "jpeg", "png", "gif", "webp", "svg", "avif", "ico" ->
                BrowserResourceType.IMAGE
            "woff", "woff2", "ttf", "otf" -> BrowserResourceType.FONT
            else -> BrowserResourceType.UNKNOWN
        }
    }

    private fun header(headers: Map<String, String>, name: String): String {
        return headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value
            .orEmpty()
    }
}
