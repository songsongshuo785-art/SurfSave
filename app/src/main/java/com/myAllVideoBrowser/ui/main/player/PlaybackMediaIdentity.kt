package com.myAllVideoBrowser.ui.main.player

import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import java.util.Locale

/** Identifies the media independently from a selected quality or a temporary CDN signature. */
internal object PlaybackMediaIdentity {
    fun fromVideoInfo(videoInfo: VideoInfo): String {
        val page = PlaybackPositionKey.normalizeHttpUrl(videoInfo.originalUrl)
        val thumbnail = PlaybackPositionKey.normalizeHttpUrl(videoInfo.thumbnail)
        val title = videoInfo.title.trim().lowercase(Locale.US)
        if (thumbnail.isNotBlank()) {
            return "page=$page|thumbnail=$thumbnail|duration=${videoInfo.duration}"
        }
        val mediaUrls = videoInfo.formats.formats
            .mapNotNull { format ->
                format.manifestUrl?.takeIf { it.isNotBlank() }
                    ?: format.url?.takeIf { it.isNotBlank() }
            }
            .map(PlaybackPositionKey::normalizeHttpUrl)
            .filter(String::isNotBlank)
            .distinct()
            .sorted()
        if (mediaUrls.isNotEmpty()) {
            return "page=$page|media=${mediaUrls.joinToString(",")}"
        }
        if (title.isNotBlank() && videoInfo.duration > 0L) {
            return "page=$page|title=$title|duration=${videoInfo.duration}"
        }
        return "page=$page|title=$title"
    }
}
