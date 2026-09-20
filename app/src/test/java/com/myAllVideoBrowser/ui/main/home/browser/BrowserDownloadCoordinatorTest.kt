package com.myAllVideoBrowser.ui.main.home.browser

import android.app.Application
import android.net.Uri
import android.os.Environment
import com.myAllVideoBrowser.data.local.room.entity.BrowserFileDownload
import com.myAllVideoBrowser.data.repository.BrowserFileDownloadRepository
import com.myAllVideoBrowser.data.repository.BrowserFileEnqueueRequest
import io.reactivex.rxjava3.core.Flowable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BrowserDownloadCoordinatorTest {
    private var enqueuedRequest: BrowserFileEnqueueRequest? = null
    private val repository = object : BrowserFileDownloadRepository {
        override fun observeDownloads(): Flowable<List<BrowserFileDownload>> = Flowable.just(emptyList())
        override fun enqueue(request: BrowserFileEnqueueRequest): Result<Long> {
            enqueuedRequest = request
            return Result.success(73L)
        }
        override fun refreshAll() = false
        override fun cancel(download: BrowserFileDownload) = Unit
        override fun retry(download: BrowserFileDownload): Result<Long> = Result.success(74L)
        override fun delete(download: BrowserFileDownload, deleteLocalFile: Boolean) = Unit
        override fun getContentUri(download: BrowserFileDownload): Uri? = null
    }
    private val coordinator = BrowserDownloadCoordinator(repository)

    @Test
    fun directMp4_confirmationSubmitsPersistentSurfSaveTaskData() {
        val request = request(
            url = "https://media.example/movie.mp4?token=temporary",
            fileName = "movie.mp4",
            mimeType = "application/octet-stream"
        )

        val plan = coordinator.plan(request)
        assertTrue(plan is BrowserDownloadPlan.DirectMedia)

        var submittedUrl: String? = null
        val result = coordinator.executeConfirmed(plan, fallbackTitle = "Page title") { videoInfo ->
            submittedUrl = videoInfo.firstUrlToString
            assertTrue(videoInfo.isRegularDownload)
            assertEquals("movie", videoInfo.title)
            assertEquals("mp4", videoInfo.ext)
            assertEquals("https://page.example/watch", videoInfo.originalUrl)
            assertEquals("session=abc", videoInfo.downloadUrls.single().headers["Cookie"])
        }

        assertTrue(result.isSuccess)
        assertEquals(request.url, submittedUrl)
    }

    @Test
    fun manifest_isKeptInFormatResolutionFlow() {
        listOf(
            Triple(
                "https://media.example/master.m3u8",
                "master.m3u8",
                "application/vnd.apple.mpegurl"
            ),
            Triple(
                "https://media.example/manifest.mpd",
                "manifest.mpd",
                "application/dash+xml"
            )
        ).forEach { (url, fileName, mimeType) ->
            val plan = coordinator.plan(request(url, fileName, mimeType))

            assertTrue(plan is BrowserDownloadPlan.Manifest)
        }
    }

    @Test
    fun apk_confirmationEnqueuesAndroidDownloadWithPublicDestinationAndHeaders() {
        val plan = coordinator.plan(
            request(
                url = "https://download.example/app?id=7",
                fileName = "TapTap.apk",
                mimeType = "application/vnd.android.package-archive"
            )
        )
        assertTrue(plan is BrowserDownloadPlan.SystemFile)
        val spec = (plan as BrowserDownloadPlan.SystemFile).spec
        assertEquals(Environment.DIRECTORY_DOWNLOADS, spec.destinationDirectory)
        assertEquals("SurfSave/Files/TapTap.apk", spec.destinationRelativePath)
        assertEquals("TapTap.apk", spec.fileName)
        assertEquals("application/vnd.android.package-archive", spec.mimeType)
        assertEquals(
            setOf("User-Agent", "Referer", "Cookie"),
            spec.headers.keys
        )

        var mediaSinkCalled = false
        val result = coordinator.executeConfirmed(plan) { mediaSinkCalled = true }

        assertTrue(result.isSuccess)
        assertFalse(mediaSinkCalled)
        val downloadId = (result.getOrThrow() as BrowserConfirmedDownload.SystemSubmitted).downloadId
        assertEquals(73L, downloadId)
        assertEquals("https://download.example/app?id=7", enqueuedRequest?.url)
        assertEquals("TapTap.apk", enqueuedRequest?.fileName)
        assertEquals("application/vnd.android.package-archive", enqueuedRequest?.mimeType)
        assertEquals("SurfSave/Files/TapTap.apk", enqueuedRequest?.relativePath)
        assertEquals(3, enqueuedRequest?.headers?.size)
    }

    @Test
    fun zipAndPdf_areSystemFilesAndNeverBecomeMediaTasks() {
        listOf(
            "archive.zip" to "application/zip",
            "manual.pdf" to "application/pdf"
        ).forEach { (fileName, mimeType) ->
            val plan = coordinator.plan(
                request(
                    url = "https://download.example/$fileName",
                    fileName = fileName,
                    mimeType = mimeType
                )
            )
            assertTrue(plan is BrowserDownloadPlan.SystemFile)
        }
    }

    @Test
    fun publicDownloads_requiresRuntimePermissionOnlyThroughAndroidP() {
        assertTrue(
            BrowserDownloadCoordinator.requiresLegacyWritePermission(
                sdkInt = 28,
                permissionGranted = false
            )
        )
        assertFalse(
            BrowserDownloadCoordinator.requiresLegacyWritePermission(
                sdkInt = 28,
                permissionGranted = true
            )
        )
        assertFalse(
            BrowserDownloadCoordinator.requiresLegacyWritePermission(
                sdkInt = 29,
                permissionGranted = false
            )
        )
    }

    private fun request(
        url: String,
        fileName: String,
        mimeType: String
    ) = BrowserDownloadRequest(
        url = url,
        pageUrl = "https://page.example/watch",
        headers = linkedMapOf(
            "User-Agent" to "SurfSave test",
            "Referer" to "https://page.example/watch",
            "Cookie" to "session=abc",
            "Authorization" to "must-not-be-forwarded"
        ),
        contentDisposition = "attachment; filename=\"$fileName\"",
        mimeType = mimeType,
        contentLength = 12_345L,
        suggestedFileName = fileName
    )
}
