package com.myAllVideoBrowser.data.local.room.dao

import android.app.Application
import androidx.room.Room
import com.myAllVideoBrowser.data.local.room.AppDatabase
import com.myAllVideoBrowser.data.local.room.entity.ProgressInfo
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 用真实 Room in-memory DB 验证按列更新的列隔离契约（钉住并发修复）：
 * - updateProgressFields 只覆盖进度/状态列，queuePosition 必须保持不变
 * - updateQueuePosition / updateQueueState 只覆盖队列列，progressDownloaded/progressTotal 必须保持不变
 *
 * 这能抓到「有人改 @Query UPDATE 误加列」这类回归，FakeProgressRepository 测不到。
 * 用 Robolectric 自带 RuntimeEnvironment 拿 Context（不依赖 androidx.test:core），
 * 并用默认 Application 跳过 DLApplication.onCreate 副作用。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ProgressDaoColumnIsolationTest {

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
    fun updateProgressFields_doesNotTouchQueuePosition() {
        dao.insertProgressInfo(progressInfo("A", downloaded = 100L, total = 1000L, queuePosition = 5L))

        dao.updateProgressFields(
            id = "A",
            downloaded = 200L,
            total = 1000L,
            fragDownloaded = 0,
            fragTotal = 1,
            status = VideoTaskState.DOWNLOADING,
            infoLine = "Downloading",
            startedAt = 1000L,
            completedAt = 0L,
            lastError = "",
            logPath = "/tmp/log",
            isLive = false
        )

        val reloaded = dao.getProgressInfoById("A")
        assertEquals(5L, reloaded?.queuePosition)        // 队列位置未被碰
        assertEquals(200L, reloaded?.progressDownloaded) // 进度已更新
    }

    @Test
    fun updateQueuePosition_doesNotTouchProgressFields() {
        dao.insertProgressInfo(progressInfo("B", downloaded = 100L, total = 1000L, queuePosition = 5L))

        dao.updateQueuePosition("B", 99L)

        val reloaded = dao.getProgressInfoById("B")
        assertEquals(99L, reloaded?.queuePosition)        // 队列位置已更新
        assertEquals(100L, reloaded?.progressDownloaded)  // 进度未被碰
        assertEquals(1000L, reloaded?.progressTotal)      // 总量未被碰
    }

    @Test
    fun updateQueueState_doesNotTouchProgressFields() {
        dao.insertProgressInfo(progressInfo("C", downloaded = 100L, total = 1000L, queuePosition = 5L))

        dao.updateQueueState(
            id = "C",
            status = VideoTaskState.PAUSE,
            queuedForLater = true,
            infoLine = "Paused",
            logPath = "/tmp/log"
        )

        val reloaded = dao.getProgressInfoById("C")
        assertEquals(VideoTaskState.PAUSE, reloaded?.downloadStatus)
        assertEquals(true, reloaded?.queuedForLater)
        assertEquals(100L, reloaded?.progressDownloaded)  // 进度未被碰
        assertEquals(1000L, reloaded?.progressTotal)      // 总量未被碰
    }

    private fun progressInfo(id: String, downloaded: Long, total: Long, queuePosition: Long): ProgressInfo {
        return ProgressInfo(
            id = id,
            videoInfo = VideoInfo(id = id),
            progressDownloaded = downloaded,
            progressTotal = total,
            downloadStatus = VideoTaskState.PENDING,
            queuePosition = queuePosition
        )
    }
}
