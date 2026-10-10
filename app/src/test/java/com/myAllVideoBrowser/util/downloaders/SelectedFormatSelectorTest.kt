package com.myAllVideoBrowser.util.downloaders

import com.myAllVideoBrowser.data.local.room.entity.VideoFormatEntity
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.data.local.room.entity.VideFormatEntityList
import com.myAllVideoBrowser.util.media.DownloadStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SelectedFormatSelectorTest {

    @Test
    fun emptyFormatListFailsFast() {
        assertThrows(IllegalArgumentException::class.java) {
            SelectedFormatSelector.select(videoInfo(emptyList()), "test")
        }
    }

    @Test
    fun stampedSingleFormatIsReturned() {
        val only = format(formatId = "0", downloadStrategy = DownloadStrategy.DIRECT_HTTP.name, url = "https://cdn/v.mp4")

        assertEquals(only, SelectedFormatSelector.select(videoInfo(listOf(only)), "test"))
    }

    @Test
    fun legacyMultiFormatKeepsFirstBehaviour() {
        val first = format(formatId = "0", url = "https://cdn/480.mp4")
        val second = format(formatId = "1", url = "https://cdn/1080.mp4")

        assertEquals(first, SelectedFormatSelector.select(videoInfo(listOf(first, second)), "test"))
    }

    @Test
    fun stampedMultiFormatIsAnInvariantViolation() {
        val first = format(formatId = "0", downloadStrategy = DownloadStrategy.DIRECT_HTTP.name, url = "https://cdn/v.mp4")
        val second = format(formatId = "1", url = "https://cdn/1080.mp4")

        assertThrows(IllegalStateException::class.java) {
            SelectedFormatSelector.select(videoInfo(listOf(first, second)), "test")
        }
    }

    @Test
    fun unknownStampKeepsLegacyMultiFormatBehaviour() {
        // 未知的持久化值（例如更高版本写入的 strategy）不算盖章：必须与 UNKNOWN_STRATEGY→legacy
        // 回落语义一致，不能把升级用户的老任务直接抛死。
        val first = format(formatId = "0", downloadStrategy = "DIRECT_FILE_V2", url = "https://cdn/480.mp4")
        val second = format(formatId = "1", url = "https://cdn/1080.mp4")

        assertEquals(first, SelectedFormatSelector.select(videoInfo(listOf(first, second)), "test"))
    }

    @Test
    fun explicitYtDlpWithoutExtractorInputFailsFast() {
        val only = format(formatId = "137", downloadStrategy = DownloadStrategy.YTDLP_FORMAT.name)

        assertThrows(IllegalStateException::class.java) {
            SelectedFormatSelector.select(videoInfo(listOf(only)), "test")
        }
    }

    private fun videoInfo(formats: List<VideoFormatEntity>): VideoInfo = VideoInfo(
        id = "video",
        title = "Title",
        ext = "mp4",
        originalUrl = "https://page.example/watch?v=1",
        formats = VideFormatEntityList(formats)
    )

    private fun format(
        formatId: String? = null,
        url: String? = null,
        downloadStrategy: String? = null
    ): VideoFormatEntity = VideoFormatEntity(
        formatId = formatId,
        url = url,
        downloadStrategy = downloadStrategy
    )
}
