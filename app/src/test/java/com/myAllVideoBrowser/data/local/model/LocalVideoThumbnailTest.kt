package com.myAllVideoBrowser.data.local.model

import android.app.Application
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LocalVideoThumbnailTest {

    @Test
    fun usableOriginalThumbnailUrl_returnsTrimmedSourceCover() {
        val video = LocalVideo(1L, Uri.parse("file:///video.mp4"), "video.mp4").apply {
            originalThumbnailUrl = "  https://cdn.example/cover.jpg  "
        }

        assertEquals("https://cdn.example/cover.jpg", video.usableOriginalThumbnailUrl)
    }

    @Test
    fun usableOriginalThumbnailUrl_returnsNullWhenCoverIsMissing() {
        val video = LocalVideo(1L, Uri.parse("file:///video.mp4"), "video.mp4").apply {
            originalThumbnailUrl = "   "
        }

        assertNull(video.usableOriginalThumbnailUrl)
    }
}
