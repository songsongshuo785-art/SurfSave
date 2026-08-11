package com.myAllVideoBrowser.util.downloaders.super_x_downloader.strategy

import com.antonkarpenko.ffmpegkit.ReturnCode
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskItem
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskState
import com.myAllVideoBrowser.util.downloaders.generic_downloader.workers.Progress
import com.myAllVideoBrowser.util.downloaders.super_x_downloader.DownloaderUtils
import com.myAllVideoBrowser.util.downloaders.super_x_downloader.SegmentDownloader
import com.myAllVideoBrowser.util.downloaders.super_x_downloader.control.FileBasedDownloadController
import com.myAllVideoBrowser.util.hls_parser.HlsPlaylistParser
import kotlinx.coroutines.*
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException

/**
 * Download strategy for HLS Live streams.
 *
 * This class continuously fetches the live playlist, downloads new segments as they appear,
 * and merges all downloaded segments into a single file when the download is stopped
 * (either by user action, by the stream ending, or by an unexpected exception).
 *
 * @param httpClient The OkHttpClient for network requests.
 * @param getMediaPlaylists A function to fetch and parse the latest version of the media playlists.
 * @param onMergeProgress A callback to update progress when merging begins.
 */
class HlsLiveDownloader(
    private val httpClient: OkHttpClient,
    private val getMediaPlaylists: suspend (url: String, headers: Map<String, String>) -> Pair<HlsPlaylistParser.MediaPlaylist?, HlsPlaylistParser.MediaPlaylist?>,
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
            val finalOutputFile = downloadDir.resolve("merged_output.mp4")
            if (mergeOnly) {
                AppLogger.d("HLS (Live): Starting in MERGE-ONLY mode from the last complete capture snapshot.")
                val mergeSession = DownloaderUtils.mergePreparedHlsCapture(
                    hlsTmpDir = downloadDir,
                    finalOutputPath = finalOutputFile.absolutePath,
                    videoCodec = videoCodec,
                    onMergeProgress = { percentage ->
                        onMergeProgress(
                            Progress(percentage.toLong(), 100L),
                            task.apply {
                                lineInfo = "Merging segments... $percentage%"
                                taskState = VideoTaskState.PREPARE
                                setIsLive(true)
                            }
                        )
                    },
                    shouldAbort = controller::isPauseOrCancelRequested
                )
                requireSuccessfulMerge(mergeSession, finalOutputFile)
                return@withContext finalOutputFile
            }

            val allVideoSegments = mutableListOf<HlsPlaylistParser.MediaSegment>()
            val allAudioSegments = mutableListOf<HlsPlaylistParser.MediaSegment>()
            var totalBytesDownloaded = 0L

            val progressCallback: (bytes: Long) -> Unit = { bytes ->
                totalBytesDownloaded += bytes
                onProgress(Progress(totalBytesDownloaded, 0))
            }

            var targetDuration = 10.0
            val downloadedVideoUrls = mutableSetOf<String>()
            val downloadedAudioUrls = mutableSetOf<String>()
            val segmentDownloader =
                SegmentDownloader(httpClient, headers, controller, progressCallback)

            AppLogger.d("HLS (Live): Starting download loop for task ${task.mId}")
            task.setIsLive(true)

            while (true) {
                when (controller.interruptionReason()) {
                    FileBasedDownloadController.InterruptionReason.PAUSE ->
                        throw CancellationException("Download was paused.")
                    FileBasedDownloadController.InterruptionReason.CANCEL ->
                        throw CancellationException("Download was canceled.")
                    FileBasedDownloadController.InterruptionReason.STOP_AND_SAVE -> break
                    FileBasedDownloadController.InterruptionReason.NONE -> Unit
                }

                AppLogger.d("HLS (Live): Fetching latest playlist for task ${task.mId}...")
                val (currentVideoPlaylist, currentAudioPlaylist) = getMediaPlaylists(
                    task.url,
                    headers
                )
                throwIfPauseOrCancelRequested(controller)

                targetDuration = (currentVideoPlaylist?.targetDuration?.toDouble()
                    ?: currentAudioPlaylist?.targetDuration?.toDouble() ?: targetDuration)

                val newVideoSegments = currentVideoPlaylist?.segments
                    ?.filterNot { it.url in downloadedVideoUrls }
                    ?: emptyList()
                val newAudioSegments = currentAudioPlaylist?.segments
                    ?.filterNot { it.url in downloadedAudioUrls }
                    ?: emptyList()

                if (newVideoSegments.isNotEmpty() || newAudioSegments.isNotEmpty()) {
                    AppLogger.d("HLS (Live): Found ${newVideoSegments.size} new video and ${newAudioSegments.size} new audio segments.")
                    val candidateVideoSegments = allVideoSegments + newVideoSegments
                    val candidateAudioSegments = allAudioSegments + newAudioSegments

                    DownloaderUtils.prepareHlsEncryptionKeys(
                        httpClient,
                        downloadDir,
                        headers.toHeaders(),
                        candidateVideoSegments,
                        candidateAudioSegments,
                        shouldAbort = controller::isPauseOrCancelRequested
                    )
                    ensureInitializationSegment(
                        candidateVideoSegments,
                        "init_video.mp4",
                        "HLS-Live-Video-Init",
                        segmentDownloader,
                        downloadDir
                    )
                    ensureInitializationSegment(
                        candidateAudioSegments,
                        "init_audio.mp4",
                        "HLS-Live-Audio-Init",
                        segmentDownloader,
                        downloadDir
                    )
                    downloadLiveSegments(
                        videoSegments = newVideoSegments,
                        audioSegments = newAudioSegments,
                        segmentDownloader = segmentDownloader,
                        videoStartIndex = allVideoSegments.size,
                        audioStartIndex = allAudioSegments.size,
                        videoUsesFmp4 = usesFmp4(candidateVideoSegments),
                        audioUsesFmp4 = usesFmp4(candidateAudioSegments),
                        downloadDir = downloadDir
                    )
                    throwIfPauseOrCancelRequested(controller)

                    allVideoSegments.addAll(newVideoSegments)
                    allAudioSegments.addAll(newAudioSegments)
                    downloadedVideoUrls.addAll(newVideoSegments.map { it.url })
                    downloadedAudioUrls.addAll(newAudioSegments.map { it.url })
                    persistCaptureSnapshot(
                        allVideoSegments,
                        allAudioSegments,
                        downloadDir,
                        controller
                    )
                    task.accumulatedDuration +=
                        (newVideoSegments.takeIf { it.isNotEmpty() } ?: newAudioSegments)
                            .sumOf { it.duration }
                            .toLong()
                } else {
                    AppLogger.d("HLS (Live): No new segments found.")
                }

                val isVideoFinished = currentVideoPlaylist?.isFinished ?: true
                val isAudioFinished = currentAudioPlaylist?.isFinished ?: true
                if (isVideoFinished && isAudioFinished) {
                    AppLogger.d("HLS (Live): Stream finished naturally. Proceeding to merge.")
                    break
                }

                val waitTime = (targetDuration / 2 * 1000).toLong().coerceAtLeast(250L)
                AppLogger.d("HLS (Live): Waiting for up to ${waitTime / 1000.0} seconds...")
                interruptibleDelay(waitTime, controller)
            }

            throwIfPauseOrCancelRequested(controller)
            if (allVideoSegments.isEmpty() && allAudioSegments.isEmpty()) {
                throw IOException("No complete segments were downloaded, nothing to merge.")
            }

            AppLogger.d("HLS (Live): Proceeding to merge ${allVideoSegments.size} video and ${allAudioSegments.size} audio segments.")
            onMergeProgress(
                Progress(totalBytesDownloaded, totalBytesDownloaded),
                task.apply {
                    taskState = VideoTaskState.PREPARE
                    lineInfo = "Merging segments..."
                    setIsLive(true)
                }
            )
            val mergeSession = DownloaderUtils.mergeHlsSegments(
                hlsTmpDir = downloadDir,
                videoSegments = allVideoSegments,
                audioSegments = allAudioSegments,
                finalOutputPath = finalOutputFile.absolutePath,
                videoCodec = videoCodec,
                onMergeProgress = { percentage ->
                    onMergeProgress(
                        Progress(totalBytesDownloaded * percentage / 100, totalBytesDownloaded),
                        task.apply {
                            lineInfo = "Merging segments... $percentage%"
                            taskState = VideoTaskState.PREPARE
                            setIsLive(true)
                        }
                    )
                },
                shouldAbort = controller::isPauseOrCancelRequested
            )
            requireSuccessfulMerge(mergeSession, finalOutputFile)
            finalOutputFile
        }
    }

    private suspend fun downloadLiveSegments(
        videoSegments: List<HlsPlaylistParser.MediaSegment>,
        audioSegments: List<HlsPlaylistParser.MediaSegment>,
        segmentDownloader: SegmentDownloader,
        videoStartIndex: Int,
        audioStartIndex: Int,
        videoUsesFmp4: Boolean,
        audioUsesFmp4: Boolean,
        downloadDir: File
    ) {
        val videoExt = if (videoUsesFmp4) "m4s" else "ts"
        val audioExt = if (audioUsesFmp4) "m4s" else "ts"

        videoSegments.forEachIndexed { offset, segment ->
            val index = videoStartIndex + offset
            val outputFile = downloadDir.resolve("segment_${"%05d".format(index)}.$videoExt")
            segmentDownloader.download(segment.url, outputFile, "HLS-Live-Video", index)
        }

        audioSegments.forEachIndexed { offset, segment ->
            val index = audioStartIndex + offset
            val outputFile = downloadDir.resolve("audio_segment_${"%05d".format(index)}.$audioExt")
            segmentDownloader.download(segment.url, outputFile, "HLS-Live-Audio", index)
        }
    }

    private suspend fun ensureInitializationSegment(
        segments: List<HlsPlaylistParser.MediaSegment>,
        outputName: String,
        logPrefix: String,
        segmentDownloader: SegmentDownloader,
        downloadDir: File
    ) {
        val urlSegments = requireUrlSegments(segments)
        val expectedInitialization = urlSegments.firstOrNull()?.initializationSegment ?: return
        if (urlSegments.any { it.initializationSegment != expectedInitialization }) {
            throw IOException("HLS live initialization segment changed during capture.")
        }
        segmentDownloader.download(
            expectedInitialization.url,
            downloadDir.resolve(outputName),
            logPrefix,
            0
        )
    }

    private fun persistCaptureSnapshot(
        videoSegments: List<HlsPlaylistParser.MediaSegment>,
        audioSegments: List<HlsPlaylistParser.MediaSegment>,
        downloadDir: File,
        controller: FileBasedDownloadController
    ) {
        DownloaderUtils.publishHlsCaptureSnapshot(
            hlsTmpDir = downloadDir,
            videoSegments = videoSegments,
            audioSegments = audioSegments,
            shouldAbort = controller::isPauseOrCancelRequested
        )
    }

    private fun usesFmp4(segments: List<HlsPlaylistParser.MediaSegment>): Boolean {
        val urlSegments = requireUrlSegments(segments)
        val initialization = urlSegments.firstOrNull()?.initializationSegment
        if (urlSegments.any { it.initializationSegment != initialization }) {
            throw IOException("HLS live initialization segment changed during capture.")
        }
        return initialization != null
    }

    private fun requireUrlSegments(
        segments: List<HlsPlaylistParser.MediaSegment>
    ): List<HlsPlaylistParser.UrlMediaSegment> {
        return segments.map { segment ->
            segment as? HlsPlaylistParser.UrlMediaSegment
                ?: throw IOException("HLS live playlist contains an unsupported media segment.")
        }
    }

    private fun throwIfPauseOrCancelRequested(controller: FileBasedDownloadController) {
        when (controller.interruptionReason()) {
            FileBasedDownloadController.InterruptionReason.PAUSE ->
                throw CancellationException("Download was paused.")
            FileBasedDownloadController.InterruptionReason.CANCEL ->
                throw CancellationException("Download was canceled.")
            FileBasedDownloadController.InterruptionReason.NONE,
            FileBasedDownloadController.InterruptionReason.STOP_AND_SAVE -> Unit
        }
    }

    private fun requireSuccessfulMerge(
        mergeSession: com.antonkarpenko.ffmpegkit.FFmpegSession,
        outputFile: File
    ) {
        if (!ReturnCode.isSuccess(mergeSession.returnCode)) {
            throw IOException("FFmpeg failed to merge live stream segments. Log: ${mergeSession.allLogsAsString}")
        }
        if (!outputFile.isFile || outputFile.length() <= 0L) {
            throw IOException("FFmpeg reported success but produced no HLS live output.")
        }
    }

    /**
     * A version of `delay` that can be interrupted by controller flags.
     * It checks for interruptions every 250ms.
     */
    private suspend fun interruptibleDelay(
        durationMillis: Long,
        controller: FileBasedDownloadController
    ) {
        val endTime = System.currentTimeMillis() + durationMillis
        while (System.currentTimeMillis() < endTime) {
            if (controller.isInterrupted()) {
                AppLogger.d("HLS (Live): Action detected during wait. Breaking delay.")
                break
            }
            delay(250L) // Short, non-blocking delay
        }
    }
}
