package com.myAllVideoBrowser.util.downloaders

import com.myAllVideoBrowser.data.local.room.entity.ProgressInfo
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

object DownloadFingerprint {
    private val volatileQueryKeys = setOf(
        "auth",
        "authorization",
        "e",
        "expire",
        "expires",
        "fbclid",
        "gclid",
        "igshid",
        "key",
        "msclkid",
        "pass",
        "password",
        "policy",
        "ref",
        "session",
        "sig",
        "signature",
        "source",
        "spm",
        "token",
        "utm_campaign",
        "utm_content",
        "utm_medium",
        "utm_source",
        "utm_term"
    )

    fun fromProgressInfo(progressInfo: ProgressInfo): String {
        return progressInfo.downloadFingerprint.ifBlank {
            fromVideoInfo(progressInfo.videoInfo)
        }
    }

    fun fromVideoInfo(videoInfo: VideoInfo): String {
        val normalizedUrls = collectUrls(videoInfo)
            .mapNotNull { normalizeUrl(it) }
            .distinct()
            .sorted()
            .ifEmpty { listOf("title:${videoInfo.title.trim().lowercase(Locale.US)}") }

        val selectedFormat = videoInfo.formats.formats.firstOrNull()
        val formatIdentity = listOf(
            selectedFormat?.formatId.orEmpty(),
            selectedFormat?.height?.takeIf { it > 0 }?.toString().orEmpty(),
            selectedFormat?.vcodec.orEmpty(),
            selectedFormat?.acodec.orEmpty(),
            videoInfo.ext
        ).joinToString(":")

        val raw = listOf(
            if (videoInfo.isRegularDownload) "regular" else "stream",
            if (videoInfo.isDetectedBySuperX) "superx" else "ytdlp",
            normalizedUrls.joinToString("|"),
            formatIdentity
        ).joinToString("#")

        return sha256(raw)
    }

    internal fun normalizeUrl(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isBlank()) {
            return null
        }

        return runCatching {
            val uri = URI(trimmed)
            val scheme = uri.scheme?.lowercase(Locale.US) ?: return@runCatching trimmed
            val host = uri.host?.lowercase(Locale.US)
            val port = when {
                uri.port == -1 -> -1
                scheme == "http" && uri.port == 80 -> -1
                scheme == "https" && uri.port == 443 -> -1
                else -> uri.port
            }
            val path = uri.rawPath.orEmpty().ifBlank { "/" }
            val query = normalizeQuery(uri.rawQuery)
            URI(
                scheme,
                uri.rawUserInfo,
                host,
                port,
                path,
                query,
                null
            ).toASCIIString()
        }.getOrElse {
            trimmed.substringBefore("#")
        }
    }

    private fun normalizeQuery(rawQuery: String?): String? {
        if (rawQuery.isNullOrBlank()) {
            return null
        }

        val kept = rawQuery.split("&")
            .mapNotNull { part ->
                val key = part.substringBefore("=", "").lowercase(Locale.US)
                if (key.isBlank() || key in volatileQueryKeys || key.startsWith("x-amz-")) {
                    null
                } else {
                    part
                }
            }
            .sorted()

        return kept.takeIf { it.isNotEmpty() }?.joinToString("&")
    }

    private fun collectUrls(videoInfo: VideoInfo): List<String> {
        val formatUrls = videoInfo.formats.formats.flatMap { format ->
            listOfNotNull(
                format.url,
                format.manifestUrl,
                format.videoOnlyUrl,
                format.audioOnlyUrl
            )
        }
        val requestUrls = videoInfo.downloadUrls.map { it.url }
        return formatUrls + requestUrls + listOf(videoInfo.originalUrl)
    }

    private fun sha256(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
