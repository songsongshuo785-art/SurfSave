package com.myAllVideoBrowser.util.downloaders.youtubedl_downloader

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class YoutubeDlOutputTemplateTest {
    @Test
    fun existingVideoExtension_isNotDuplicated() {
        assertEquals(
            "${File("tmp").absolutePath}/clip.%(ext)s",
            youtubeDlOutputTemplate(File("tmp"), "clip.mp4")
        )
    }

    @Test
    fun existingAudioExtension_isNotDuplicated() {
        assertEquals(
            "${File("tmp").absolutePath}/track.%(ext)s",
            youtubeDlOutputTemplate(File("tmp"), "track.mp3")
        )
    }
}
