package com.myAllVideoBrowser.util.telegram

import com.myAllVideoBrowser.data.local.room.entity.VideoFormatEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class TelegramDownloadPolicyTest {
    private val temporaryFormat = VideoFormatEntity(
        formatId = "720p",
        url = "https://cdn.example/video.mp4?token=temporary",
        manifestUrl = "https://cdn.example/master.m3u8?token=temporary",
        httpHeaders = mapOf("Referer" to "https://t.me/channel/42"),
        videoOnlyUrl = "https://cdn.example/video-only.mp4",
        audioOnlyUrl = "https://cdn.example/audio-only.m4a",
        width = 1280,
        height = 720
    )

    @Test
    fun telegramQueueFormatKeepsSelectorButDropsSessionOnlyAddresses() {
        val queued = TelegramDownloadPolicy.prepareFormatForQueue(
            "https://t.me/channel/42?single=1",
            temporaryFormat
        )

        assertEquals("720p", queued.formatId)
        assertEquals(1280, queued.width)
        assertEquals(720, queued.height)
        assertNull(queued.url)
        assertNull(queued.manifestUrl)
        assertNull(queued.httpHeaders)
        assertNull(queued.videoOnlyUrl)
        assertNull(queued.audioOnlyUrl)
    }

    @Test
    fun ordinaryMediaFormatIsNotChanged() {
        val queued = TelegramDownloadPolicy.prepareFormatForQueue(
            "https://example.com/watch/42",
            temporaryFormat
        )

        assertSame(temporaryFormat, queued)
    }
}
