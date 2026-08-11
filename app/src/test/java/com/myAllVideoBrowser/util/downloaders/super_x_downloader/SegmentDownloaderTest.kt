package com.myAllVideoBrowser.util.downloaders.super_x_downloader

import android.app.Application
import com.myAllVideoBrowser.util.downloaders.super_x_downloader.control.FileBasedDownloadController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SegmentDownloaderTest {
    private lateinit var server: MockWebServer
    private lateinit var downloadDirectory: File
    private lateinit var controller: FileBasedDownloadController

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        downloadDirectory = Files.createTempDirectory("segment-downloader-test").toFile()
        controller = FileBasedDownloadController(downloadDirectory)
        controller.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        downloadDirectory.deleteRecursively()
    }

    @Test
    fun pauseDuringResponse_removesStagingFileAndDoesNotPublishSegment() = runBlocking {
        val payload = ByteArray(32 * 1024) { (it % 251).toByte() }
        enqueueSlowResponse(payload)
        val output = downloadDirectory.resolve("segment_00000.ts")
        val staging = downloadDirectory.resolve("segment_00000.ts.part")
        val downloader = createDownloader()

        supervisorScope {
            val download = async(Dispatchers.IO) {
                downloader.download(server.url("/segment.ts").toString(), output, "HLS", 0)
            }
            awaitPartialFile(staging)
            controller.requestPause()

            try {
                download.await()
                throw AssertionError("Expected segment download to be canceled by pause.")
            } catch (_: CancellationException) {
                // Expected.
            }
        }

        assertFalse(output.exists())
        assertFalse(staging.exists())
    }

    @Test
    fun stopAndSaveDuringResponse_allowsCurrentCompleteSegmentToPublish() = runBlocking {
        val payload = ByteArray(16 * 1024) { (it % 239).toByte() }
        enqueueSlowResponse(payload)
        val output = downloadDirectory.resolve("segment_00000.ts")
        val staging = downloadDirectory.resolve("segment_00000.ts.part")
        val downloader = createDownloader()

        val downloadedBytes = supervisorScope {
            val download = async(Dispatchers.IO) {
                downloader.download(server.url("/segment.ts").toString(), output, "HLS", 0)
            }
            awaitPartialFile(staging)
            controller.requestStopAndSave()
            download.await()
        }

        assertEquals(payload.size.toLong(), downloadedBytes)
        assertArrayEquals(payload, output.readBytes())
        assertFalse(staging.exists())
        assertEquals(
            FileBasedDownloadController.InterruptionReason.STOP_AND_SAVE,
            controller.interruptionReason()
        )
    }

    @Test
    fun cancelFlag_takesPriorityOverPauseAndStopAndSave() {
        controller.requestStopAndSave()
        controller.requestPause()
        controller.requestCancel()

        assertEquals(
            FileBasedDownloadController.InterruptionReason.CANCEL,
            controller.interruptionReason()
        )
        assertTrue(controller.isPauseOrCancelRequested())
    }

    private fun createDownloader(): SegmentDownloader {
        return SegmentDownloader(
            client = OkHttpClient.Builder().build(),
            headers = emptyMap(),
            controller = controller
        )
    }

    private fun enqueueSlowResponse(payload: ByteArray) {
        server.enqueue(
            MockResponse()
                .setBody(okio.Buffer().write(payload))
                .throttleBody(256, 5L, TimeUnit.MILLISECONDS)
        )
    }

    private suspend fun awaitPartialFile(staging: File) {
        withTimeout(5_000L) {
            while (!staging.isFile || staging.length() <= 0L) {
                delay(10L)
            }
        }
    }
}
