package com.myAllVideoBrowser.util.downloaders.youtubedl_downloader

import com.myAllVideoBrowser.data.local.room.entity.ProgressInfo
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskState
import java.io.File

internal enum class YoutubeDlRetryAction {
    RETRY_PUBLICATION,
    REQUEUE_DOWNLOAD,
    NONE
}

internal fun youtubeDlRetryAction(
    task: ProgressInfo,
    publishedTargetExists: Boolean = false
): YoutubeDlRetryAction {
    val isFailure = task.downloadStatus == VideoTaskState.ERROR ||
        task.downloadStatus == VideoTaskState.ENOSPC
    if (!isFailure && task.downloadStatus != VideoTaskState.PAUSE) {
        return YoutubeDlRetryAction.NONE
    }

    val sourceExists = File(task.finalizationSource).isFile
    val targetAlreadyRejected = task.lastError.contains(
        "target validation failed",
        ignoreCase = true
    )
    val hasRecoverablePublicationArtifact = sourceExists ||
        (publishedTargetExists && !targetAlreadyRejected)
    val canRetryPublication = isFailure &&
        task.executionToken.isNotBlank() &&
        task.finalizationSource.isNotBlank() &&
        task.finalizationTarget.isNotBlank() &&
        hasRecoverablePublicationArtifact
    return if (canRetryPublication) {
        YoutubeDlRetryAction.RETRY_PUBLICATION
    } else {
        YoutubeDlRetryAction.REQUEUE_DOWNLOAD
    }
}
