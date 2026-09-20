package com.myAllVideoBrowser.data.repository

import android.app.Application
import android.app.DownloadManager
import com.myAllVideoBrowser.data.local.room.entity.BrowserFileDownload
import com.myAllVideoBrowser.data.local.room.entity.BrowserFileDownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BrowserFileDownloadStatusTest {
    @Test
    fun androidStatusesMapToStableApplicationStatuses() {
        assertEquals(
            BrowserFileDownloadStatus.PENDING,
            mapBrowserFileDownloadStatus(DownloadManager.STATUS_PENDING)
        )
        assertEquals(
            BrowserFileDownloadStatus.RUNNING,
            mapBrowserFileDownloadStatus(DownloadManager.STATUS_RUNNING)
        )
        assertEquals(
            BrowserFileDownloadStatus.PAUSED,
            mapBrowserFileDownloadStatus(DownloadManager.STATUS_PAUSED)
        )
        assertEquals(
            BrowserFileDownloadStatus.SUCCESSFUL,
            mapBrowserFileDownloadStatus(DownloadManager.STATUS_SUCCESSFUL)
        )
        assertEquals(
            BrowserFileDownloadStatus.FAILED,
            mapBrowserFileDownloadStatus(DownloadManager.STATUS_FAILED)
        )
        assertEquals(BrowserFileDownloadStatus.FAILED, mapBrowserFileDownloadStatus(999))
    }

    @Test
    fun progressAndActionsFollowPersistentStatus() {
        val running = sample(status = BrowserFileDownloadStatus.RUNNING, downloaded = 25, total = 100)
        assertTrue(running.isActive)
        assertEquals(25, running.progressPercent)
        assertFalse(running.canOpen)

        val complete = sample(status = BrowserFileDownloadStatus.SUCCESSFUL).copy(
            localUri = "content://downloads/my_downloads/1"
        )
        assertTrue(complete.canOpen)
        assertFalse(complete.canRetry)

        assertTrue(sample(status = BrowserFileDownloadStatus.FAILED).canRetry)
        assertTrue(sample(status = BrowserFileDownloadStatus.MISSING).canRetry)
    }

    private fun sample(status: Int, downloaded: Long = 0, total: Long = 0) =
        BrowserFileDownload(
            downloadManagerId = 1,
            url = "https://download.example/file.zip",
            fileName = "file.zip",
            relativePath = "SurfSave/Files/file.zip",
            status = status,
            downloadedBytes = downloaded,
            totalBytes = total
        )
}
