package com.myAllVideoBrowser.util.downloaders.youtubedl_downloader

import com.myAllVideoBrowser.DLApplication
import com.myAllVideoBrowser.data.local.room.entity.ProgressInfo
import com.myAllVideoBrowser.data.repository.ProgressRepository
import com.myAllVideoBrowser.util.FileUtil
import com.myAllVideoBrowser.util.downloaders.DownloadQueueManager
import com.myAllVideoBrowser.util.downloaders.DownloadTaskLogger
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskState
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

internal enum class YoutubeDlRecoveryAction {
    NONE, ENSURE_DOWNLOAD, PAUSE, CANCEL, STOP_AND_SAVE, FINALIZE
}

internal fun youtubeDlRecoveryAction(task: ProgressInfo): YoutubeDlRecoveryAction {
    return when (task.downloadStatus) {
        VideoTaskState.PREPARE,
        VideoTaskState.START,
        VideoTaskState.DOWNLOADING,
        VideoTaskState.PROXYREADY -> YoutubeDlRecoveryAction.ENSURE_DOWNLOAD

        VideoTaskState.PAUSING -> if (task.stopReason == YoutubeDlStopReason.STOP_AND_SAVE) {
            YoutubeDlRecoveryAction.STOP_AND_SAVE
        } else {
            YoutubeDlRecoveryAction.PAUSE
        }

        VideoTaskState.CANCELING -> YoutubeDlRecoveryAction.CANCEL
        VideoTaskState.FINALIZING -> YoutubeDlRecoveryAction.FINALIZE
        else -> YoutubeDlRecoveryAction.NONE
    }
}

@Singleton
class YoutubeDlRecoveryCoordinator @Inject constructor(
    private val application: DLApplication,
    private val progressRepository: ProgressRepository,
    private val downloadQueueManager: DownloadQueueManager,
    private val taskLogger: DownloadTaskLogger,
    private val finalizationCoordinator: YoutubeDlFinalizationCoordinator,
    private val terminalEffects: YoutubeDlTerminalEffects,
    fileUtil: FileUtil
) {
    private val executionResources = YoutubeDlExecutionResources(fileUtil)

    fun recover() {
        try {
            progressRepository.getProgressInfosOnce()
                .filter(::isYoutubeDlTask)
                .forEach { staleSnapshot ->
                    val task = normalizeExecutionToken(staleSnapshot) ?: return@forEach
                    try {
                        recoverTask(task)
                    } catch (error: Throwable) {
                        taskLogger.error(task.id, "Failed to recover yt-dlp state ${task.downloadStatus}", error)
                    }
                }
        } catch (error: Throwable) {
            taskLogger.error(RECOVERY_LOG_ID, "Failed to scan yt-dlp recovery state", error)
        } finally {
            try {
                downloadQueueManager.scheduleNext()
            } catch (error: Throwable) {
                taskLogger.error(RECOVERY_LOG_ID, "Failed to advance queue after yt-dlp recovery", error)
            }
        }
    }

    private fun normalizeExecutionToken(task: ProgressInfo): ProgressInfo? {
        if (youtubeDlRecoveryAction(task) == YoutubeDlRecoveryAction.NONE) return task
        if (task.executionToken.isNotBlank()) return task

        val token = UUID.randomUUID().toString()
        val adopted = progressRepository.adoptLegacyYtDlpExecution(task.id, token)
        check(adopted in 0..1) { "Legacy yt-dlp adoption updated $adopted rows" }
        val current = progressRepository.getProgressInfoById(task.id)
        if (current?.executionToken.isNullOrBlank()) {
            taskLogger.warn(task.id, "Legacy yt-dlp state could not be assigned an execution token")
            return null
        }
        return current
    }

    private fun recoverTask(task: ProgressInfo) {
        when (youtubeDlRecoveryAction(task)) {
            YoutubeDlRecoveryAction.NONE -> Unit
            YoutubeDlRecoveryAction.ENSURE_DOWNLOAD -> YoutubeDlDownloader.ensureDownload(application, task)
            YoutubeDlRecoveryAction.PAUSE -> YoutubeDlDownloader.pauseDownload(application, task)
            YoutubeDlRecoveryAction.CANCEL ->
                YoutubeDlDownloader.cancelDownload(application, task, task.removePartialOnCancel)
            YoutubeDlRecoveryAction.STOP_AND_SAVE ->
                YoutubeDlDownloader.stopAndSaveDownload(application, task)
            YoutubeDlRecoveryAction.FINALIZE -> recoverFinalization(task)
        }
    }

    private fun recoverFinalization(task: ProgressInfo) {
        when (val result = finalizationCoordinator.recover(task)) {
            YoutubeDlFinalizationCoordinator.Result.NotOwner -> Unit
            is YoutubeDlFinalizationCoordinator.Result.Committed -> {
                terminalEffects.apply(
                    task.id,
                    task.executionToken,
                    result.status,
                    result.error,
                    result.targetPath
                ) {
                    if (result.status == VideoTaskState.SUCCESS) {
                        val key = YoutubeDlDownloader.executionKey(task.id, task.executionToken)
                        executionResources.deleteExecution(task.id, key)
                    }
                }
            }
        }
    }

    private fun isYoutubeDlTask(task: ProgressInfo): Boolean {
        return !task.videoInfo.isRegularDownload && !task.videoInfo.isDetectedBySuperX
    }

    private companion object {
        const val RECOVERY_LOG_ID = "yt-dlp-recovery"
    }
}
