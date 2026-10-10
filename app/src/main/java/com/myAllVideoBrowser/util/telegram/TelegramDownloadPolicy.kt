package com.myAllVideoBrowser.util.telegram

import com.myAllVideoBrowser.data.local.room.entity.VideoFormatEntity
import com.myAllVideoBrowser.util.media.DownloadStrategy

object TelegramDownloadPolicy {
    /**
     * Telegram 帖子链接必须由 yt-dlp 对帖子页面重新解析，因此这里会清空所有具体 URL 字段。
     *
     * 关键点：清空 URL 的同时必须**显式改写成 [DownloadStrategy.YTDLP_FORMAT]** 并记录
     * `extractorInputUrl = originalUrl`。否则调用方若仍按“清空前的 URL”推断策略，会得出
     * DIRECT_HTTP，把这里的转换覆盖回去（评审确认的时序问题）。
     */
    fun prepareFormatForQueue(
        originalUrl: String,
        format: VideoFormatEntity
    ): VideoFormatEntity {
        if (TelegramPostUrl.parse(originalUrl) == null) return format

        return format.copy(
            url = null,
            manifestUrl = null,
            httpHeaders = null,
            manifestRequestUrl = null,
            manifestRequestHeaders = null,
            videoOnlyUrl = null,
            audioOnlyUrl = null,
            downloadStrategy = DownloadStrategy.YTDLP_FORMAT.name,
            extractorInputUrl = originalUrl,
            sourcePageUrl = format.sourcePageUrl ?: originalUrl
        )
    }
}
