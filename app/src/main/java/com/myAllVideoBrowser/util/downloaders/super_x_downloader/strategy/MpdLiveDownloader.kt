package com.myAllVideoBrowser.util.downloaders.super_x_downloader.strategy

import com.antonkarpenko.ffmpegkit.FFmpegKit
import com.antonkarpenko.ffmpegkit.FFmpegSession
import com.antonkarpenko.ffmpegkit.ReturnCode
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.MediaCodecClassifier
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskItem
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskState
import com.myAllVideoBrowser.util.downloaders.generic_downloader.workers.Progress
import com.myAllVideoBrowser.util.downloaders.super_x_downloader.SegmentDownloader
import com.myAllVideoBrowser.util.downloaders.super_x_downloader.control.FileBasedDownloadController
import com.myAllVideoBrowser.util.hls_parser.MpdPlaylistParser
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/**
 * Download strategy for LIVE MPD (MPEG-DASH) manifests.
 *
 * This class "records" a live stream by sequentially downloading segments as they become available
 * until the download is cancelled by the user. It then merges the captured segments.
 *
 * @param httpClient The OkHttpClient for network requests.
 * @param getMpdRepresentations A function to parse the MPD manifest and find the correct video/audio streams.
 * @param onMergeProgress A callback to update progress when merging begins.
 * @param videoCodec The video codec to check for compatibility.
 */
class MpdLiveDownloader(
    private val httpClient: OkHttpClient,
    private val getMpdRepresentations: suspend (url: String, headers: Map<String, String>) -> Pair<MpdPlaylistParser.MpdRepresentation?, MpdPlaylistParser.MpdRepresentation?>,
    private val onMergeProgress: (progress: Progress, task: VideoTaskItem) -> Unit,
    private val videoCodec: String?,
    private val mergeOnly: Boolean = false
) : ManifestDownloader {
    override suspend fun download(
        task: VideoTaskItem,
        headers: Map<String, String>,
        downloadDir: File,
        controller: FileBasedDownloadController,
        onProgress: (progress: Progress) -> Unit
    ): File {
        return withContext(Dispatchers.IO) {
            var totalBytesDownloaded = 0L
            val finalOutputFile = downloadDir.resolve("merged_output.mp4")
            var captureIndex = MpdLiveCaptureIndex.Snapshot()

            val progressCallback: (bytes: Long) -> Unit = { bytes ->
                totalBytesDownloaded += bytes
                // Total size is unknown for live streams, so it's 0.
                onProgress(Progress(totalBytesDownloaded, 0))
            }

            if (!mergeOnly) {
                throwIfPauseOrCancelRequested(controller)
                // 1. Initial Manifest Parse and Init Segment Download
                val (initialVideoRep, initialAudioRep) = getMpdRepresentations(task.url, headers)
                downloadInitSegments(
                    initialVideoRep, initialAudioRep, downloadDir, controller, headers
                )
                captureIndex = MpdLiveCaptureIndex.loadOrMigrate(downloadDir)
                totalBytesDownloaded =
                    MpdLiveCaptureIndex.filesInOrder(
                        downloadDir,
                        captureIndex,
                        MpdLiveCaptureIndex.Stream.VIDEO
                    ).sumOf { it.length() } +
                    MpdLiveCaptureIndex.filesInOrder(
                        downloadDir,
                        captureIndex,
                        MpdLiveCaptureIndex.Stream.AUDIO
                    ).sumOf { it.length() }

                // 2. Start Recording Loop
                val segmentDownloader =
                    SegmentDownloader(httpClient, headers, controller, progressCallback)
                var updateInterval = 2000L

                AppLogger.d("MPD (Live): Starting recording loop for task ${task.mId}")
                task.setIsLive(true)

                while (currentCoroutineContext().isActive) {
                    when (controller.interruptionReason()) {
                        FileBasedDownloadController.InterruptionReason.PAUSE ->
                            throw CancellationException("MPD live recording paused by user.")
                        FileBasedDownloadController.InterruptionReason.CANCEL ->
                            throw CancellationException("MPD live recording canceled by user.")
                        FileBasedDownloadController.InterruptionReason.STOP_AND_SAVE -> break
                        FileBasedDownloadController.InterruptionReason.NONE -> Unit
                    }
                    val (videoRep, audioRep) = getMpdRepresentations(task.url, headers)
                    throwIfPauseOrCancelRequested(controller)

                    updateInterval = videoRep?.manifest?.minimumUpdatePeriod?.let {
                        (it * 1000).toLong().coerceAtLeast(1000L)
                    } ?: audioRep?.manifest?.minimumUpdatePeriod?.let {
                        (it * 1000).toLong().coerceAtLeast(1000L)
                    } ?: updateInterval

                    val newVideoSegments =
                        videoRep?.segments?.filterNot {
                            MpdLiveCaptureIndex.containsUrl(
                                captureIndex,
                                MpdLiveCaptureIndex.Stream.VIDEO,
                                it.url
                            )
                        }
                            ?: emptyList()
                    val newAudioSegments =
                        audioRep?.segments?.filterNot {
                            MpdLiveCaptureIndex.containsUrl(
                                captureIndex,
                                MpdLiveCaptureIndex.Stream.AUDIO,
                                it.url
                            )
                        }
                            ?: emptyList()

                    if (newVideoSegments.isNotEmpty() || newAudioSegments.isNotEmpty()) {
                        AppLogger.d("MPD (Live): Found ${newVideoSegments.size} new video and ${newAudioSegments.size} new audio segments.")

                        for (segment in newVideoSegments) {
                            if (MpdLiveCaptureIndex.containsUrl(
                                    captureIndex,
                                    MpdLiveCaptureIndex.Stream.VIDEO,
                                    segment.url
                                )
                            ) {
                                continue
                            }
                            val entry = MpdLiveCaptureIndex.nextEntry(
                                captureIndex,
                                MpdLiveCaptureIndex.Stream.VIDEO,
                                segment.url
                            )
                            val videoFile = downloadDir.resolve(entry.fileName)
                            segmentDownloader.download(segment.url, videoFile, "MPD-Live-V", 0)
                            captureIndex = MpdLiveCaptureIndex.publishEntry(
                                downloadDir,
                                captureIndex,
                                MpdLiveCaptureIndex.Stream.VIDEO,
                                entry
                            )
                            task.accumulatedDuration += segment.durationSeconds.toLong()
                        }
                        for (segment in newAudioSegments) {
                            if (MpdLiveCaptureIndex.containsUrl(
                                    captureIndex,
                                    MpdLiveCaptureIndex.Stream.AUDIO,
                                    segment.url
                                )
                            ) {
                                continue
                            }
                            val entry = MpdLiveCaptureIndex.nextEntry(
                                captureIndex,
                                MpdLiveCaptureIndex.Stream.AUDIO,
                                segment.url
                            )
                            val audioFile = downloadDir.resolve(entry.fileName)
                            segmentDownloader.download(segment.url, audioFile, "MPD-Live-A", 0)
                            captureIndex = MpdLiveCaptureIndex.publishEntry(
                                downloadDir,
                                captureIndex,
                                MpdLiveCaptureIndex.Stream.AUDIO,
                                entry
                            )
                            if (newVideoSegments.isEmpty()) {
                                task.accumulatedDuration += segment.durationSeconds.toLong()
                            }
                        }
                    } else {
                        AppLogger.d("MPD (Live): No new segments found.")
                    }

                    if (videoRep?.manifest?.type == "static" || audioRep?.manifest?.type == "static") {
                        AppLogger.d("MPD (Live): Stream type changed to 'static'. Ending recording.")
                        break
                    }

                    interruptibleDelay(updateInterval, controller)
                }
            } else {
                AppLogger.d("MPD (Live): Starting in MERGE-ONLY mode from local capture files.")
                captureIndex = MpdLiveCaptureIndex.loadOrMigrate(downloadDir)
                totalBytesDownloaded =
                    MpdLiveCaptureIndex.filesInOrder(
                        downloadDir,
                        captureIndex,
                        MpdLiveCaptureIndex.Stream.VIDEO
                    ).sumOf { it.length() } +
                    MpdLiveCaptureIndex.filesInOrder(
                        downloadDir,
                        captureIndex,
                        MpdLiveCaptureIndex.Stream.AUDIO
                    ).sumOf { it.length() }
            }

            throwIfPauseOrCancelRequested(controller)
            val videoFiles = MpdLiveCaptureIndex.filesInOrder(
                downloadDir,
                captureIndex,
                MpdLiveCaptureIndex.Stream.VIDEO
            )
            val audioFiles = MpdLiveCaptureIndex.filesInOrder(
                downloadDir,
                captureIndex,
                MpdLiveCaptureIndex.Stream.AUDIO
            )
            if (videoFiles.isEmpty() && audioFiles.isEmpty()) {
                throw IOException("No complete MPD segments were recorded, nothing to merge.")
            }

            var isPreparing = true
            onMergeProgress(
                Progress(0, totalBytesDownloaded),
                task.apply {
                    taskState = VideoTaskState.PREPARE
                    lineInfo = "Preparing segments... 0%"
                    setIsLive(true)
                }
            )
            val mergeSession = mergeCapturedSegments(
                mpdTmpDir = downloadDir,
                videoSegments = videoFiles,
                audioSegments = audioFiles,
                finalOutputPath = finalOutputFile.absolutePath,
                totalDurationSeconds = task.accumulatedDuration.toDouble(),
                shouldAbort = controller::isPauseOrCancelRequested
            ) { percentage ->
                if (isPreparing && percentage == 100) {
                    isPreparing = false
                }
                val message =
                    if (isPreparing) "Preparing segments... $percentage%" else "Merging... $percentage%"
                onMergeProgress(
                    Progress(totalBytesDownloaded * percentage / 100, totalBytesDownloaded),
                    task.apply {
                        lineInfo = message
                        taskState = VideoTaskState.PREPARE
                        setIsLive(true)
                    }
                )
            }

            if (!ReturnCode.isSuccess(mergeSession.returnCode)) {
                throw IOException("FFmpeg failed to merge live stream segments. Log: ${mergeSession.allLogsAsString}")
            }
            if (!finalOutputFile.isFile || finalOutputFile.length() <= 0L) {
                throw IOException("FFmpeg reported success but produced no MPD live output.")
            }
            finalOutputFile
        }
    }

    private suspend fun downloadInitSegments(
        videoRep: MpdPlaylistParser.MpdRepresentation?,
        audioRep: MpdPlaylistParser.MpdRepresentation?,
        downloadDir: File,
        controller: FileBasedDownloadController,
        headers: Map<String, String>
    ) {
        val segmentDownloader = SegmentDownloader(httpClient, headers, controller)
        coroutineScope {
            videoRep?.initializationUrl?.let { url ->
                launch {
                    AppLogger.d("MPD Live: Downloading video init segment.")
                    segmentDownloader.download(
                        url, downloadDir.resolve("video_init.m4s"), "MPD-Live-V-Init", 0
                    )
                }
            }
            audioRep?.initializationUrl?.let { url ->
                launch {
                    AppLogger.d("MPD Live: Downloading audio init segment.")
                    segmentDownloader.download(
                        url, downloadDir.resolve("audio_init.m4s"), "MPD-Live-A-Init", 0
                    )
                }
            }
        }
        throwIfPauseOrCancelRequested(controller)
    }

    private suspend fun interruptibleDelay(
        durationMillis: Long, controller: FileBasedDownloadController
    ) {
        val endTime = System.currentTimeMillis() + durationMillis
        while (System.currentTimeMillis() < endTime) {
            if (controller.isInterrupted()) break
            delay(250L)
        }
    }

    private fun mergeCapturedSegments(
        mpdTmpDir: File,
        videoSegments: List<File>,
        audioSegments: List<File>,
        finalOutputPath: String,
        totalDurationSeconds: Double,
        shouldAbort: () -> Boolean,
        onProgress: (percentage: Int) -> Unit
    ): FFmpegSession {
        val tempVideoFile = mpdTmpDir.resolve("temp_video.mp4")
        val tempAudioFile = mpdTmpDir.resolve("temp_audio.mp4")
        val hasVideo = videoSegments.isNotEmpty()
        val hasAudio = audioSegments.isNotEmpty()

        val videoFilesToConcat = if (hasVideo) {
            val init = mpdTmpDir.resolve("video_init.m4s")
            requireCompleteFile(init, "MPD live video initialization segment")
            listOf(init) + videoSegments
        } else emptyList()

        val audioFilesToConcat = if (hasAudio) {
            val init = mpdTmpDir.resolve("audio_init.m4s")
            requireCompleteFile(init, "MPD live audio initialization segment")
            listOf(init) + audioSegments
        } else emptyList()

        val totalConcatSize =
            (videoFilesToConcat.sumOf { if (it.exists()) it.length() else 0L } + audioFilesToConcat.sumOf { if (it.exists()) it.length() else 0L })
        var concatenatedBytes = 0L

        val concatProgressCallback: (bytes: Long) -> Unit = { bytes ->
            concatenatedBytes += bytes
            if (totalConcatSize > 0) {
                val percentage = ((concatenatedBytes * 100) / totalConcatSize).toInt()
                onProgress(percentage.coerceIn(0, 100))
            }
        }

        if (hasVideo) {
            manualConcat(videoFilesToConcat, tempVideoFile, shouldAbort, concatProgressCallback)
        }
        if (hasAudio) {
            manualConcat(audioFilesToConcat, tempAudioFile, shouldAbort, concatProgressCallback)
        }

        return mergeBaseUrlStreams(
            tempVideoFile.takeIf { hasVideo && it.isFile && it.length() > 0L },
            tempAudioFile.takeIf { hasAudio && it.isFile && it.length() > 0L },
            finalOutputPath,
            totalDurationSeconds,
            onProgress,
            shouldAbort
        )
    }

    private fun mergeBaseUrlStreams(
        videoFile: File?,
        audioFile: File?,
        finalOutputPath: String,
        totalDurationSeconds: Double,
        onProgress: ((percentage: Int) -> Unit)?,
        shouldAbort: () -> Boolean
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

        addCommonMergeArguments(
            arguments, videoFile?.exists() == true, audioFile?.exists() == true, finalOutputPath, false
        )

        val commandString = arguments.joinToString(" ")
        AppLogger.d("FFmpeg: Executing MPD Live merge with command: $commandString")

        val latch = CountDownLatch(1)
        lateinit var finalSession: FFmpegSession
        val abortSignaled = AtomicBoolean(false)

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
                return@executeAsync
            }
            if (onProgress != null && totalDurationSeconds > 0) {
                val totalDurationMillis = (totalDurationSeconds * 1000).toLong()
                val currentTimeMillis = statistics.time
                if (currentTimeMillis > 0) {
                    val percentage = ((currentTimeMillis * 100) / totalDurationMillis).toInt()
                    onProgress(percentage.coerceIn(0, 100))
                }
            }
        })

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
            throw CancellationException("MPD live merge interrupted by user.")
        }

        return finalSession
    }

    private fun addCommonMergeArguments(
        arguments: MutableList<String>,
        hasVideo: Boolean,
        hasAudio: Boolean,
        finalOutputPath: String,
        isSegmentMerge: Boolean
    ) {
        arguments.apply {
            when {
                hasVideo && hasAudio -> {
                    add("-map"); add("0:v:0?"); add("-map"); add("1:a:0?")
                }

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
            if (!isSegmentMerge) {
                add("-bsf:a"); add("aac_adtstoasc"); add("-movflags"); add("+faststart")
            }
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
        filesToConcat.forEach { requireCompleteFile(it, "MPD live capture input") }
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
                                throw CancellationException("MPD live concatenation interrupted by user.")
                            }
                            val bytesRead = input.read(buffer)
                            if (bytesRead < 0) break
                            output.write(buffer, 0, bytesRead)
                            bytesSinceLastUpdate += bytesRead

                            val now = System.currentTimeMillis()
                            if (now - lastUpdateTime > updateInterval) {
                                onProgress?.invoke(bytesSinceLastUpdate)
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

    private fun requireCompleteFile(file: File, label: String) {
        if (!file.isFile || file.length() <= 0L) {
            throw IOException("$label is missing or empty: ${file.absolutePath}")
        }
    }

    private fun throwIfPauseOrCancelRequested(controller: FileBasedDownloadController) {
        when (controller.interruptionReason()) {
            FileBasedDownloadController.InterruptionReason.PAUSE ->
                throw CancellationException("MPD live recording paused by user.")
            FileBasedDownloadController.InterruptionReason.CANCEL ->
                throw CancellationException("MPD live recording canceled by user.")
            FileBasedDownloadController.InterruptionReason.NONE,
            FileBasedDownloadController.InterruptionReason.STOP_AND_SAVE -> Unit
        }
    }
}
