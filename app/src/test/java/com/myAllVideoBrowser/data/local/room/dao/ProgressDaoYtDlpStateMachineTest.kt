package com.myAllVideoBrowser.data.local.room.dao

import android.app.Application
import androidx.room.Room
import com.myAllVideoBrowser.data.local.room.AppDatabase
import com.myAllVideoBrowser.data.local.room.entity.ProgressInfo
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskState
import com.myAllVideoBrowser.util.downloaders.youtubedl_downloader.YoutubeDlStopReason
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ProgressDaoYtDlpStateMachineTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: ProgressDao

    @Before
    fun setup() {
        val context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.progressDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun claimExecution_onlyFirstTokenWins() {
        dao.insertProgressInfo(progressInfo("claim"))

        val first = dao.claimYtDlpExecution("claim", "token-a", 100L, "/logs/a")
        val second = dao.claimYtDlpExecution("claim", "token-b", 200L, "/logs/b")

        assertEquals(listOf(0, 1), listOf(first, second).sorted())
        val stored = requireNotNull(dao.getProgressInfoById("claim"))
        assertEquals(VideoTaskState.PREPARE, stored.downloadStatus)
        assertEquals("token-a", stored.executionToken)
        assertEquals(YoutubeDlStopReason.NONE, stored.stopReason)
        assertEquals(100L, stored.startedAt)
        assertEquals("/logs/a", stored.logPath)
    }

    @Test
    fun progressUpdate_rejectsOldTokenAndNonActiveStates() {
        dao.insertProgressInfo(progressInfo("active"))
        assertEquals(1, dao.claimYtDlpExecution("active", "current", 10L, "/logs/current"))
        assertEquals(0, updateProgress("active", "old", 25L))
        assertEquals(1, updateProgress("active", "current", 50L))
        assertEquals(50L, dao.getProgressInfoById("active")?.progressDownloaded)

        assertEquals(
            1,
            dao.requestYtDlpPause(
                id = "active",
                token = "current",
                reason = YoutubeDlStopReason.PAUSE,
                queuedForLater = false,
                infoLine = "Pausing",
                logPath = "/logs/current"
            )
        )
        assertEquals(0, updateProgress("active", "current", 75L))

        dao.insertProgressInfo(
            progressInfo(
                id = "canceling",
                status = VideoTaskState.CANCELING,
                token = "cancel-token",
                stopReason = YoutubeDlStopReason.CANCEL
            )
        )
        assertEquals(0, updateProgress("canceling", "cancel-token", 75L))

        dao.insertProgressInfo(
            progressInfo(
                id = "finalizing",
                status = VideoTaskState.FINALIZING,
                token = "final-token"
            )
        )
        assertEquals(0, updateProgress("finalizing", "final-token", 75L))
    }

    @Test
    fun pauseThenCancel_cancelWinsAndLatePauseCannotCommit() {
        dao.insertProgressInfo(progressInfo("pause-cancel"))
        assertEquals(1, dao.claimYtDlpExecution("pause-cancel", "run-token", 10L, "/logs/run"))
        assertEquals(
            1,
            dao.requestYtDlpPause(
                id = "pause-cancel",
                token = "run-token",
                reason = YoutubeDlStopReason.PAUSE,
                queuedForLater = true,
                infoLine = "Pausing",
                logPath = "/logs/run"
            )
        )
        assertEquals(
            1,
            dao.requestYtDlpCancel(
                id = "pause-cancel",
                expectedToken = "run-token",
                assignedToken = "cancel-token",
                removePartial = true,
                logPath = "/logs/cancel"
            )
        )

        assertEquals(0, dao.commitYtDlpPause("pause-cancel", "run-token", "Paused"))
        assertEquals(0, dao.commitYtDlpCanceled("pause-cancel", "run-token", 100L))
        assertEquals(1, dao.commitYtDlpCanceled("pause-cancel", "cancel-token", 100L))
        assertEquals(0, dao.commitYtDlpCanceled("pause-cancel", "cancel-token", 200L))

        val stored = requireNotNull(dao.getProgressInfoById("pause-cancel"))
        assertEquals(VideoTaskState.CANCELED, stored.downloadStatus)
        assertEquals(YoutubeDlStopReason.CANCEL, stored.stopReason)
        assertEquals("cancel-token", stored.executionToken)
        assertEquals(true, stored.removePartialOnCancel)
        assertEquals(100L, stored.completedAt)
    }

    @Test
    fun stopAndSaveCanClaimFinalization_butOrdinaryPauseCannot() {
        dao.insertProgressInfo(
            progressInfo(
                id = "stop-save",
                status = VideoTaskState.PAUSING,
                token = "stop-token",
                stopReason = YoutubeDlStopReason.STOP_AND_SAVE
            )
        )
        dao.insertProgressInfo(
            progressInfo(
                id = "ordinary-pause",
                status = VideoTaskState.PAUSING,
                token = "pause-token",
                stopReason = YoutubeDlStopReason.PAUSE
            )
        )

        assertEquals(0, dao.commitYtDlpPause("stop-save", "stop-token", "Paused"))
        assertEquals(
            1,
            dao.claimYtDlpFinalization("stop-save", "stop-token", "/tmp/stop", "/out/stop")
        )
        assertEquals(
            0,
            dao.claimYtDlpFinalization("ordinary-pause", "pause-token", "/tmp/pause", "/out/pause")
        )

        val stopSave = requireNotNull(dao.getProgressInfoById("stop-save"))
        assertEquals(VideoTaskState.FINALIZING, stopSave.downloadStatus)
        assertEquals(YoutubeDlStopReason.STOP_AND_SAVE, stopSave.stopReason)
        assertEquals("/tmp/stop", stopSave.finalizationSource)
        assertEquals("/out/stop", stopSave.finalizationTarget)
        assertEquals(VideoTaskState.PAUSING, dao.getProgressInfoById("ordinary-pause")?.downloadStatus)
    }

    @Test
    fun legacyActiveStates_adoptAsSafePauseTransition() {
        val legacyStates = listOf(
            VideoTaskState.PREPARE,
            VideoTaskState.START,
            VideoTaskState.DOWNLOADING,
            VideoTaskState.PROXYREADY
        )
        legacyStates.forEach { state ->
            val id = "legacy-active-$state"
            dao.insertProgressInfo(progressInfo(id = id, status = state))

            assertEquals(1, dao.adoptLegacyYtDlpExecution(id, "adopted-$id"))
            assertEquals(0, dao.adoptLegacyYtDlpExecution(id, "second-$id"))

            val stored = requireNotNull(dao.getProgressInfoById(id))
            assertEquals(VideoTaskState.PAUSING, stored.downloadStatus)
            assertEquals(YoutubeDlStopReason.PAUSE, stored.stopReason)
            assertEquals("adopted-$id", stored.executionToken)
        }
    }

    @Test
    fun blankTokenTransitionalStates_areNotAdopted() {
        val transitionalStates = listOf(
            Triple("blank-pausing", VideoTaskState.PAUSING, YoutubeDlStopReason.PAUSE),
            Triple("blank-canceling", VideoTaskState.CANCELING, YoutubeDlStopReason.CANCEL),
            Triple("blank-finalizing", VideoTaskState.FINALIZING, YoutubeDlStopReason.NONE)
        )
        transitionalStates.forEach { (id, state, reason) ->
            dao.insertProgressInfo(progressInfo(id = id, status = state, stopReason = reason))

            assertEquals(0, dao.adoptLegacyYtDlpExecution(id, "unsafe-token"))
            val stored = requireNotNull(dao.getProgressInfoById(id))
            assertEquals(state, stored.downloadStatus)
            assertEquals(reason, stored.stopReason)
            assertEquals("", stored.executionToken)
        }
    }

    @Test
    fun resumeRequiresStablePauseAndRejectsOldExecutionAfterNewClaim() {
        dao.insertProgressInfo(progressInfo("resume"))
        assertEquals(1, dao.claimYtDlpExecution("resume", "old-token", 10L, "/logs/old"))
        assertEquals(
            1,
            dao.requestYtDlpPause(
                id = "resume",
                token = "old-token",
                reason = YoutubeDlStopReason.PAUSE,
                queuedForLater = false,
                infoLine = "Pausing",
                logPath = "/logs/old"
            )
        )
        assertEquals(0, dao.resumeYtDlp("resume", 20L, "/logs/resume"))
        assertEquals(1, dao.commitYtDlpPause("resume", "old-token", "Paused"))
        assertEquals(1, dao.resumeYtDlp("resume", 20L, "/logs/resume"))

        val pending = requireNotNull(dao.getProgressInfoById("resume"))
        assertEquals(VideoTaskState.PENDING, pending.downloadStatus)
        assertEquals("", pending.executionToken)
        assertEquals(YoutubeDlStopReason.NONE, pending.stopReason)
        assertEquals(0, updateProgress("resume", "old-token", 30L))
        assertEquals(0, dao.commitYtDlpError("resume", "old-token", 100L, "late error"))
        assertEquals(0, dao.claimYtDlpFinalization("resume", "old-token", "/tmp/old", "/out/old"))

        assertEquals(1, dao.claimYtDlpExecution("resume", "new-token", 200L, "/logs/new"))
        assertEquals(0, updateProgress("resume", "old-token", 40L))
        assertEquals(0, dao.commitYtDlpError("resume", "old-token", 300L, "late error"))
        assertEquals(0, dao.claimYtDlpFinalization("resume", "old-token", "/tmp/old", "/out/old"))
        assertEquals(1, updateProgress("resume", "new-token", 50L))

        val current = requireNotNull(dao.getProgressInfoById("resume"))
        assertEquals("new-token", current.executionToken)
        assertEquals(VideoTaskState.DOWNLOADING, current.downloadStatus)
        assertEquals(50L, current.progressDownloaded)
    }

    @Test
    fun finalization_claimAndTerminalCommitAreExactlyOnce() {
        dao.insertProgressInfo(progressInfo("finalize", total = 1_000L))
        assertEquals(1, dao.claimYtDlpExecution("finalize", "current", 10L, "/logs/run"))

        assertEquals(0, dao.claimYtDlpFinalization("finalize", "old", "/tmp/old", "/out/old"))
        assertEquals(1, dao.claimYtDlpFinalization("finalize", "current", "/tmp/source", "/out/target"))
        assertEquals(0, dao.claimYtDlpFinalization("finalize", "current", "/tmp/other", "/out/other"))
        assertEquals(
            0,
            dao.requestYtDlpPause(
                id = "finalize",
                token = "current",
                reason = YoutubeDlStopReason.PAUSE,
                queuedForLater = false,
                infoLine = "Pausing",
                logPath = "/logs/run"
            )
        )
        assertEquals(
            0,
            dao.requestYtDlpCancel(
                id = "finalize",
                expectedToken = "current",
                assignedToken = "cancel",
                removePartial = true,
                logPath = "/logs/cancel"
            )
        )

        assertEquals(0, commitFinalization("finalize", "old", VideoTaskState.SUCCESS, 100L, ""))
        assertEquals(1, commitFinalization("finalize", "current", VideoTaskState.SUCCESS, 200L, ""))
        assertEquals(0, commitFinalization("finalize", "current", VideoTaskState.ERROR, 300L, "late error"))

        val stored = requireNotNull(dao.getProgressInfoById("finalize"))
        assertEquals(VideoTaskState.SUCCESS, stored.downloadStatus)
        assertEquals(1_000L, stored.progressDownloaded)
        assertEquals(200L, stored.completedAt)
        assertEquals("/tmp/source", stored.finalizationSource)
        assertEquals("/out/target", stored.finalizationTarget)
    }

    @Test
    fun ordinaryErrorCommit_requiresCurrentActiveExecution() {
        dao.insertProgressInfo(progressInfo("error"))
        assertEquals(1, dao.claimYtDlpExecution("error", "current", 10L, "/logs/run"))

        assertEquals(0, dao.commitYtDlpError("error", "old", 100L, "old failure"))
        assertEquals(1, dao.commitYtDlpError("error", "current", 200L, "visible failure"))
        assertEquals(0, dao.commitYtDlpError("error", "current", 300L, "late failure"))

        val stored = requireNotNull(dao.getProgressInfoById("error"))
        assertEquals(VideoTaskState.ERROR, stored.downloadStatus)
        assertEquals("visible failure", stored.lastError)
        assertEquals(200L, stored.completedAt)
    }

    @Test
    fun pendingBlankToken_canBeCanceledCommittedAndConditionallyDeleted() {
        dao.insertProgressInfo(progressInfo("pending-cancel"))

        assertEquals(
            1,
            dao.requestYtDlpCancel(
                id = "pending-cancel",
                expectedToken = "",
                assignedToken = "cancel-token",
                removePartial = true,
                logPath = "/logs/cancel"
            )
        )
        val canceling = requireNotNull(dao.getProgressInfoById("pending-cancel"))
        assertEquals(VideoTaskState.CANCELING, canceling.downloadStatus)
        assertEquals(YoutubeDlStopReason.CANCEL, canceling.stopReason)
        assertEquals("cancel-token", canceling.executionToken)
        assertEquals(true, canceling.removePartialOnCancel)

        assertEquals(0, dao.commitYtDlpCanceled("pending-cancel", "", 100L))
        assertEquals(1, dao.commitYtDlpCanceled("pending-cancel", "cancel-token", 100L))
        assertEquals(0, dao.deleteCommittedYtDlpCanceled("pending-cancel", "wrong"))
        assertEquals(1, dao.deleteCommittedYtDlpCanceled("pending-cancel", "cancel-token"))
        assertNull(dao.getProgressInfoById("pending-cancel"))
    }

    private fun updateProgress(id: String, token: String, downloaded: Long): Int {
        return dao.updateYtDlpProgress(
            id = id,
            token = token,
            downloaded = downloaded,
            total = 100L,
            fragDownloaded = 1,
            fragTotal = 2,
            infoLine = "Downloading",
            startedAt = 10L,
            logPath = "/logs/$id",
            isLive = false
        )
    }

    private fun commitFinalization(id: String, token: String, status: Int, completedAt: Long, error: String): Int {
        return dao.commitYtDlpFinalization(
            id = id,
            token = token,
            status = status,
            completedAt = completedAt,
            lastError = error,
            infoLine = if (error.isEmpty()) "Done" else "Failed"
        )
    }

    private fun progressInfo(
        id: String,
        status: Int = VideoTaskState.PENDING,
        token: String = "",
        stopReason: Int = YoutubeDlStopReason.NONE,
        total: Long = 100L
    ): ProgressInfo {
        return ProgressInfo(
            id = id,
            videoInfo = VideoInfo(id = id),
            progressTotal = total,
            downloadStatus = status,
            stopReason = stopReason,
            executionToken = token
        )
    }
}
