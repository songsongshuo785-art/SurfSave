package com.myAllVideoBrowser.util.downloaders

import android.content.Context
import com.myAllVideoBrowser.DLApplication
import com.myAllVideoBrowser.data.local.room.entity.ProgressInfo
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.data.repository.ProgressRepository
import com.myAllVideoBrowser.util.DownloadFilenameTemplate
import com.myAllVideoBrowser.util.FileUtil
import com.myAllVideoBrowser.util.SharedPrefHelper
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskState
import io.reactivex.rxjava3.core.Flowable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

/**
 * 钉住 DownloadQueueManager.scheduleNextLocked 的运行时边界：
 * 当某个任务的 engineRouter.start 抛异常时，后续任务不能被重复启动。
 *
 * 背景：catch 内若调用会触发调度的 onTaskTerminal，会递归 scheduleNextLocked 并启动后续任务，
 * 回到外层 forEach 后旧快照里的同一任务会被再启动一次。修复方式是 catch 只 markTerminalLocked（不调度），
 * 循环结束后统一推进一次。
 */
class DownloadQueueManagerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val contextPlaceholder: Context = Mockito.mock(Context::class.java)

    @Test
    fun enqueue_duplicateUsesFingerprintQueryWithoutLoadingQueueOrScanningFiles() {
        val incoming = VideoInfo(
            id = "incoming",
            title = "clip",
            ext = "mp4",
            isRegularDownload = true
        )
        val fingerprint = DownloadFingerprint.fromVideoInfo(incoming)
        val repo = FakeProgressRepository().apply {
            save(
                ProgressInfo(
                    id = "existing",
                    videoInfo = incoming.copy(id = "existing"),
                    downloadStatus = VideoTaskState.DOWNLOADING,
                    downloadFingerprint = fingerprint
                )
            )
        }
        val app = Mockito.mock(DLApplication::class.java)
        val sharedPrefHelper = Mockito.mock(SharedPrefHelper::class.java)
        val fileUtil = Mockito.mock(FileUtil::class.java)
        val taskLogger = Mockito.mock(DownloadTaskLogger::class.java)
        val engineRouter = Mockito.mock(DownloadEngineRouter::class.java)
        Mockito.`when`(sharedPrefHelper.getDownloadFilenameTemplate())
            .thenReturn(DownloadFilenameTemplate.DEFAULT_TEMPLATE)
        val manager = DownloadQueueManager(
            app,
            repo,
            sharedPrefHelper,
            fileUtil,
            engineRouter,
            taskLogger
        )

        val result = manager.enqueue(incoming)

        assertEquals(true, result is DownloadQueueManager.EnqueueResult.Duplicate)
        assertEquals(1, repo.fingerprintLookupCount)
        assertEquals(0, repo.progressInfosOnceReadCount)
        Mockito.verifyNoInteractions(fileUtil)
        Mockito.verifyNoInteractions(engineRouter)
    }

    @Test
    fun enqueue_existingManagedFileUsesExactNameQueryWithoutLoadingQueue() {
        val incoming = VideoInfo(
            id = "incoming",
            title = "clip",
            ext = "mp4",
            isRegularDownload = true
        )
        val repo = FakeProgressRepository()
        val app = Mockito.mock(DLApplication::class.java)
        val sharedPrefHelper = Mockito.mock(SharedPrefHelper::class.java)
        val fileUtil = Mockito.mock(FileUtil::class.java)
        val taskLogger = Mockito.mock(DownloadTaskLogger::class.java)
        val engineRouter = Mockito.mock(DownloadEngineRouter::class.java)
        Mockito.`when`(sharedPrefHelper.getDownloadFilenameTemplate())
            .thenReturn(DownloadFilenameTemplate.DEFAULT_TEMPLATE)
        Mockito.`when`(fileUtil.hasDownloadWithName(app, "clip.mp4")).thenReturn(true)
        val manager = DownloadQueueManager(
            app,
            repo,
            sharedPrefHelper,
            fileUtil,
            engineRouter,
            taskLogger
        )

        val result = manager.enqueue(incoming)

        assertEquals(true, result is DownloadQueueManager.EnqueueResult.Rejected)
        assertEquals(1, repo.fingerprintLookupCount)
        assertEquals(0, repo.progressInfosOnceReadCount)
        verify(fileUtil).hasDownloadWithName(app, "clip.mp4")
        Mockito.verifyNoInteractions(engineRouter)
    }

    @Test
    fun scheduleNext_doesNotStartLaterTaskTwiceWhenEarlierStartThrows() {
        val repo = FakeProgressRepository()
        repo.save(progressInfo("A", queuePosition = 1L))
        repo.save(progressInfo("B", queuePosition = 2L))

        val app = Mockito.mock(DLApplication::class.java)
        val sharedPrefHelper = Mockito.mock(SharedPrefHelper::class.java)
        val fileUtil = Mockito.mock(FileUtil::class.java)
        val taskLogger = Mockito.mock(DownloadTaskLogger::class.java)
        val engineRouter = Mockito.mock(DownloadEngineRouter::class.java)

        Mockito.`when`(sharedPrefHelper.getMaxConcurrentDownloads()).thenReturn(2)
        Mockito.`when`(taskLogger.logPath(ArgumentMatchers.anyString())).thenReturn("/tmp/task.log")

        // start(A) 抛异常，start(B) 正常返回
        doAnswer { invocation ->
            val task = invocation.getArgument<ProgressInfo>(1)
            if (task.id == "A") throw RuntimeException("start A failed")
            null
        }.`when`(engineRouter).start(anyContext(), anyProgressInfo())

        val manager = DownloadQueueManager(
            application = app,
            progressRepository = repo,
            sharedPrefHelper = sharedPrefHelper,
            fileUtil = fileUtil,
            engineRouter = engineRouter,
            taskLogger = taskLogger
        )
        val startedNow = manager.scheduleNext()

        // 旧实现会启动 B 两次（catch 递归 + 外层 forEach），这里断言总共恰好 2 次（A、B 各一次）
        val startCaptor = ArgumentCaptor.forClass(ProgressInfo::class.java)
        verify(engineRouter, times(2)).start(anyContext(), captureProgressInfo(startCaptor))
        assertEquals(listOf("A", "B"), startCaptor.allValues.map { it.id })

        // 返回值只含实际启动成功的任务：A 启动抛异常已回滚 ERROR，不在 startedNow 里
        assertEquals(listOf("B"), startedNow.map { it.id })

        // A 被回滚为 ERROR，B 进入 PREPARE 正在下载
        assertEquals(VideoTaskState.ERROR, repo.getProgressInfoById("A")?.downloadStatus)
        assertEquals(VideoTaskState.PREPARE, repo.getProgressInfoById("B")?.downloadStatus)
    }

    @Test
    fun cancel_pendingYtDlpPersistsIntentWithoutDeletingTask() {
        val repo = FakeProgressRepository()
        repo.save(progressInfo("A", queuePosition = 1L))
        val app = Mockito.mock(DLApplication::class.java)
        val sharedPrefHelper = Mockito.mock(SharedPrefHelper::class.java)
        val fileUtil = Mockito.mock(FileUtil::class.java)
        val taskLogger = Mockito.mock(DownloadTaskLogger::class.java)
        val engineRouter = Mockito.mock(DownloadEngineRouter::class.java)
        Mockito.`when`(taskLogger.logPath("A")).thenReturn("/tmp/A.log")
        val manager = DownloadQueueManager(
            app,
            repo,
            sharedPrefHelper,
            fileUtil,
            engineRouter,
            taskLogger
        )

        manager.cancel("A", removeFile = true)

        val updated = requireNotNull(repo.getProgressInfoById("A"))
        assertEquals(VideoTaskState.CANCELING, updated.downloadStatus)
        assertEquals(2, updated.stopReason)
        assertEquals(true, updated.removePartialOnCancel)
        assertNotEquals("", updated.executionToken)
        verify(engineRouter).cancel(app, updated, true)
    }

    @Test
    fun scheduleNext_startFailureCannotOverwriteConcurrentCancelIntent() {
        val repo = FakeProgressRepository()
        repo.save(progressInfo("A", queuePosition = 1L))
        val app = Mockito.mock(DLApplication::class.java)
        val sharedPrefHelper = Mockito.mock(SharedPrefHelper::class.java)
        val fileUtil = Mockito.mock(FileUtil::class.java)
        val taskLogger = Mockito.mock(DownloadTaskLogger::class.java)
        val engineRouter = Mockito.mock(DownloadEngineRouter::class.java)
        Mockito.`when`(sharedPrefHelper.getMaxConcurrentDownloads()).thenReturn(1)
        Mockito.`when`(taskLogger.logPath("A")).thenReturn("/tmp/A.log")
        doAnswer { invocation ->
            val claimed = invocation.getArgument<ProgressInfo>(1)
            assertEquals(
                1,
                repo.requestYtDlpCancel(
                    claimed.id,
                    claimed.executionToken,
                    claimed.executionToken,
                    false,
                    claimed.logPath
                )
            )
            throw RuntimeException("native start failed after cancel won")
        }.`when`(engineRouter).start(anyContext(), anyProgressInfo())
        val manager = DownloadQueueManager(
            app,
            repo,
            sharedPrefHelper,
            fileUtil,
            engineRouter,
            taskLogger
        )

        val started = manager.scheduleNext()

        val updated = requireNotNull(repo.getProgressInfoById("A"))
        assertEquals(emptyList<String>(), started.map { it.id })
        assertEquals(VideoTaskState.CANCELING, updated.downloadStatus)
        assertEquals(2, updated.stopReason)
        assertEquals("", updated.lastError)
    }

    @Test
    fun scheduleNext_transitionStateStillOccupiesConcurrencySlot() {
        val repo = FakeProgressRepository()
        repo.save(
            progressInfo(
                "A",
                queuePosition = 1L,
                status = VideoTaskState.PAUSING,
                executionToken = "token-A"
            )
        )
        repo.save(progressInfo("B", queuePosition = 2L))
        val app = Mockito.mock(DLApplication::class.java)
        val sharedPrefHelper = Mockito.mock(SharedPrefHelper::class.java)
        val fileUtil = Mockito.mock(FileUtil::class.java)
        val taskLogger = Mockito.mock(DownloadTaskLogger::class.java)
        val engineRouter = Mockito.mock(DownloadEngineRouter::class.java)
        Mockito.`when`(sharedPrefHelper.getMaxConcurrentDownloads()).thenReturn(1)
        val manager = DownloadQueueManager(
            app,
            repo,
            sharedPrefHelper,
            fileUtil,
            engineRouter,
            taskLogger
        )

        assertEquals(emptyList<String>(), manager.scheduleNext().map { it.id })
        Mockito.verifyNoInteractions(engineRouter)
        assertEquals(VideoTaskState.PENDING, repo.getProgressInfoById("B")?.downloadStatus)
    }

    @Test
    fun resume_failedYtDlpWithSource_retriesPublicationWithoutRequeueingDownload() {
        val source = temporaryFolder.newFile("downloaded.mp4")
        val repo = FakeProgressRepository().apply {
            save(failedYtDlpTask("publication-retry", source.absolutePath))
        }
        val app = Mockito.mock(DLApplication::class.java)
        val fileUtil = Mockito.mock(FileUtil::class.java)
        val engineRouter = Mockito.mock(DownloadEngineRouter::class.java)
        val manager = DownloadQueueManager(
            app,
            repo,
            Mockito.mock(SharedPrefHelper::class.java),
            fileUtil,
            engineRouter,
            Mockito.mock(DownloadTaskLogger::class.java)
        )

        manager.resume("publication-retry")

        val updated = requireNotNull(repo.getProgressInfoById("publication-retry"))
        assertEquals(VideoTaskState.FINALIZING, updated.downloadStatus)
        assertEquals(source.absolutePath, updated.finalizationSource)
        verify(engineRouter).recoverFinalization(app, updated)
        Mockito.verifyNoMoreInteractions(engineRouter)
    }

    @Test
    fun resume_failedYtDlpWithoutRecoverableArtifact_requeuesDownload() {
        val missingSource = temporaryFolder.root.resolve("missing.mp4")
        val repo = FakeProgressRepository().apply {
            save(failedYtDlpTask("download-retry", missingSource.absolutePath))
        }
        val app = Mockito.mock(DLApplication::class.java)
        val engineRouter = Mockito.mock(DownloadEngineRouter::class.java)
        val manager = DownloadQueueManager(
            app,
            repo,
            Mockito.mock(SharedPrefHelper::class.java),
            Mockito.mock(FileUtil::class.java),
            engineRouter,
            Mockito.mock(DownloadTaskLogger::class.java)
        )

        manager.resume("download-retry")

        val updated = requireNotNull(repo.getProgressInfoById("download-retry"))
        assertEquals(VideoTaskState.PENDING, updated.downloadStatus)
        assertEquals("", updated.executionToken)
        assertEquals("", updated.finalizationSource)
        assertEquals("", updated.finalizationTarget)
        assertTrue(updated.queuePosition > 0L)
        Mockito.verifyNoInteractions(engineRouter)
    }

    // Mockito 的 any(Class)/capture() 对对象类型返回 null，直接传给 Kotlin 非空参数会触发
    // Intrinsics 的 NPE（"any(...) must not be null"）。这里先注册 matcher（Mockito 只认 matcher
    // 注册，不看返回值），再返回非 null 占位值，绕过 Kotlin 非空检查。
    private fun anyContext(): Context {
        ArgumentMatchers.any(Context::class.java)
        return contextPlaceholder
    }

    private fun anyProgressInfo(): ProgressInfo {
        ArgumentMatchers.any(ProgressInfo::class.java)
        return progressInfo("matcher-placeholder", 0L)
    }

    private fun captureProgressInfo(captor: ArgumentCaptor<ProgressInfo>): ProgressInfo {
        captor.capture()
        return progressInfo("capture-placeholder", 0L)
    }

    private fun progressInfo(
        id: String,
        queuePosition: Long,
        status: Int = VideoTaskState.PENDING,
        executionToken: String = ""
    ): ProgressInfo {
        return ProgressInfo(
            id = id,
            videoInfo = VideoInfo(id = id),
            downloadStatus = status,
            queuePosition = queuePosition,
            executionToken = executionToken
        )
    }

    private fun failedYtDlpTask(id: String, sourcePath: String): ProgressInfo = ProgressInfo(
        id = id,
        videoInfo = VideoInfo(id = id, title = id, ext = "mp4"),
        downloadStatus = VideoTaskState.ERROR,
        executionToken = "token-$id",
        finalizationSource = sourcePath,
        finalizationTarget = temporaryFolder.root.resolve("$id.mp4").absolutePath,
        lastError = "Media publication failed",
        logPath = temporaryFolder.root.resolve("$id.log").absolutePath
    )

    /** 内存版 ProgressRepository，状态真实演进，供队列调度测试使用。 */
    private class FakeProgressRepository : ProgressRepository {
        private val store = linkedMapOf<String, ProgressInfo>()
        var fingerprintLookupCount = 0
            private set
        var progressInfosOnceReadCount = 0
            private set

        fun save(info: ProgressInfo) {
            store[info.id] = info
        }

        override fun getProgressInfos(): Flowable<List<ProgressInfo>> =
            throw UnsupportedOperationException("not used in this test")

        override fun getProgressInfosOnce(): List<ProgressInfo> {
            progressInfosOnceReadCount++
            return store.values.toList()
        }

        override fun getProgressInfoById(id: String): ProgressInfo? = store[id]

        override fun findDuplicateByFingerprint(fingerprint: String): ProgressInfo? {
            fingerprintLookupCount++
            return store.values.firstOrNull {
                it.downloadFingerprint == fingerprint &&
                    it.downloadStatus != VideoTaskState.ERROR &&
                    it.downloadStatus != VideoTaskState.CANCELED
            }
        }

        override fun saveProgressInfo(progressInfo: ProgressInfo) {
            store[progressInfo.id] = progressInfo
        }

        override fun saveProgressInfos(progressInfos: List<ProgressInfo>) {
            progressInfos.forEach { store[it.id] = it }
        }

        override fun deleteProgressInfo(progressInfo: ProgressInfo) {
            store.remove(progressInfo.id)
        }

        override fun updateProgressFields(
            id: String,
            downloaded: Long,
            total: Long,
            fragDownloaded: Int,
            fragTotal: Int,
            status: Int,
            infoLine: String,
            startedAt: Long,
            completedAt: Long,
            lastError: String,
            logPath: String,
            isLive: Boolean
        ) {
            store[id]?.let {
                store[id] = it.copy(
                    progressDownloaded = downloaded,
                    progressTotal = total,
                    fragmentsDownloaded = fragDownloaded,
                    fragmentsTotal = fragTotal,
                    downloadStatus = status,
                    infoLine = infoLine,
                    startedAt = startedAt,
                    completedAt = completedAt,
                    lastError = lastError,
                    logPath = logPath,
                    isLive = isLive
                )
            }
        }

        override fun updateQueuePosition(id: String, position: Long) {
            store[id]?.let { store[id] = it.copy(queuePosition = position) }
        }

        override fun updateQueueState(
            id: String,
            status: Int,
            queuedForLater: Boolean,
            infoLine: String,
            logPath: String
        ) {
            store[id]?.let {
                store[id] = it.copy(
                    downloadStatus = status,
                    queuedForLater = queuedForLater,
                    infoLine = infoLine,
                    logPath = logPath
                )
            }
        }

        override fun claimYtDlpExecution(
            id: String,
            token: String,
            startedAt: Long,
            logPath: String
        ): Int = mutateIf(id, {
            it.downloadStatus == VideoTaskState.PENDING && !it.queuedForLater
        }) {
            it.copy(
                downloadStatus = VideoTaskState.PREPARE,
                executionToken = token,
                stopReason = 0,
                removePartialOnCancel = false,
                finalizationSource = "",
                finalizationTarget = "",
                startedAt = it.startedAt.takeIf { value -> value != 0L } ?: startedAt,
                logPath = logPath,
                infoLine = "Preparing"
            )
        }

        override fun requestYtDlpPause(
            id: String,
            token: String,
            reason: Int,
            queuedForLater: Boolean,
            infoLine: String,
            logPath: String
        ): Int = mutateIf(id, {
            it.executionToken == token &&
                it.stopReason == 0 &&
                it.downloadStatus in ACTIVE_YT_DLP_STATES
        }) {
            it.copy(
                downloadStatus = VideoTaskState.PAUSING,
                stopReason = reason,
                queuedForLater = queuedForLater,
                infoLine = infoLine,
                logPath = logPath
            )
        }

        override fun requestYtDlpCancel(
            id: String,
            expectedToken: String,
            assignedToken: String,
            removePartial: Boolean,
            logPath: String
        ): Int = mutateIf(id, {
            it.executionToken == expectedToken &&
                it.downloadStatus in CANCELABLE_YT_DLP_STATES &&
                it.stopReason in setOf(0, 1, 3)
        }) {
            it.copy(
                downloadStatus = VideoTaskState.CANCELING,
                stopReason = 2,
                executionToken = assignedToken,
                removePartialOnCancel = removePartial,
                queuedForLater = false,
                infoLine = "Canceling",
                logPath = logPath
            )
        }

        override fun resumeYtDlp(id: String, queuePosition: Long, logPath: String): Int =
            mutateIf(id, {
                it.downloadStatus == VideoTaskState.PAUSE ||
                    it.downloadStatus == VideoTaskState.ERROR ||
                    it.downloadStatus == VideoTaskState.ENOSPC
            }) {
                it.copy(
                    downloadStatus = VideoTaskState.PENDING,
                    stopReason = 0,
                    executionToken = "",
                    removePartialOnCancel = false,
                    finalizationSource = "",
                    finalizationTarget = "",
                    queuedForLater = false,
                    infoLine = "Queued",
                    queuePosition = queuePosition,
                    logPath = logPath
                )
            }

        override fun retryYtDlpFinalization(id: String, token: String, logPath: String): Int =
            mutateIf(id, {
                it.executionToken == token &&
                    (it.downloadStatus == VideoTaskState.ERROR ||
                        it.downloadStatus == VideoTaskState.ENOSPC) &&
                    it.finalizationSource.isNotBlank() &&
                    it.finalizationTarget.isNotBlank()
            }) {
                it.copy(
                    downloadStatus = VideoTaskState.FINALIZING,
                    completedAt = 0,
                    lastError = "",
                    infoLine = "Retrying media publication",
                    logPath = logPath
                )
            }

        override fun updateYtDlpProgress(
            id: String,
            token: String,
            downloaded: Long,
            total: Long,
            fragDownloaded: Int,
            fragTotal: Int,
            infoLine: String,
            startedAt: Long,
            logPath: String,
            isLive: Boolean
        ): Int = mutateIf(id, {
            it.executionToken == token &&
                it.stopReason == 0 &&
                it.downloadStatus in ACTIVE_YT_DLP_STATES
        }) {
            it.copy(
                progressDownloaded = downloaded,
                progressTotal = total,
                fragmentsDownloaded = fragDownloaded,
                fragmentsTotal = fragTotal,
                downloadStatus = VideoTaskState.DOWNLOADING,
                infoLine = infoLine,
                startedAt = it.startedAt.takeIf { value -> value != 0L } ?: startedAt,
                logPath = logPath,
                isLive = isLive
            )
        }

        override fun claimYtDlpFinalization(
            id: String,
            token: String,
            source: String,
            target: String
        ): Int = mutateIf(id, {
            it.executionToken == token &&
                ((it.stopReason == 0 && it.downloadStatus in ACTIVE_YT_DLP_STATES) ||
                    (it.stopReason == 3 && it.downloadStatus == VideoTaskState.PAUSING))
        }) {
            it.copy(
                downloadStatus = VideoTaskState.FINALIZING,
                finalizationSource = source,
                finalizationTarget = target,
                infoLine = "Finalizing"
            )
        }

        override fun commitYtDlpFinalization(
            id: String,
            token: String,
            status: Int,
            completedAt: Long,
            lastError: String,
            infoLine: String
        ): Int = mutateIf(id, {
            it.executionToken == token && it.downloadStatus == VideoTaskState.FINALIZING
        }) {
            it.copy(
                downloadStatus = status,
                completedAt = completedAt,
                progressDownloaded = if (status == VideoTaskState.SUCCESS) {
                    it.progressTotal
                } else {
                    it.progressDownloaded
                },
                lastError = lastError,
                infoLine = infoLine
            )
        }

        override fun commitYtDlpError(
            id: String,
            token: String,
            completedAt: Long,
            error: String
        ): Int = mutateIf(id, {
            it.executionToken == token &&
                it.stopReason == 0 &&
                it.downloadStatus in ACTIVE_YT_DLP_STATES
        }) {
            it.copy(
                downloadStatus = VideoTaskState.ERROR,
                completedAt = completedAt,
                lastError = error,
                infoLine = error
            )
        }

        override fun commitYtDlpPause(id: String, token: String, infoLine: String): Int =
            mutateIf(id, {
                it.executionToken == token &&
                    it.downloadStatus == VideoTaskState.PAUSING &&
                    it.stopReason == 1
            }) {
                it.copy(downloadStatus = VideoTaskState.PAUSE, infoLine = infoLine)
            }

        override fun commitYtDlpCanceled(id: String, token: String, completedAt: Long): Int =
            mutateIf(id, {
                it.executionToken == token &&
                    it.downloadStatus == VideoTaskState.CANCELING &&
                    it.stopReason == 2
            }) {
                it.copy(
                    downloadStatus = VideoTaskState.CANCELED,
                    completedAt = completedAt,
                    infoLine = "Canceled"
                )
            }

        override fun deleteCommittedYtDlpCanceled(id: String, token: String): Int {
            val task = store[id] ?: return 0
            if (task.executionToken != token || task.downloadStatus != VideoTaskState.CANCELED) {
                return 0
            }
            store.remove(id)
            return 1
        }

        override fun adoptLegacyYtDlpExecution(id: String, token: String): Int =
            mutateIf(id, {
                it.executionToken.isBlank() && it.downloadStatus in RECOVERABLE_YT_DLP_STATES
            }) {
                it.copy(
                    executionToken = token,
                    infoLine = "Recovering legacy download"
                )
            }

        private fun mutateIf(
            id: String,
            predicate: (ProgressInfo) -> Boolean,
            update: (ProgressInfo) -> ProgressInfo
        ): Int {
            val current = store[id] ?: return 0
            if (!predicate(current)) {
                return 0
            }
            store[id] = update(current)
            return 1
        }

        private companion object {
            val ACTIVE_YT_DLP_STATES = setOf(
                VideoTaskState.PREPARE,
                VideoTaskState.START,
                VideoTaskState.DOWNLOADING,
                VideoTaskState.PROXYREADY
            )
            val CANCELABLE_YT_DLP_STATES = ACTIVE_YT_DLP_STATES + setOf(
                VideoTaskState.PENDING,
                VideoTaskState.PAUSE,
                VideoTaskState.PAUSING
            )
            val RECOVERABLE_YT_DLP_STATES = ACTIVE_YT_DLP_STATES + setOf(
                VideoTaskState.PAUSING,
                VideoTaskState.CANCELING,
                VideoTaskState.FINALIZING
            )
        }
    }
}
