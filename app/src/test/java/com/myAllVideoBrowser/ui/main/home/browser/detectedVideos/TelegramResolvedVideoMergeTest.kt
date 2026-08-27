package com.myAllVideoBrowser.ui.main.home.browser.detectedVideos

import com.myAllVideoBrowser.data.local.room.entity.VideFormatEntityList
import com.myAllVideoBrowser.data.local.room.entity.VideoFormatEntity
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TelegramResolvedVideoMergeTest {
    @Test
    fun resolvedPostCandidateReplacesTemporaryDetectionButKeepsUiIdentity() {
        val detected = VideoInfo(
            id = "existing-id",
            title = "Temporary detection",
            ext = "mp4",
            thumbnail = "https://cdn.example/cover.jpg",
            duration = 1_000L,
            originalUrl = "https://cdn.example/temporary.mp4",
            formats = VideFormatEntityList(
                listOf(
                    VideoFormatEntity(
                        formatId = "0",
                        url = "https://cdn.example/temporary.mp4"
                    )
                )
            ),
            isRegularDownload = true,
            isDetectedBySuperX = true
        )
        val resolved = VideoInfo(
            id = "resolved-id",
            title = "Telegram post video",
            ext = "mp4",
            duration = 2_000L,
            originalUrl = "https://t.me/channel/42?single=1",
            formats = VideFormatEntityList(
                listOf(
                    VideoFormatEntity(
                        formatId = "best",
                        url = "https://cdn.example/current.mp4"
                    )
                )
            ),
            isRegularDownload = false
        )

        val merged = VideoDetectionTabViewModel.mergeTelegramResolvedVideo(detected, resolved)

        assertEquals("existing-id", merged.id)
        assertEquals("Telegram post video", merged.title)
        assertEquals("https://t.me/channel/42?single=1", merged.originalUrl)
        assertEquals("best", merged.formats.formats.single().formatId)
        assertEquals("https://cdn.example/current.mp4", merged.formats.formats.single().url)
        assertEquals("https://cdn.example/cover.jpg", merged.thumbnail)
        assertEquals(2_000L, merged.duration)
        assertFalse(merged.isRegularDownload)
        assertFalse(merged.isDetectedBySuperX)
    }
}
