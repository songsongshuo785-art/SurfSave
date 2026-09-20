package com.myAllVideoBrowser.ui.main.player

import java.net.URI
import java.util.Locale

/** Stable identity for a playback position. Temporary CDN signatures are never used as the key. */
internal object PlaybackPositionKey {
    fun forMedia(
        source: String,
        mediaUrl: String,
        mediaIdentity: String,
        mediaKind: PlaybackMediaKind
    ): String {
        if (source == VideoPlayerFragment.SOURCE_VIDEO_LIBRARY) {
            return "local:${mediaUrl.trim()}"
        }

        val identity = mediaIdentity.trim()
        if (identity.isNotBlank()) {
            return "online:$identity"
        }

        return "online-media:${normalizeHttpUrl(mediaUrl).ifBlank { mediaUrl.trim() }}"
    }

    fun normalizeHttpUrl(raw: String): String {
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return raw.trim()
        val scheme = uri.scheme?.lowercase(Locale.US) ?: return raw.trim()
        if (scheme != "http" && scheme != "https") return raw.trim()

        val host = uri.host?.lowercase(Locale.US) ?: return raw.trim()
        val path = uri.rawPath.orEmpty().ifBlank { "/" }
        val query = uri.rawQuery
            ?.split('&')
            ?.mapNotNull { part ->
                val name = part.substringBefore('=', part).lowercase(Locale.US)
                if (name in VOLATILE_QUERY_NAMES) return@mapNotNull null
                part.takeIf(String::isNotBlank)
            }
            ?.joinToString("&")
            ?.takeIf(String::isNotBlank)
        return buildString {
            append(scheme).append("://").append(host)
            if (uri.port != -1) append(':').append(uri.port)
            append(path)
            if (query != null) append('?').append(query)
        }
    }

    private val VOLATILE_QUERY_NAMES = setOf(
        "token", "sig", "signature", "expires", "exp", "auth", "authorization",
        "hdnea", "session", "sessionid", "key", "md5", "hash", "policy"
    )
}
