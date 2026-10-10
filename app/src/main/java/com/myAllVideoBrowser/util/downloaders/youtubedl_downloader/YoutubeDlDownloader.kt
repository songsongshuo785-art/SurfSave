package com.myAllVideoBrowser.util.downloaders.youtubedl_downloader

import android.content.Context
import android.util.Base64
import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.google.gson.Gson
import com.myAllVideoBrowser.data.local.room.entity.ProgressInfo
import com.myAllVideoBrowser.data.local.room.entity.VideoFormatEntity
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.ContextUtils
import com.myAllVideoBrowser.util.downloaders.DownloadEngineKind
import com.myAllVideoBrowser.util.downloaders.DownloadEngineKindResolver
import com.myAllVideoBrowser.util.downloaders.SelectedFormatSelector
import com.myAllVideoBrowser.util.downloaders.generic_downloader.GenericDownloader
import com.myAllVideoBrowser.util.media.DownloadStrategyResolver
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

object YoutubeDlDownloader : GenericDownloader() {
    private const val DOWNLOAD_WORK_PREFIX = "ytdlp-download-"
    private const val CONTROL_WORK_PREFIX = "ytdlp-control-"

    fun startDownload(context: Context, task: ProgressInfo) =
        enqueue(context, task, DownloaderActions.DOWNLOAD, includeFormat = true)

    override fun startDownload(context: Context, videoInfo: VideoInfo) {
        error("yt-dlp downloads must start from a claimed ProgressInfo execution.")
    }

    override fun resumeDownload(context: Context, progressInfo: ProgressInfo) =
        enqueue(context, progressInfo, DownloaderActions.RESUME, includeFormat = true)

    override fun pauseDownload(context: Context, progressInfo: ProgressInfo) =
        enqueue(context, progressInfo, DownloaderActions.PAUSE)

    override fun cancelDownload(
        context: Context,
        progressInfo: ProgressInfo,
        removeFile: Boolean
    ) = enqueue(
        context,
        progressInfo,
        DownloaderActions.CANCEL,
        removeFile = removeFile
    )

    fun stopAndSaveDownload(context: Context, progressInfo: ProgressInfo) =
        enqueue(context, progressInfo, DownloaderActions.STOP_SAVE_ACTION)

    fun recoverFinalization(context: Context, progressInfo: ProgressInfo) =
        enqueue(context, progressInfo, DownloaderActions.RECOVER_FINALIZATION)

    fun ensureDownload(context: Context, progressInfo: ProgressInfo) =
        enqueue(context, progressInfo, DownloaderActions.RESUME, includeFormat = true)

    override fun getDownloadDataFromVideoInfo(videoInfo: VideoInfo): Data.Builder =
        buildDownloadData(videoInfo, videoInfo.id, includeFormat = true)

    override fun getWorkRequest(id: String): OneTimeWorkRequest.Builder =
        OneTimeWorkRequest.Builder(YoutubeDlDownloaderWorker::class.java)
            .addTag(id)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)

    fun executionKey(taskId: String, token: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$taskId:$token".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun enqueue(
        context: Context,
        task: ProgressInfo,
        action: String,
        removeFile: Boolean = false,
        includeFormat: Boolean = false
    ) {
        require(task.executionToken.isNotBlank()) { "yt-dlp execution token is missing." }
        val key = executionKey(task.id, task.executionToken)
        val data = buildDownloadData(task.videoInfo, key, includeFormat)
            .putString(Constants.ACTION_KEY, action)
            .putString(Constants.EXECUTION_TOKEN_KEY, task.executionToken)
            .putString(Constants.EXECUTION_KEY, key)
            .putBoolean(Constants.IS_FILE_REMOVE_KEY, removeFile)
            .build()
        val request = getWorkRequest(key)
            .addTag(task.id)
            .setInputData(data)
            .build()
        val isDownload = action == DownloaderActions.DOWNLOAD ||
            action == DownloaderActions.RESUME
        val uniqueName = if (isDownload) {
            "$DOWNLOAD_WORK_PREFIX$key"
        } else {
            "$CONTROL_WORK_PREFIX$key-$action"
        }
        WorkManager.getInstance(context)
            .enqueueUniqueWork(uniqueName, ExistingWorkPolicy.KEEP, request)
            .result
            .get(30, TimeUnit.SECONDS)
    }

    private fun buildDownloadData(
        videoInfo: VideoInfo,
        cacheKey: String,
        includeFormat: Boolean
    ): Data.Builder {
        // 只有真正要执行/续跑下载时才严格校验选中的 format
        // （pause / cancel / stop-and-save / recover-finalization 不需要，也不应因数据问题而失败）。
        val videoUrl = if (includeFormat) {
            resolveExecutionUrl(videoInfo)
        } else {
            legacyExecutionUrl(videoInfo)
        }
        val data = Data.Builder()
            .putString(Constants.URL_KEY, videoUrl)
            .putString(Constants.TITLE_KEY, videoInfo.title)
            .putString(Constants.FILENAME_KEY, videoInfo.name)
            .putString(Constants.ORIGIN_KEY, videoUrl)
            .putString(Constants.TASK_ID_KEY, videoInfo.id)

        if (includeFormat) {
            selectedFormatForExecution(videoInfo)?.let { format ->
                val encoded = Base64.encodeToString(
                    Gson().toJson(format).toByteArray(Charsets.UTF_8),
                    Base64.DEFAULT
                )
                val compressed = compressString(encoded)
                saveStringToSharedPreferences(
                    ContextUtils.getApplicationContext(),
                    cacheKey,
                    compressed
                )
                AppLogger.d("Saved yt-dlp format for execution $cacheKey")
            }
        }
        return data
    }

    /**
     * yt-dlp 的执行目标 URL。
     *
     * 必须是「产生这个 formatId 的那一次 yt-dlp 输入」，而不是 `videoInfo.originalUrl`：
     * 否则「页面 URL + 直链 format」的混合对象会再次把页面地址交给 yt-dlp 的 generic extractor
     * （Hanime1 撞 Cloudflare 的根因）。
     *
     * **只读 resolver 的结果**：`extractorInputUrl` 的 LEGACY 回退（`videoInfo.originalUrl`）只允许
     * 发生在 `DownloadStrategyResolver` 内部，这里不再有第二套 explicit/legacy 判据。
     */
    private fun resolveExecutionUrl(videoInfo: VideoInfo): String {
        val selected = SelectedFormatSelector.select(videoInfo, "ytdlp")
        val resolution = DownloadStrategyResolver.resolve(videoInfo, selected)
        // 用「执行引擎是 yt-dlp」而不是字面的 YTDLP_FORMAT：路由把 PAGE_EXTRACTOR 也交给本引擎。
        require(DownloadEngineKindResolver.engineKindOf(resolution) == DownloadEngineKind.YTDLP) {
            "ytdlp: task ${videoInfo.id} resolved to ${resolution.strategy.name}."
        }
        return requireNotNull(resolution.extractorInputUrl?.takeIf { it.isNotBlank() }) {
            "ytdlp: task ${videoInfo.id} has no extractor input URL."
        }
    }

    /** 控制类动作（pause/cancel/stop-and-save）沿用改动前的 URL 计算，避免无意义的行为变更。 */
    private fun legacyExecutionUrl(videoInfo: VideoInfo): String =
        if (videoInfo.downloadUrls.isNotEmpty()) {
            videoInfo.originalUrl
        } else {
            videoInfo.formats.formats.firstOrNull()?.url.orEmpty()
        }

    /** 与 [resolveExecutionUrl] 同一条 format，保证 -f 的 formatId 与实际输入 URL 同源。 */
    private fun selectedFormatForExecution(videoInfo: VideoInfo): VideoFormatEntity? =
        SelectedFormatSelector.select(videoInfo, "ytdlp-format")
}
