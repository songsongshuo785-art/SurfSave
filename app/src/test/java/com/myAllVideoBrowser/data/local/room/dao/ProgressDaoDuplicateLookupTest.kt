package com.myAllVideoBrowser.data.local.room.dao

import android.app.Application
import androidx.room.Room
import com.myAllVideoBrowser.data.local.room.AppDatabase
import com.myAllVideoBrowser.data.local.room.entity.ProgressInfo
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskState
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
class ProgressDaoDuplicateLookupTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: ProgressDao

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = database.progressDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun findDuplicateByFingerprint_matchesExactlyAndExcludesRetryableTerminalRows() {
        val active = progressInfo("active", "same", VideoTaskState.DOWNLOADING, queuedAt = 30L)
        dao.insertProgressInfo(progressInfo("error", "same", VideoTaskState.ERROR, queuedAt = 20L))
        dao.insertProgressInfo(progressInfo("canceled", "same", VideoTaskState.CANCELED, queuedAt = 10L))
        dao.insertProgressInfo(progressInfo("other", "different", VideoTaskState.SUCCESS, queuedAt = 40L))
        dao.insertProgressInfo(active)

        assertEquals("active", dao.findDuplicateByFingerprint("same")?.id)
        assertEquals("other", dao.findDuplicateByFingerprint("different")?.id)

        dao.deleteProgressInfo(active)
        assertNull(dao.findDuplicateByFingerprint("same"))
    }

    private fun progressInfo(
        id: String,
        fingerprint: String,
        status: Int,
        queuedAt: Long
    ): ProgressInfo {
        return ProgressInfo(
            id = id,
            videoInfo = VideoInfo(id = id),
            downloadStatus = status,
            queuedAt = queuedAt,
            downloadFingerprint = fingerprint
        )
    }
}
