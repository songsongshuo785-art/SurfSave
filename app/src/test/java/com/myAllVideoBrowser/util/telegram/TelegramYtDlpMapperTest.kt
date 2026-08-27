package com.myAllVideoBrowser.util.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramYtDlpMapperTest {
    private val requestedPost = requireNotNull(
        TelegramPostUrl.parse("https://t.me/vorposte/29342")
    )

    @Test
    fun mapsSingleVideoAndKeepsPostUrlForDownloadReExtraction() {
        val result = TelegramYtDlpMapper.parse(
            """
            {
              "id":"29342",
              "title":"Public clip",
              "channel":"Channel",
              "webpage_url":"https://t.me/vorposte/29342?single=1",
              "duration":33,
              "thumbnail":"https://cdn.example/cover.jpg",
              "formats":[{
                "format_id":"0",
                "url":"https://cdn.example/temporary.mp4?token=short-lived",
                "ext":"mp4",
                "width":1280,
                "height":720,
                "http_headers":{"Referer":"https://t.me/"}
              }]
            }
            """.trimIndent(),
            requestedPost
        )

        assertEquals(1, result.videos.size)
        val video = result.videos.single()
        assertEquals("https://t.me/vorposte/29342?single=1", video.originalUrl)
        assertEquals("https://cdn.example/temporary.mp4?token=short-lived", video.formats.formats.single().url)
        assertEquals(33_000L, video.duration)
        assertFalse(video.isRegularDownload)
    }

    @Test
    fun mapsPlaylistEntriesInOriginalOrderAndKeepsTheirOwnSingleUrls() {
        val result = TelegramYtDlpMapper.parse(
            """
            {
              "title":"Channel 29342",
              "description":"Two videos",
              "entries":[
                {
                  "id":"29342",
                  "webpage_url":"https://t.me/vorposte/29342?single=1",
                  "duration":33,
                  "formats":[{"url":"https://cdn.example/first.mp4","ext":"mp4"}]
                },
                {
                  "id":"29343",
                  "webpage_url":"https://t.me/vorposte/29343?single=1",
                  "duration":35,
                  "formats":[{"url":"https://cdn.example/second.mp4","ext":"mp4"}]
                }
              ]
            }
            """.trimIndent(),
            requestedPost
        )

        assertEquals(
            listOf("https://cdn.example/first.mp4", "https://cdn.example/second.mp4"),
            result.videos.map { it.formats.formats.single().url }
        )
        assertEquals(
            listOf(
                "https://t.me/vorposte/29342?single=1",
                "https://t.me/vorposte/29343?single=1"
            ),
            result.videos.map { it.originalUrl }
        )
    }

    @Test
    fun representsThumbnailWithoutPublicMediaAsPosterOnly() {
        val result = TelegramYtDlpMapper.parse(
            """
            {
              "id":"29342",
              "title":"Large file",
              "thumbnail":"https://cdn.example/cover.jpg",
              "webpage_url":"https://t.me/vorposte/29342"
            }
            """.trimIndent(),
            requestedPost
        )

        assertTrue(result.videos.isEmpty())
        assertEquals(1, result.preview.posterOnlyCount)
        assertEquals(TelegramMediaAvailability.POSTER_ONLY, result.preview.items.single().availability)
    }
}
