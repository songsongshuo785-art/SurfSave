package com.myAllVideoBrowser.util.telegram

import com.myAllVideoBrowser.data.local.room.entity.VideoFormatEntity

object TelegramDownloadPolicy {
    fun prepareFormatForQueue(
        originalUrl: String,
        format: VideoFormatEntity
    ): VideoFormatEntity {
        if (TelegramPostUrl.parse(originalUrl) == null) return format

        return format.copy(
            url = null,
            manifestUrl = null,
            httpHeaders = null,
            videoOnlyUrl = null,
            audioOnlyUrl = null
        )
    }
}
