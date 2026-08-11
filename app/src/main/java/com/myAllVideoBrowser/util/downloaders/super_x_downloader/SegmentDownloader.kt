package com.myAllVideoBrowser.util.downloaders.super_x_downloader

import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.downloaders.super_x_downloader.control.FileBasedDownloadController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * A coroutine-based downloader for individual media segments.
 * It handles retries and checks for cancellation/pause signals.
 */
class SegmentDownloader(
    private val client: OkHttpClient,
    private val headers: Map<String, String>,
    private val controller: FileBasedDownloadController,
    private val onProgress: ((bytes: Long) -> Unit)? = null
) {

    companion object {
        private const val RETRY_COUNT = 3
    }

    /**
     * Downloads a single segment from a URL to a file with retries.
     * This is a suspend function and integrates with structured concurrency.
     *
     * @param segmentUrl The URL of the segment to download.
     * @param outputFile The file where the segment will be saved.
     * @param logPrefix A prefix for logging (e.g., "HLS", "MPD").
     * @param segmentIdentifier A unique identifier for the segment for logging (e.g., index).
     * @return The number of bytes downloaded.
     * @throws IOException if the download fails after all retries.
     * @throws CancellationException if a pause or cancel is requested via the controller.
     */
    suspend fun download(
        segmentUrl: String,
        outputFile: File,
        logPrefix: String,
        segmentIdentifier: Any
    ): Long {
        if (outputFile.exists() && outputFile.length() > 0) {
            AppLogger.d("$logPrefix: Segment $segmentIdentifier already exists. Skipping.")
            return outputFile.length()
        }

        val parentDirectory = outputFile.parentFile
            ?: throw IOException("Segment output has no parent directory: ${outputFile.absolutePath}")
        if ((!parentDirectory.exists() && !parentDirectory.mkdirs()) || !parentDirectory.isDirectory) {
            throw IOException("Unable to create segment output directory: ${parentDirectory.absolutePath}")
        }
        val stagingFile = File(parentDirectory, "${outputFile.name}.part")

        var lastException: Exception? = null
        for (attempt in 1..RETRY_COUNT) {
            // Check for interruption before every attempt
            if (controller.isPauseOrCancelRequested()) {
                deleteStagingFile(stagingFile, null)
                throw CancellationException("Download interrupted by user.")
            }

            try {
                currentCoroutineContext().ensureActive()
                if (stagingFile.exists() && !stagingFile.delete()) {
                    throw IOException("Unable to clear stale segment staging file: ${stagingFile.absolutePath}")
                }
                AppLogger.d("$logPrefix: Downloading segment $segmentIdentifier from $segmentUrl (Attempt $attempt/$RETRY_COUNT)")
                val request = Request.Builder().url(segmentUrl).headers(headers.toHeaders()).build()

                val call = client.newCall(request)
                val bytesCopied = call.execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("Failed to download segment $segmentIdentifier. HTTP ${response.code}")
                    }

                    var copied = 0L
                    response.body.byteStream().use { input ->
                        stagingFile.outputStream().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                if (controller.isPauseOrCancelRequested()) {
                                    call.cancel()
                                    throw CancellationException("Download interrupted by user.")
                                }
                                val bytesRead = input.read(buffer)
                                if (bytesRead < 0) break
                                output.write(buffer, 0, bytesRead)
                                copied += bytesRead
                            }
                        }
                    }
                    copied
                }
                if (bytesCopied <= 0L) {
                    throw IOException("Downloaded segment $segmentIdentifier is empty.")
                }
                if (controller.isPauseOrCancelRequested()) {
                    throw CancellationException("Download interrupted by user.")
                }
                if (outputFile.exists() && !outputFile.delete()) {
                    throw IOException("Unable to replace segment file: ${outputFile.absolutePath}")
                }
                if (!stagingFile.renameTo(outputFile)) {
                    throw IOException("Unable to publish segment file: ${outputFile.absolutePath}")
                }
                AppLogger.d("$logPrefix: Segment $segmentIdentifier downloaded successfully.")
                onProgress?.invoke(bytesCopied)
                return bytesCopied // Success, return the size
            } catch (e: Exception) {
                if (e is CancellationException) {
                    deleteStagingFile(stagingFile, e)
                    throw e
                }

                lastException = e
                AppLogger.w("$logPrefix: Failed to download segment $segmentIdentifier on attempt $attempt: ${e.message}")
                deleteStagingFile(stagingFile, e)

                // Don't wait on the last attempt
                if (attempt < RETRY_COUNT) {
                    delay(1000L * attempt)
                }
            }
        }
        throw IOException(
            "Failed to download segment $segmentIdentifier after $RETRY_COUNT attempts.",
            lastException
        )
    }

    private fun deleteStagingFile(stagingFile: File, error: Exception?) {
        if (stagingFile.exists() && !stagingFile.delete()) {
            val cleanupError = IOException(
                "Unable to remove segment staging file: ${stagingFile.absolutePath}"
            )
            if (error != null) {
                error.addSuppressed(cleanupError)
            } else {
                throw cleanupError
            }
        }
    }
}
