package com.myAllVideoBrowser.ui.main.video

import android.app.Application
import android.net.Uri
import com.myAllVideoBrowser.data.local.model.LocalVideo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MediaLibraryPresentationTest {
    private fun media(id: Long, name: String, mime: String = "", source: String = "") =
        LocalVideo(id, Uri.parse("content://media/$id"), name).apply { mimeType = mime; sourceUrl = source }

    @Test fun multipleSearchTermsMatchAcrossTitleAndSourceWithoutReordering() {
        val first = media(3, "Ocean Walk.mp4", "video/mp4", "https://www.example.org/watch")
        val second = media(2, "Ocean Sunrise.mp4", "video/mp4", "https://example.org/other")
        val third = media(1, "Mountain.mp4", "video/mp4", "https://another.org")
        assertEquals(listOf(first, second), MediaLibraryPresentation.filter(listOf(first, second, third), "  OCEAN\tExample  ", LibraryMediaType.ALL))
        assertEquals(listOf(second), MediaLibraryPresentation.filter(listOf(first, second, third), "sunrise example", LibraryMediaType.VIDEO))
    }

    @Test fun typeFilterSupportsLegacyFilesAndGenericMimeWhileRespectingVideoMime() {
        val audio = media(1, "Track.MP3")
        val generic = media(2, "Audio.m4a", "application/octet-stream")
        val declared = media(3, "Recording.bin", "audio/ogg")
        val video = media(4, "Odd name.mp3", "video/mp4")
        val all = listOf(audio, generic, declared, video)
        assertEquals(listOf(audio, generic, declared), MediaLibraryPresentation.filter(all, "", LibraryMediaType.AUDIO))
        assertEquals(listOf(video), MediaLibraryPresentation.filter(all, "", LibraryMediaType.VIDEO))
        assertEquals(all, MediaLibraryPresentation.filter(all, "  ", LibraryMediaType.ALL))
    }
}
