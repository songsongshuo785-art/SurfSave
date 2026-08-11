package com.myAllVideoBrowser.util.downloaders.youtubedl_downloader

import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YoutubeDlTerminalNotificationGateTest {

    @Test
    fun matchingExecutionAndStatus_canShowNotification() {
        assertTrue(
            shouldShowYoutubeDlTerminalNotification(
                currentExecutionToken = "token-1",
                currentStatus = VideoTaskState.PAUSE,
                expectedExecutionToken = "token-1",
                expectedStatus = VideoTaskState.PAUSE
            )
        )
    }

    @Test
    fun replacedExecution_cannotShowStaleNotification() {
        assertFalse(
            shouldShowYoutubeDlTerminalNotification(
                currentExecutionToken = "token-2",
                currentStatus = VideoTaskState.DOWNLOADING,
                expectedExecutionToken = "token-1",
                expectedStatus = VideoTaskState.PAUSE
            )
        )
    }

    @Test
    fun changedStatus_cannotShowStaleNotification() {
        assertFalse(
            shouldShowYoutubeDlTerminalNotification(
                currentExecutionToken = "token-1",
                currentStatus = VideoTaskState.DOWNLOADING,
                expectedExecutionToken = "token-1",
                expectedStatus = VideoTaskState.ERROR
            )
        )
    }

    @Test
    fun missingTask_cannotShowNotification() {
        assertFalse(
            shouldShowYoutubeDlTerminalNotification(
                currentExecutionToken = null,
                currentStatus = null,
                expectedExecutionToken = "token-1",
                expectedStatus = VideoTaskState.CANCELED
            )
        )
    }
}
