package com.myAllVideoBrowser.util.downloaders.youtubedl_downloader

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import androidx.work.WorkerParameters
import com.myAllVideoBrowser.DLApplication
import com.myAllVideoBrowser.data.local.room.entity.ProgressInfo
import com.myAllVideoBrowser.data.local.model.Proxy
import com.myAllVideoBrowser.data.local.room.entity.VideoFormatEntity
import com.myAllVideoBrowser.util.CookieUtils
import com.myAllVideoBrowser.util.downloaders.DownloadTaskLogger
import com.myAllVideoBrowser.util.downloaders.generic_downloader.GenericDownloader
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskItem
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskState
import com.myAllVideoBrowser.util.downloaders.generic_downloader.workers.GenericDownloadWorkerWrapper
import com.google.gson.Gson
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.FileUtil
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDLResponse
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers
import java.io.File
import java.util.Date
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine

internal fun dispatchYoutubeDlInitializationTerminalCommit(
    terminalEffectsAvailable: Boolean,
    applyTerminalEffects: () -> Unit,
    advanceQueue: () -> Unit
) {
    if (terminalEffectsAvailable) {
        applyTerminalEffects()
    } else {
        advanceQueue()
    }
}

class YoutubeDlDownloaderWorker(appContext: Context, workerParams: WorkerParameters) :
    GenericDownloadWorkerWrapper(appContext, workerParams) {
    companion object {
        const val IS_FINISHED_DOWNLOAD_ACTION_ERROR_KEY = "IS_FINISHED_DOWNLOAD_ACTION_ERROR_KEY"
        const val DOWNLOAD_FILENAME_KEY = "download_filename"
        const val IS_FINISHED_DOWNLOAD_ACTION_KEY = "action"
        private const val UPDATE_INTERVAL = 1000
    }

    private lateinit var tmpFile: File
    private var isLiveCounter: Int = 0
    private var isDownloadOk: Boolean = false
    private var isDownloadJustStarted: Boolean = false
    private var monitorProcessDisposable: Disposable? = null
    private var progressCached = 0
    private var downloadJobDisposable: Disposable? = null
    private var cookieFile: File? = null
    private var lastTmpDirSize = 0L
    private var taskId = ""
    private var executionToken = ""
    private var executionKey = ""
    private lateinit var executionResources: YoutubeDlExecutionResources
    private lateinit var finalizationCoordinator: YoutubeDlFinalizationCoordinator
    private lateinit var terminalEffects: YoutubeDlTerminalEffects
    private var workerContinuation: CancellableContinuation<Result>? = null
    private val continuationCompleted = AtomicBoolean(false)

    @Volatile
    var time = 0L

    override suspend fun doWork(): Result {
        return suspendCancellableCoroutine { continuation ->
            workerContinuation = continuation
            continuation.invokeOnCancellation { onWorkCancelled() }
            try {
                bindExecutionInput()
                executionResources = YoutubeDlExecutionResources(fileUtil)
                val publisher = YoutubeDlMediaPublisher(
                    applicationContext,
                    fileUtil,
                    downloadTaskLogger
                ).apply { bindTask(taskId) }
                finalizationCoordinator = YoutubeDlFinalizationCoordinator(
                    progressRepository,
                    publisher
                )
                terminalEffects = YoutubeDlTerminalEffects(
                    applicationContext as DLApplication,
                    progressRepository,
                    notificationsHelper,
                    downloadQueueManager,
                    downloadTaskLogger
                )
                val action = inputData.getString(GenericDownloader.Constants.ACTION_KEY)
                    ?.takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException("yt-dlp action is missing")
                handleAction(
                    action,
                    getTaskFromInput(),
                    emptyMap(),
                    inputData.getBoolean(GenericDownloader.Constants.IS_FILE_REMOVE_KEY, false)
                )
            } catch (error: Throwable) {
                handleInitializationFailure(error)
            }
        }.also { afterDone() }
    }

    override fun afterDone() {
        monitorProcessDisposable?.dispose()
        monitorProcessDisposable = null
        downloadJobDisposable?.dispose()
        downloadJobDisposable = null
        CookieUtils.deleteTemporaryCookieFile(cookieFile)
        cookieFile = null
    }

    override fun onWorkCancelled() {
        destroyExecutionProcess()
        monitorProcessDisposable?.dispose()
        downloadJobDisposable?.dispose()
        CookieUtils.deleteTemporaryCookieFile(cookieFile)
    }

    override fun handleAction(
        action: String, task: VideoTaskItem, headers: Map<String, String>, isFileRemove: Boolean
    ) {
        if (currentExecutionFor(action) == null) {
            completeWorker(Result.success())
            return
        }
        when (action) {
            GenericDownloader.DownloaderActions.DOWNLOAD -> startDownload(task)
            GenericDownloader.DownloaderActions.RESUME -> resumeDownload(task)
            GenericDownloader.DownloaderActions.PAUSE -> pauseDownload()
            GenericDownloader.DownloaderActions.CANCEL -> cancelDownload(isFileRemove)
            GenericDownloader.DownloaderActions.STOP_SAVE_ACTION -> stopAndSave(task)
            GenericDownloader.DownloaderActions.RECOVER_FINALIZATION -> recoverFinalization()
            else -> throw IllegalArgumentException("Unsupported yt-dlp action: $action")
        }
    }

    private fun stopAndSave(task: VideoTaskItem) {
        destroyExecutionProcess()
        tmpFile = executionResources.prepare(taskId, executionKey, true)
        val source = executionResources.findStopAndSaveSource(tmpFile)
        val requestedName = task.fileName.orEmpty()
            .ifBlank { task.title.orEmpty() }
            .ifBlank { "download" }
        val target = uniqueTargetFor(
            "${File(requestedName).nameWithoutExtension.ifBlank { "download" }}.mp4"
        )
        finalizeCandidate(source?.absolutePath.orEmpty(), target.absolutePath)
    }

    @SuppressLint("CheckResult")
    private fun startDownload(
        task: VideoTaskItem, isContinue: Boolean = false
    ) {
        downloadTaskLogger.info(taskId, "yt-dlp execution started for ${task.fileName}")
        val vFormat = deserializeVideoFormat()
        val url = inputData.getString(GenericDownloader.Constants.ORIGIN_KEY)
            ?.takeIf { it.isNotBlank() }
            ?: inputData.getString(GenericDownloader.Constants.URL_KEY)
                ?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("yt-dlp URL is missing")
        val taskTitle = task.title.orEmpty().ifBlank { task.fileName.orEmpty() }
        hideNotifications(taskId)
        val request = YoutubeDLRequest(url)
        cookieFile = CookieUtils.addCookiesToRequest(url, request)
        tmpFile = executionResources.prepare(taskId, executionKey, isContinue)
        val shouldContinue = isContinue || !tmpFile.listFiles().isNullOrEmpty()
        configureYoutubedlRequest(
            request,
            vFormat,
            task.fileName.orEmpty().ifBlank { taskTitle },
            shouldContinue
        )

        task.taskState = VideoTaskState.DOWNLOADING
        if (!saveProgress(
            taskId,
            line = LineInfo(taskId, 0.0, 0.0, sourceLine = "Starting..."),
            task
        )) {
            completeWorker(Result.success())
            return
        }
        showProgress(taskId, taskTitle, 0, "Starting...", tmpFile)
        monitorDownloadProcess(taskId, task)

        if (!fileUtil.isFreeSpaceAvailable()) {
            commitActiveError("Not enough space")
            return
        }
        startDownloadProcess(url, request, task, taskId)
    }

    private fun resumeDownload(task: VideoTaskItem) {
        startDownload(task, true)
    }

    private fun pauseDownload() {
        destroyExecutionProcess()
        commitPause(progressRepository.getProgressInfoById(taskId))
    }

    private fun commitPause(current: ProgressInfo?) {
        val infoLine = if (current?.queuedForLater == true) "Saved for later" else "Paused"
        val committed = progressRepository.commitYtDlpPause(taskId, executionToken, infoLine)
        checkAffectedRows(committed, "commit pause")
        if (committed == 1) applyTerminalEffects(VideoTaskState.PAUSE)
        completeWorker(Result.success())
    }

    private fun cancelDownload(removeFileFromWorkData: Boolean) {
        destroyExecutionProcess()
        val current = progressRepository.getProgressInfoById(taskId)
        commitCanceled(current?.removePartialOnCancel == true || removeFileFromWorkData)
    }

    private fun commitCanceled(removePartial: Boolean) {
        val committed = progressRepository.commitYtDlpCanceled(
            taskId,
            executionToken,
            System.currentTimeMillis()
        )
        checkAffectedRows(committed, "commit cancel")
        if (committed == 1) {
            applyTerminalEffects(VideoTaskState.CANCELED) {
                if (removePartial) executionResources.deleteExecution(taskId, executionKey)
            }
        }
        completeWorker(Result.success())
    }

    @SuppressLint("CheckResult")
    private fun startDownloadProcess(
        url: String,
        request: YoutubeDLRequest,
        task: VideoTaskItem,
        taskId: String,
    ) {
        downloadJobDisposable?.dispose()
        downloadJobDisposable = Observable.fromCallable<YoutubeDLResponse> {
            YoutubeDL.getInstance().execute(request, executionKey) { pr, _, line ->
                if (line.contains("[download] Destination:")) {
                    isDownloadJustStarted = true
                }
                if (line.contains(Regex("""\[download] {3}\d+"""))) {
                    isDownloadOk = true
                }

                val lineInfo: LineInfo? = try {
                    parseInfoFromLine(line)
                } catch (e: Throwable) {
                    null
                }

                progressCached = pr.toInt()

                if (Date().time - time > UPDATE_INTERVAL && !continuationCompleted.get()) {
                    time = Date().time

                    val totalBytes = (lineInfo?.total ?: 0).toLong()

                    val downloadBytes = (totalBytes * (pr / 100.0)).toLong()
                    val downloadBytesFixed = if (downloadBytes > 0) {
                        downloadBytes
                    } else {
                        0
                    }
                    task.also {
                        it.percent = pr
                        it.totalSize = totalBytes
                        it.downloadSize = downloadBytesFixed
                        it.taskState = VideoTaskState.DOWNLOADING
                    }

                    if (saveProgress(taskId, lineInfo, task)) {
                        showProgress(
                            taskId,
                            task.title.orEmpty().ifBlank { task.fileName.orEmpty() },
                            pr.toInt(),
                            line,
                            tmpFile
                        )
                    }

                    if (!fileUtil.isFreeSpaceAvailable()) {
                        destroyExecutionProcess()
                        commitActiveError("Not enough space")
                        return@execute
                    }
                }
            }
        }
            .subscribeOn(Schedulers.io())
            .subscribe(
                { response -> handleDownloadResponse(response) },
                { error -> handleExecutionError(error, task) }
            )
    }

    private fun handleDownloadResponse(response: YoutubeDLResponse) {
        if (continuationCompleted.get()) return
        if (response.exitCode != 0) {
            commitActiveError("yt-dlp exited with code ${response.exitCode}")
            return
        }
        val source = executionResources.findFinalMedia(tmpFile)
        if (source == null) {
            commitActiveError("yt-dlp completed without a media file")
            return
        }
        val target = uniqueTargetFor(source.name)
        finalizeCandidate(source.absolutePath, target.absolutePath)
    }

    private fun configureYoutubedlRequest(
        request: YoutubeDLRequest, vFormat: VideoFormatEntity, fileName: String, isContinue: Boolean
    ) {
        request.addOption("--progress")

        val threadsCount = sharedPrefHelper.getM3u8DownloaderThreadCount()
        request.addOption("-N", threadsCount)

        val isAudioOnly = vFormat.vcodec == "none" && vFormat.acodec != "none"

        if (isAudioOnly) {
            request.addOption("--audio-quality", "0")
            request.addOption("--extract-audio")
            request.addOption("--audio-format", "mp3")
        } else {
            val videoOnly = vFormat.vcodec != "none" && vFormat.acodec == "none"
            if (videoOnly) {
                request.addOption("-f", "${vFormat.formatId}+bestaudio")
            } else {
                request.addOption("-f", "${vFormat.formatId}")
            }

            request.addOption("--recode-video", "mp4")
            request.addOption("--merge-output-format", "mp4")
        }


        // any another downloader has issues
        request.addOption("--hls-prefer-native")
        // without this download will start again from beginning after error
        request.addOption("--hls-use-mpegts")

        if (isContinue) {
            request.addOption("--continue")
        }

//        $youtube-dl --proxy http://user:password@your_proxy.com:port url
        val currentProxy = proxyController.getCurrentRunningProxy()
        if (currentProxy != Proxy.noProxy()) {
            val (user, password) = proxyController.getProxyCredentials()
            if (user.isNotEmpty() && password.isNotEmpty()) {
                request.addOption(
                    "--proxy",
                    "http://${user}:${password}@${currentProxy.host}:${currentProxy.port}"
                )
            } else {
                request.addOption("--proxy", "${currentProxy.host}:${currentProxy.port}")
            }
        }

        request.addOption("-o", youtubeDlOutputTemplate(tmpFile, fileName))

        vFormat.httpHeaders?.forEach {
            if (it.key != "Cookie") {
                request.addOption("--add-header", "${it.key}:${it.value}")
            }
        }
    }

    @SuppressLint("CheckResult")
    private fun monitorDownloadProcess(taskId: String, task: VideoTaskItem) {
        monitorProcessDisposable =
            Observable.interval(0, 1, TimeUnit.SECONDS).subscribeOn(Schedulers.io())
                .map { FileUtil.calculateFolderSize(tmpFile) }.onErrorReturn { -1 }
                .subscribe { folderSize ->
                    if (!continuationCompleted.get() && folderSize > 0 && folderSize != lastTmpDirSize) {
                        val downloadedTmpFolderSize =
                            FileUtil.getFileSizeReadable(folderSize.toDouble())
                        lastTmpDirSize = folderSize

                        if (progressCached > 0) {
                            isDownloadOk = true
                            monitorProcessDisposable?.dispose()
                            return@subscribe
                        }

                        if (isDownloadJustStarted && !isDownloadOk) {
                            ++isLiveCounter
                            if (isLiveCounter > 2) {
                                isLiveCounter = 3

                                val downloaded = lastTmpDirSize
                                val saved = saveProgress(
                                    taskId, LineInfo(
                                        "LIVE",
                                        downloaded.toDouble(),
                                        downloaded.toDouble(),
                                        sourceLine = "Downloading live stream...downloaded: $downloadedTmpFolderSize, press stop and save, to stop downloading and save downloaded at any time...!"
                                    ), task.also { item ->
                                        item.taskState = VideoTaskState.DOWNLOADING
                                        item.lineInfo = downloadedTmpFolderSize
                                        item.downloadSize = downloaded
                                        item.totalSize = downloaded
                                    })
                                if (saved) {
                                    showProgress(
                                        taskId,
                                        task.title.orEmpty().ifBlank { task.fileName.orEmpty() },
                                        99,
                                        "Downloading Live Stream... $downloadedTmpFolderSize",
                                        tmpFile
                                    )
                                }
                            }
                        }
                    }
                }
    }

    private fun handleExecutionError(throwable: Throwable, task: VideoTaskItem) {
        downloadTaskLogger.error(
            taskId,
            "yt-dlp process failed for ${task.fileName.orEmpty()}",
            throwable
        )
        val current = progressRepository.getProgressInfoById(taskId)
        if (current == null || current.executionToken != executionToken) {
            completeWorker(Result.success())
            return
        }
        when {
            current.downloadStatus == VideoTaskState.PAUSING &&
                current.stopReason == YoutubeDlStopReason.STOP_AND_SAVE -> stopAndSave(task)
            current.downloadStatus == VideoTaskState.PAUSING -> commitPause(current)
            current.downloadStatus == VideoTaskState.CANCELING ->
                commitCanceled(current.removePartialOnCancel)
            current.downloadStatus == VideoTaskState.FINALIZING -> recoverFinalization()
            current.downloadStatus.isStableState() -> completeWorker(Result.success())
            throwable is YoutubeDL.CanceledException ->
                commitActiveError("yt-dlp process stopped unexpectedly")
            else -> commitActiveError(throwable.message ?: "yt-dlp download failed")
        }
    }

    //[download]   0.3% of ~  49.94MiB at  438.62KiB/s ETA 04:41 (frag 2/201)
    private fun parseInfoFromLine(line: String?): LineInfo? {
        if (line == null || !line.startsWith("[download]")) {
            return if (line != null) LineInfo("download", 0.0, 0.0, sourceLine = line) else null
        }

        val parts = line.split(Regex(" +"))
        val percent = parts[1].replace("%", "").trim().toDoubleOrNull() ?: return null

        val totalStrIndex = if (line.contains("~")) 4 else 3
        val totalStr = parts[totalStrIndex]

        val unitMatcher = Regex("\\p{L}").find(totalStr) ?: return null
        val totalValue =
            totalStr.substring(0, unitMatcher.range.first).toDoubleOrNull() ?: return null
        val totalUnit = totalStr.substring(unitMatcher.range.first)
        val totalParsed = LineInfo.parse("$totalValue $totalUnit")

        val fragInfo = parts.last().let {
            if (it.contains(")")) {
                val (downloadedFragStr, totalFragStr) = it.split("/")
                val downloadedFrag = downloadedFragStr.replace("(frag ", "").toIntOrNull()
                val totalFrag = totalFragStr.replace(") ", "").toIntOrNull()
                downloadedFrag to totalFrag
            } else {
                null to null
            }
        }

        return LineInfo(
            "download",
            totalParsed * percent / 100,
            totalParsed,
            fragInfo.first,
            fragInfo.second,
            sourceLine = line
        )
    }

    private class LineInfo(
        val id: String,
        val progress: Double,
        val total: Double,
        val fragDownloaded: Int? = null,
        val fragTotal: Int? = null,
        val sourceLine: String
    ) {
        companion object {
            private const val KB_FACTOR: Long = 1000
            private const val KIB_FACTOR: Long = 1024
            private const val MB_FACTOR = 1000 * KB_FACTOR
            private const val MIB_FACTOR = 1024 * KIB_FACTOR
            private const val GB_FACTOR = 1000 * MB_FACTOR
            private const val GIB_FACTOR = 1024 * MIB_FACTOR

            fun parse(arg0: String): Double {
                val spaceNdx = arg0.indexOf(" ")
                val ret = arg0.substring(0, spaceNdx).toDouble()
                when (arg0.substring(spaceNdx + 1)) {
                    "GB" -> return ret * GB_FACTOR
                    "GiB" -> return ret * GIB_FACTOR
                    "MB" -> return ret * MB_FACTOR
                    "MiB" -> return ret * MIB_FACTOR
                    "KB" -> return ret * KB_FACTOR
                    "KiB" -> return ret * KIB_FACTOR
                    "B" -> return ret
                }
                return (-1).toDouble()
            }
        }

        override fun toString(): String {
            return "${FileUtil.getFileSizeReadable(progress)} / ${
                FileUtil.getFileSizeReadable(
                    total
                )
            }  frag: $fragDownloaded / $fragTotal"
        }
    }

    private fun showProgress(
        taskId: String, name: String, progress: Int, line: String, tmpFile: File
    ) {
        val text = line.replace(tmpFile.toString(), "")

        val taskItem = VideoTaskItem("").also {
            it.mId = taskId
            it.fileName = name
            it.taskState = VideoTaskState.DOWNLOADING
            it.percent = progress.toFloat()
            it.lineInfo = text
        }
        val data = notificationsHelper.createNotificationBuilder(taskItem)

        showLongRunningNotificationAsync(data.first, data.second)
    }


    @SuppressLint("CheckResult")
    override fun finishWork(item: VideoTaskItem?) {
        val failed = item?.taskState == VideoTaskState.ERROR ||
            item?.taskState == VideoTaskState.ENOSPC
        completeWorker(if (failed) Result.failure() else Result.success())
    }

    private fun saveProgress(
        taskId: String, line: LineInfo? = null, task: VideoTaskItem
    ): Boolean {
        if (continuationCompleted.get()) return false
        val current = progressRepository.getProgressInfoById(taskId) ?: return false
        val total = line?.total?.takeIf { it > 0.0 }?.toLong()
            ?: task.totalSize.takeIf { it > 0L }
            ?: current.progressTotal
        val downloaded = task.downloadSize.takeIf { it > 0L } ?: current.progressDownloaded
        val updated = progressRepository.updateYtDlpProgress(
            id = taskId,
            token = executionToken,
            downloaded = downloaded,
            total = total,
            fragDownloaded = line?.fragDownloaded ?: current.fragmentsDownloaded,
            fragTotal = line?.fragTotal ?: current.fragmentsTotal,
            infoLine = line?.sourceLine.orEmpty(),
            startedAt = System.currentTimeMillis(),
            logPath = current.logPath.ifBlank { downloadTaskLogger.logPath(taskId) },
            isLive = current.isLive || line?.id == "LIVE"
        )
        checkAffectedRows(updated, "update progress")
        return updated == 1
    }

    private fun deserializeVideoFormat(): VideoFormatEntity {
        val raw = YoutubeDlDownloader.loadHeadersStringFromSharedPreferences(
            applicationContext,
            executionKey
        ) ?: throw IllegalStateException("yt-dlp format cache is missing")
        val decompressed = YoutubeDlDownloader.decompressString(raw)
        val json = String(Base64.decode(decompressed, Base64.DEFAULT), Charsets.UTF_8)
        return Gson().fromJson(json, VideoFormatEntity::class.java)
            ?: throw IllegalStateException("yt-dlp format cache is invalid")
    }

    private fun bindExecutionInput() {
        taskId = inputData.getString(GenericDownloader.Constants.TASK_ID_KEY).orEmpty()
        executionToken = inputData.getString(GenericDownloader.Constants.EXECUTION_TOKEN_KEY).orEmpty()
        executionKey = inputData.getString(GenericDownloader.Constants.EXECUTION_KEY).orEmpty()
        require(taskId.isNotBlank()) { "yt-dlp task id is missing" }
        require(executionToken.isNotBlank()) { "yt-dlp execution token is missing" }
        require(executionKey.isNotBlank()) { "yt-dlp execution key is missing" }
        require(executionKey == YoutubeDlDownloader.executionKey(taskId, executionToken)) {
            "yt-dlp execution key does not match its task and token"
        }
    }

    private fun currentExecutionFor(action: String): ProgressInfo? {
        val current = progressRepository.getProgressInfoById(taskId) ?: return null
        if (current.executionToken != executionToken) return null
        val accepted = when (action) {
            GenericDownloader.DownloaderActions.DOWNLOAD,
            GenericDownloader.DownloaderActions.RESUME ->
                current.stopReason == YoutubeDlStopReason.NONE &&
                    current.downloadStatus.isActiveYtDlpState()
            GenericDownloader.DownloaderActions.PAUSE ->
                current.downloadStatus == VideoTaskState.PAUSING &&
                    current.stopReason == YoutubeDlStopReason.PAUSE
            GenericDownloader.DownloaderActions.CANCEL ->
                current.downloadStatus == VideoTaskState.CANCELING &&
                    current.stopReason == YoutubeDlStopReason.CANCEL
            GenericDownloader.DownloaderActions.STOP_SAVE_ACTION ->
                current.downloadStatus == VideoTaskState.PAUSING &&
                    current.stopReason == YoutubeDlStopReason.STOP_AND_SAVE
            GenericDownloader.DownloaderActions.RECOVER_FINALIZATION ->
                current.downloadStatus == VideoTaskState.FINALIZING
            else -> false
        }
        return current.takeIf { accepted }
    }

    private fun recoverFinalization() {
        val current = progressRepository.getProgressInfoById(taskId)
        if (current == null || current.executionToken != executionToken) {
            completeWorker(Result.success())
            return
        }
        handleFinalizationResult(finalizationCoordinator.recover(current))
    }

    private fun finalizeCandidate(sourcePath: String, targetPath: String) {
        handleFinalizationResult(
            finalizationCoordinator.claimAndFinalize(
                taskId,
                executionToken,
                sourcePath,
                targetPath
            )
        )
    }

    private fun handleFinalizationResult(result: YoutubeDlFinalizationCoordinator.Result) {
        when (result) {
            YoutubeDlFinalizationCoordinator.Result.NotOwner -> completeWorker(Result.success())
            is YoutubeDlFinalizationCoordinator.Result.Committed -> {
                applyTerminalEffects(
                    result.status,
                    result.error,
                    result.targetPath
                ) {
                    if (result.status == VideoTaskState.SUCCESS) {
                        executionResources.deleteExecution(taskId, executionKey)
                    }
                }
                completeWorker(
                    if (result.status == VideoTaskState.SUCCESS) Result.success()
                    else Result.failure()
                )
            }
        }
    }

    private fun commitActiveError(rawError: String) {
        val error = cleanError(rawError)
        val committed = progressRepository.commitYtDlpError(
            taskId,
            executionToken,
            System.currentTimeMillis(),
            error
        )
        checkAffectedRows(committed, "commit error")
        if (committed == 1) {
            applyTerminalEffects(VideoTaskState.ERROR, error)
            completeWorker(Result.failure())
        } else {
            completeWorker(Result.success())
        }
    }

    private fun uniqueTargetFor(fileName: String): File {
        val safeName = File(fileName).name.ifBlank { "download.mp4" }
        return fileUtil.uniqueMediaTarget(
            applicationContext,
            File(fileUtil.folderDir, safeName)
        )
    }

    private fun destroyExecutionProcess() {
        if (executionKey.isBlank()) return
        try {
            YoutubeDL.getInstance().destroyProcessById(executionKey)
        } catch (error: Throwable) {
            AppLogger.e("Failed to stop yt-dlp execution $executionKey", error)
        }
    }

    private fun applyTerminalEffects(
        status: Int,
        error: String = "",
        outputPath: String = "",
        cleanup: () -> Unit = {}
    ) {
        try {
            terminalEffects.apply(taskId, executionToken, status, error, outputPath, cleanup)
        } catch (effectError: Throwable) {
            AppLogger.e("yt-dlp terminal effects failed for $taskId", effectError)
            try {
                downloadQueueManager.onYtDlpTerminal()
            } catch (scheduleError: Throwable) {
                AppLogger.e("yt-dlp fallback queue scheduling failed for $taskId", scheduleError)
            }
        }
    }

    private fun handleInitializationFailure(error: Throwable) {
        AppLogger.e("yt-dlp worker initialization failed for $taskId", error)
        try {
            val action = inputData.getString(GenericDownloader.Constants.ACTION_KEY).orEmpty()
            if (taskId.isBlank() || executionToken.isBlank()) {
                completeWorker(Result.failure())
                return
            }
            val message = cleanError(error.message ?: "yt-dlp worker initialization failed")
            val committed = progressRepository.commitYtDlpError(
                taskId,
                executionToken,
                System.currentTimeMillis(),
                message
            )
            checkAffectedRows(committed, "commit initialization error")
            if (committed == 1) {
                dispatchYoutubeDlInitializationTerminalCommit(
                    terminalEffectsAvailable = ::terminalEffects.isInitialized,
                    applyTerminalEffects = {
                        applyTerminalEffects(VideoTaskState.ERROR, message)
                    },
                    advanceQueue = ::advanceQueueAfterInitializationFailure
                )
                completeWorker(Result.failure())
                return
            }

            val current = progressRepository.getProgressInfoById(taskId)
            if (current == null ||
                current.executionToken != executionToken ||
                current.downloadStatus.isStableState()
            ) {
                completeWorker(Result.success())
                return
            }
            val controlAction = action == GenericDownloader.DownloaderActions.PAUSE ||
                action == GenericDownloader.DownloaderActions.CANCEL ||
                action == GenericDownloader.DownloaderActions.STOP_SAVE_ACTION ||
                action == GenericDownloader.DownloaderActions.RECOVER_FINALIZATION
            completeWorker(if (controlAction) Result.retry() else Result.failure())
        } catch (commitError: Throwable) {
            AppLogger.e("Failed to persist yt-dlp initialization error for $taskId", commitError)
            completeWorker(Result.failure())
        }
    }

    private fun advanceQueueAfterInitializationFailure() {
        try {
            downloadQueueManager.onYtDlpTerminal()
        } catch (scheduleError: Throwable) {
            AppLogger.e(
                "Failed to advance queue after yt-dlp initialization error for $taskId",
                scheduleError
            )
        }
    }

    private fun cleanError(raw: String): String {
        return sanitizeYoutubeDlError(raw, "yt-dlp download failed")
    }

    private fun checkAffectedRows(rows: Int, operation: String) {
        check(rows in 0..1) { "yt-dlp $operation updated $rows rows" }
    }

    private fun completeWorker(result: Result) {
        if (!continuationCompleted.compareAndSet(false, true)) return
        setDone()
        workerContinuation?.takeIf { it.isActive }?.resume(result)
    }

    private fun Int.isActiveYtDlpState(): Boolean {
        return this == VideoTaskState.PREPARE ||
            this == VideoTaskState.START ||
            this == VideoTaskState.DOWNLOADING ||
            this == VideoTaskState.PROXYREADY
    }

    private fun Int.isStableState(): Boolean {
        return this == VideoTaskState.PAUSE ||
            this == VideoTaskState.CANCELED ||
            this == VideoTaskState.SUCCESS ||
            this == VideoTaskState.ERROR ||
            this == VideoTaskState.ENOSPC
    }

    private fun hideNotifications(taskId: String) {
        notificationsHelper.hideNotification(taskId.hashCode())
        notificationsHelper.hideNotification(taskId.hashCode() + 1)
    }
}

internal fun youtubeDlOutputTemplate(directory: File, requestedName: String): String {
    val baseName = File(requestedName).nameWithoutExtension.ifBlank { "download" }
    return "${directory.absolutePath}/$baseName.%(ext)s"
}
