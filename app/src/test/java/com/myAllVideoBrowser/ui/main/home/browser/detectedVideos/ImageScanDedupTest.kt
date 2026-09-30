package com.myAllVideoBrowser.ui.main.home.browser.detectedVideos

import com.myAllVideoBrowser.data.local.room.entity.DownloadRequestData
import com.myAllVideoBrowser.data.local.room.entity.VideFormatEntityList
import com.myAllVideoBrowser.data.local.room.entity.VideoFormatEntity
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Repeated scans of the same page must not re-issue HEAD/Range probes for images
 * that are already in the detected list.
 */
class ImageScanDedupTest {
    private fun image(url: String, ext: String = "jpg", formats: Boolean = true): VideoInfo {
        return VideoInfo(
            id = "image-$url",
            title = "photo",
            ext = ext,
            originalUrl = "https://page.example/gallery",
            formats = if (formats) {
                VideFormatEntityList(
                    listOf(
                        VideoFormatEntity(
                            formatId = "direct",
                            url = url,
                            ext = ext
                        )
                    )
                )
            } else {
                VideFormatEntityList(emptyList())
            }
        )
    }

    private fun video(url: String): VideoInfo {
        return VideoInfo(
            id = "video-$url",
            title = "clip",
            ext = "mp4",
            originalUrl = "https://page.example/watch",
            formats = VideFormatEntityList(
                listOf(
                    VideoFormatEntity(
                        formatId = "best",
                        url = url,
                        ext = "mp4"
                    )
                )
            )
        )
    }

    @Test
    fun alreadyDetectedImageUrlIsReportedAsDuplicate() {
        val existing = listOf(image("https://cdn.example/photo.jpg"))

        assertTrue(
            VideoDetectionTabViewModel.isImageAlreadyDetected(
                existing,
                "https://cdn.example/photo.jpg"
            )
        )
    }

    @Test
    fun unknownImageUrlIsNotReportedAsDuplicate() {
        val existing = listOf(image("https://cdn.example/photo.jpg"))

        assertFalse(
            VideoDetectionTabViewModel.isImageAlreadyDetected(
                existing,
                "https://cdn.example/other.jpg"
            )
        )
    }

    @Test
    fun sameUrlOnAVideoDoesNotSuppressTheImageProbe() {
        val existing = listOf(video("https://cdn.example/photo.jpg"))

        assertFalse(
            VideoDetectionTabViewModel.isImageAlreadyDetected(
                existing,
                "https://cdn.example/photo.jpg"
            )
        )
    }

    @Test
    fun temporaryQueryParametersDoNotDefeatDedup() {
        val existing = listOf(image("https://cdn.example/photo.jpg"))

        assertTrue(
            VideoDetectionTabViewModel.isImageAlreadyDetected(
                existing,
                "https://cdn.example/photo.jpg?token=abc&e=1735689600&signature=deadbeef"
            )
        )
    }

    @Test
    fun wwwPrefixAndTrailingSlashAreNormalized() {
        val existing = listOf(image("https://www.cdn.example/photo.jpg/"))

        assertTrue(
            VideoDetectionTabViewModel.isImageAlreadyDetected(
                existing,
                "https://cdn.example/photo.jpg"
            )
        )
    }

    @Test
    fun blankUrlIsNeverReportedAsDuplicate() {
        val existing = listOf(image("https://cdn.example/photo.jpg"))

        assertFalse(VideoDetectionTabViewModel.isImageAlreadyDetected(existing, ""))
        assertFalse(VideoDetectionTabViewModel.isImageAlreadyDetected(existing, "   "))
    }

    @Test
    fun imageKnownOnlyThroughDownloadUrlsIsStillRecognized() {
        val existing = listOf(
            VideoInfo(
                id = "image-download-url",
                title = "photo",
                ext = "webp",
                originalUrl = "https://page.example/gallery",
                downloadUrls = listOf(
                    DownloadRequestData(url = "https://cdn.example/photo.webp")
                )
            )
        )

        assertTrue(
            VideoDetectionTabViewModel.isImageAlreadyDetected(
                existing,
                "https://cdn.example/photo.webp"
            )
        )
    }

    @Test
    fun emptyDetectedListNeverReportsDuplicates() {
        assertFalse(
            VideoDetectionTabViewModel.isImageAlreadyDetected(
                emptyList(),
                "https://cdn.example/photo.jpg"
            )
        )
    }
}
