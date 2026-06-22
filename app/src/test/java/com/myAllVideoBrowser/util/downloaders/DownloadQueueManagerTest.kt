package com.myAllVideoBrowser.util.downloaders

import android.content.Context
import com.myAllVideoBrowser.DLApplication
import com.myAllVideoBrowser.data.local.room.entity.ProgressInfo
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.data.repository.ProgressRepository
import com.myAllVideoBrowser.util.FileUtil
import com.myAllVideoBrowser.util.SharedPrefHelper
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskState
import io.reactivex.rxjava3.core.Flowable
import org.junit.Assert.assertEquals
import org.junit.Test
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

    private val contextPlaceholder: Context = Mockito.mock(Context::class.java)

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

    private fun progressInfo(id: String, queuePosition: Long): ProgressInfo {
        return ProgressInfo(
            id = id,
            videoInfo = VideoInfo(id = id),
            downloadStatus = VideoTaskState.PENDING,
            queuePosition = queuePosition
        )
    }

    /** 内存版 ProgressRepository，状态真实演进，供队列调度测试使用。 */
    private class FakeProgressRepository : ProgressRepository {
        private val store = linkedMapOf<String, ProgressInfo>()

        fun save(info: ProgressInfo) {
            store[info.id] = info
        }

        override fun getProgressInfos(): Flowable<List<ProgressInfo>> =
            throw UnsupportedOperationException("not used in this test")

        override fun getProgressInfosOnce(): List<ProgressInfo> = store.values.toList()

        override fun getProgressInfoById(id: String): ProgressInfo? = store[id]

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
    }
}
