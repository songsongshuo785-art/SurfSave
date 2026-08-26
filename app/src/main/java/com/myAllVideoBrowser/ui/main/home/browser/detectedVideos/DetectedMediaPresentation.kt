package com.myAllVideoBrowser.ui.main.home.browser.detectedVideos

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import java.net.URI
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

data class PageMediaMetadata(
    val pageUrl: String = "",
    val canonicalUrl: String = "",
    val contentUrls: List<String> = emptyList(),
    val durationMs: Long = 0L
)

object PageMediaMetadataParser {
    private val isoDurationPattern = Regex(
        """^P(?:(\d+(?:\.\d+)?)D)?(?:T(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?)?$""",
        RegexOption.IGNORE_CASE
    )

    fun parse(payload: String): PageMediaMetadata {
        if (payload.isBlank()) return PageMediaMetadata()

        val element = JsonParser.parseString(payload)
        require(element.isJsonObject) { "Page media metadata must be a JSON object." }
        val json = element.asJsonObject
        val urls = json.getAsJsonArray("contentUrls")
            ?.mapNotNull { value ->
                value.takeIf { it.isJsonPrimitive }
                    ?.asString
                    ?.trim()
                    ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            }
            ?.distinct()
            .orEmpty()

        val durationMs = sequenceOf(
            json.stringValue("duration"),
            json.stringValue("durationSeconds")
        ).mapNotNull(::parseDurationMillis)
            .firstOrNull { it > 0L }
            ?: 0L

        return PageMediaMetadata(
            pageUrl = json.stringValue("pageUrl").trim(),
            canonicalUrl = json.stringValue("canonicalUrl").trim(),
            contentUrls = urls,
            durationMs = durationMs
        )
    }

    fun parseDurationMillis(rawValue: String?): Long? {
        val value = rawValue?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        value.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }?.let {
            return (it * 1_000.0).roundToLong()
        }

        val match = isoDurationPattern.matchEntire(value) ?: return null
        val days = match.groupValues[1].toDoubleOrNull() ?: 0.0
        val hours = match.groupValues[2].toDoubleOrNull() ?: 0.0
        val minutes = match.groupValues[3].toDoubleOrNull() ?: 0.0
        val seconds = match.groupValues[4].toDoubleOrNull() ?: 0.0
        return (((days * 24.0 + hours) * 60.0 + minutes) * 60.0 + seconds)
            .times(1_000.0)
            .roundToLong()
    }

    private fun JsonObject.stringValue(name: String): String {
        val value = get(name) ?: return ""
        return value.takeUnless { it.isJsonNull }
            ?.takeIf { it.isJsonPrimitive }
            ?.asString
            .orEmpty()
    }
}

object DetectedMediaPresentation {
    private const val DURATION_MATCH_TOLERANCE_MS = 2_000L
    private val temporaryQueryKeys = setOf(
        "x-amz-signature",
        "x-amz-credential",
        "x-amz-date",
        "x-amz-expires",
        "x-amz-security-token",
        "signature",
        "sig",
        "token",
        "expires",
        "expire",
        "e",
        "st",
        "se",
        "sp",
        "sv",
        "hash",
        "key",
        "auth",
        "policy",
        "range"
    )

    fun sort(videos: List<VideoInfo>, metadata: PageMediaMetadata): List<VideoInfo> {
        return videos.withIndex()
            .sortedWith(
                compareBy<IndexedValue<VideoInfo>> { rank(it.value, metadata) }
                    .thenByDescending { indexed ->
                        detectedDurationMs(indexed.value)
                            .takeIf { rank(indexed.value, metadata) == 2 }
                            ?: 0L
                    }
                    .thenBy { it.index }
            )
            .map { it.value }
    }

    fun displayDurationMs(video: VideoInfo, metadata: PageMediaMetadata): Long {
        val detectedDuration = detectedDurationMs(video)
        if (detectedDuration > 0L) return detectedDuration

        return metadata.durationMs.takeIf {
            it > 0L && matchesDeclaredContentUrl(video, metadata)
        } ?: 0L
    }

    fun formatDuration(durationMs: Long, isLive: Boolean, liveText: String, unknownText: String): String {
        if (isLive) return liveText
        if (durationMs <= 0L) return unknownText

        val totalSeconds = durationMs / 1_000L
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    fun detectedDurationMs(video: VideoInfo): Long {
        val formatDuration = video.formats.formats.mapNotNull { it.duration }.maxOrNull() ?: 0L
        return maxOf(video.duration, formatDuration)
    }

    private fun rank(video: VideoInfo, metadata: PageMediaMetadata): Int {
        if (matchesDeclaredContentUrl(video, metadata)) return 0

        val duration = detectedDurationMs(video)
        if (
            duration > 0L &&
            metadata.durationMs > 0L &&
            abs(duration - metadata.durationMs) <= DURATION_MATCH_TOLERANCE_MS
        ) {
            return 1
        }
        if (duration > 0L) return 2
        if (video.isM3u8 || video.isMpd) return 3
        return 4
    }

    private fun matchesDeclaredContentUrl(video: VideoInfo, metadata: PageMediaMetadata): Boolean {
        if (metadata.contentUrls.isEmpty()) return false
        val declaredUrls = metadata.contentUrls.map(::normalizeMediaUrl).filter { it.isNotBlank() }.toSet()
        if (declaredUrls.isEmpty()) return false
        return candidateUrls(video)
            .map(::normalizeMediaUrl)
            .any { it.isNotBlank() && it in declaredUrls }
    }

    private fun candidateUrls(video: VideoInfo): Sequence<String> = sequence {
        video.downloadUrls.forEach { yield(it.url) }
        video.formats.formats.forEach { format ->
            listOf(format.url, format.manifestUrl, format.videoOnlyUrl, format.audioOnlyUrl)
                .filterNotNull()
                .forEach { yield(it) }
        }
    }

    private fun normalizeMediaUrl(rawUrl: String): String {
        val value = rawUrl.trim()
        if (value.isBlank()) return ""

        return runCatching {
            val uri = URI(value)
            val host = uri.host?.lowercase(Locale.US)?.removePrefix("www.").orEmpty()
            if (host.isBlank()) return@runCatching ""
            val path = uri.path.orEmpty().trimEnd('/')
            val stableQuery = uri.rawQuery
                ?.split("&")
                ?.filterNot { queryPart ->
                    val key = queryPart.substringBefore("=").lowercase(Locale.US)
                    key in temporaryQueryKeys ||
                        key.startsWith("utm_") ||
                        key.contains("token") ||
                        key.contains("signature") ||
                        key.contains("expires") ||
                        key.contains("expire")
                }
                ?.sorted()
                ?.joinToString("&")
                .orEmpty()
            if (stableQuery.isBlank()) "$host$path" else "$host$path?$stableQuery"
        }.getOrDefault("").lowercase(Locale.US)
    }
}

class ProtectedMediaPageTracker {
    data class Snapshot(
        val generation: Long,
        val pageUrl: String,
        val hasProtectedMedia: Boolean
    )

    private var generation = 0L
    private var pageUrl = ""
    private var hasProtectedMedia = false

    @Synchronized
    fun beginPage(url: String): Snapshot {
        generation += 1L
        pageUrl = url
        hasProtectedMedia = false
        return snapshot()
    }

    @Synchronized
    fun markProtectedMedia(expectedGeneration: Long): Snapshot? {
        if (expectedGeneration != generation) return null
        hasProtectedMedia = true
        return snapshot()
    }

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(generation, pageUrl, hasProtectedMedia)
}
