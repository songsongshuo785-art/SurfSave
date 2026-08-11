package com.myAllVideoBrowser.util.downloaders.custom_downloader

import com.myAllVideoBrowser.util.AppLogger
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.EOFException
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URL
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.Date
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

interface DownloadListener {
    fun onSuccess()

    fun onFailure(e: Throwable)

    fun onProgressUpdate(downloadedBytes: Long, totalBytes: Long)

    fun onChunkProgressUpdate(downloadedBytes: Long, allBytesChunk: Long, chunkIndex: Int)

    fun onChunkFailure(e: Throwable, index: CustomFileDownloader.Chunk)
}

class CustomFileDownloader(
    private val url: URL,
    // File is always must be placed in folder with file name without extension
    private val file: File,
    private val threadCount: Int,
    private val headers: Map<String, String>,
    private val client: OkHttpClient,
    private val listener: DownloadListener?,
    private val isForceStreamDownloadMode: Boolean,
) {
    private val executorService: ExecutorService = Executors.newFixedThreadPool(threadCount)
    private val isPaused = AtomicBoolean(false)

    private val isSaved = AtomicBoolean(false)
    private val isCanceled = AtomicBoolean(false)
    private val isTerminalCallbackDelivered = AtomicBoolean(false)
    private var lastProgressUpdate = AtomicLong(0L)
    private val totalBytesAll = AtomicLong(0L)
    private val totalBytesChunks = AtomicLongArray(threadCount)
    private val copiedBytesChunks = AtomicLongArray(threadCount)
    private val callBackIntervalMin = 1000

    companion object {
        const val PAUSE_ACTION = "PAUSE_ACTION"

        const val STOPPED_AND_SAVE_ACTION = "STOPPED_AND_SAVE_ACTION"

        const val CANCELED_ACTION = "CANCELED_ACTION"

        private val CONTENT_RANGE_PATTERN =
            Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+)", RegexOption.IGNORE_CASE)

        fun pause(fileToPause: File) {
            if (fileToPause.isDirectory) {
                throw Error("fileToPause is directory")
            }
            File(fileToPause.parentFile, Helper.PAUSE_FILE_NAME).createNewFile()
        }

        fun pauseByDownloadsFolder(directoryToPause: File) {
            if (!directoryToPause.isDirectory) {
                throw Error("fileToPause is not dir")
            }
            File(directoryToPause.parentFile, Helper.PAUSE_FILE_NAME).createNewFile()
        }

        fun stopAndSave(fileToSave: File) {
            if (fileToSave.isDirectory) {
                throw Error("fileToSave is directory")
            }
            val isPaused = Helper.isPaused(fileToSave)
            File(fileToSave.parentFile, Helper.SAVE_FILE_NAME).createNewFile()
            if (isPaused) {
                Helper.unPause(fileToSave)
            }
        }

        fun isPaused(fileToPause: File): Boolean {
            if (fileToPause.isDirectory) {
                throw Error("fileToPause is directory")
            }
            return Helper.isPaused(fileToPause)
        }

        fun isStoppedAndSave(fileToSave: File): Boolean {
            if (fileToSave.isDirectory) {
                throw Error("File is directory")
            }

            return Helper.isSave(fileToSave)
        }

        fun cancel(fileToCancel: File) {
            if (fileToCancel.isDirectory) {
                throw Error("File is directory")
            }

            val downloadDirectory = fileToCancel.parentFile ?: return
            File(downloadDirectory, Helper.CANCEL_FILE_NAME).createNewFile()
            if (!downloadDirectory.deleteRecursively() && downloadDirectory.exists()) {
                // Windows/JVM tests and some filesystems cannot unlink an open file. Keep an
                // explicit marker so the active downloader still observes the cancellation.
                File(downloadDirectory, Helper.CANCEL_FILE_NAME).createNewFile()
            }
        }

        fun cancelByDownloadDirectory(dirToCancel: File) {
            if (!dirToCancel.isDirectory) {
                throw Error("File is not dir")
            }

            dirToCancel.deleteRecursively()
        }
    }

    private val totalCopiedBytes: Long
        get() {
            var sum = 0L
            for (i in 0..<copiedBytesChunks.length()) {
                val value = copiedBytesChunks.get(i)
                sum += value
            }

            return sum
        }

    fun download() {
        val failure = try {
            downloadInternal()
            null
        } catch (e: Throwable) {
            unwrapExecutionException(e)
        } finally {
            executorService.shutdownNow()
        }

        if (failure == null) {
            deliverSuccess()
        } else {
            deliverFailure(failure)
        }
    }

    private fun downloadInternal() {
        val contentSize = getContentLength()
        totalBytesAll.set(contentSize)

        Helper.unPause(file)
        Helper.unStopAndSave(file)

        RandomAccessFile(file, "rw").use { randomAccessFile ->
            val fileChannel = randomAccessFile.channel
            val supportsRange = contentSize > 0L && isUrlSupportingBytesRangeHeader(contentSize)
            val useRegularStream =
                !supportsRange || (threadCount == 1 && isForceStreamDownloadMode)

            if (useRegularStream) {
                AppLogger.d("Range download unavailable, using a single stream.")
                val result = executorService.submit {
                    downloadRegularStream(fileChannel)
                }
                awaitFuture(result)
            } else {
                randomAccessFile.setLength(contentSize)
                downloadRanges(contentSize, fileChannel)
            }
        }

        throwIfControlRequested()
    }

    private fun downloadRanges(contentSize: Long, fileChannel: FileChannel) {
        val chunkCount = minOf(threadCount.toLong(), contentSize).toInt()
        val chunkSize = contentSize / chunkCount
        val ranges = (0 until chunkCount).map {
            val start = it * chunkSize
            val end = if (it == chunkCount - 1) contentSize - 1 else (it + 1) * chunkSize - 1
            start..end
        }

        val chunkFutureMap = mutableMapOf<Chunk, Future<*>>()
        AppLogger.d(
            "Start Downloading: file: $file threadCount: $chunkCount ranges: $ranges"
        )
        ranges.forEachIndexed { index, range ->
            val chunk = Chunk(index, range, range.last - range.first + 1)
            chunkFutureMap[chunk] = executorService.submit {
                downloadChunk(range, fileChannel, index)
            }
        }

        var firstFailure: Throwable? = null
        chunkFutureMap.forEach { (chunk, future) ->
            try {
                awaitFuture(future)
            } catch (e: Throwable) {
                val failure = unwrapExecutionException(e)
                if (firstFailure == null) {
                    firstFailure = failure
                }
                onChunkFailure(failure, chunk)
            }
        }

        throwIfControlRequested()
        firstFailure?.let {
            throw it
        }
    }

    private fun awaitFuture(future: Future<*>) {
        try {
            future.get()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        }
    }

    private fun unwrapExecutionException(error: Throwable): Throwable {
        return if (error is ExecutionException && error.cause != null) {
            error.cause!!
        } else {
            error
        }
    }

    private fun downloadRegularStream(fileChannel: FileChannel) {
        val req = getOkRequest()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) {
                throw IOException("Failed to download file: ${res.code}")
            }
            if (res.code == 206) {
                throw IOException("Unexpected partial response for single-stream download")
            }

            val contentLength = res.body.contentLength()
            if (contentLength == -1L) {
                AppLogger.w("Content length is unknown for single-threaded download.")
            } else {
                totalBytesAll.set(contentLength)
            }

            fileChannel.truncate(0L)
            val buffer = ByteArray(Helper.DOWNLOAD_BUFFER_SIZE)
            var bytesCopied = 0L

            copiedBytesChunks[0] = 0L
            totalBytesChunks[0] = contentLength

            res.body.byteStream().use { urlStream ->
                while (true) {
                    throwIfControlRequested()
                    val bytesRead = urlStream.read(buffer)
                    if (bytesRead < 0) {
                        break
                    }
                    if (bytesRead == 0) {
                        continue
                    }
                    writeFully(fileChannel, buffer, bytesRead, bytesCopied)
                    bytesCopied += bytesRead
                    copiedBytesChunks[0] = bytesCopied
                    onProgressUpdate(bytesCopied, totalBytesAll.get())
                }
            }

            throwIfControlRequested()
            if (contentLength >= 0L && bytesCopied != contentLength) {
                throw EOFException(
                    "Single-stream response length mismatch: expected=$contentLength actual=$bytesCopied"
                )
            }
        }
    }

    private fun deliverSuccess() {
        if (!isTerminalCallbackDelivered.compareAndSet(false, true)) return
        AppLogger.d("DOWNLOAD SUCCESS: $file")
        listener?.onSuccess()
    }

    private fun deliverFailure(e: Throwable) {
        if (!isTerminalCallbackDelivered.compareAndSet(false, true)) return
        AppLogger.e("Task Download Failed $e")

        if (e.message == CANCELED_ACTION) {
            val downloadDir = file.parentFile
            if (downloadDir?.exists() == true && downloadDir.isDirectory) {
                downloadDir.deleteRecursively()
            }
        }
        listener?.onFailure(e)
    }

    private fun onProgressUpdate(downloadedBytes: Long, totalBytes: Long) {
        val time = Date().time
        if (time - lastProgressUpdate.get() >= callBackIntervalMin) {
            isPaused.set(Helper.isPaused(file))
            isCanceled.set(Helper.isCanceled(file))
            isSaved.set(Helper.isSave(file))

            lastProgressUpdate.set(time)
            listener?.onProgressUpdate(downloadedBytes, totalBytes)
        }
    }

    private fun onChunkProgressUpdate(downloadedBytes: Long, allBytes: Long, chunkIndex: Int) {
        copiedBytesChunks[chunkIndex] = downloadedBytes

        onProgressUpdate(totalCopiedBytes, totalBytesAll.get())

        listener?.onChunkProgressUpdate(downloadedBytes, allBytes, chunkIndex)
    }

    private fun onChunkFailure(e: Throwable, index: Chunk) {
        AppLogger.e("Chunk $index Download Failed: ${e.message}", e)
        listener?.onChunkFailure(e, index)
    }

    private fun downloadChunk(range: LongRange, fileChannel: FileChannel, chunkIndex: Int) {
        val chunkFile = File(file.parentFile, "chunk_$chunkIndex")
        val isResume = !chunkFile.createNewFile()
        var bytesCopied = 0L
        if (isResume) {
            bytesCopied = chunkFile.inputStream().use { chunkStream ->
                chunkStream.bufferedReader().use {
                    val text = it.readText().trim()
                    text.toLongOrNull() ?: 0L
                }
            }
        }
        AppLogger.d(
            "CHUNK $chunkIndex DOWNLOAD START, bytes copied: $bytesCopied  isResume: $isResume"
        )

        copiedBytesChunks[chunkIndex] = bytesCopied

        val totalChunkBytes = range.last - range.first + 1
        if (bytesCopied !in 0L..totalChunkBytes) {
            throw IOException(
                "Invalid saved chunk progress: chunk=$chunkIndex saved=$bytesCopied total=$totalChunkBytes"
            )
        }
        totalBytesChunks[chunkIndex] = totalChunkBytes

        if (bytesCopied == totalChunkBytes) {
            copiedBytesChunks[chunkIndex] = totalChunkBytes

            return
        }

        val requestedStart = range.first + bytesCopied
        val requestedEnd = range.last
        val expectedResponseBytes = requestedEnd - requestedStart + 1
        val req = getOkRequestRange(requestedStart, requestedEnd)

        client.newCall(req).execute().use { res ->
            validateRangeResponse(
                response = res,
                requestedStart = requestedStart,
                requestedEnd = requestedEnd,
                expectedTotalBytes = totalBytesAll.get()
            )

            val responseLength = res.body.contentLength()
            if (responseLength >= 0L && responseLength != expectedResponseBytes) {
                throw IOException(
                    "Range response length mismatch: expected=$expectedResponseBytes actual=$responseLength"
                )
            }

            val buffer = ByteArray(Helper.DOWNLOAD_BUFFER_SIZE)
            var remainingBytes = expectedResponseBytes
            copiedBytesChunks[chunkIndex] = bytesCopied

            RandomAccessFile(chunkFile, "rw").channel.use { chunkChannel ->
                res.body.byteStream().use { urlStream ->
                    while (remainingBytes > 0L) {
                        throwIfControlRequested()
                        val maxRead = minOf(buffer.size.toLong(), remainingBytes).toInt()
                        val bytesRead = urlStream.read(buffer, 0, maxRead)
                        if (bytesRead < 0) {
                            break
                        }
                        if (bytesRead == 0) {
                            continue
                        }

                        writeFully(fileChannel, buffer, bytesRead, range.first + bytesCopied)
                        bytesCopied += bytesRead
                        remainingBytes -= bytesRead
                        writeChunkProgress(chunkChannel, bytesCopied)
                        onChunkProgressUpdate(bytesCopied, totalChunkBytes, chunkIndex)
                    }
                }
            }

            throwIfControlRequested()
            if (remainingBytes != 0L || bytesCopied != totalChunkBytes) {
                throw EOFException(
                    "Range response ended early: chunk=$chunkIndex remaining=$remainingBytes"
                )
            }
        }
    }

    private fun validateRangeResponse(
        response: Response,
        requestedStart: Long,
        requestedEnd: Long,
        expectedTotalBytes: Long
    ) {
        if (response.code != 206) {
            throw IOException(
                "Server ignored byte range $requestedStart-$requestedEnd: HTTP ${response.code}"
            )
        }

        val contentRange = parseContentRange(response.header("Content-Range"))
            ?: throw IOException("Missing or invalid Content-Range response header")
        if (contentRange.start != requestedStart || contentRange.end != requestedEnd) {
            throw IOException(
                "Unexpected Content-Range: expected=$requestedStart-$requestedEnd " +
                    "actual=${contentRange.start}-${contentRange.end}"
            )
        }
        if (expectedTotalBytes > 0L && contentRange.total != expectedTotalBytes) {
            throw IOException(
                "Unexpected Content-Range total: expected=$expectedTotalBytes actual=${contentRange.total}"
            )
        }
    }

    private fun parseContentRange(value: String?): ByteContentRange? {
        val match = value?.trim()?.let { CONTENT_RANGE_PATTERN.matchEntire(it) } ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].toLongOrNull() ?: return null
        if (start < 0L || end < start || total <= end) return null
        return ByteContentRange(start, end, total)
    }

    private fun writeFully(
        channel: FileChannel,
        bytes: ByteArray,
        length: Int,
        position: Long
    ) {
        val byteBuffer = ByteBuffer.wrap(bytes, 0, length)
        var writePosition = position
        while (byteBuffer.hasRemaining()) {
            val written = channel.write(byteBuffer, writePosition)
            if (written <= 0) {
                throw IOException("Unable to make progress while writing download data")
            }
            writePosition += written
        }
    }

    private fun writeChunkProgress(channel: FileChannel, bytesCopied: Long) {
        val progressBytes = bytesCopied.toString().toByteArray(Charsets.UTF_8)
        channel.truncate(0L)
        writeFully(channel, progressBytes, progressBytes.size, 0L)
    }

    private fun throwIfControlRequested() {
        val saved = Helper.isSave(file)
        val paused = Helper.isPaused(file)
        val canceled = Helper.isCanceled(file)

        isSaved.set(saved)
        isPaused.set(paused)
        isCanceled.set(canceled)

        when {
            canceled -> throw DownloadControlException(CANCELED_ACTION)
            saved -> throw DownloadControlException(STOPPED_AND_SAVE_ACTION)
            paused -> throw DownloadControlException(PAUSE_ACTION)
        }
    }

    private fun isUrlSupportingBytesRangeHeader(contentSize: Long): Boolean {
        val req = getOkRequestRange(0, 0)

        try {
            client.newCall(req).execute().use { res ->
                if (res.code != 206) return false
                val contentRange = parseContentRange(res.header("Content-Range")) ?: return false
                val responseLength = res.body.contentLength()
                return contentRange.start == 0L &&
                    contentRange.end == 0L &&
                    contentRange.total == contentSize &&
                    (responseLength == -1L || responseLength == 1L)
            }
        } catch (e: Throwable) {
            return false
        }
    }

    private fun getOkRequest(): Request {
        return Request.Builder()
            .url(url)
            .headers(headers.toHeaders())
            // Detection may capture the WebView's media sub-request headers. A stale Range
            // header must never turn a full-file inspection or stream download into a partial
            // response that is then published as a complete file.
            .removeHeader("Range")
            .build()
    }

    private fun getOkRequestRange(startByte: Long?, endByte: Long?): Request {
        val end = endByte ?: ""
        val range = "bytes=$startByte-$end"

        return Request.Builder().url(url).headers(headers.toHeaders()).header("Range", range)
            .build()
    }

    private fun getContentLength(): Long {
        val req = getOkRequest()
        return client.newCall(req).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Failed to inspect download: HTTP ${response.code}")
            }
            if (response.code == 206) {
                throw IOException("Unexpected partial response while inspecting download")
            }
            response.body.contentLength()
        }
    }

    private data class ByteContentRange(val start: Long, val end: Long, val total: Long)

    private class DownloadControlException(action: String) : IOException(action)

    data class Chunk(val chunkIndex: Int, val range: LongRange, val chunkSize: Long) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Chunk

            if (chunkIndex != other.chunkIndex) return false
            if (range != other.range) return false
            if (chunkSize != other.chunkSize) return false

            return true
        }

        override fun hashCode(): Int {
            var result = chunkIndex
            result = 31 * result + range.hashCode()
            result = 31 * result + chunkSize.hashCode()
            return result
        }
    }

    private object Helper {
        const val PAUSE_FILE_NAME = "pause"

        const val CANCEL_FILE_NAME = "cancel"

        const val SAVE_FILE_NAME = "save"

        const val DOWNLOAD_BUFFER_SIZE = 1024

        fun unPause(fileToUnStop: File) {
            File(fileToUnStop.parentFile, PAUSE_FILE_NAME).delete()
        }

        fun unStopAndSave(fileToUnStop: File) {
            File(fileToUnStop.parentFile, SAVE_FILE_NAME).delete()
        }

        fun isPaused(fileToCheck: File): Boolean {
            return File(
                fileToCheck.parentFile, PAUSE_FILE_NAME
            ).exists()
        }

        fun isSave(fileToCheck: File): Boolean {
            return File(
                fileToCheck.parentFile, SAVE_FILE_NAME
            ).exists()
        }

        fun isCanceled(fileToCheck: File): Boolean {
            return !(fileToCheck.parentFile?.exists() ?: false) || File(
                fileToCheck.parentFile, CANCEL_FILE_NAME
            ).exists()
        }
    }
}
