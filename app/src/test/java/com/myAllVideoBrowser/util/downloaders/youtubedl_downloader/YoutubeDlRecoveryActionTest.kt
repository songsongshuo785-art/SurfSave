package com.myAllVideoBrowser.util.downloaders.youtubedl_downloader

import com.myAllVideoBrowser.data.local.room.entity.ProgressInfo
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskState
import org.junit.Assert.assertEquals
import org.junit.Test

class YoutubeDlRecoveryActionTest {

    @Test
    fun activeStates_ensureExistingDownload() {
        listOf(
            VideoTaskState.PREPARE,
            VideoTaskState.START,
            VideoTaskState.DOWNLOADING,
            VideoTaskState.PROXYREADY
        ).forEach { state ->
            assertEquals(
                YoutubeDlRecoveryAction.ENSURE_DOWNLOAD,
                youtubeDlRecoveryAction(task(state))
            )
        }
    }

    @Test
    fun transitionalStates_mapToTheirRecoveryOperations() {
        assertEquals(
            YoutubeDlRecoveryAction.PAUSE,
            youtubeDlRecoveryAction(task(VideoTaskState.PAUSING, YoutubeDlStopReason.PAUSE))
        )
        assertEquals(
            YoutubeDlRecoveryAction.STOP_AND_SAVE,
            youtubeDlRecoveryAction(
                task(VideoTaskState.PAUSING, YoutubeDlStopReason.STOP_AND_SAVE)
            )
        )
        assertEquals(
            YoutubeDlRecoveryAction.CANCEL,
            youtubeDlRecoveryAction(task(VideoTaskState.CANCELING, YoutubeDlStopReason.CANCEL))
        )
        assertEquals(
            YoutubeDlRecoveryAction.FINALIZE,
            youtubeDlRecoveryAction(task(VideoTaskState.FINALIZING))
        )
    }

    @Test
    fun stableState_requiresNoRecoveryWork() {
        assertEquals(
            YoutubeDlRecoveryAction.NONE,
            youtubeDlRecoveryAction(task(VideoTaskState.PAUSE, YoutubeDlStopReason.PAUSE))
        )
    }

    private fun task(status: Int, stopReason: Int = YoutubeDlStopReason.NONE): ProgressInfo {
        return ProgressInfo(
            id = "task-$status-$stopReason",
            videoInfo = VideoInfo(id = "video"),
            downloadStatus = status,
            stopReason = stopReason,
            executionToken = "token"
        )
    }
}
