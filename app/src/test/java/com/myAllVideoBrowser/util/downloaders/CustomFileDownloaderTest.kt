package com.myAllVideoBrowser.util.downloaders.custom_downloader

import android.app.Application
import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        writeRangeLayout(3_000L, listOf(0L..1499L, 1500L..2999L))
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
        writeRangeLayout(1_500L, listOf(0L..1499L))
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

    @Test
    fun systemInterruption_stopsNetworkButPreservesResumeDataWithoutCancelMarker() {
        val payload = "I".repeat(128 * 1024)
        enqueueSlowSingleStream(payload)
        val listener = RecordingDownloadListener()
        val executor = Executors.newSingleThreadExecutor()
        val downloader = createDownloader(listener, forceStream = true)

        try {
            val download = executor.submit { downloader.download() }
            waitUntil { outputFile.exists() && outputFile.length() > 0L }
            downloader.interruptForSystemStop()
            download.get(10, TimeUnit.SECONDS)

            assertEquals(0, listener.successCount.get())
            assertEquals(CustomFileDownloader.SYSTEM_INTERRUPTED_ACTION, listener.failures.single().message)
            assertTrue(downloadDirectory.isDirectory)
            assertTrue(outputFile.exists())
            assertFalse(File(downloadDirectory, "cancel").exists())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun resumeAfterThreadCountChange_keepsOriginalRangesAndProducesCompleteFile() {
        val payload = "0123456789ABCDEF".toByteArray()
        outputFile.writeBytes(ByteArray(payload.size))
        outputFile.outputStream().use { output ->
            output.write(payload, 0, 2)
            output.write(ByteArray(6))
            output.write(payload, 8, 2)
            output.write(ByteArray(6))
        }
        writeRangeLayout(16L, listOf(0L..7L, 8L..15L))
        File(downloadDirectory, "chunk_0").writeText("2", Charsets.UTF_8)
        File(downloadDirectory, "chunk_1").writeText("2", Charsets.UTF_8)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range")
                if (range == null) return MockResponse().setResponseCode(200).setBody(payload.toString(Charsets.UTF_8))
                val match = Regex("bytes=(\\d+)-(\\d+)").matchEntire(range)
                    ?: return MockResponse().setResponseCode(400)
                val start = match.groupValues[1].toInt()
                val end = match.groupValues[2].toInt()
                val body = payload.copyOfRange(start, end + 1).toString(Charsets.UTF_8)
                return rangeResponse("bytes $start-$end/${payload.size}", body)
            }
        }

        val listener = RecordingDownloadListener()
        createDownloader(listener, threadCount = 4).download()

        assertEquals(1, listener.successCount.get())
        assertArrayEquals(payload, outputFile.readBytes())
        val requestedRanges = generateSequence { server.takeRequest(200, TimeUnit.MILLISECONDS) }
            .mapNotNull { it.getHeader("Range") }
            .toList()
        assertTrue(requestedRanges.contains("bytes=2-7"))
        assertTrue(requestedRanges.contains("bytes=10-15"))
        assertTrue(requestedRanges.none { it == "bytes=2-3" || it == "bytes=6-7" })
    }

    @Test
    fun scopedRedirect_replaysCredentialsAtOriginButStripsThemFromCrossOriginTarget() {
        val targetServer = MockWebServer()
        targetServer.start()
        val payload = "redirected-complete-file"
        try {
            repeat(3) {
                server.enqueue(
                    MockResponse()
                        .setResponseCode(302)
                        .setHeader("Location", targetServer.url("/video.bin"))
                )
                targetServer.enqueue(MockResponse().setResponseCode(200).setBody(payload))
            }
            val listener = RecordingDownloadListener()
            val originUrl = server.url("/authenticated/video.bin")
            val downloader = CustomFileDownloader(
                url = originUrl.toUrl(),
                file = outputFile,
                threadCount = 1,
                headers = mapOf(
                    "Cookie" to "session=origin",
                    "Authorization" to "Bearer origin",
                    "User-Agent" to "SurfSave test"
                ),
                client = OkHttpClient(),
                listener = listener,
                isForceStreamDownloadMode = true,
                credentialOriginUrl = originUrl.toString()
            )

            downloader.download()

            assertEquals(1, listener.successCount.get())
            assertArrayEquals(payload.toByteArray(), outputFile.readBytes())
            repeat(3) {
                val originRequest = server.takeRequest()
                assertEquals("session=origin", originRequest.getHeader("Cookie"))
                assertEquals("Bearer origin", originRequest.getHeader("Authorization"))
                val targetRequest = targetServer.takeRequest()
                assertNull(targetRequest.getHeader("Cookie"))
                assertNull(targetRequest.getHeader("Authorization"))
                assertEquals("SurfSave test", targetRequest.getHeader("User-Agent"))
            }
        } finally {
            targetServer.shutdown()
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

    private fun writeRangeLayout(totalLength: Long, ranges: List<LongRange>) {
        val layout = mapOf(
            "schemaVersion" to 1,
            "resourceUrl" to server.url("/video.bin").toString(),
            "totalLength" to totalLength,
            "etag" to null,
            "lastModified" to null,
            "chunks" to ranges.mapIndexed { index, range ->
                mapOf("index" to index, "start" to range.first, "end" to range.last)
            }
        )
        File(downloadDirectory, "range_layout_v1.json")
            .writeText(Gson().toJson(layout), Charsets.UTF_8)
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
