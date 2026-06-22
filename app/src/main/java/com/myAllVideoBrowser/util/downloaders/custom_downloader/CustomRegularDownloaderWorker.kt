package com.myAllVideoBrowser.util.downloaders.custom_downloader

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.core.net.toFile
import androidx.core.net.toUri
import androidx.work.WorkerParameters
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.DownloadedMediaValidator
import com.myAllVideoBrowser.util.FfmpegProcessor
import com.myAllVideoBrowser.util.downloaders.generic_downloader.GenericDownloader
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskItem
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskState
import com.myAllVideoBrowser.util.downloaders.generic_downloader.workers.GenericDownloadWorkerWrapper
import com.myAllVideoBrowser.util.downloaders.generic_downloader.workers.Progress
import java.io.File
import java.net.URL
import java.util.Date
import kotlin.coroutines.resume

// TODO: REFACTORING
class CustomRegularDownloaderWorker(appContext: Context, workerParams: WorkerParameters) :
    GenericDownloadWorkerWrapper(appContext, workerParams) {
    private var fileMovedSuccess = false
    private var outputFileName: String? = null
    private var progressCached: Progress = Progress(0, 0)

    @Volatile
    private var lastSavedTime = 0L


    companion object {
        private const val PROGRESS_UPDATE_INTERVAL = 1000
    }

    override fun handleAction(
        action: String, task: VideoTaskItem, headers: Map<String, String>, isFileRemove: Boolean
    ) {
        when (action) {
            GenericDownloader.DownloaderActions.DOWNLOAD -> {
                startDownload(task, headers)
            }

            GenericDownloader.DownloaderActions.CANCEL -> {
                cancelTask(task)
            }

            GenericDownloader.DownloaderActions.PAUSE -> {
                pauseTask(task)
            }

            GenericDownloader.DownloaderActions.RESUME -> {
                startDownload(task, headers)
            }

            GenericDownloader.DownloaderActions.STOP_SAVE_ACTION -> {
                stopAndSave(task)
            }
        }
    }

    @Synchronized
    override fun finishWork(item: VideoTaskItem?) {
        AppLogger.d("FINISHING... ${item?.filePath} $item")

        if (getDone()) {
            // Already resumed or finishing
            AppLogger.w("finishWork called but worker is already done. Ignoring.")
            return
        }

        val taskId = item?.mId ?: run {
            AppLogger.d("Cannot finish work because taskId is null: $item")
            if (!getDone()) {
                setDone()
                getContinuation().resume(Result.failure())
            }
            return
        }
        setDone()

        CustomRegularDownloader.deleteHeadersStringFromSharedPreferences(applicationContext, taskId)

        try {
            // 先确定最终状态：成功但文件未移动成功 → 视为 ERROR，
            // 避免通知 / Result / 落库三者基于不同状态产生不一致
            val wasSuccessWithoutMove =
                item.taskState == VideoTaskState.SUCCESS && !fileMovedSuccess
            if (wasSuccessWithoutMove) {
                item.taskState = VideoTaskState.ERROR
            }
            val progressInfo = if (wasSuccessWithoutMove) {
                "Error Moving File"
            } else {
                item.errorMessage ?: "Error"
            }
            val result =
                if (item.taskState == VideoTaskState.ERROR) Result.failure() else Result.success()

            // 真正成功（未被翻成 ERROR）且拿到可信最终大小时，用文件大小回填进度，
            // 避免未知长度直播/流式留下 -1 或 0 的进度
            if (item.taskState == VideoTaskState.SUCCESS && item.totalSize > 0) {
                progressCached = Progress(item.totalSize, item.totalSize)
            }

            saveProgress(item.mId, progressCached, item.taskState, progressInfo)
            downloadQueueManager.onTaskTerminal(taskId, item.taskState, progressInfo)

            // 通知放到最后，基于最终状态
            val notificationData = notificationsHelper.createNotificationBuilder(item)
            showNotificationFinal(notificationData.first, notificationData.second)

            getContinuation().resume(result)
        } catch (e: Throwable) {
            AppLogger.e("FINISHING UNEXPECTED ERROR $item ${e.message}", e)
            downloadTaskLogger.error(taskId, "Regular download finish failed", e)
            if (!getDone()) {
                setDone()
                try {
                    getContinuation().resume(Result.failure())
                } catch (ignored: IllegalStateException) {
                    // Ignored because another thread might have failed it first
                }
            }
        }
    }

    @Synchronized
    private fun handleSuccessfulDownload(item: VideoTaskItem, srcPath: File) {
        if (outputFileName == null) {
            AppLogger.d("Output file name is NULL")
            return
        }

        AppLogger.d("handleSuccessfulDownload started for item: ${item.mId}")
        val target = fixFileName(File(fileUtil.folderDir, File(outputFileName!!).name).path)

        var isPreprocessed = false

        val sourcePath: File
        if (!srcPath.exists()) {
            AppLogger.w("Source file not exists in handleSuccessfulDownload: $srcPath")
            sourcePath = File(
                "${
                    File(
                        srcPath.parentFile,
                        "${srcPath.nameWithoutExtension}_processed"
                    )
                }.${srcPath.extension}"
            )

            AppLogger.w("Try preprocessed name ${sourcePath.exists()} $sourcePath")

            if (!sourcePath.exists()) {
                return finishWork(item.also {
                    it.taskState = VideoTaskState.ERROR
                    it.errorMessage = "Downloaded file disappeared"
                })
            }
            isPreprocessed = true
        } else {
            sourcePath = srcPath
        }

        try {
            var isProcessFfmpeg: Boolean = sharedPrefHelper.getIsProcessDownloadFfmpeg()
            if (!isProcessFfmpeg) {
                val isOnlyLive = sharedPrefHelper.getIsProcessOnlyLiveDownloadFfmpeg()
                isProcessFfmpeg = isOnlyLive && item.isLive
            }

            var processedUri: Uri? = null

            val isFlv = item.isLive && item.url.contains(".flv", ignoreCase = true)
            if ((isProcessFfmpeg || isFlv) && !isPreprocessed) {
                val startProgress = Progress(0, sourcePath.length())
                showProgressProcessing(item, startProgress)
                saveProgress(
                    item.mId,
                    startProgress,
                    VideoTaskState.PREPARE,
                    "Ffmpeg processing..."
                )
                AppLogger.d("START FFMPEG PROCESSING... $sourcePath")

                // Determine if it's an FLV stream
                AppLogger.d("IS FLV: $isFlv")
                // The FfmpegProcessor call is now correctly blocking this thread
                processedUri = FfmpegProcessor.getInstance()
                    .processDownload(sourcePath.toUri(), isFlv) { percents ->
                        val percentInt = percents
                        if (percentInt in 1..99) {
                            val currentProgress = Progress(
                                (sourcePath.length() * percents / 100f).toLong(),
                                sourcePath.length()
                            )
                            showProgressProcessing(item, currentProgress)
                        }
                    }
                AppLogger.d("END FFMPEG PROCESSING... $processedUri")
                if (processedUri == null) {
                    throw Error("FFmpeg processing failed")
                }
                val endProgress = Progress(sourcePath.length(), sourcePath.length())
                showProgressProcessing(item, endProgress)
                saveProgress(
                    item.mId,
                    endProgress,
                    VideoTaskState.PREPARE,
                    "Ffmpeg processing success!"
                )
            }

            val finalSource = processedUri ?: sourcePath.toUri()
            if (!finalSource.toFile().exists() && target.toUri().toFile().exists()) {
                val finalSize = File(target).length()
                fileMovedSuccess = true  // 目标文件已存在即视为到位，避免 finishWork 误判为移动失败
                val finalItem = item.apply {
                    taskState = VideoTaskState.SUCCESS
                    filePath = target
                    lineInfo = "Download Success"
                    totalSize = finalSize
                    downloadSize = finalSize
                }
                finishWork(finalItem)

                return
            }
            AppLogger.d("START MOVING... $finalSource -> $target")
            fileMovedSuccess =
                fileUtil.moveMedia(applicationContext, finalSource, File(target).toUri())
            AppLogger.d("END MOVING... fileMovedSuccess: $fileMovedSuccess")

            if (fileMovedSuccess) {
                finalSource.toFile().parentFile?.deleteRecursively()
            }
            val finalItem: VideoTaskItem?
            if (!fileMovedSuccess) {
                throw Error("File Move error")
            } else {
                val targetFile = File(target)
                val validationError = DownloadedMediaValidator.validate(targetFile, item.isLive)
                if (validationError != null) {
                    finishWork(item.also {
                        it.taskState = VideoTaskState.ERROR
                        it.errorMessage = validationError
                        it.filePath = target
                    })
                    return
                }

                val finalSize = targetFile.length()
                finalItem = item.apply {
                    taskState = VideoTaskState.SUCCESS
                    filePath = target
                    lineInfo = "Download Success"
                    totalSize = finalSize
                    downloadSize = finalSize
                }
                finalSource.toFile().parentFile?.deleteRecursively()
            }

            finishWork(finalItem)

        } catch (e: Throwable) {
            val targetFl = File(target)
            if (targetFl.exists()) {
                sourcePath.parentFile?.deleteRecursively()
                AppLogger.d("Target file exists $targetFl  marking success ${item.mId}")
                finishWork(item.also { it.taskState = VideoTaskState.SUCCESS })
            } else {
                AppLogger.e("Error during post-processing: ${e.message}", e)
                finishWork(item.also {
                    it.taskState = VideoTaskState.ERROR
                    it.errorMessage = e.message
                })
            }
        }
    }

    private fun getProgressInfo(taskState: Int): String {
        return when (taskState) {
            VideoTaskState.PAUSE -> "PAUSED"
            VideoTaskState.CANCELED -> "CANCELED"
            else -> "ERROR"
        }
    }

    private fun startDownload(taskItem: VideoTaskItem, headers: Map<String, String>) {
        AppLogger.d("Start download regular: $taskItem headers: $headers")
        val taskId = inputData.getString(GenericDownloader.Constants.TASK_ID_KEY)!!
        downloadTaskLogger.info(taskId, "Regular download started: ${taskItem.fileName}")
        val url = taskItem.url
        showProgress(taskItem.also { it.mId = taskId }, progressCached)

        val tmpDir = fileUtil.tmpDir.resolve(taskId).apply { mkdirs() }
        outputFileName = tmpDir.resolve(taskItem.fileName).toString()
        val fixedHeaders = decodeCookieHeader(headers)
        updateProgressInfoAndStartDownload(taskItem, taskId, url, fixedHeaders)
    }

    private fun updateProgressInfoAndStartDownload(
        taskItem: VideoTaskItem, taskId: String, url: String, headers: Map<String, String>
    ) {
        saveProgress(taskId, Progress(0, 0), VideoTaskState.PREPARE)
        val threadCount = sharedPrefHelper.getRegularDownloaderThreadCount()
        val okHttpClient = proxyOkHttpClient.getProxyOkHttpClient()
        val isForceStreamDownload = sharedPrefHelper.getIsForceStreamDownload()
        CustomFileDownloader(
            URL(url),
            File(outputFileName!!),
            threadCount,
            headers,
            okHttpClient,
            createDownloadListener(taskItem, taskId),
            isForceStreamDownload
        ).download()
    }

    private fun createDownloadListener(taskItem: VideoTaskItem, taskId: String): DownloadListener {
        return object : DownloadListener {
            override fun onSuccess() {
                AppLogger.d("Download Success, proceeding to post-processing for $outputFileName")
                // DO NOT call finishWork here. Instead, trigger the next step.
                handleSuccessfulDownload(taskItem.also {
                    it.taskState = VideoTaskState.SUCCESS
                    it.mId = taskId
                    it.filePath = outputFileName
                }, File(outputFileName!!))
            }

            override fun onFailure(e: Throwable) {

                AppLogger.e("${e.message} Download Failed for $outputFileName", e)
                downloadTaskLogger.error(taskId, "Regular download failed: ${e.message}", e)

                val taskState = when (e.message) {
                    CustomFileDownloader.STOPPED_AND_SAVE_ACTION -> VideoTaskState.SUCCESS
                    CustomFileDownloader.PAUSE_ACTION -> VideoTaskState.PAUSE
                    CustomFileDownloader.CANCELED_ACTION -> VideoTaskState.CANCELED
                    else -> if (taskItem.isLive) VideoTaskState.SUCCESS else VideoTaskState.ERROR
                }

                if (e.message == CustomFileDownloader.STOPPED_AND_SAVE_ACTION) {
                    taskItem.apply { this.setIsLive(true) }
                }
                if (taskState == VideoTaskState.SUCCESS || (taskState == VideoTaskState.ERROR && taskItem.isLive)) {
                    val item = taskItem.also {
                        it.taskState = VideoTaskState.SUCCESS
                        it.mId = taskId
                        it.filePath = outputFileName
                    }
                    AppLogger.d("HANDLING TASK SUCCESS IN FAILURE $item")
                    handleSuccessfulDownload(item, File(outputFileName!!))
                } else if (taskState == VideoTaskState.PAUSE) {
                    val item = taskItem.also {
                        it.taskState = taskState
                        it.lineInfo = "PAUSED"
                        it.mId = taskId
                    }
                    AppLogger.d("HANDLING TASK PAUSE IN FAILURE $taskItem")
                    finishWork(item)
                } else {
                    val item = taskItem.also {
                        it.taskState = taskState
                        it.errorMessage = e.message
                        it.mId = taskId
                    }
                    AppLogger.d("HANDLING TASK EROR IN FAILURE $item")
                    finishWork(item)
                }
            }

            override fun onProgressUpdate(downloadedBytes: Long, totalBytes: Long) {
                progressCached = Progress(downloadedBytes, totalBytes)
                // 未知长度（total<=0，含 contentLength=-1）钳到 0，不透传负值、不伪造总量；
                // 仅修复 downloaded>total 的越界
                val totalBytesFixed = when {
                    totalBytes <= 0L -> 0L
                    downloadedBytes > totalBytes -> downloadedBytes
                    else -> totalBytes
                }
                onProgress(Progress(downloadedBytes, totalBytesFixed), taskItem.also {
                    it.mId = taskId
                    it.filePath = outputFileName
                })
            }

            override fun onChunkProgressUpdate(
                downloadedBytes: Long,
                allBytesChunk: Long,
                chunkIndex: Int
            ) {
            }

            override fun onChunkFailure(e: Throwable, index: CustomFileDownloader.Chunk) {}
        }
    }

    private fun onProgress(progress: Progress, downloadTask: VideoTaskItem) {
        if (getDone()) return

        progressCached = progress

        val currentTime = Date().time
        if (currentTime - lastSavedTime > PROGRESS_UPDATE_INTERVAL) {
            lastSavedTime = currentTime

            val taskItem = VideoTaskItem(downloadTask.url).also {
                it.mId = downloadTask.mId.toString()
                it.downloadSize = downloadTask.downloadSize
                it.fileName = outputFileName?.let { it1 -> File(it1).name } ?: downloadTask.fileName
                it.taskState = VideoTaskState.DOWNLOADING
                it.percent = if (progress.totalBytes > 0) {
                    (progress.currentBytes.toFloat() / progress.totalBytes * 100f)
                } else {
                    0f
                }
            }


            showProgress(taskItem, progress)
            saveProgress(downloadTask.mId, progress, VideoTaskState.DOWNLOADING)
        }
    }

    private fun cancelTask(task: VideoTaskItem) {
        val taskId = inputData.getString(GenericDownloader.Constants.TASK_ID_KEY)
        if (taskId == null) {
            finishWorkWithFailureTaskId(task)

            return
        }

        val tmpFile = fileUtil.tmpDir.resolve(taskId).resolve(File(task.fileName).name)
        CustomFileDownloader.cancel(tmpFile)

        getContinuation().resume(Result.success())
//        finishWork(task.also {
//            it.mId = taskId
//            it.taskState = VideoTaskState.CANCELED
//        })
    }

    private fun pauseTask(task: VideoTaskItem) {
        val taskId = inputData.getString(GenericDownloader.Constants.TASK_ID_KEY)

        if (taskId == null) {
            finishWorkWithFailureTaskId(task)

            return
        }

        val tmpFile = fileUtil.tmpDir.resolve(taskId).resolve(File(task.fileName).name)
        CustomFileDownloader.pause(tmpFile)

        getContinuation().resume(Result.success())
//        finishWork(task.also {
//            it.mId = taskId
//            it.taskState = VideoTaskState.PAUSE
//        })
    }

    private fun stopAndSave(task: VideoTaskItem) {
        val taskId = inputData.getString(GenericDownloader.Constants.TASK_ID_KEY)
            ?: return finishWorkWithFailureTaskId(task)

        val tmpFile = fileUtil.tmpDir.resolve(taskId).resolve(File(task.fileName).name)
        outputFileName = tmpFile.path
        AppLogger.d("STOPPING AND SAVING>>>>  $tmpFile")
        CustomFileDownloader.stopAndSave(tmpFile)
        getContinuation().resume(Result.success())
    }

    private fun showProgressProcessing(taskItem: VideoTaskItem, progress: Progress?) {
        val text = "Processing: ${taskItem.fileName}"
        val totalSize = progress?.totalBytes ?: 0
        val downloadSize = progress?.currentBytes ?: 0

        taskItem.apply {
            lineInfo = text
            taskState = VideoTaskState.PREPARE
            this.totalSize = totalSize
            this.downloadSize = downloadSize
            percent = getPercentFromBytes(downloadSize, totalSize)
        }

        val notificationData = notificationsHelper.createNotificationBuilder(taskItem)
        showLongRunningNotificationAsync(notificationData.first, notificationData.second)
    }


    private fun showProgress(taskItem: VideoTaskItem, progress: Progress?) {
        val text = "Downloading: ${taskItem.fileName}"
        val totalSize = progress?.totalBytes ?: 0
        val downloadSize = progress?.currentBytes ?: 0

        taskItem.apply {
            lineInfo = text
            taskState = VideoTaskState.DOWNLOADING
            this.totalSize = totalSize
            this.downloadSize = downloadSize
            percent = getPercentFromBytes(downloadSize, totalSize)
        }

        val notificationData = notificationsHelper.createNotificationBuilder(taskItem)
        showLongRunningNotificationAsync(notificationData.first, notificationData.second)
    }

    private fun saveProgress(
        taskId: String, progress: Progress, downloadStatus: Int, infoLine: String = ""
    ) {
        if (getDone() && downloadStatus == VideoTaskState.DOWNLOADING) {
            AppLogger.d(
                "saveProgress task returned cause DONE!!! $progress"
            )
            return
        }

        // 单条查询取代全表 blockingFirst，避免每秒全表阻塞读
        val dbTask = progressRepository.getProgressInfoById(taskId) ?: return
        if (dbTask.downloadStatus == VideoTaskState.SUCCESS) {
            return
        }

        val isBytesNoTouch = progress.totalBytes <= 0L
        val isNoTouchCurrent = progress.currentBytes == 0L

        if (!isBytesNoTouch && (downloadStatus != VideoTaskState.ERROR)) {
            dbTask.infoLine = infoLine
            dbTask.progressTotal = progress.totalBytes
        }

        if (downloadStatus != VideoTaskState.SUCCESS) {
            if (!isNoTouchCurrent) {
                dbTask.progressDownloaded = progress.currentBytes
            }
        } else {
            if (!isBytesNoTouch) {
                dbTask.progressDownloaded = dbTask.progressTotal
            }
        }

        dbTask.downloadStatus = downloadStatus
        if (dbTask.logPath.isBlank()) {
            dbTask.logPath = downloadTaskLogger.logPath(taskId)
        }
        if (downloadStatus == VideoTaskState.PREPARE ||
            downloadStatus == VideoTaskState.START ||
            downloadStatus == VideoTaskState.DOWNLOADING
        ) {
            dbTask.startedAt = dbTask.startedAt.takeIf { it > 0 } ?: System.currentTimeMillis()
        }
        if (downloadStatus == VideoTaskState.ERROR || downloadStatus == VideoTaskState.ENOSPC) {
            dbTask.lastError = infoLine
        }
        if (downloadStatus == VideoTaskState.SUCCESS ||
            downloadStatus == VideoTaskState.ERROR ||
            downloadStatus == VideoTaskState.ENOSPC ||
            downloadStatus == VideoTaskState.CANCELED
        ) {
            dbTask.completedAt = System.currentTimeMillis()
        }

        val isLive =
            dbTask.progressTotal == dbTask.progressDownloaded && downloadStatus == VideoTaskState.DOWNLOADING
        if (dbTask.isLive != true && isLive) {
            dbTask.isLive = true
        }

        if (getDone() && downloadStatus == VideoTaskState.DOWNLOADING) {
            AppLogger.d(
                "saveProgress task returned cause DONE!!! $progress"
            )
        } else {
            // 按列更新：只覆盖进度/状态列，queuePosition 不被碰；
            // fragments 原样回传（Regular 不使用分片），避免与队列重排互相整行覆盖
            progressRepository.updateProgressFields(
                id = dbTask.id,
                downloaded = dbTask.progressDownloaded,
                total = dbTask.progressTotal,
                fragDownloaded = dbTask.fragmentsDownloaded,
                fragTotal = dbTask.fragmentsTotal,
                status = dbTask.downloadStatus,
                infoLine = dbTask.infoLine,
                startedAt = dbTask.startedAt,
                completedAt = dbTask.completedAt,
                lastError = dbTask.lastError,
                logPath = dbTask.logPath,
                isLive = dbTask.isLive
            )
        }
    }

    private fun decodeCookieHeader(headers: Map<String, String>): Map<String, String> {
        val fixedHeaders = headers.toMutableMap()
        headers.forEach { (key, value) ->
            if (key == "Cookie") {
                fixedHeaders[key] = try {
                    String(Base64.decode(value, Base64.DEFAULT))
                } catch (e: IllegalArgumentException) {
                    AppLogger.w("Cookie header is not valid Base64 (length=${value.length}), using raw value")
                    value
                }
            }
        }
        return fixedHeaders
    }

    private fun finishWorkWithFailureTaskId(task: VideoTaskItem) {
        AppLogger.d("Cannot finish work because taskId is null: $task")
        try {
            getContinuation().resume(Result.failure())
        } catch (e: Throwable) {
            AppLogger.e("Regular: resume failure failed", e)
        }
    }
}
