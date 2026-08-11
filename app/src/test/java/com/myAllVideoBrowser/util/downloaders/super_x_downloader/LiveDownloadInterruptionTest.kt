package com.myAllVideoBrowser.util.downloaders.super_x_downloader

import android.app.Application
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskItem
import com.myAllVideoBrowser.util.downloaders.super_x_downloader.control.FileBasedDownloadController
import com.myAllVideoBrowser.util.downloaders.super_x_downloader.strategy.HlsLiveDownloader
import com.myAllVideoBrowser.util.downloaders.super_x_downloader.strategy.MpdLiveDownloader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class LiveDownloadInterruptionTest {
    private lateinit var downloadDirectory: File
    private lateinit var controller: FileBasedDownloadController
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        downloadDirectory = Files.createTempDirectory("live-interruption-test").toFile()
        controller = FileBasedDownloadController(downloadDirectory)
        controller.start()
        client = OkHttpClient.Builder().build()
    }

    @After
    fun tearDown() {
        downloadDirectory.deleteRecursively()
    }

    @Test
    fun hlsPauseBeforeStart_skipsManifestFetchAndMerge() = runBlocking {
        val fetchCount = AtomicInteger(0)
        val mergeCount = AtomicInteger(0)
        controller.requestPause()
        val downloader = HlsLiveDownloader(
            httpClient = client,
            getMediaPlaylists = { _, _ ->
                fetchCount.incrementAndGet()
                null to null
            },
            onMergeProgress = { _, _ -> mergeCount.incrementAndGet() },
            videoCodec = null
        )

        expectCancellation {
            downloader.download(task(), emptyMap(), downloadDirectory, controller) { }
        }

        assertEquals(0, fetchCount.get())
        assertEquals(0, mergeCount.get())
    }

    @Test
    fun mpdCancelBeforeStart_skipsManifestFetchAndMerge() = runBlocking {
        val fetchCount = AtomicInteger(0)
        val mergeCount = AtomicInteger(0)
        controller.requestCancel()
        val downloader = MpdLiveDownloader(
            httpClient = client,
            getMpdRepresentations = { _, _ ->
                fetchCount.incrementAndGet()
                null to null
            },
            onMergeProgress = { _, _ -> mergeCount.incrementAndGet() },
            videoCodec = null
        )

        expectCancellation {
            downloader.download(task(), emptyMap(), downloadDirectory, controller) { }
        }

        assertEquals(0, fetchCount.get())
        assertEquals(0, mergeCount.get())
    }

    @Test
    fun hlsManifestFailure_doesNotMergePreviouslyCapturedSegments() = runBlocking {
        val previousOutput = "previous-hls-output".toByteArray()
        val finalOutput = downloadDirectory.resolve("merged_output.mp4")
        finalOutput.writeBytes(previousOutput)
        downloadDirectory.resolve("segment_00000.ts").writeText("old-segment", Charsets.UTF_8)
        val mergeCount = AtomicInteger(0)
        val downloader = HlsLiveDownloader(
            httpClient = client,
            getMediaPlaylists = { _, _ -> throw IOException("manifest fetch failed") },
            onMergeProgress = { _, _ -> mergeCount.incrementAndGet() },
            videoCodec = null
        )

        expectIOException {
            downloader.download(task(), emptyMap(), downloadDirectory, controller) { }
        }

        assertEquals(0, mergeCount.get())
        assertArrayEquals(previousOutput, finalOutput.readBytes())
    }

    @Test
    fun mpdManifestFailure_doesNotMergePreviouslyCapturedSegments() = runBlocking {
        val previousOutput = "previous-mpd-output".toByteArray()
        val finalOutput = downloadDirectory.resolve("merged_output.mp4")
        finalOutput.writeBytes(previousOutput)
        downloadDirectory.resolve("video_init.m4s").writeText("old-init", Charsets.UTF_8)
        downloadDirectory.resolve("segment_123.m4s").writeText("old-segment", Charsets.UTF_8)
        val mergeCount = AtomicInteger(0)
        val downloader = MpdLiveDownloader(
            httpClient = client,
            getMpdRepresentations = { _, _ -> throw IOException("manifest fetch failed") },
            onMergeProgress = { _, _ -> mergeCount.incrementAndGet() },
            videoCodec = null
        )

        expectIOException {
            downloader.download(task(), emptyMap(), downloadDirectory, controller) { }
        }

        assertEquals(0, mergeCount.get())
        assertArrayEquals(previousOutput, finalOutput.readBytes())
    }

    private fun task(): VideoTaskItem = VideoTaskItem("https://example.com/live/manifest")

    private suspend fun expectCancellation(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected live download to be canceled.")
        } catch (_: CancellationException) {
            // Expected.
        }
    }

    private suspend fun expectIOException(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected manifest failure to propagate.")
        } catch (error: IOException) {
            assertEquals("manifest fetch failed", error.message)
        }
    }
}
