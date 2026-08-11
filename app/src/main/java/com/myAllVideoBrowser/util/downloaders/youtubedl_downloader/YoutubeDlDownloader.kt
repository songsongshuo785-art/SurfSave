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
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.ContextUtils
import com.myAllVideoBrowser.util.downloaders.generic_downloader.GenericDownloader
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
        val videoUrl = if (videoInfo.downloadUrls.isNotEmpty()) {
            videoInfo.originalUrl
        } else {
            videoInfo.formats.formats.firstOrNull()?.url.orEmpty()
        }
        val data = Data.Builder()
            .putString(Constants.URL_KEY, videoUrl)
            .putString(Constants.TITLE_KEY, videoInfo.title)
            .putString(Constants.FILENAME_KEY, videoInfo.name)
            .putString(Constants.ORIGIN_KEY, videoInfo.originalUrl)
            .putString(Constants.TASK_ID_KEY, videoInfo.id)

        if (includeFormat) {
            videoInfo.formats.formats.firstOrNull()?.let { format ->
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
}
