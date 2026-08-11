package com.myAllVideoBrowser.util.downloaders.custom_downloader

import android.app.Application
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class CustomFileDownloaderTest {
    private lateinit var server: MockWebServer
    private lateinit var downloadDirectory: File
    private lateinit var outputFile: File

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        downloadDirectory = Files.createTempDirectory("custom-file-downloader-test").toFile()
        outputFile = File(downloadDirectory, "video.bin")
    }

    @After
    fun tearDown() {
        server.shutdown()
        downloadDirectory.deleteRecursively()
    }

    @Test
    fun rangedChunkTail_doesNotWritePastItsBoundary() {
        val firstChunk = "A".repeat(1_500)
        val secondChunk = "B".repeat(1_500)
        val completePayload = (firstChunk + secondChunk).toByteArray()

        // Simulate a resumed download whose second chunk is already complete. The first chunk's
        // short final read must not overwrite any byte in the second chunk.
        outputFile.writeBytes(completePayload)
        File(downloadDirectory, "chunk_1").writeText("1500", Charsets.UTF_8)
        server.enqueue(MockResponse().setResponseCode(200).setBody(firstChunk + secondChunk))
        server.enqueue(rangeResponse("bytes 0-0/3000", "A"))
        server.enqueue(rangeResponse("bytes 0-1499/3000", firstChunk))

        val listener = RecordingDownloadListener()
        createDownloader(listener, threadCount = 2).download()

        assertEquals(1, listener.successCount.get())
        assertEquals(0, listener.failures.size)
        assertEquals(1, listener.terminalCount())
        assertEquals(3_000L, outputFile.length())
        assertArrayEquals(completePayload, outputFile.readBytes())
    }

    @Test
    fun resumedRangeWithOneByteRemaining_downloadsTheFinalByte() {
        val payload = "Z".repeat(1_499).toByteArray() + byteArrayOf('Q'.code.toByte())
        val existing = payload.copyOf().also { it[it.lastIndex] = 'X'.code.toByte() }
        outputFile.writeBytes(existing)
        File(downloadDirectory, "chunk_0").writeText("1499", Charsets.UTF_8)
        server.enqueue(MockResponse().setResponseCode(200).setBody(payload.toString(Charsets.UTF_8)))
        server.enqueue(rangeResponse("bytes 0-0/1500", "Z"))
        server.enqueue(rangeResponse("bytes 1499-1499/1500", "Q"))

        val listener = RecordingDownloadListener()
        createDownloader(listener).download()

        assertEquals(1, listener.successCount.get())
        assertEquals(0, listener.failures.size)
        assertEquals(1, listener.terminalCount())
        assertArrayEquals(payload, outputFile.readBytes())
        server.takeRequest()
        server.takeRequest()
        assertEquals("bytes=1499-1499", server.takeRequest().getHeader("Range"))
    }

    @Test
    fun capturedRangeHeader_isNotReusedForFullFileRequests() {
        val payload = "complete-file-body"
        server.enqueue(MockResponse().setResponseCode(200).setBody(payload))
        server.enqueue(rangeResponse("bytes 0-0/${payload.length}", payload.take(1)))
        server.enqueue(MockResponse().setResponseCode(200).setBody(payload))

        val listener = RecordingDownloadListener()
        createDownloader(
            listener = listener,
            forceStream = true,
            headers = mapOf("Range" to "bytes=5-9")
        ).download()

        assertEquals(1, listener.successCount.get())
        assertEquals(0, listener.failures.size)
        assertEquals(1, listener.terminalCount())
        assertArrayEquals(payload.toByteArray(), outputFile.readBytes())
        assertEquals(null, server.takeRequest().getHeader("Range"))
        assertEquals("bytes=0-0", server.takeRequest().getHeader("Range"))
        assertEquals(null, server.takeRequest().getHeader("Range"))
    }

    @Test
    fun rangedResponseWithUnexpectedLength_failsWithoutSuccess() {
        val payload = "R".repeat(1_500)
        server.enqueue(MockResponse().setResponseCode(200).setBody(payload))
        server.enqueue(rangeResponse("bytes 0-0/1500", "R"))
        server.enqueue(rangeResponse("bytes 0-1499/1500", payload + "X"))

        val listener = RecordingDownloadListener()
        createDownloader(listener).download()

        assertEquals(0, listener.successCount.get())
        assertEquals(1, listener.failures.size)
        assertEquals(1, listener.terminalCount())
        assertTrue(listener.failures.single().message.orEmpty().contains("length mismatch"))
    }

    @Test
    fun multipleRangeFailures_convergeToOneTerminalFailure() {
        val payload = "M".repeat(3_000)
        server.enqueue(MockResponse().setResponseCode(200).setBody(payload))
        server.enqueue(rangeResponse("bytes 0-0/3000", "M"))
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(503))

        val listener = RecordingDownloadListener()
        createDownloader(listener, threadCount = 2).download()

        assertEquals(0, listener.successCount.get())
        assertEquals(1, listener.failures.size)
        assertEquals(2, listener.chunkFailureCount.get())
        assertEquals(1, listener.terminalCount())
    }

    @Test
    fun ignoredRangeProbe_fallsBackToSingleStreamAndSucceedsOnce() {
        val payload = "stream-tail-".repeat(137)
        server.enqueue(MockResponse().setResponseCode(200).setBody(payload))
        server.enqueue(MockResponse().setResponseCode(200).setBody(payload))
        server.enqueue(MockResponse().setResponseCode(200).setBody(payload))

        val listener = RecordingDownloadListener()
        createDownloader(listener).download()

        assertEquals(1, listener.successCount.get())
        assertEquals(0, listener.failures.size)
        assertEquals(1, listener.terminalCount())
        assertArrayEquals(payload.toByteArray(), outputFile.readBytes())
    }

    @Test
    fun singleStreamHttpFailure_reportsFailureOnlyOnce() {
        val payload = "known-length"
        server.enqueue(MockResponse().setResponseCode(200).setBody(payload))
        server.enqueue(rangeResponse("bytes 0-0/${payload.length}", payload.take(1)))
        server.enqueue(MockResponse().setResponseCode(503).setBody("unavailable"))

        val listener = RecordingDownloadListener()
        createDownloader(listener, forceStream = true).download()

        assertEquals(0, listener.successCount.get())
        assertEquals(1, listener.failures.size)
        assertEquals(1, listener.terminalCount())
        assertTrue(listener.failures.single().message.orEmpty().contains("503"))
    }

    @Test
    fun unexpectedPartialSingleStreamResponse_reportsFailureOnlyOnce() {
        val payload = "F".repeat(1_500)
        server.enqueue(MockResponse().setResponseCode(200).setBody(payload))
        server.enqueue(rangeResponse("bytes 0-0/1500", "F"))
        server.enqueue(rangeResponse("bytes 0-749/1500", payload.take(750)))

        val listener = RecordingDownloadListener()
        createDownloader(listener, forceStream = true).download()

        assertEquals(0, listener.successCount.get())
        assertEquals(1, listener.failures.size)
        assertEquals(1, listener.terminalCount())
        assertTrue(listener.failures.single().message.orEmpty().contains("partial response"))
    }

    @Test
    fun shortRangeResponse_reportsFailureWithoutSuccess() {
        val payload = "S".repeat(1_500)
        server.enqueue(MockResponse().setResponseCode(200).setBody(payload))
        server.enqueue(rangeResponse("bytes 0-0/1500", "S"))
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-1499/1500")
                .setChunkedBody("S".repeat(1_000), 256)
        )

        val listener = RecordingDownloadListener()
        createDownloader(listener).download()

        assertEquals(0, listener.successCount.get())
        assertEquals(1, listener.failures.size)
        assertEquals(1, listener.terminalCount())
        assertTrue(listener.failures.single().message.orEmpty().contains("ended early"))
    }

    @Test
    fun pauseDuringSingleStream_reportsPauseWithoutLateSuccess() {
        val payload = "P".repeat(128 * 1024)
        enqueueSlowSingleStream(payload)
        val listener = RecordingDownloadListener()
        val executor = Executors.newSingleThreadExecutor()

        try {
            val download = executor.submit {
                createDownloader(listener, forceStream = true).download()
            }
            waitUntil { outputFile.exists() && outputFile.length() > 0L }
            CustomFileDownloader.pause(outputFile)
            download.get(10, TimeUnit.SECONDS)

            assertEquals(0, listener.successCount.get())
            assertEquals(1, listener.failures.size)
            assertEquals(CustomFileDownloader.PAUSE_ACTION, listener.failures.single().message)
            assertEquals(1, listener.terminalCount())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun cancelDuringSingleStream_reportsCancelWithoutLateSuccess() {
        val payload = "C".repeat(128 * 1024)
        enqueueSlowSingleStream(payload)
        val listener = RecordingDownloadListener()
        val executor = Executors.newSingleThreadExecutor()

        try {
            val download = executor.submit {
                createDownloader(listener, forceStream = true).download()
            }
            waitUntil { outputFile.exists() && outputFile.length() > 0L }
            CustomFileDownloader.cancel(outputFile)
            download.get(10, TimeUnit.SECONDS)

            assertEquals(0, listener.successCount.get())
            assertEquals(1, listener.failures.size)
            assertEquals(CustomFileDownloader.CANCELED_ACTION, listener.failures.single().message)
            assertEquals(1, listener.terminalCount())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun cancelMarker_takesPriorityOverStopAndSaveMarker() {
        val payload = "X".repeat(16 * 1024)
        enqueueSlowSingleStream(payload, throttleMillis = 100L)
        val listener = RecordingDownloadListener()
        val executor = Executors.newSingleThreadExecutor()

        try {
            val download = executor.submit {
                createDownloader(listener, forceStream = true).download()
            }
            waitUntil { outputFile.exists() && outputFile.length() > 0L }
            CustomFileDownloader.stopAndSave(outputFile)
            assertTrue(File(downloadDirectory, "save").exists())
            assertTrue(File(downloadDirectory, "cancel").createNewFile())
            download.get(10, TimeUnit.SECONDS)

            assertEquals(0, listener.successCount.get())
            assertEquals(1, listener.failures.size)
            assertEquals(CustomFileDownloader.CANCELED_ACTION, listener.failures.single().message)
            assertEquals(1, listener.terminalCount())
        } finally {
            executor.shutdownNow()
        }
    }

    private fun createDownloader(
        listener: DownloadListener,
        threadCount: Int = 1,
        forceStream: Boolean = false,
        headers: Map<String, String> = emptyMap()
    ): CustomFileDownloader {
        return CustomFileDownloader(
            url = server.url("/video.bin").toUrl(),
            file = outputFile,
            threadCount = threadCount,
            headers = headers,
            client = OkHttpClient(),
            listener = listener,
            isForceStreamDownloadMode = forceStream
        )
    }

    private fun enqueueSlowSingleStream(payload: String, throttleMillis: Long = 20L) {
        server.enqueue(MockResponse().setResponseCode(200).setBody(payload))
        server.enqueue(rangeResponse("bytes 0-0/${payload.length}", payload.take(1)))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(payload)
                .throttleBody(512, throttleMillis, TimeUnit.MILLISECONDS)
        )
    }

    private fun rangeResponse(contentRange: String, body: String): MockResponse {
        return MockResponse()
            .setResponseCode(206)
            .setHeader("Content-Range", contentRange)
            .setBody(body)
    }

    private fun waitUntil(timeoutMillis: Long = 5_000L, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (!condition()) {
            if (System.nanoTime() >= deadline) {
                throw AssertionError("Timed out waiting for download to start")
            }
            Thread.sleep(10L)
        }
    }

    private class RecordingDownloadListener : DownloadListener {
        val successCount = AtomicInteger(0)
        val failures = CopyOnWriteArrayList<Throwable>()
        val chunkFailureCount = AtomicInteger(0)

        override fun onSuccess() {
            successCount.incrementAndGet()
        }

        override fun onFailure(e: Throwable) {
            failures += e
        }

        override fun onProgressUpdate(downloadedBytes: Long, totalBytes: Long) = Unit

        override fun onChunkProgressUpdate(
            downloadedBytes: Long,
            allBytesChunk: Long,
            chunkIndex: Int
        ) = Unit

        override fun onChunkFailure(e: Throwable, index: CustomFileDownloader.Chunk) {
            chunkFailureCount.incrementAndGet()
        }

        fun terminalCount(): Int = successCount.get() + failures.size
    }
}
