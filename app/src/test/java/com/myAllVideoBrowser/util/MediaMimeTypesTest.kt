package com.myAllVideoBrowser.util

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class MediaMimeTypesTest {

    @Test
    fun resolvesByExtensionWhenDeclaredTypeIsBlank() {
        assertEquals("image/jpeg", MediaMimeTypes.resolve("", "photo.jpg"))
        assertEquals("audio/mpeg", MediaMimeTypes.resolve("", "track.mp3"))
        assertEquals("video/mp4", MediaMimeTypes.resolve("", "clip.mp4"))
        assertEquals("image/webp", MediaMimeTypes.resolve("", "cover.webp"))
    }

    @Test
    fun keepsDeclaredType() {
        assertEquals(
            "custom/type",
            MediaMimeTypes.resolve("custom/type", "photo.jpg"),
        )
    }

    @Test
    fun unknownExtensionFallsBackToWildcard() {
        assertEquals("*/*", MediaMimeTypes.resolve("", "file.unknown"))
    }
}
