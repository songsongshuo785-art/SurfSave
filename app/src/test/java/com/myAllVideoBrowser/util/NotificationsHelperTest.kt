package com.myAllVideoBrowser.util

import android.app.Application
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskItem
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskState
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NotificationsHelperTest {
    private val context: Application
        get() = RuntimeEnvironment.getApplication()

    @Test
    fun errorNotificationShowsRetryAction() {
        val notification = NotificationsHelper(context)
            .createNotificationBuilder(task(VideoTaskState.ERROR))
            .second
            .build()

        val actionTitles = notification.actions.map { it.title.toString() }
        assertTrue(actionTitles.contains(context.getString(R.string.progress_menu_retry)))
    }

    @Test
    fun pausedNotificationKeepsResumeAction() {
        val notification = NotificationsHelper(context)
            .createNotificationBuilder(task(VideoTaskState.PAUSE))
            .second
            .build()

        val actionTitles = notification.actions.map { it.title.toString() }
        assertTrue(actionTitles.contains(context.getString(R.string.progress_menu_resume)))
    }

    private fun task(state: Int) = VideoTaskItem("https://example.com/video.mp4").apply {
        mId = "notification-task-$state"
        fileName = "video.mp4"
        taskState = state
        errorMessage = "failed"
    }
}
