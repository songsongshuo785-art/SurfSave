package com.myAllVideoBrowser.util.telegram

import com.myAllVideoBrowser.data.local.room.entity.VideoInfo

enum class TelegramMediaAvailability {
    PLAYABLE,
    POSTER_ONLY
}

data class TelegramPostPreviewItem(
    val availability: TelegramMediaAvailability,
    val originalUrl: String,
    val mediaUrl: String = "",
    val thumbnail: String = "",
    val durationMs: Long = 0L
)

data class TelegramPostPreview(
    val postUrl: String,
    val channel: String = "",
    val description: String = "",
    val thumbnail: String = "",
    val items: List<TelegramPostPreviewItem> = emptyList()
) {
    val playableCount: Int
        get() = items.count { it.availability == TelegramMediaAvailability.PLAYABLE }

    val posterOnlyCount: Int
        get() = items.count { it.availability == TelegramMediaAvailability.POSTER_ONLY }
}

data class TelegramPostResolution(
    val preview: TelegramPostPreview,
    val videos: List<VideoInfo>
)

class TelegramPostUnavailableException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
