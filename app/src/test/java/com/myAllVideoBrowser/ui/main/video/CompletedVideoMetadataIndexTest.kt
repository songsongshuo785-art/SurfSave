package com.myAllVideoBrowser.ui.main.video

import com.myAllVideoBrowser.data.local.room.entity.ProgressInfo
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CompletedVideoMetadataIndexTest {
    @Test
    fun exactUriWinsWhenTwoTasksHaveTheSameFileName() {
        val first = progress("first", "content://media/video/1")
        val second = progress("second", "content://media/video/2")
        val index = CompletedVideoMetadataIndex.from(listOf(first, second))

        assertEquals(second, index.find("content://media/video/2", "same.mp4"))
    }

    @Test
    fun ambiguousLegacyFileNameDoesNotGuess() {
        val first = progress("first", "")
        val second = progress("second", "")
        val index = CompletedVideoMetadataIndex.from(listOf(first, second))

        assertNull(index.find("content://media/video/9", "same.mp4"))
    }

    @Test
    fun duplicateFinalUriDoesNotGuess() {
        val first = progress("first", "content://media/video/1")
        val second = progress("second", "content://media/video/1")
        val index = CompletedVideoMetadataIndex.from(listOf(first, second))

        assertNull(index.find("content://media/video/1", "unrelated.mp4"))
    }

    @Test
    fun untrustedImportedMetadataDoesNotMatchByUriOrFileName() {
        val imported = progress(
            id = "imported",
            uri = "content://media/video/1",
            mediaBindingTrusted = false
        )
        val index = CompletedVideoMetadataIndex.from(listOf(imported))

        assertNull(index.find("content://media/video/1", "same.mp4"))
    }

    private fun progress(
        id: String,
        uri: String,
        mediaBindingTrusted: Boolean = true
    ) = ProgressInfo(
        id = id,
        videoInfo = VideoInfo(id = "video-$id", title = "same", ext = "mp4"),
        finalMediaUri = uri,
        mediaBindingTrusted = mediaBindingTrusted
    )
}
