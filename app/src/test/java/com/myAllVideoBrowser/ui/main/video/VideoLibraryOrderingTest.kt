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
class VideoLibraryOrderingTest {
    @Test
    fun newestVideoIsFirst() {
        val videos = listOf(
            video("older.mp4", "content://media/downloads/1", 1_000L),
            video("newest.mp4", "content://media/downloads/2", 3_000L),
            video("middle.mp4", "content://media/downloads/3", 2_000L)
        )

        val ordered = VideoLibraryOrdering.newestFirst(videos)

        assertEquals(listOf("newest.mp4", "middle.mp4", "older.mp4"), ordered.map { it.name })
    }

    @Test
    fun equalTimesUseStableNameAndUriTieBreakers() {
        val videos = listOf(
            video("same.mp4", "content://media/downloads/b", 2_000L),
            video("Beta.mp4", "content://media/downloads/c", 2_000L),
            video("alpha.mp4", "content://media/downloads/d", 2_000L),
            video("same.mp4", "content://media/downloads/a", 2_000L)
        )

        val ordered = VideoLibraryOrdering.newestFirst(videos)

        assertEquals(
            listOf(
                "content://media/downloads/d",
                "content://media/downloads/c",
                "content://media/downloads/a",
                "content://media/downloads/b"
            ),
            ordered.map { it.uri.toString() }
        )
    }

    @Test
    fun unknownTimesAreLast() {
        val videos = listOf(
            video("unknown.mp4", "content://media/downloads/1", 0L),
            video("known.mp4", "content://media/downloads/2", 1L)
        )

        val ordered = VideoLibraryOrdering.newestFirst(videos)

        assertEquals(listOf("known.mp4", "unknown.mp4"), ordered.map { it.name })
    }

    private fun video(name: String, uri: String, sortTimeMillis: Long): LocalVideo {
        return LocalVideo(0L, Uri.parse(uri), name).apply {
            this.sortTimeMillis = sortTimeMillis
        }
    }
}
