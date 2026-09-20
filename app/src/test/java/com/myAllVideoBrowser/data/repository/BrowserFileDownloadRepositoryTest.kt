package com.myAllVideoBrowser.data.repository

import android.app.Application
import android.app.DownloadManager
import android.content.Context
import android.webkit.CookieManager
import com.myAllVideoBrowser.data.local.room.dao.BrowserFileDownloadDao
import com.myAllVideoBrowser.data.local.room.entity.BrowserFileDownload
import com.myAllVideoBrowser.data.local.room.entity.BrowserFileDownloadStatus
import io.reactivex.rxjava3.core.Flowable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BrowserFileDownloadRepositoryTest {
    private lateinit var application: Application
    private lateinit var downloadManager: DownloadManager
    private lateinit var dao: FakeBrowserFileDownloadDao
    private lateinit var repository: BrowserFileDownloadRepositoryImpl

    @Before
    fun setup() {
        application = RuntimeEnvironment.getApplication()
        downloadManager = application.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dao = FakeBrowserFileDownloadDao()
        repository = BrowserFileDownloadRepositoryImpl(application, downloadManager, dao)
    }

    @Test
    fun enqueueUsesHeadersForTransferButPersistsOnlyNonSensitiveMetadata() {
        val result = repository.enqueue(
            request(
                headers = mapOf(
                    "User-Agent" to "SurfSave test",
                    "Cookie" to "session=private"
                )
            )
        )

        assertTrue(result.isSuccess)
        val managerId = result.getOrThrow()
        val stored = dao.rows.single()
        assertEquals(managerId, stored.downloadManagerId)
        assertEquals("https://page.example/download", stored.sourcePageUrl)
        assertEquals("SurfSave test", stored.userAgent)
        assertEquals("SurfSave/Files/archive.zip", stored.relativePath)
        assertFalse(stored.toString().contains("session=private"))

        val managerRequest = Shadows.shadowOf(downloadManager).getRequest(managerId)
        val shadowRequest = Shadows.shadowOf(managerRequest)
        assertEquals(2, shadowRequest.requestHeaders.size)
        assertTrue(shadowRequest.destination.toString().contains("Download/SurfSave/Files/archive.zip"))
    }

    @Test
    fun enqueueChoosesBrowserStyleSuffixForARecordedNameCollision() {
        dao.rows += BrowserFileDownload(
            id = 1,
            downloadManagerId = 100,
            url = "https://download.example/old.zip",
            fileName = "archive.zip",
            relativePath = "SurfSave/Files/archive.zip"
        )

        val result = repository.enqueue(request())

        assertTrue(result.isSuccess)
        assertEquals("archive (1).zip", dao.rows.last().fileName)
        assertEquals("SurfSave/Files/archive (1).zip", dao.rows.last().relativePath)
    }

    @Test
    fun enqueueTruncatesMaxLengthBaseNameBeforeAddingCollisionSuffix() {
        val originalName = "a".repeat(176) + ".zip"
        dao.rows += BrowserFileDownload(
            id = 1,
            downloadManagerId = 100,
            url = "https://download.example/old.zip",
            fileName = originalName,
            relativePath = "SurfSave/Files/$originalName"
        )

        val result = repository.enqueue(
            request().copy(
                fileName = originalName,
                relativePath = "SurfSave/Files/$originalName"
            )
        )

        assertTrue(result.isSuccess)
        val storedName = dao.rows.last().fileName
        assertEquals(BrowserFileDestinationPolicy.MAX_FILE_NAME_LENGTH, storedName.length)
        assertTrue(storedName.endsWith(" (1).zip"))
        assertEquals("SurfSave/Files/$storedName", dao.rows.last().relativePath)
    }

    @Test
    fun enqueueRollsBackSystemTaskWhenRoomInsertFails() {
        dao.failInsert = true

        val result = repository.enqueue(request())

        assertTrue(result.isFailure)
        val attemptedManagerId = requireNotNull(dao.lastInsertAttempt).downloadManagerId
        assertNull(Shadows.shadowOf(downloadManager).getRequest(attemptedManagerId))
    }

    @Test
    fun retryRestoresUserAgentRefererAndCurrentTargetCookie() {
        CookieManager.getInstance().setCookie(
            "https://download.example/archive.zip",
            "session=fresh"
        )
        val originalId = repository.enqueue(
            request(headers = mapOf("User-Agent" to "SurfSave retry"))
        ).getOrThrow()
        val stored = dao.rows.single().copy(status = 5)
        dao.update(stored)

        val retriedId = repository.retry(stored).getOrThrow()

        assertTrue(retriedId != originalId)
        val managerRequest = Shadows.shadowOf(downloadManager).getRequest(retriedId)
        val headers = Shadows.shadowOf(managerRequest).requestHeaders.associate { it.first to it.second }
        assertEquals("SurfSave retry", headers["User-Agent"])
        assertEquals("https://page.example/download", headers["Referer"])
        assertEquals("session=fresh", headers["Cookie"])
        assertFalse(dao.rows.single().toString().contains("session=fresh"))
    }

    @Test
    fun untrustedImportedRecordDoesNotOperateOldSystemTaskAndRetryCreatesNewBinding() {
        val oldManagerId = repository.enqueue(request()).getOrThrow()
        val imported = dao.rows.single().copy(
            status = BrowserFileDownloadStatus.RUNNING,
            localUri = "content://downloads/all_downloads/$oldManagerId",
            systemBindingTrusted = false
        )
        dao.update(imported)

        assertNull(repository.getContentUri(imported))
        repository.refreshAll()
        val refreshed = dao.rows.single()
        assertEquals(BrowserFileDownloadStatus.MISSING, refreshed.status)
        assertEquals("", refreshed.localUri)
        assertFalse(refreshed.systemBindingTrusted)
        assertTrue(Shadows.shadowOf(downloadManager).getRequest(oldManagerId) != null)

        val retriedId = repository.retry(refreshed).getOrThrow()
        assertTrue(retriedId != oldManagerId)
        assertTrue(Shadows.shadowOf(downloadManager).getRequest(oldManagerId) != null)
        assertTrue(Shadows.shadowOf(downloadManager).getRequest(retriedId) != null)
        assertEquals(retriedId, dao.rows.single().downloadManagerId)
        assertTrue(dao.rows.single().systemBindingTrusted)
    }

    @Test
    fun untrustedImportedRecordCanBeDeletedWithoutRemovingOldSystemTask() {
        val oldManagerId = repository.enqueue(request()).getOrThrow()
        val imported = dao.rows.single().copy(systemBindingTrusted = false)
        dao.update(imported)

        repository.delete(imported, deleteLocalFile = true)

        assertTrue(dao.rows.isEmpty())
        assertTrue(Shadows.shadowOf(downloadManager).getRequest(oldManagerId) != null)
    }

    @Test
    fun untrustedImportedRecordCanBeCanceledWithoutRemovingOldSystemTask() {
        val oldManagerId = repository.enqueue(request()).getOrThrow()
        val imported = dao.rows.single().copy(systemBindingTrusted = false)
        dao.update(imported)

        repository.cancel(imported)

        val canceled = dao.rows.single()
        assertEquals(BrowserFileDownloadStatus.CANCELED, canceled.status)
        assertFalse(canceled.systemBindingTrusted)
        assertTrue(Shadows.shadowOf(downloadManager).getRequest(oldManagerId) != null)
    }

    @Test
    fun unsafeDestinationIsRejectedAndCannotDeleteOutsideFile() {
        val enqueueResult = repository.enqueue(
            request().copy(relativePath = "SurfSave/Files/../outside.zip")
        )
        assertTrue(enqueueResult.isFailure)
        assertTrue(dao.rows.isEmpty())

        val outsideFile = kotlin.io.path.createTempFile("surfsave-outside", ".zip").toFile()
        try {
            val malicious = BrowserFileDownload(
                id = 91,
                downloadManagerId = -91,
                url = "https://download.example/outside.zip",
                fileName = "outside.zip",
                relativePath = "../outside.zip",
                localUri = outsideFile.toURI().toString(),
                systemBindingTrusted = true
            )
            dao.rows += malicious

            repository.delete(malicious, deleteLocalFile = true)

            assertTrue(outsideFile.exists())
            assertTrue(dao.rows.isEmpty())
        } finally {
            outsideFile.delete()
        }
    }

    private fun request(headers: Map<String, String> = emptyMap()) = BrowserFileEnqueueRequest(
        url = "https://download.example/archive.zip",
        sourcePageUrl = "https://page.example/download",
        fileName = "archive.zip",
        mimeType = "application/zip",
        expectedSize = 1234,
        headers = headers,
        relativePath = "SurfSave/Files/archive.zip"
    )

    private class FakeBrowserFileDownloadDao : BrowserFileDownloadDao {
        val rows = mutableListOf<BrowserFileDownload>()
        var failInsert = false
        var lastInsertAttempt: BrowserFileDownload? = null

        override fun observeAll(): Flowable<List<BrowserFileDownload>> = Flowable.just(rows.toList())
        override fun getAll(): List<BrowserFileDownload> = rows.toList()
        override fun getActive(): List<BrowserFileDownload> = rows.filter { it.isActive }
        override fun getById(id: Long): BrowserFileDownload? = rows.find { it.id == id }

        override fun insert(download: BrowserFileDownload): Long {
            lastInsertAttempt = download
            if (failInsert) error("simulated database failure")
            val id = (rows.maxOfOrNull { it.id } ?: 0L) + 1
            rows += download.copy(id = id)
            return id
        }

        override fun insertAll(downloads: List<BrowserFileDownload>) {
            downloads.forEach(::insert)
        }

        override fun update(download: BrowserFileDownload) {
            val index = rows.indexOfFirst { it.id == download.id }
            if (index >= 0) rows[index] = download
        }

        override fun delete(download: BrowserFileDownload) {
            rows.removeAll { it.id == download.id }
        }

        override fun clear() {
            rows.clear()
        }
    }
}
