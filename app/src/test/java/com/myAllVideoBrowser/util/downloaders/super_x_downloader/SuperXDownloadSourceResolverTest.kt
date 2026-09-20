package com.myAllVideoBrowser.util.downloaders.super_x_downloader

import com.myAllVideoBrowser.data.local.room.entity.VideFormatEntityList
import com.myAllVideoBrowser.data.local.room.entity.VideoFormatEntity
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SuperXDownloadSourceResolverTest {
    @Test
    fun pageOriginalUrl_neverOverridesSelectedManifest() {
        val source = SuperXDownloadSourceResolver.resolve(
            videoInfo(
                originalUrl = "https://page.example/view_video.php?id=1",
                format = VideoFormatEntity(
                    formatId = "hls-720p-1000000",
                    manifestUrl = "https://cdn.example/master.m3u8?token=fresh",
                    url = "https://cdn.example/variant.m3u8?token=fresh",
                    httpHeaders = mapOf("Referer" to "https://page.example/"),
                    vcodec = "avc1"
                )
            )
        )

        assertEquals("https://cdn.example/master.m3u8?token=fresh", source.url)
        assertEquals("https://page.example/", source.headers["Referer"])
        assertEquals("hls-720p-1000000", source.formatId)
        assertEquals("avc1", source.videoCodec)
    }

    @Test
    fun selectedFormatUrl_isUsedWhenManifestUrlIsMissing() {
        val source = SuperXDownloadSourceResolver.resolve(
            videoInfo(
                originalUrl = "https://page.example/watch/1",
                format = VideoFormatEntity(url = "https://cdn.example/stream.mpd")
            )
        )

        assertEquals("https://cdn.example/stream.mpd", source.url)
    }

    @Test
    fun authenticatedManifestEntryAndHeadersArePreferredForDownload() {
        val source = SuperXDownloadSourceResolver.resolve(
            videoInfo(
                originalUrl = "https://page.example/watch/1",
                format = VideoFormatEntity(
                    manifestUrl = "https://cdn.example/resolved/master.m3u8",
                    httpHeaders = mapOf("User-Agent" to "SurfSave test"),
                    manifestRequestUrl = "https://origin.example/auth/master.m3u8",
                    manifestRequestHeaders = mapOf(
                        "Cookie" to "session=origin",
                        "Authorization" to "Bearer origin",
                        "User-Agent" to "SurfSave test"
                    )
                )
            )
        )

        assertEquals("https://origin.example/auth/master.m3u8", source.url)
        assertEquals("session=origin", source.headers["Cookie"])
        assertEquals("Bearer origin", source.headers["Authorization"])
    }

    @Test
    fun missingSelectedMediaUrl_failsInsteadOfFallingBackToHtmlPage() {
        assertThrows(IllegalArgumentException::class.java) {
            SuperXDownloadSourceResolver.resolve(
                videoInfo(
                    originalUrl = "https://page.example/watch/1",
                    format = VideoFormatEntity(formatId = "hls-720p-1")
                )
            )
        }
    }

    private fun videoInfo(originalUrl: String, format: VideoFormatEntity) = VideoInfo(
        originalUrl = originalUrl,
        formats = VideFormatEntityList(listOf(format)),
        isDetectedBySuperX = true
    )
}
