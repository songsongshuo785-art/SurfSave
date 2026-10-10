package com.myAllVideoBrowser.util

import com.myAllVideoBrowser.data.local.room.entity.VideoFormatEntity
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.data.local.room.entity.VideFormatEntityList
import com.myAllVideoBrowser.util.media.DownloadStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoFormatUiIdentityTest {

    /**
     * 回归测试（问题④）：同一视频的直链质量在**新数据**里都会被盖章 DIRECT_HTTP（探针/浏览器捕获），
     * 旧实现的选择键是「formatId 优先」，会把 480/720/1080 合并成一条。
     */
    @Test
    fun sortFormatsKeepsDistinctDirectQualitiesThatShareFormatId() {
        val video = videoInfo(directFormats())
        val heights = listOf(1080, 720, 480)

        assertEquals(
            heights,
            VideoFormatUi.sortFormats(video, video.formats.formats).map { it.height }
        )
    }

    /** 升级前的旧数据没有盖章，但当时直接探针捕获的候选都带 isRegularDownload=true。 */
    @Test
    fun legacyUnstampedDirectCandidatesStayDistinct() {
        val video = videoInfo(directFormats(stamped = false)).copy(isRegularDownload = true)

        assertEquals(
            listOf(1080, 720, 480),
            VideoFormatUi.sortFormats(video, video.formats.formats).map { it.height }
        )
    }

    @Test
    fun selectionKeyDiffersForDirectQualitiesSharingFormatId() {
        val video = videoInfo(directFormats())
        val keys = video.formats.formats.map { VideoFormatUi.selectionKey(video, it) }

        assertEquals(keys.size, keys.distinct().size)
        assertNotEquals(keys[0], keys[2])
    }

    @Test
    fun findFormatStillResolvesTheLegacyKeyWrittenBeforeTheUpgrade() {
        val video = videoInfo(directFormats())

        // 升级前保存的旧选择键 = formatId（"0"）；仍必须能解析回一条真实 format。
        assertEquals(
            video.formats.formats.first().id,
            VideoFormatUi.findFormat(video, "0")?.id
        )
        assertEquals(
            video.formats.formats[1].id,
            VideoFormatUi.findFormat(video, VideoFormatUi.selectionKey(video, video.formats.formats[1]))?.id
        )
        assertNull(VideoFormatUi.findFormat(video, "missing-key"))
        assertNull(VideoFormatUi.findFormat(video, null))
    }

    @Test
    fun defaultSelectionKeyPrefersTheHighestQuality() {
        val video = videoInfo(directFormats())

        assertEquals(
            VideoFormatUi.selectionKey(video, video.formats.formats.first()),
            VideoFormatUi.defaultSelectionKey(video)
        )
    }

    private fun directFormats(stamped: Boolean = true): List<VideoFormatEntity> =
        listOf(1080, 720, 480).map { height ->
            VideoFormatEntity(
                formatId = "0",
                format = "mp4",
                formatNote = "${height}p",
                ext = "mp4",
                height = height,
                url = "https://cdn.example/$height.mp4",
                downloadStrategy = DownloadStrategy.DIRECT_HTTP.name.takeIf { stamped }
            )
        }

    private fun videoInfo(formats: List<VideoFormatEntity>): VideoInfo = VideoInfo(
        id = "video",
        title = "Title",
        ext = "mp4",
        originalUrl = "https://page.example/watch?v=1",
        formats = VideFormatEntityList(formats)
    )
}
