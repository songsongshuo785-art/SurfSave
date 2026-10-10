package com.myAllVideoBrowser.util.downloaders

import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskState
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadStartupRecoveryCoordinatorTest {

    @Test
    fun activeDownloadStatesAreResumed() {
        val active = listOf(
            VideoTaskState.PREPARE,
            VideoTaskState.START,
            VideoTaskState.DOWNLOADING,
            VideoTaskState.PROXYREADY
        )

        active.forEach { status ->
            assertEquals(
                "status=$status",
                OrphanRecoveryDecision.RESUME,
                orphanRecoveryDecision(status)
            )
        }
    }

    @Test
    fun interruptedUserActionsKeepTheirIntent() {
        assertEquals(OrphanRecoveryDecision.MARK_PAUSED, orphanRecoveryDecision(VideoTaskState.PAUSING))
        assertEquals(OrphanRecoveryDecision.MARK_CANCELED, orphanRecoveryDecision(VideoTaskState.CANCELING))
        assertEquals(OrphanRecoveryDecision.MARK_ERROR, orphanRecoveryDecision(VideoTaskState.FINALIZING))
    }

    @Test
    fun queueAndTerminalStatesAreNeverTouched() {
        val untouched = listOf(
            VideoTaskState.DEFAULT,
            VideoTaskState.PENDING,
            VideoTaskState.PAUSE,
            VideoTaskState.SUCCESS,
            VideoTaskState.ERROR,
            VideoTaskState.ENOSPC,
            VideoTaskState.CANCELED
        )

        untouched.forEach { status ->
            assertEquals(
                "status=$status",
                OrphanRecoveryDecision.SKIP,
                orphanRecoveryDecision(status)
            )
        }
    }
}
