package com.myAllVideoBrowser.util.downloaders.generic_downloader.workers

import android.content.Context
import android.os.Build
import android.util.Base64
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.myAllVideoBrowser.util.FileUtil
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskItem
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.downloaders.generic_downloader.GenericDownloader
import com.myAllVideoBrowser.util.proxy_utils.ProxyService
import java.io.File
import java.io.IOException
import java.io.Serializable
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine

abstract class GenericDownloadWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {
    @Volatile
    private lateinit var continuation: CancellableContinuation<Result>
    private val fileDir: String = File(
        applicationContext.filesDir.absolutePath, FileUtil.FOLDER_NAME
    ).absolutePath

    private val finishing = AtomicBoolean(false)
    private val continuationCompleted = AtomicBoolean(false)

    private companion object {
        const val STOP_REASON_UNAVAILABLE = Int.MIN_VALUE
    }

    abstract fun finishWork(item: VideoTaskItem?)

    abstract fun handleAction(
        action: String, task: VideoTaskItem, headers: Map<String, String>, isFileRemove: Boolean
    )

    open fun onWorkCancelled() {
    }

    open fun fixFileName(fileName: String): String {
        val currentFile = File(fileName)
        if (!currentFile.exists()) return fileName

        var counter = 1
        var fixedFileName: File
        do {
            fixedFileName =
                File(currentFile.parent, "${currentFile.nameWithoutExtension}_cp$counter.mp4")
            counter++
        } while (fixedFileName.exists())

        return fixedFileName.absolutePath
    }

    open fun getTaskFromInput(): VideoTaskItem {
        val url = inputData.getString(GenericDownloader.Constants.URL_KEY)
        val fileName =
            inputData.getString(GenericDownloader.Constants.FILENAME_KEY) ?: url.hashCode()
                .toString()
        val title = inputData.getString(GenericDownloader.Constants.TITLE_KEY)
        val taskId = inputData.getString(GenericDownloader.Constants.TASK_ID_KEY)

        return VideoTaskItem(url).apply {
            mId = taskId
            this.title = title
            this.fileName = fileName
            saveDir = fileDir
            filePath = File(saveDir).resolve(fileName).toString()
        }
    }

    override suspend fun doWork(): Result {
        return suspendCancellableCoroutine { continuation ->
            setWorkContinuation(continuation)
            continuation.invokeOnCancellation {
                logStopReason()
                onWorkCancelled()
            }
            try {
                if (!ProxyService.isRunning) {
                    AppLogger.d("ProxyService is not running.")
                } else {
                    AppLogger.d("ProxyService is running.")
                }

                val task = getTaskFromInput()
                val action = inputData.getString(GenericDownloader.Constants.ACTION_KEY)
                val isFileRemove =
                    inputData.getString(GenericDownloader.Constants.IS_FILE_REMOVE_KEY)
                        .toString() == "true"
                val headers = loadHeaders(task.mId)

                if (action.isNullOrBlank() || task.url == null) {
                    completeWork(Result.failure())
                    return@suspendCancellableCoroutine
                }

                handleAction(action, task, headers, isFileRemove)
            } catch (e: IllegalArgumentException) {
                AppLogger.e("Invalid input: $e")
                completeWork(Result.failure())
            } catch (e: IOException) {
                AppLogger.e("Download error:- $e")
                completeWork(Result.failure())
            } catch (e: Exception) {
                AppLogger.e("Unexpected error: $e")
                completeWork(Result.failure())
            }
        }.also {
            afterDone()
        }
    }

    private fun loadHeaders(taskId: String): Map<String, String> {
        val inpHeaders =
            GenericDownloader.getInstance()
                .loadHeadersStringFromSharedPreferences(applicationContext, taskId)
        return inpHeaders?.let {
            try {
                val decodedHeaders =
                    String(
                        Base64.decode(
                            GenericDownloader.getInstance().decompressString(it),
                            Base64.DEFAULT
                        )
                    )

                val headersType = object : TypeToken<Map<String, String>>() {}.type
                Gson().fromJson<Map<String, String>>(decodedHeaders, headersType).orEmpty()
            } catch (e: Exception) {
                emptyMap()
            }
        } ?: emptyMap()
    }

    open fun afterDone() {

    }

    /**
     * WorkManager 停止 Worker 时记录 stopReason。
     * CoroutineWorker.onStopped() 是 final，这里在 invokeOnCancellation 里读取。
     * 必须同步、轻量：不做数据库查询，也不启动异步任务（进程可能马上被回收）。
     */
    private fun logStopReason() {
        val taskId = inputData.getString(GenericDownloader.Constants.TASK_ID_KEY).orEmpty()
        val reasonCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            stopReason
        } else {
            STOP_REASON_UNAVAILABLE
        }
        AppLogger.d(
            "DOWNLOAD_WORKER_STOP: taskId=$taskId worker=${javaClass.simpleName} " +
                "attempt=$runAttemptCount stopReason=${stopReasonName(reasonCode)}($reasonCode) " +
                "sdkInt=${Build.VERSION.SDK_INT}"
        )
    }

    private fun stopReasonName(code: Int): String = when (code) {
        -256 -> "NOT_STOPPED"
        -512 -> "UNKNOWN"
        -128 -> "FOREGROUND_SERVICE_TIMEOUT"
        1 -> "CANCELLED_BY_APP"
        2 -> "PREEMPT"
        3 -> "TIMEOUT"
        4 -> "DEVICE_STATE"
        5 -> "CONSTRAINT_BATTERY_NOT_LOW"
        6 -> "CONSTRAINT_CHARGING"
        7 -> "CONSTRAINT_CONNECTIVITY"
        8 -> "CONSTRAINT_DEVICE_IDLE"
        9 -> "CONSTRAINT_STORAGE_NOT_LOW"
        10 -> "QUOTA"
        11 -> "BACKGROUND_RESTRICTION"
        12 -> "APP_STANDBY"
        13 -> "USER"
        14 -> "SYSTEM_PROCESSING"
        15 -> "ESTIMATED_APP_LAUNCH_TIME_CHANGED"
        else -> "UNAVAILABLE"
    }

    fun setDone() {
        finishing.set(true)
    }

    fun getDone(): Boolean {
        return finishing.get()
    }

    protected fun tryStartFinishing(): Boolean = finishing.compareAndSet(false, true)

    protected fun completeWork(result: Result): Boolean {
        finishing.set(true)
        if (!continuationCompleted.compareAndSet(false, true)) return false
        if (continuation.isActive) {
            continuation.resume(result)
        }
        return true
    }

    private fun setWorkContinuation(continuation: CancellableContinuation<Result>) {
        this.continuation = continuation
    }
}


class Progress(var currentBytes: Long, var totalBytes: Long) : Serializable {
    override fun toString(): String {
        return "Progress{currentBytes=$currentBytes, totalBytes=$totalBytes}"
    }
}
