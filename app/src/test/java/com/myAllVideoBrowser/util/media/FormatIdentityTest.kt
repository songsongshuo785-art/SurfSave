package com.myAllVideoBrowser.util.media

import com.myAllVideoBrowser.data.local.room.entity.VideoFormatEntity
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.data.local.room.entity.VideFormatEntityList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FormatIdentityTest {

    @Test
    fun directFormatsWithSameConstantFormatIdStayDistinct() {
        // 探针捕获的直链 formatId 都会被写成 "0"，身份必须靠 URL 区分。
        val video = videoInfo()
        val low = format(
            formatId = "0",
            url = "https://cdn.example/480.mp4",
            downloadStrategy = DownloadStrategy.DIRECT_HTTP.name
        )
        val high = format(
            formatId = "0",
            url = "https://cdn.example/1080.mp4",
            downloadStrategy = DownloadStrategy.DIRECT_HTTP.name
        )

        assertNotEquals(
            FormatIdentity.of(video, low),
            FormatIdentity.of(video, high)
        )
    }

    @Test
    fun hlsQualitiesSharingOneManifestStayDistinct() {
        val video = videoInfo()
        val low = format(
            formatId = "hls-480p-800",
            url = "https://cdn.example/master.m3u8",
            manifestUrl = "https://cdn.example/master.m3u8",
            height = 480,
            bitrate = 800_000,
            downloadStrategy = DownloadStrategy.HLS_MANIFEST.name
        )
        val high = low.copy(formatId = "hls-1080p-5000", height = 1080, bitrate = 5_000_000)

        assertNotEquals(
            FormatIdentity.of(video, low),
            FormatIdentity.of(video, high)
        )
    }

    @Test
    fun ytDlpIdentityDependsOnTheExtractorInputThatProducedTheFormatId() {
        val video = videoInfo()
        val fromPage = format(
            formatId = "137",
            url = "https://video.example/137.mp4",
            downloadStrategy = DownloadStrategy.YTDLP_FORMAT.name,
            extractorInputUrl = "https://page.example/watch?v=1"
        )
        val fromMediaUrl = fromPage.copy(extractorInputUrl = "https://video.example/137.mp4")

        assertNotEquals(
            FormatIdentity.of(video, fromPage),
            FormatIdentity.of(video, fromMediaUrl)
        )
        assertEquals(
            FormatIdentity.of(video, fromPage),
            FormatIdentity.of(video, fromPage.copy(url = "https://other-cdn.example/137.mp4"))
        )
    }

    @Test
    fun blankIdentityFallsBackToFormatPrimaryKey() {
        val video = videoInfo()
        val format = VideoFormatEntity(
            id = "primary-key",
            downloadStrategy = DownloadStrategy.DIRECT_HTTP.name
        )

        assertEquals("direct|primary-key", FormatIdentity.of(video, format))
    }

    @Test
    fun legacySelectionKeyReproducesPreviousAlgorithm() {
        val format = format(formatId = "0", format = "mp4", url = "https://cdn.example/480.mp4")

        assertEquals("0", FormatIdentity.legacySelectionKey(format))
        assertEquals(
            "https://cdn.example/480.mp4",
            FormatIdentity.legacySelectionKey(format.copy(formatId = null, format = null))
        )
    }

    private fun videoInfo(): VideoInfo = VideoInfo(
        id = "video",
        title = "Title",
        ext = "mp4",
        originalUrl = "https://page.example/watch?v=1",
        formats = VideFormatEntityList(emptyList())
    )

    private fun format(
        formatId: String? = null,
        format: String? = null,
        url: String? = null,
        manifestUrl: String? = null,
        height: Int = 0,
        bitrate: Long? = null,
        downloadStrategy: String? = null,
        extractorInputUrl: String? = null
    ): VideoFormatEntity = VideoFormatEntity(
        formatId = formatId,
        format = format,
        url = url,
        manifestUrl = manifestUrl,
        height = height,
        bitrate = bitrate,
        downloadStrategy = downloadStrategy,
        extractorInputUrl = extractorInputUrl
    )
}
