package com.myAllVideoBrowser.util.downloaders

import com.myAllVideoBrowser.data.local.room.entity.VideoFormatEntity
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.data.local.room.entity.VideFormatEntityList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DownloadFingerprintTest {

    @Test
    fun normalizeUrl_removesVolatileQueryAndSortsStableQuery() {
        val first = DownloadFingerprint.normalizeUrl(
            "HTTPS://Example.com:443/video.mp4?token=abc&utm_source=x&b=2&a=1#frag"
        )
        val second = DownloadFingerprint.normalizeUrl(
            "https://example.com/video.mp4?a=1&b=2&token=def"
        )

        assertEquals(second, first)
    }

    @Test
    fun fromVideoInfo_ignoresSignedTokenChanges() {
        val first = videoInfo("https://cdn.example.com/video.mp4?token=one&quality=720")
        val second = videoInfo("https://cdn.example.com/video.mp4?token=two&quality=720")

        assertEquals(
            DownloadFingerprint.fromVideoInfo(first),
            DownloadFingerprint.fromVideoInfo(second)
        )
    }

    @Test
    fun fromVideoInfo_ignoresTitleChangesWhenUrlsMatch() {
        val first = videoInfo("https://cdn.example.com/video.mp4?token=one&quality=720", title = "Old")
        val second = videoInfo("https://cdn.example.com/video.mp4?token=one&quality=720", title = "New")

        assertEquals(
            DownloadFingerprint.fromVideoInfo(first),
            DownloadFingerprint.fromVideoInfo(second)
        )
    }

    @Test
    fun fromVideoInfo_keepsDifferentFormatIdentitySeparate() {
        val low = videoInfo("https://cdn.example.com/video.mp4?token=one", height = 720)
        val high = videoInfo("https://cdn.example.com/video.mp4?token=one", height = 1080)

        assertNotEquals(
            DownloadFingerprint.fromVideoInfo(low),
            DownloadFingerprint.fromVideoInfo(high)
        )
    }

    private fun videoInfo(url: String, height: Int = 720, title: String = "Title"): VideoInfo {
        return VideoInfo(
            id = "id-$height",
            title = title,
            ext = "mp4",
            originalUrl = "https://example.com/watch?v=1",
            formats = VideFormatEntityList(
                listOf(
                    VideoFormatEntity(
                        formatId = "mp4-$height",
                        height = height,
                        vcodec = "h264",
                        acodec = "aac",
                        url = url
                    )
                )
            )
        )
    }
}
