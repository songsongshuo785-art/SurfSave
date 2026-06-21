package com.myAllVideoBrowser.util

import com.myAllVideoBrowser.data.local.room.entity.VideoFormatEntity
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadFilenameTemplateTest {

    @Test
    fun render_replacesCoreVariablesAndCleansName() {
        val rendered = DownloadFilenameTemplate.render(
            videoInfo = VideoInfo(title = "A/B: Test", ext = "mp4"),
            template = "%(title)s-%(resolution)s.%(ext)s",
            selectedFormat = VideoFormatEntity(height = 1080, ext = "mp4")
        )

        assertEquals("AB_Test-1080p", rendered.baseName)
        assertEquals("mp4", rendered.extension)
        assertEquals("AB_Test-1080p.mp4", rendered.fileName)
    }

    @Test
    fun render_supportsPlaylistVariables() {
        val rendered = DownloadFilenameTemplate.render(
            videoInfo = VideoInfo(title = "Episode", ext = "mp4"),
            template = "%(playlist_index)s-%(playlist_title)s-%(title)s.%(ext)s",
            selectedFormat = VideoFormatEntity(ext = "mp4"),
            context = DownloadFilenameTemplate.Context(
                playlistIndex = 7,
                playlistTitle = "Course"
            )
        )

        assertEquals("007-Course-Episode", rendered.baseName)
    }
}
