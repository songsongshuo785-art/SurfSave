package com.myAllVideoBrowser.ui.main.home.browser.detectedVideos

import com.myAllVideoBrowser.data.local.room.entity.DownloadRequestData
import com.myAllVideoBrowser.data.local.room.entity.VideFormatEntityList
import com.myAllVideoBrowser.data.local.room.entity.VideoFormatEntity
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectedMediaPresentationTest {
    @Test
    fun pageMetadataParser_readsStandardUrlsAndIsoDuration() {
        val metadata = PageMediaMetadataParser.parse(
            """{
                "pageUrl":"https://example.test/watch/1",
                "canonicalUrl":"https://example.test/watch/1",
                "contentUrls":["https://cdn.test/main.m3u8", "https://cdn.test/main.m3u8"],
                "duration":"PT1H12M40.5S",
                "durationSeconds":""
            }""".trimIndent()
        )

        assertEquals("https://example.test/watch/1", metadata.pageUrl)
        assertEquals(listOf("https://cdn.test/main.m3u8"), metadata.contentUrls)
        assertEquals(4_360_500L, metadata.durationMs)
    }

    @Test
    fun pageMetadataParser_fallsBackToDurationSeconds() {
        val metadata = PageMediaMetadataParser.parse(
            """{"contentUrls":[],"duration":"","durationSeconds":"515.25"}"""
        )

        assertEquals(515_250L, metadata.durationMs)
    }

    @Test
    fun declaredContentUrl_ranksBeforeLongerCandidates() {
        val declared = video("declared", "https://cdn.test/main.m3u8?token=new", 120_000L, hls = true)
        val longer = video("longer", "https://ads.test/long.mp4", 900_000L)
        val metadata = PageMediaMetadata(
            contentUrls = listOf("https://cdn.test/main.m3u8?token=old"),
            durationMs = 120_000L
        )

        assertEquals(
            listOf("declared", "longer"),
            DetectedMediaPresentation.sort(listOf(longer, declared), metadata).map { it.id }
        )
    }

    @Test
    fun durationWithinTwoSeconds_ranksBeforeOtherKnownDurations() {
        val close = video("close", "https://cdn.test/close.mp4", 598_000L)
        val longerButDifferent = video("longer", "https://cdn.test/long.mp4", 1_200_000L)
        val metadata = PageMediaMetadata(durationMs = 600_000L)

        assertEquals(
            listOf("close", "longer"),
            DetectedMediaPresentation.sort(listOf(longerButDifferent, close), metadata).map { it.id }
        )
    }

    @Test
    fun knownDurations_sortDescendingWhenNoPageMatchExists() {
        val short = video("short", "https://cdn.test/short.mp4", 10_000L)
        val long = video("long", "https://cdn.test/long.mp4", 30_000L)

        assertEquals(
            listOf("long", "short"),
            DetectedMediaPresentation.sort(listOf(short, long), PageMediaMetadata()).map { it.id }
        )
    }

    @Test
    fun unknownStreams_rankBeforeUnknownDirectUrls() {
        val direct = video("direct", "https://cdn.test/video.mp4")
        val dash = video("dash", "https://cdn.test/manifest.mpd", mpd = true)
        val hls = video("hls", "https://cdn.test/master.m3u8", hls = true)

        assertEquals(
            listOf("dash", "hls", "direct"),
            DetectedMediaPresentation.sort(listOf(direct, dash, hls), PageMediaMetadata()).map { it.id }
        )
    }

    @Test
    fun equalRank_preservesDetectionOrder() {
        val first = video("first", "https://cdn.test/a.mp4")
        val second = video("second", "https://cdn.test/b.mp4")

        assertEquals(
            listOf("first", "second"),
            DetectedMediaPresentation.sort(listOf(first, second), PageMediaMetadata()).map { it.id }
        )
    }

    @Test
    fun durationFormatting_handlesKnownLiveAndUnknownValues() {
        assertEquals("08:35", DetectedMediaPresentation.formatDuration(515_000L, false, "Live", "Unknown"))
        assertEquals("01:12:40", DetectedMediaPresentation.formatDuration(4_360_000L, false, "Live", "Unknown"))
        assertEquals("Live", DetectedMediaPresentation.formatDuration(0L, true, "Live", "Unknown"))
        assertEquals("Unknown", DetectedMediaPresentation.formatDuration(0L, false, "Live", "Unknown"))
    }

    @Test
    fun declaredUrl_usesPageDurationForDisplayWhenCandidateDurationIsUnknown() {
        val video = video("main", "https://cdn.test/main.mp4")
        val metadata = PageMediaMetadata(
            contentUrls = listOf("https://cdn.test/main.mp4"),
            durationMs = 515_000L
        )

        assertEquals(515_000L, DetectedMediaPresentation.displayDurationMs(video, metadata))
    }

    @Test
    fun protectedMediaTracker_rejectsOldPageCallbacksAndClearsOnNavigation() {
        val tracker = ProtectedMediaPageTracker()
        val first = tracker.beginPage("https://example.test/first")
        val marked = tracker.markProtectedMedia(first.generation)
        assertTrue(marked?.hasProtectedMedia == true)

        val second = tracker.beginPage("https://example.test/second")
        assertFalse(second.hasProtectedMedia)
        assertNull(tracker.markProtectedMedia(first.generation))
        assertEquals(second.generation, tracker.snapshot().generation)
        assertFalse(tracker.snapshot().hasProtectedMedia)
    }

    private fun video(
        id: String,
        url: String,
        durationMs: Long = 0L,
        hls: Boolean = false,
        mpd: Boolean = false
    ): VideoInfo {
        val protocol = when {
            hls -> "m3u8_native"
            mpd -> "dash"
            else -> "https"
        }
        val formatId = when {
            hls -> "hls-main"
            mpd -> "dash-main"
            else -> "http-main"
        }
        return VideoInfo(
            id = id,
            downloadUrls = listOf(DownloadRequestData(url)),
            duration = durationMs,
            formats = VideFormatEntityList(
                listOf(
                    VideoFormatEntity(
                        id = "$id-format",
                        formatId = formatId,
                        url = url,
                        protocol = protocol,
                        duration = durationMs.takeIf { it > 0L }
                    )
                )
            )
        )
    }
}
