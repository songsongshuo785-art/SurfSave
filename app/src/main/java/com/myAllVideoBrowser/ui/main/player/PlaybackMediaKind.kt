package com.myAllVideoBrowser.ui.main.player

import com.myAllVideoBrowser.data.local.room.entity.VideoFormatEntity
import java.util.Locale

enum class PlaybackMediaKind {
    AUTO,
    HLS,
    DASH;

    companion object {
        fun fromSerialized(value: String?): PlaybackMediaKind = entries.firstOrNull {
            it.name.equals(value, ignoreCase = true)
        } ?: AUTO
    }
}

object PlaybackMediaKindResolver {
    fun resolve(format: VideoFormatEntity): PlaybackMediaKind {
        if (format.isM3u8) return PlaybackMediaKind.HLS
        if (format.isMpd) return PlaybackMediaKind.DASH

        val descriptor = sequenceOf(
            format.protocol,
            format.formatId,
            format.format,
            format.manifestUrl,
            format.url
        ).filterNotNull().joinToString(" ").lowercase(Locale.US)

        return when {
            descriptor.contains("m3u8") || descriptor.contains(" hls") -> PlaybackMediaKind.HLS
            descriptor.contains("dash") || descriptor.contains(".mpd") -> PlaybackMediaKind.DASH
            else -> PlaybackMediaKind.AUTO
        }
    }
}

object PlaybackFormatRefreshMatcher {
    fun find(
        formats: List<VideoFormatEntity>,
        formatId: String,
        height: Int,
        mediaKind: PlaybackMediaKind
    ): VideoFormatEntity? {
        formats.firstOrNull {
            formatId.isNotBlank() && it.formatId == formatId && !it.url.isNullOrBlank()
        }?.let { return it }

        val matchingKind = formats.filter {
            !it.url.isNullOrBlank() &&
                (mediaKind == PlaybackMediaKind.AUTO || PlaybackMediaKindResolver.resolve(it) == mediaKind)
        }
        return matchingKind.firstOrNull { height > 0 && it.height == height }
            ?: matchingKind.maxByOrNull { it.height }
    }
}

data class PlaybackRefreshPlan(
    val useSuperXDetector: Boolean,
    val isHls: Boolean,
    val isDash: Boolean
)

object PlaybackRefreshPlanResolver {
    fun resolve(
        detectedBySuperX: Boolean,
        mediaKind: PlaybackMediaKind
    ): PlaybackRefreshPlan = PlaybackRefreshPlan(
        useSuperXDetector = detectedBySuperX,
        isHls = mediaKind == PlaybackMediaKind.HLS,
        isDash = mediaKind == PlaybackMediaKind.DASH
    )
}

enum class PlaybackFailureKind {
    HTTP_AUTHORIZATION,
    MANIFEST,
    DECODER,
    DRM,
    NETWORK,
    UNKNOWN
}

object PlaybackFailureClassifier {
    fun classify(errorCodeName: String, httpResponseCode: Int?): PlaybackFailureKind {
        if (httpResponseCode == 401 || httpResponseCode == 403) {
            return PlaybackFailureKind.HTTP_AUTHORIZATION
        }
        val normalized = errorCodeName.uppercase(Locale.US)
        return when {
            "DRM" in normalized -> PlaybackFailureKind.DRM
            "DECODER" in normalized || "DECODING" in normalized -> PlaybackFailureKind.DECODER
            "PARSING_MANIFEST" in normalized -> PlaybackFailureKind.MANIFEST
            "IO_NETWORK" in normalized || "TIMEOUT" in normalized ||
                "CONNECTION_FAILED" in normalized -> PlaybackFailureKind.NETWORK
            else -> PlaybackFailureKind.UNKNOWN
        }
    }
}
