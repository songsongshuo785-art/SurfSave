package com.myAllVideoBrowser.util

import com.google.gson.JsonParser
import com.myAllVideoBrowser.data.local.room.entity.DownloadRequestData
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RoomConverterTest {

    private val converter = RoomConverter()

    @Test
    fun convertListVideosToJson_writesVersionedEnvelopeAndRoundTrips() {
        val source = VideoInfo(
            id = "video-1",
            downloadUrls = listOf(
                DownloadRequestData(
                    url = "https://cdn.example/video.mp4",
                    headers = mapOf("Referer" to "https://example/watch/1")
                )
            ),
            title = "Title",
            ext = "mp4",
            originalUrl = "https://example/watch/1",
            isRegularDownload = true,
            isDetectedBySuperX = true
        )

        val json = converter.convertListVideosToJson(source)
        val envelope = JsonParser.parseString(json).asJsonObject
        val payload = envelope.getAsJsonObject("payload")

        assertEquals(1, envelope.get("version").asInt)
        assertTrue(payload.has("downloadUrls"))
        assertTrue(payload.has("isRegularDownload"))
        assertFalse(payload.has("urls"))
        assertFalse(payload.has("isRegular"))
        assertEquals(source, converter.convertJsonToVideo(json))
    }

    @Test
    fun convertJsonToVideo_readsLegacyPlainJsonAndOldFieldAliases() {
        val legacyJson = """
            {
              "id":"legacy",
              "urls":[{"url":"https://cdn.example/legacy.mp4","method":"GET","headers":{}}],
              "title":"Legacy title",
              "ext":"mp4",
              "thumbnail":"",
              "duration":1200,
              "original_url":"https://example/legacy",
              "formats":{"formats":[]},
              "isRegular":true,
              "isLive":false,
              "detectedBySuperX":true
            }
        """.trimIndent()

        val restored = converter.convertJsonToVideo(legacyJson)

        assertEquals("legacy", restored.id)
        assertEquals("https://cdn.example/legacy.mp4", restored.downloadUrls.single().url)
        assertEquals("https://example/legacy", restored.originalUrl)
        assertTrue(restored.isRegularDownload)
        assertTrue(restored.isDetectedBySuperX)
    }

    @Test
    fun convertJsonToVideo_rejectsUnknownEnvelopeVersionExplicitly() {
        val error = expectIllegalArgument {
            converter.convertJsonToVideo("""{"version":99,"payload":{}}""")
        }

        assertTrue(error.message.orEmpty().contains("Unsupported VideoInfo envelope version: 99"))
    }

    @Test
    fun convertJsonToVideo_rejectsIncompleteEnvelopeInsteadOfTreatingItAsLegacy() {
        val error = expectIllegalArgument {
            converter.convertJsonToVideo("""{"version":1}""")
        }

        assertTrue(error.message.orEmpty().contains("missing payload"))
    }

    @Test
    fun convertJsonToVideo_rejectsFractionalAndNonIntegerExponentVersions() {
        listOf("1.5", "1e-1").forEach { version ->
            val error = expectIllegalArgument {
                converter.convertJsonToVideo("""{"version":$version,"payload":{}}""")
            }

            assertTrue(error.message.orEmpty().contains("must be an integer"))
        }
    }

    @Test
    fun convertJsonToVideo_rejectsOverflowingVersionsWithoutTruncatingToOne() {
        listOf("2147483648", "4294967297", "999999999999999999999999999999").forEach { version ->
            val error = expectIllegalArgument {
                converter.convertJsonToVideo("""{"version":$version,"payload":{}}""")
            }

            assertTrue(error.message.orEmpty().contains("outside the supported integer range"))
        }
    }

    private fun expectIllegalArgument(block: () -> Unit): IllegalArgumentException {
        return try {
            block()
            fail("Expected IllegalArgumentException")
            throw AssertionError("unreachable")
        } catch (error: IllegalArgumentException) {
            error
        }
    }
}
