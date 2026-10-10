package com.myAllVideoBrowser.util.media

import com.myAllVideoBrowser.data.local.room.entity.VideoFormatEntity
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.data.local.room.entity.VideFormatEntityList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadStrategyResolverTest {

    @Test
    fun explicitStrategyWinsOverLegacyFlags() {
        val video = videoInfo(isRegularDownload = true)
        val format = format(downloadStrategy = DownloadStrategy.YTDLP_FORMAT.name, extractorInputUrl = "https://page/watch?v=1")

        val resolution = DownloadStrategyResolver.resolve(video, format)

        assertEquals(DownloadStrategy.YTDLP_FORMAT, resolution.strategy)
        assertEquals(DownloadStrategyProvenance.EXPLICIT, resolution.provenance)
        assertEquals("https://page/watch?v=1", resolution.extractorInputUrl)
        assertTrue(resolution.isValid)
    }

    @Test
    fun explicitYtDlpWithoutExtractorInputIsInvalidAndDoesNotFallBackToOriginalUrl() {
        val video = videoInfo()
        val format = format(downloadStrategy = DownloadStrategy.YTDLP_FORMAT.name)

        val resolution = DownloadStrategyResolver.resolve(video, format)

        assertFalse(resolution.isValid)
        assertNull(resolution.extractorInputUrl)
    }

    @Test
    fun unknownPersistedStrategyFallsBackToLegacyInference() {
        val video = videoInfo(isRegularDownload = true)
        val format = format(downloadStrategy = "FUTURE_STRATEGY")

        val resolution = DownloadStrategyResolver.resolve(video, format)

        assertEquals(DownloadStrategy.DIRECT_HTTP, resolution.strategy)
        assertEquals(DownloadStrategyProvenance.LEGACY, resolution.provenance)
        assertTrue(resolution.isValid)
    }

    @Test
    fun legacyRegularDownloadBecomesDirectHttp() {
        val resolution = DownloadStrategyResolver.resolve(
            videoInfo(isRegularDownload = true),
            format()
        )

        assertEquals(DownloadStrategy.DIRECT_HTTP, resolution.strategy)
        assertEquals(DownloadStrategyProvenance.LEGACY, resolution.provenance)
    }

    @Test
    fun formatLocalSuperXEvidenceBecomesManifestStrategyWithoutVideoInfoFlag() {
        val video = videoInfo(isDetectedBySuperX = false)
        val format = format(formatId = "hls-1080p-5000", manifestUrl = "https://cdn/master.m3u8")

        val resolution = DownloadStrategyResolver.resolve(video, format)

        assertEquals(DownloadStrategy.HLS_MANIFEST, resolution.strategy)
        assertEquals(DownloadStrategyProvenance.LEGACY, resolution.provenance)
    }

    @Test
    fun mergedYtDlpFormatIsNotPollutedByVideoInfoLevelSuperXFlag() {
        // mergeDuplicateVideoInfo 会产生 isDetectedBySuperX=true 的 VideoInfo，
        // 但同一对象里也可能带着 yt-dlp 的 format：必须按 format 局部证据判断。
        val video = videoInfo(isDetectedBySuperX = true)
        val format = format(formatId = "137", url = "https://video.example/137.mp4")

        val resolution = DownloadStrategyResolver.resolve(video, format)

        assertEquals(DownloadStrategy.YTDLP_FORMAT, resolution.strategy)
        assertEquals("https://page.example/watch?v=1", resolution.extractorInputUrl)
    }

    @Test
    fun dashFormatIdResolvesToDashManifest() {
        val resolution = DownloadStrategyResolver.resolve(
            videoInfo(),
            format(formatId = "mpd-720p-2000", protocol = "http_dash_segments")
        )

        assertEquals(DownloadStrategy.DASH_MANIFEST, resolution.strategy)
    }

    private fun videoInfo(
        isRegularDownload: Boolean = false,
        isDetectedBySuperX: Boolean = false
    ): VideoInfo = VideoInfo(
        id = "video",
        title = "Title",
        ext = "mp4",
        originalUrl = "https://page.example/watch?v=1",
        isRegularDownload = isRegularDownload,
        isDetectedBySuperX = isDetectedBySuperX,
        formats = VideFormatEntityList(emptyList())
    )

    private fun format(
        formatId: String? = null,
        url: String? = "https://cdn.example/video.mp4",
        manifestUrl: String? = null,
        protocol: String? = null,
        downloadStrategy: String? = null,
        extractorInputUrl: String? = null
    ): VideoFormatEntity = VideoFormatEntity(
        formatId = formatId,
        url = url,
        manifestUrl = manifestUrl,
        protocol = protocol,
        downloadStrategy = downloadStrategy,
        extractorInputUrl = extractorInputUrl
    )
}
