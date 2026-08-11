package com.myAllVideoBrowser.util.downloaders.youtubedl_downloader

import android.os.Handler
import android.os.Looper
import com.myAllVideoBrowser.DLApplication
import com.myAllVideoBrowser.data.repository.ProgressRepository
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.NotificationsHelper
import com.myAllVideoBrowser.util.downloaders.DownloadQueueManager
import com.myAllVideoBrowser.util.downloaders.DownloadTaskLogger
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskItem
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskState
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

internal fun shouldShowYoutubeDlTerminalNotification(
    currentExecutionToken: String?,
    currentStatus: Int?,
    expectedExecutionToken: String,
    expectedStatus: Int
): Boolean {
    return expectedExecutionToken.isNotBlank() &&
        currentExecutionToken == expectedExecutionToken &&
        currentStatus == expectedStatus
}

@Singleton
class YoutubeDlTerminalEffects @Inject constructor(
    private val application: DLApplication,
    private val progressRepository: ProgressRepository,
    private val notificationsHelper: NotificationsHelper,
    private val downloadQueueManager: DownloadQueueManager,
    private val taskLogger: DownloadTaskLogger
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    fun apply(
        taskId: String,
        executionToken: String,
        status: Int,
        error: String = "",
        outputPath: String = "",
        cleanup: () -> Unit = {}
    ) {
        val snapshot = runEffectWithResult(taskId, "load terminal snapshot") {
            progressRepository.getProgressInfoById(taskId)
        }
        val item = VideoTaskItem(snapshot?.videoInfo?.originalUrl.orEmpty()).apply {
            mId = taskId
            title = snapshot?.videoInfo?.title.orEmpty()
            fileName = outputPath.takeIf { it.isNotBlank() }?.let(::File)?.name
                ?: snapshot?.videoInfo?.name.orEmpty()
            taskState = status
            errorMessage = error
            downloadSize = snapshot?.progressDownloaded ?: 0L
            totalSize = snapshot?.progressTotal ?: 0L
            percent = if (status == VideoTaskState.SUCCESS) 100F else snapshot?.progress?.toFloat() ?: 0F
            lineInfo = snapshot?.infoLine.orEmpty()
        }

        runEffect(taskId, "hide primary notification") {
            notificationsHelper.hideNotification(taskId.hashCode())
        }
        runEffect(taskId, "hide secondary notification") {
            notificationsHelper.hideNotification(taskId.hashCode() + 1)
        }
        runEffectWithResult(taskId, "create final notification") {
            notificationsHelper.createNotificationBuilder(item)
        }?.let { notification ->
            runEffect(taskId, "schedule final notification") {
                mainHandler.postDelayed(
                    {
                        val current = runEffectWithResult(
                            taskId,
                            "verify final notification ownership"
                        ) {
                            progressRepository.getProgressInfoById(taskId)
                        }
                        if (shouldShowYoutubeDlTerminalNotification(
                                current?.executionToken,
                                current?.downloadStatus,
                                executionToken,
                                status
                            )
                        ) {
                            runEffect(taskId, "show final notification") {
                                notificationsHelper.showNotification(notification)
                            }
                        }
                    },
                    FINAL_NOTIFICATION_DELAY_MS
                )
            }
        }

        runEffect(taskId, "write terminal log") {
            if (status == VideoTaskState.ERROR || status == VideoTaskState.ENOSPC) {
                taskLogger.error(
                    taskId,
                    "yt-dlp execution failed: ${error.ifBlank { "Unknown error" }}"
                )
            } else {
                taskLogger.info(taskId, "yt-dlp execution committed state $status")
            }
        }

        try {
            cleanup()
        } catch (cleanupError: Throwable) {
            AppLogger.e("yt-dlp terminal cleanup failed for $taskId", cleanupError)
            runEffect(taskId, "write cleanup failure log") {
                taskLogger.warn(taskId, "yt-dlp terminal cleanup failed", cleanupError)
            }
        }
        runEffect(taskId, "delete execution header cache") {
            YoutubeDlDownloader.deleteHeadersStringFromSharedPreferences(
                application.applicationContext,
                YoutubeDlDownloader.executionKey(taskId, executionToken)
            )
        }

        if (status == VideoTaskState.CANCELED) {
            runEffect(taskId, "delete committed canceled row") {
                val deleted = progressRepository.deleteCommittedYtDlpCanceled(
                    taskId,
                    executionToken
                )
                if (deleted !in 0..1) {
                    AppLogger.e("yt-dlp canceled cleanup removed $deleted rows for $taskId")
                }
            }
        }

        runEffect(taskId, "advance download queue") {
            downloadQueueManager.onYtDlpTerminal()
        }
    }

    private fun runEffect(taskId: String, name: String, action: () -> Unit) {
        try {
            action()
        } catch (error: Throwable) {
            AppLogger.e("Failed to $name for yt-dlp task $taskId", error)
        }
    }

    private fun <T> runEffectWithResult(taskId: String, name: String, action: () -> T): T? {
        return try {
            action()
        } catch (error: Throwable) {
            AppLogger.e("Failed to $name for yt-dlp task $taskId", error)
            null
        }
    }

    private companion object {
        const val FINAL_NOTIFICATION_DELAY_MS = 2_000L
    }
}
