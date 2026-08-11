package com.myAllVideoBrowser.util.downloaders.youtubedl_downloader

import com.myAllVideoBrowser.data.local.room.entity.ProgressInfo
import com.myAllVideoBrowser.data.repository.ProgressRepository
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskState
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class YoutubeDlFinalizationCoordinator @Inject constructor(
    private val progressRepository: ProgressRepository,
    private val mediaPublisher: YoutubeDlMediaPublisher
) {
    sealed class Result {
        data class Committed(
            val status: Int,
            val error: String,
            val targetPath: String
        ) : Result()

        data object NotOwner : Result()
    }

    open fun claimAndFinalize(
        taskId: String,
        executionToken: String,
        sourcePath: String,
        targetPath: String
    ): Result {
        val claimed = progressRepository.claimYtDlpFinalization(
            taskId,
            executionToken,
            sourcePath,
            targetPath
        )
        checkSingleRow(claimed, "claim finalization")
        if (claimed != 1) return Result.NotOwner
        return publishAndCommit(taskId, executionToken, sourcePath, targetPath)
    }

    open fun recover(task: ProgressInfo): Result {
        if (task.executionToken.isBlank() || task.downloadStatus != VideoTaskState.FINALIZING) {
            return Result.NotOwner
        }
        return publishAndCommit(
            task.id,
            task.executionToken,
            task.finalizationSource,
            task.finalizationTarget
        )
    }

    private fun publishAndCommit(
        taskId: String,
        executionToken: String,
        sourcePath: String,
        targetPath: String
    ): Result {
        val publishError = try {
            mediaPublisher.publish(sourcePath, targetPath)
        } catch (failure: Throwable) {
            failure.message ?: "Finalization failed"
        }
        val error = publishError
            ?.let { sanitizeYoutubeDlError(it, "Finalization failed") }
            .orEmpty()
        val status = if (error.isBlank()) VideoTaskState.SUCCESS else VideoTaskState.ERROR
        val infoLine = if (status == VideoTaskState.SUCCESS) "Completed" else error
        val committed = progressRepository.commitYtDlpFinalization(
            taskId,
            executionToken,
            status,
            System.currentTimeMillis(),
            error,
            infoLine
        )
        checkSingleRow(committed, "commit finalization")
        return if (committed == 1) {
            Result.Committed(status, error, targetPath)
        } else {
            Result.NotOwner
        }
    }

    private fun checkSingleRow(rows: Int, operation: String) {
        check(rows in 0..1) { "yt-dlp $operation updated $rows rows" }
    }
}
