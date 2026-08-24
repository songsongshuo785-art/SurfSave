package com.myAllVideoBrowser.util.downloaders.super_x_downloader.strategy

import com.antonkarpenko.ffmpegkit.FFmpegKit
import com.antonkarpenko.ffmpegkit.FFmpegSession
import com.antonkarpenko.ffmpegkit.ReturnCode
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.MediaCodecClassifier
import com.myAllVideoBrowser.util.downloaders.custom_downloader.CustomFileDownloader
import com.myAllVideoBrowser.util.downloaders.custom_downloader.DownloadListener
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskItem
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskState
import com.myAllVideoBrowser.util.downloaders.generic_downloader.workers.Progress
import com.myAllVideoBrowser.util.downloaders.super_x_downloader.SegmentDownloader
import com.myAllVideoBrowser.util.downloaders.super_x_downloader.control.FileBasedDownloadController
import com.myAllVideoBrowser.util.hls_parser.MpdPlaylistParser
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Download strategy for MPD (MPEG-DASH) manifests.
 *
 * This class handles two primary MPD types:
 * 1. Segment-based (SegmentTemplate/SegmentList): Downloads all segments in parallel and merges them.
 * 2. BaseURL-based (SegmentBase): Downloads complete video/audio files and merges them.
 *
 * @param httpClient The OkHttpClient for network requests.
 * @param getMpdRepresentations A function to parse the MPD manifest and find the correct video/audio streams.
 * @param onMergeProgress A callback to update progress when merging begins.
 * @param threadCount The number of parallel downloads for segments or chunks.
 * @param videoCodec The video codec to check for compatibility.
 */
class MpdDownloader(
    private val httpClient: OkHttpClient,
    private val getMpdRepresentations: suspend (url: String, headers: Map<String, String>) -> Pair<MpdPlaylistParser.MpdRepresentation?, MpdPlaylistParser.MpdRepresentation?>,
    private val onMergeProgress: (progress: Progress, task: VideoTaskItem) -> Unit,
    private val threadCount: Int,
    private val videoCodec: String?
) : ManifestDownloader {

    // Mutex to protect progress updates from concurrent BaseURL downloads
    private val progressMutex = Mutex()
    private var videoProgress = Progress(0, 0)
    private var audioProgress = Progress(0, 0)

    override suspend fun download(
        task: VideoTaskItem,
        headers: Map<String, String>,
        downloadDir: File,
        controller: FileBasedDownloadController,
        onProgress: (progress: Progress) -> Unit
    ): File {
        return withContext(Dispatchers.IO) {
            val (videoRep, audioRep) = getMpdRepresentations(task.url, headers)

            if (videoRep?.isDrmProtected == true || audioRep?.isDrmProtected == true) {
                AppLogger.e("MPD: Content is DRM-protected. Download is not permitted.")
                throw IOException("Cannot download: The content is protected by DRM and cannot be downloaded.")
            }

            val primaryRep = videoRep ?: audioRep
            ?: throw IOException("No downloadable video or audio representation found for the selected format.")

            val finalOutputFile = if (primaryRep.segments.isNotEmpty()) {
                AppLogger.d("MPD: Detected manifest with segments. Starting segment download.")
                downloadBySegments(
                    task,
                    headers,
                    downloadDir,
                    videoRep,
                    audioRep,
                    controller,
                    onProgress
                )
            } else if (primaryRep.baseUrls.isNotEmpty()) {
                AppLogger.d("MPD: Detected manifest with Base URLs. Starting file download.")
                downloadByBaseUrl(
                    task,
                    headers,
                    downloadDir,
                    videoRep,
                    audioRep,
                    controller,
                    onProgress
                )
            } else {
                throw IOException("MPD manifest contains neither segments nor base URLs. Cannot download.")
            }

            finalOutputFile
        }
    }

    /**
     * Downloads DASH content by fetching individual segments in parallel.
     */
    private suspend fun downloadBySegments(
        task: VideoTaskItem,
        headers: Map<String, String>,
        downloadDir: File,
        videoRep: MpdPlaylistParser.MpdRepresentation?, // Changed from List<Segment>
        audioRep: MpdPlaylistParser.MpdRepresentation?, // Changed from List<Segment>
        controller: FileBasedDownloadController,
        onProgress: (progress: Progress) -> Unit
    ): File = coroutineScope {
        val totalBytesDownloaded = AtomicLong(0)
        val segmentsCompleted = AtomicLong(0)

        val videoSegments = videoRep?.segments
        val audioSegments = audioRep?.segments

        val totalSegmentsToDownload = (videoSegments?.size ?: 0) + (audioSegments?.size ?: 0)
        if (totalSegmentsToDownload == 0) {
            throw IOException("No segments found to download for either video or audio.")
        }

        // Resume logic
        val alreadyDownloadedVideo = videoSegments?.filter {
            downloadDir.resolve("segment_${"%05d".format(videoSegments.indexOf(it))}.m4s").exists()
        } ?: emptyList()
        val alreadyDownloadedAudio = audioSegments?.filter {
            downloadDir.resolve("audio_segment_${"%05d".format(audioSegments.indexOf(it))}.m4s")
                .exists()
        } ?: emptyList()

        // Sum initial size and report progress
        val initialSize = (alreadyDownloadedVideo.sumOf {
            downloadDir.resolve(
                "segment_${
                    "%05d".format(videoSegments!!.indexOf(it))
                }.m4s"
            ).length()
        } +
                alreadyDownloadedAudio.sumOf {
                    downloadDir.resolve(
                        "audio_segment_${
                            "%05d".format(
                                audioSegments!!.indexOf(it)
                            )
                        }.m4s"
                    ).length()
                })
        totalBytesDownloaded.set(initialSize)
        segmentsCompleted.set((alreadyDownloadedVideo.size + alreadyDownloadedAudio.size).toLong())

        // Download remaining segments
        val segmentDownloader = SegmentDownloader(httpClient, headers, controller)
        val dispatcher = Dispatchers.IO.limitedParallelism(threadCount)
        val downloadJobs = mutableListOf<Job>()

        videoRep?.initializationUrl?.let { url ->
            val job = launch(dispatcher) {
                AppLogger.d("MPD: Downloading video init segment from $url")
                segmentDownloader.download(
                    url,
                    downloadDir.resolve("video_init.m4s"),
                    "MPD-Video-Init",
                    0
                )
            }
            downloadJobs.add(job)
        }

        audioRep?.initializationUrl?.let { url ->
            val job = launch(dispatcher) {
                AppLogger.d("MPD: Downloading audio init segment from $url")
                segmentDownloader.download(
                    url,
                    downloadDir.resolve("audio_init.m4s"),
                    "MPD-Audio-Init",
                    0
                )
            }
            downloadJobs.add(job)
        }

        videoSegments?.filterNot { alreadyDownloadedVideo.contains(it) }?.forEach { segment ->
            val index = videoSegments.indexOf(segment)
            val job = launch(dispatcher) {
                val downloadedBytes = segmentDownloader.download(
                    segment.url,
                    downloadDir.resolve("segment_${"%05d".format(index)}.m4s"),
                    "MPD-Video",
                    index
                )
                val completed = segmentsCompleted.incrementAndGet()
                val totalDownloaded = totalBytesDownloaded.addAndGet(downloadedBytes)
                val estimatedTotal =
                    if (completed > 0) (totalDownloaded / completed) * totalSegmentsToDownload else 0
                onProgress(Progress(totalDownloaded, estimatedTotal))
            }
            downloadJobs.add(job)
        }

        audioSegments?.filterNot { alreadyDownloadedAudio.contains(it) }?.forEach { segment ->
            val index = audioSegments.indexOf(segment)
            val job = launch(dispatcher) {
                val downloadedBytes = segmentDownloader.download(
                    segment.url,
                    downloadDir.resolve("audio_segment_${"%05d".format(index)}.m4s"),
                    "MPD-Audio",
                    index
                )
                val completed = segmentsCompleted.incrementAndGet()
                val totalDownloaded = totalBytesDownloaded.addAndGet(downloadedBytes)
                val estimatedTotal =
                    if (completed > 0) (totalDownloaded / completed) * totalSegmentsToDownload else 0
                onProgress(Progress(totalDownloaded, estimatedTotal))
            }
            downloadJobs.add(job)
        }

        downloadJobs.joinAll()
        if (controller.isInterrupted()) throw CancellationException("Download interrupted.")

        val totalDurationSeconds = videoRep?.segments?.sumOf { it.durationSeconds }
            ?: audioRep?.segments?.sumOf { it.durationSeconds } ?: 0.0
        AppLogger.d("MPD (Segments): All segments downloaded. Merging...")
        var isPreparing = true
        onMergeProgress(
            Progress(0, totalBytesDownloaded.get()),
            task.apply {
                this.lineInfo = "Merging... 0%"
                this.taskState = VideoTaskState.PREPARE
            })

        val finalOutputFile = downloadDir.resolve("merged_output.mp4")
        val mergeSession =
            mergeMpdSegments(
                downloadDir,
                videoRep,
                audioRep,
                finalOutputFile.absolutePath,
                totalDurationSeconds,
                controller::isInterrupted

            ) { percentage ->
                if (isPreparing && percentage == 100) {
                    isPreparing = false
                }

                val message =
                    if (isPreparing) "Preparing segments... $percentage%" else "Merging... $percentage%"

                onMergeProgress(
                    Progress(
                        totalBytesDownloaded.get() * percentage / 100,
                        totalBytesDownloaded.get()
                    ),
                    task.apply {
                        this.lineInfo = message
                        this.taskState = VideoTaskState.PREPARE
                    }
                )
            }

        if (!ReturnCode.isSuccess(mergeSession.returnCode)) {
            throw IOException("FFmpeg failed to merge MPD segments. Log: ${mergeSession.allLogsAsString}")
        }
        if (!finalOutputFile.isFile || finalOutputFile.length() <= 0L) {
            throw IOException("FFmpeg reported success but produced no MPD output.")
        }
        downloadDir.resolve("temp_video.mp4").delete()
        downloadDir.resolve("temp_audio.mp4").delete()
        finalOutputFile
    }

    /**
     * Downloads DASH content using Base URLs, which represent complete files.
     */
    private suspend fun downloadByBaseUrl(
        task: VideoTaskItem,
        headers: Map<String, String>,
        downloadDir: File,
        videoRep: MpdPlaylistParser.MpdRepresentation?,
        audioRep: MpdPlaylistParser.MpdRepresentation?,
        controller: FileBasedDownloadController,
        onProgress: (progress: Progress) -> Unit
    ): File = coroutineScope {
        val videoTempFile = downloadDir.resolve("video_stream.mp4")
        val audioTempFile = downloadDir.resolve("audio_stream.mp4")
        val finalFile = downloadDir.resolve("merged_output.mp4")

        val jobs = mutableListOf<Deferred<*>>()

        // Launch video download if it exists
        videoRep?.baseUrls?.takeIf { it.isNotEmpty() }?.let { urls ->
            val job = async {
                downloadFileWithCustomDownloader(
                    urls,
                    videoTempFile,
                    headers,
                    controller
                ) { downloaded, total ->
                    launch {
                        progressMutex.withLock {
                            videoProgress = Progress(downloaded, total)
                            onProgress(
                                Progress(
                                    videoProgress.currentBytes + audioProgress.currentBytes,
                                    videoProgress.totalBytes + audioProgress.totalBytes
                                )
                            )
                        }
                    }
                }
            }
            jobs.add(job)
        }

        // Launch audio download if it exists
        audioRep?.baseUrls?.takeIf { it.isNotEmpty() }?.let { urls ->
            val job = async {
                downloadFileWithCustomDownloader(
                    urls,
                    audioTempFile,
                    headers,
                    controller
                ) { downloaded, total ->
                    launch {
                        progressMutex.withLock {
                            audioProgress = Progress(downloaded, total)
                            onProgress(
                                Progress(
                                    videoProgress.currentBytes + audioProgress.currentBytes,
                                    videoProgress.totalBytes + audioProgress.totalBytes
                                )
                            )
                        }
                    }
                }
            }
            jobs.add(job)
        }

        jobs.awaitAll()
        if (controller.isInterrupted()) throw CancellationException("Download interrupted.")

        AppLogger.d("MPD (BaseURL): All stream downloads complete. Merging...")
        val totalDurationSeconds = videoRep?.segments?.sumOf { it.durationSeconds }
            ?: audioRep?.segments?.sumOf { it.durationSeconds } ?: 0.0

        onMergeProgress(
            Progress(
                0,
                videoTempFile.length() + audioTempFile.length()
            ),
            task.apply {
                this.lineInfo = "Merging... 0%"
                this.taskState = VideoTaskState.PREPARE
            }
        )
        val totalBytesDownloaded = videoProgress.totalBytes + audioProgress.totalBytes;
        val mergeSession = mergeBaseUrlStreams(
            videoTempFile,
            audioTempFile.takeIf { it.exists() },
            finalFile.absolutePath,
            totalDurationSeconds = totalDurationSeconds,
            onMergeProgress = { percentage ->
                onMergeProgress(
                    Progress(totalBytesDownloaded * percentage / 100, totalBytesDownloaded),
                    task.apply {
                        this.lineInfo = "Merging... $percentage%"
                        this.taskState = VideoTaskState.PREPARE
                    }
                )
            },
            shouldAbort = controller::isInterrupted,
            audioCodec = audioRep?.codecs
        )
        if (!ReturnCode.isSuccess(mergeSession.returnCode)) {
            throw IOException("FFmpeg failed to merge BaseURL streams. Log: ${mergeSession.allLogsAsString}")
        }
        if (!finalFile.isFile || finalFile.length() <= 0L) {
            throw IOException("FFmpeg reported success but produced no MPD BaseURL output.")
        }
        videoTempFile.delete()
        audioTempFile.delete()
        finalFile
    }

    /**
     * Wraps the legacy CustomFileDownloader in a pausable/cancellable coroutine.
     */
    private suspend fun downloadFileWithCustomDownloader(
        urls: List<String>,
        outputFile: File,
        headers: Map<String, String>,
        controller: FileBasedDownloadController,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ) = suspendCancellableCoroutine { continuation ->
        val listener = object : DownloadListener {
            override fun onSuccess() {
                if (continuation.isActive) continuation.resume(Unit)
            }

            override fun onFailure(e: Throwable) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onProgressUpdate(downloadedBytes: Long, totalBytes: Long) {
                onProgress(downloadedBytes, totalBytes)
            }

            override fun onChunkProgressUpdate(
                downloadedBytes: Long,
                allBytesChunk: Long,
                chunkIndex: Int
            ) {
            }

            override fun onChunkFailure(e: Throwable, index: CustomFileDownloader.Chunk) {}
        }

        val downloader = CustomFileDownloader(
            URL(urls.first()),
            outputFile,
            threadCount,
            headers,
            httpClient,
            listener,
            false
        )
        downloader.download()

        continuation.invokeOnCancellation {
            CustomFileDownloader.pause(outputFile)
        }

        CoroutineScope(continuation.context).launch {
            while (isActive) { // Check `isActive` of this new scope
                if (controller.isInterrupted()) {
                    CustomFileDownloader.pause(outputFile)
                    break
                }
                delay(500)
            }
        }
    }

    private fun mergeMpdSegments(
        mpdTmpDir: File,
        videoRep: MpdPlaylistParser.MpdRepresentation?,
        audioRep: MpdPlaylistParser.MpdRepresentation?,
        finalOutputPath: String,
        totalDurationSeconds: Double,
        shouldAbort: () -> Boolean,
        onMergeProgress: ((percentage: Int) -> Unit)?
    ): FFmpegSession {
        val videoSegments = videoRep?.segments
        val audioSegments = audioRep?.segments
        val hasVideo = !videoSegments.isNullOrEmpty()
        val hasAudio = !audioSegments.isNullOrEmpty()

        AppLogger.d("MPD: Starting manual concat merge. ${videoSegments?.size ?: 0} video, ${audioSegments?.size ?: 0} audio.")

        val tempVideoFile = mpdTmpDir.resolve("temp_video.mp4")
        val tempAudioFile = mpdTmpDir.resolve("temp_audio.mp4")

        val videoFilesToConcat = mutableListOf<File>()
        if (hasVideo) {
            if (videoRep?.initializationUrl != null) {
                val videoInitFile = mpdTmpDir.resolve("video_init.m4s")
                if (!videoInitFile.isFile || videoInitFile.length() <= 0L) {
                    throw IOException("MPD video initialization segment is missing or empty: ${videoInitFile.absolutePath}")
                }
                videoFilesToConcat.add(videoInitFile)
            }
            videoSegments.indices.forEach { index ->
                videoFilesToConcat.add(mpdTmpDir.resolve("segment_${"%05d".format(index)}.m4s"))
            }
        }

        val audioFilesToConcat = mutableListOf<File>()
        if (hasAudio) {
            if (audioRep?.initializationUrl != null) {
                val audioInitFile = mpdTmpDir.resolve("audio_init.m4s")
                if (!audioInitFile.isFile || audioInitFile.length() <= 0L) {
                    throw IOException("MPD audio initialization segment is missing or empty: ${audioInitFile.absolutePath}")
                }
                audioFilesToConcat.add(audioInitFile)
            }
            audioSegments.indices.forEach { index ->
                audioFilesToConcat.add(mpdTmpDir.resolve("audio_segment_${"%05d".format(index)}.m4s"))
            }
        }

        val totalConcatSize = (videoFilesToConcat.sumOf { if (it.exists()) it.length() else 0L } +
                audioFilesToConcat.sumOf { if (it.exists()) it.length() else 0L })
        var concatenatedBytes = 0L

        val concatProgressCallback: (bytes: Long) -> Unit = { bytes ->
            concatenatedBytes += bytes
            if (totalConcatSize > 0) {
                val percentage = ((concatenatedBytes * 100) / totalConcatSize).toInt()
                onMergeProgress?.invoke(percentage)
            }
        }

        if (hasVideo) {
            manualConcat(videoFilesToConcat, tempVideoFile, shouldAbort, concatProgressCallback)
        }
        if (hasAudio) {
            manualConcat(audioFilesToConcat, tempAudioFile, shouldAbort, concatProgressCallback)
        }

        AppLogger.d("MPD: Concatenation finished. Starting FFmpeg merge.")

        return mergeBaseUrlStreams(
            tempVideoFile.takeIf { hasVideo && it.isFile && it.length() > 0L },
            tempAudioFile.takeIf { it.exists() && it.length() > 0 },
            finalOutputPath,
            totalDurationSeconds,
            onMergeProgress,
            shouldAbort
        )
    }

    private fun mergeBaseUrlStreams(
        videoFile: File?,
        audioFile: File?,
        finalOutputPath: String,
        totalDurationSeconds: Double,
        onMergeProgress: ((percentage: Int) -> Unit)?,
        shouldAbort: () -> Boolean,
        audioCodec: String? = null
    ): FFmpegSession {
        val arguments = mutableListOf<String>()

        if (videoFile?.exists() == true) {
            arguments.addAll(listOf("-i", videoFile.absolutePath))
        }

        audioFile?.takeIf { it.exists() }?.let {
            arguments.addAll(listOf("-i", it.absolutePath))
        }

        if (arguments.isEmpty()) {
            throw IOException("No valid video or audio files to merge.")
        }
        arguments.add(0, "-y")

        val hasVideo = videoFile?.exists() == true
        val hasAudio = audioFile?.exists() == true

        addCommonMergeArguments(arguments, hasVideo, hasAudio, finalOutputPath, audioCodec)

        val commandString = arguments.joinToString(" ")
        AppLogger.d("FFmpeg: Executing MPD BaseURL merge with command: $commandString")

        val latch = CountDownLatch(1)
        lateinit var finalSession: FFmpegSession
        val abortSignaled = AtomicBoolean(false)
        val sessionId = AtomicLong(-1L)

        val session = FFmpegKit.executeAsync(commandString, { completedSession ->
            finalSession = completedSession
            if (!ReturnCode.isSuccess(completedSession.returnCode)) {
                AppLogger.e("FFmpeg merge failed. Log: ${completedSession.allLogsAsString}")
            }
            latch.countDown()
        }, { log ->
            AppLogger.d("FFmpeg: ${log.message}")
        }, { statistics ->
            if (shouldAbort()) {
                abortSignaled.set(true)
                sessionId.get().takeIf { it >= 0L }?.let(FFmpegKit::cancel)
                return@executeAsync
            }
            if (onMergeProgress != null && totalDurationSeconds > 0) {
                val totalDurationMillis = (totalDurationSeconds * 1000).toLong()
                val currentTimeMillis = statistics.time
                if (currentTimeMillis > 0) {
                    val percentage = ((currentTimeMillis * 100) / totalDurationMillis).toInt()
                    onMergeProgress(percentage.coerceIn(0, 100))
                }
            }
        })
        sessionId.set(session.sessionId)

        try {
            while (!latch.await(250L, TimeUnit.MILLISECONDS)) {
                if (shouldAbort()) {
                    abortSignaled.set(true)
                    FFmpegKit.cancel(session.sessionId)
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            FFmpegKit.cancel(session.sessionId)
            throw IOException("FFmpeg merge was interrupted.", e)
        }
        if (abortSignaled.get() || shouldAbort()) {
            throw CancellationException("MPD merge interrupted by user.")
        }

        return finalSession
    }

    private fun addCommonMergeArguments(
        arguments: MutableList<String>,
        hasVideo: Boolean,
        hasAudio: Boolean,
        finalOutputPath: String,
        audioCodec: String? = null
    ) {
        arguments.apply {
            when {
                hasVideo && hasAudio -> {
                    // Map video from the first input (0) and audio from the second (1).
                    // This is now applied to both segment and base URL merges.
                    add("-map"); add("0:v:0?"); add("-map"); add("1:a:0?")
                }
                // This case might not be strictly necessary if you always have one input
                // when there's only one stream, but it's good for robustness.
                hasVideo -> {
                    add("-map"); add("0:v:0?")
                }

                hasAudio -> {
                    add("-map"); add("0:a:0?")
                }
            }

            if (hasVideo && MediaCodecClassifier.requiresH264Transcode(videoCodec)) {
                add("-c:v"); add("libx264"); add("-preset"); add("veryfast"); add("-crf"); add("23"); add(
                    "-pix_fmt"
                ); add("yuv420p")
                if (hasAudio) {
                    add("-c:a")
                    add("copy")
                }
            } else {
                add("-c"); add("copy")
            }

            if (hasAudio && audioCodec?.contains("aac", ignoreCase = true) == true) {
                add("-bsf:a"); add("aac_adtstoasc")
            }

            add("-movflags"); add("+faststart")

            add("-y"); add(finalOutputPath)
        }
    }

    private fun manualConcat(
        filesToConcat: List<File>,
        outputFile: File,
        shouldAbort: () -> Boolean,
        onProgress: ((bytesCopied: Long) -> Unit)? = null
    ) {
        if (filesToConcat.isEmpty()) {
            throw IOException("No files to concatenate for ${outputFile.name}.")
        }
        filesToConcat.forEach { file ->
            if (!file.isFile || file.length() <= 0L) {
                throw IOException("MPD merge input is missing or empty: ${file.absolutePath}")
            }
        }
        AppLogger.d("ManualConcat: Starting for ${outputFile.name}. Concatenating ${filesToConcat.size} files.")

        try {
            outputFile.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var lastUpdateTime = System.currentTimeMillis()
                val updateInterval = 250L
                var bytesSinceLastUpdate = 0L

                filesToConcat.forEach { file ->
                    file.inputStream().use { input ->
                        while (true) {
                            if (shouldAbort()) {
                                throw CancellationException("MPD concatenation interrupted by user.")
                            }
                            val bytesRead = input.read(buffer)
                            if (bytesRead < 0) break
                            output.write(buffer, 0, bytesRead)
                            bytesSinceLastUpdate += bytesRead

                            val now = System.currentTimeMillis()
                            if (now - lastUpdateTime > updateInterval) {
                                onProgress?.invoke(bytesSinceLastUpdate)
                                // Reset counters
                                bytesSinceLastUpdate = 0L
                                lastUpdateTime = now
                            }
                        }
                    }
                }
                if (bytesSinceLastUpdate > 0) {
                    onProgress?.invoke(bytesSinceLastUpdate)
                }
            }
        } catch (error: Exception) {
            if (outputFile.exists() && !outputFile.delete()) {
                error.addSuppressed(IOException("Unable to remove incomplete MPD concatenation."))
            }
            throw error
        }
        AppLogger.d("ManualConcat: Finished. Output size: ${outputFile.length()} bytes.")
    }
}
