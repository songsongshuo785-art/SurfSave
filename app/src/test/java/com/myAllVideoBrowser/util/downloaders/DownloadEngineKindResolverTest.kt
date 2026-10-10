package com.myAllVideoBrowser.util.downloaders

import com.myAllVideoBrowser.data.local.room.entity.VideoFormatEntity
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.data.local.room.entity.VideFormatEntityList
import com.myAllVideoBrowser.util.media.DownloadStrategy
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadEngineKindResolverTest {

    @Test
    fun legacyFlagsKeepTheOldJudgementWhenNothingIsStamped() {
        assertEquals(
            DownloadEngineKind.REGULAR,
            DownloadEngineKindResolver.kindOf(videoInfo(isRegularDownload = true))
        )
        assertEquals(
            DownloadEngineKind.SUPERX,
            DownloadEngineKindResolver.kindOf(videoInfo(isDetectedBySuperX = true))
        )
        assertEquals(
            DownloadEngineKind.YTDLP,
            DownloadEngineKindResolver.kindOf(videoInfo())
        )
    }

    @Test
    fun stampedDirectFormatIsRegularEvenWhenLegacyFlagsSayOtherwise() {
        val video = videoInfo(
            isDetectedBySuperX = true,
            formats = listOf(format(downloadStrategy = DownloadStrategy.DIRECT_HTTP.name, url = "https://cdn/v.mp4"))
        )

        assertEquals(DownloadEngineKind.REGULAR, DownloadEngineKindResolver.kindOf(video))
    }

    @Test
    fun stampedManifestFormatsUseSuperX() {
        val hls = videoInfo(
            formats = listOf(
                format(
                    formatId = "hls-1080p-5000",
                    manifestUrl = "https://cdn/master.m3u8",
                    downloadStrategy = DownloadStrategy.HLS_MANIFEST.name
                )
            )
        )
        val dash = videoInfo(
            formats = listOf(
                format(
                    formatId = "mpd-720p-2000",
                    downloadStrategy = DownloadStrategy.DASH_MANIFEST.name
                )
            )
        )

        assertEquals(DownloadEngineKind.SUPERX, DownloadEngineKindResolver.kindOf(hls))
        assertEquals(DownloadEngineKind.SUPERX, DownloadEngineKindResolver.kindOf(dash))
    }

    @Test
    fun unknownStampFallsBackToLegacyFlags() {
        // 未知的持久化 strategy 不算盖章：旧 SuperX 任务不能被 resolver 的 legacy 顺序
        // （isRegularDownload → format 级证据 → YTDLP）误判成 YTDLP。
        val video = videoInfo(
            isDetectedBySuperX = true,
            formats = listOf(format(downloadStrategy = "DIRECT_FILE_V2", url = "https://cdn/v.mp4"))
        )

        assertEquals(DownloadEngineKind.SUPERX, DownloadEngineKindResolver.kindOf(video))
    }

    @Test
    fun stampedYtDlpFormatUsesYtDlp() {
        val video = videoInfo(
            formats = listOf(
                format(
                    formatId = "137",
                    downloadStrategy = DownloadStrategy.YTDLP_FORMAT.name,
                    extractorInputUrl = "https://page/watch?v=1"
                )
            )
        )

        assertEquals(DownloadEngineKind.YTDLP, DownloadEngineKindResolver.kindOf(video))
    }

    @Test
    fun mixedVideoUsesTheSelectedFormatStrategyWhenAnySiblingIsStamped() {
        // merge 后的 VideoInfo：一条盖章 HLS + 一条未盖章直链（isRegularDownload=true 的 legacy 证据）。
        val manifest = format(
            formatId = "hls-1080p-5000",
            manifestUrl = "https://cdn/master.m3u8",
            downloadStrategy = DownloadStrategy.HLS_MANIFEST.name
        )
        val direct = format(url = "https://cdn/v.mp4")
        val video = videoInfo(isRegularDownload = true, formats = listOf(manifest, direct))

        assertEquals(DownloadEngineKind.SUPERX, DownloadEngineKindResolver.kindOf(video, manifest))
        assertEquals(DownloadEngineKind.REGULAR, DownloadEngineKindResolver.kindOf(video, direct))
    }

    private fun videoInfo(
        isRegularDownload: Boolean = false,
        isDetectedBySuperX: Boolean = false,
        formats: List<VideoFormatEntity> = emptyList()
    ): VideoInfo = VideoInfo(
        id = "video",
        title = "Title",
        ext = "mp4",
        originalUrl = "https://page.example/watch?v=1",
        isRegularDownload = isRegularDownload,
        isDetectedBySuperX = isDetectedBySuperX,
        formats = VideFormatEntityList(formats)
    )

    private fun format(
        formatId: String? = null,
        url: String? = null,
        manifestUrl: String? = null,
        downloadStrategy: String? = null,
        extractorInputUrl: String? = null
    ): VideoFormatEntity = VideoFormatEntity(
        formatId = formatId,
        url = url,
        manifestUrl = manifestUrl,
        downloadStrategy = downloadStrategy,
        extractorInputUrl = extractorInputUrl
    )
}
