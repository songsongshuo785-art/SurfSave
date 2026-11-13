package com.myAllVideoBrowser.ui.main.progress

import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.data.local.room.entity.ProgressInfo
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskState
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadFilterTest {

    private fun info(id: String, status: Int) = ProgressInfo(
        id = id,
        videoInfo = VideoInfo(title = id, ext = "mp4"),
        downloadStatus = status,
    )

    @Test
    fun downloadsAll_containsActiveAndCompleted() {
        val active = listOf(info("active", VideoTaskState.DOWNLOADING))
        val history = listOf(info("done", VideoTaskState.SUCCESS))

        assertEquals(
            listOf("active", "done"),
            DownloadFilter.mediaFor(R.id.downloads_all, active, history).map { it.id },
        )
        assertEquals(
            listOf("active"),
            DownloadFilter.mediaFor(R.id.downloads_active, active, history).map { it.id },
        )
        assertEquals(
            listOf("done"),
            DownloadFilter.mediaFor(R.id.downloads_completed, active, history).map { it.id },
        )
    }
}
