package com.myAllVideoBrowser.ui.main.video

import android.app.Application
import android.net.Uri
import com.myAllVideoBrowser.data.local.model.LocalVideo
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MediaLibraryPresentationTest {

    private fun media(id: Long, name: String, mime: String = "", source: String = "") =
        LocalVideo(id, Uri.parse("content://media/$id"), name).apply { mimeType = mime; sourceUrl = source }

    @Test
    fun multipleSearchTermsMatchAcrossTitleAndSourceWithoutReordering() {
        val first = media(3, "Ocean Walk.mp4", "video/mp4", "https://www.example.org/watch")
        val second = media(2, "Ocean Sunrise.mp4", "video/mp4", "https://example.org/other")
        val third = media(1, "Mountain.mp4", "video/mp4", "https://another.org")
        assertEquals(listOf(first, second), MediaLibraryPresentation.filter(listOf(first, second, third), "  OCEAN\tExample  ", LibraryMediaType.ALL))
        assertEquals(listOf(second), MediaLibraryPresentation.filter(listOf(first, second, third), "sunrise example", LibraryMediaType.VIDEO))
    }

    @Test
    fun typeFilterSupportsLegacyFilesAndGenericMimeWhileRespectingVideoMime() {
        val audio = media(1, "Track.MP3")
        val generic = media(2, "Audio.m4a", "application/octet-stream")
        val declared = media(3, "Recording.bin", "audio/ogg")
        val video = media(4, "Odd name.mp3", "video/mp4")
        val all = listOf(audio, generic, declared, video)
        assertEquals(listOf(audio, generic, declared), MediaLibraryPresentation.filter(all, "", LibraryMediaType.AUDIO))
        assertEquals(listOf(video), MediaLibraryPresentation.filter(all, "", LibraryMediaType.VIDEO))
        assertEquals(all, MediaLibraryPresentation.filter(all, "  ", LibraryMediaType.ALL))
    }

    @Test
    fun imageFilterSeparatesImagesFromVideoAndAudio() {
        val items = listOf(
            item("clip.mp4", "video/mp4"),
            item("song.mp3", "audio/mpeg"),
            item("photo.webp", "image/webp"),
            item("vector.svg", "")
        )

        assertEquals(listOf("clip.mp4"), names(MediaLibraryPresentation.filter(items, "", LibraryMediaType.VIDEO)))
        assertEquals(listOf("song.mp3"), names(MediaLibraryPresentation.filter(items, "", LibraryMediaType.AUDIO)))
        assertEquals(listOf("photo.webp", "vector.svg"), names(MediaLibraryPresentation.filter(items, "", LibraryMediaType.IMAGE)))
        assertEquals(4, MediaLibraryPresentation.filter(items, "", LibraryMediaType.ALL).size)
    }

    @Test
    fun imageFilterStillAppliesSearchTerms() {
        val items = listOf(item("sunset.jpg", "image/jpeg").apply { sourceUrl = "https://photos.example/a" })

        assertEquals(
            listOf("sunset.jpg"),
            names(MediaLibraryPresentation.filter(items, "photos", LibraryMediaType.IMAGE))
        )
        assertEquals(
            emptyList<String>(),
            names(MediaLibraryPresentation.filter(items, "video", LibraryMediaType.IMAGE))
        )
    }

    private fun item(name: String, mimeType: String): LocalVideo = media(1L, name, mimeType)

    private fun names(items: List<LocalVideo>): List<String> = items.map { it.name }
}
