package com.myAllVideoBrowser.util.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramPostPreviewParserTest {
    private val post = requireNotNull(TelegramPostUrl.parse("https://t.me/cctv1/17551"))

    @Test
    fun parsesPlayableAndPosterOnlyItemsInDocumentOrder() {
        val html = """
            <div class="tgme_widget_message_author">CCTV 1</div>
            <div class="tgme_widget_message_text">A public post</div>
            <a class="tgme_widget_message_video_player" href="/cctv1/17551">
              <i class="tgme_widget_message_video_thumb" style="background-image:url('/thumb-one.jpg')"></i>
              <video src="https://cdn.example/video-one.mp4"></video>
              <time>01:05</time>
            </a>
            <a class="tgme_widget_message_video_player" href="https://t.me/cctv1/17552">
              <i class="tgme_widget_message_video_thumb" style="background-image:url('https://cdn.example/thumb-two.jpg')"></i>
              <time>00:22</time>
            </a>
        """.trimIndent()

        val preview = TelegramPostPreviewParser.parse(html, post)

        assertEquals("CCTV 1", preview.channel)
        assertEquals("A public post", preview.description)
        assertEquals(2, preview.items.size)
        assertEquals(TelegramMediaAvailability.PLAYABLE, preview.items[0].availability)
        assertEquals("https://cdn.example/video-one.mp4", preview.items[0].mediaUrl)
        assertEquals(65_000L, preview.items[0].durationMs)
        assertEquals(TelegramMediaAvailability.POSTER_ONLY, preview.items[1].availability)
        assertEquals("https://t.me/cctv1/17552?single=1", preview.items[1].originalUrl)
        assertEquals(1, preview.playableCount)
        assertEquals(1, preview.posterOnlyCount)
        assertTrue(preview.thumbnail.endsWith("/thumb-one.jpg"))
    }

    @Test
    fun fallbackVideoUsesOriginalItemPositionAndReExtractableBestFormat() {
        val preview = TelegramPostPreview(
            postUrl = post.canonicalUrl,
            channel = "CCTV 1",
            description = "Two items",
            items = listOf(
                TelegramPostPreviewItem(
                    availability = TelegramMediaAvailability.POSTER_ONLY,
                    originalUrl = "https://t.me/cctv1/17551?single=1",
                    thumbnail = "https://cdn.example/cover.jpg"
                ),
                TelegramPostPreviewItem(
                    availability = TelegramMediaAvailability.PLAYABLE,
                    originalUrl = "https://t.me/cctv1/17552?single=1",
                    mediaUrl = "https://cdn.example/video.mp4"
                )
            )
        )

        val video = TelegramPostResolver.mapPreviewToResolution(preview, post).videos.single()

        assertEquals("Two items (2/2)", video.title)
        assertEquals("https://t.me/cctv1/17552?single=1", video.originalUrl)
        assertEquals("best", video.formats.formats.single().formatId)
    }
}
