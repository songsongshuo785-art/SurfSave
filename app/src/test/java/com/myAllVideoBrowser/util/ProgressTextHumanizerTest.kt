package com.myAllVideoBrowser.util

import android.app.Application
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.data.local.room.entity.ProgressInfo
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ProgressTextHumanizerTest {

    @Test
    fun unknownTotalDoesNotRenderZeroOverZero() {
        val context = RuntimeEnvironment.getApplication()
        val info = ProgressInfo(
            videoInfo = VideoInfo(title = "clip", ext = "mp4"),
            progressDownloaded = 0,
            progressTotal = 0,
            downloadStatus = VideoTaskState.DOWNLOADING
        )

        val line = ProgressTextHumanizer.progressLine(context, info)
        assertFalse(line.contains("0 B / 0 B"))
        assertTrue(line.contains(context.getString(R.string.candidate_unknown_size)))
        assertTrue(info.isProgressIndeterminate)
    }

    @Test
    fun knownTotalAtZeroPercentUsesDeterminateProgress() {
        val info = ProgressInfo(
            videoInfo = VideoInfo(title = "clip", ext = "mp4"),
            progressDownloaded = 0,
            progressTotal = 10,
            downloadStatus = VideoTaskState.DOWNLOADING
        )

        assertFalse(info.isProgressIndeterminate)
    }
}
