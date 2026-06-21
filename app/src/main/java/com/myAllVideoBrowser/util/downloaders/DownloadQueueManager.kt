package com.myAllVideoBrowser.util.downloaders

import com.myAllVideoBrowser.DLApplication
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.data.local.room.entity.ProgressInfo
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.data.repository.ProgressRepository
import com.myAllVideoBrowser.util.DownloadFilenameTemplate
import com.myAllVideoBrowser.util.FileUtil
import com.myAllVideoBrowser.util.SharedPrefHelper
import com.myAllVideoBrowser.util.VideoFormatUi
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskState
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadQueueManager @Inject constructor(
    private val application: DLApplication,
    private val progressRepository: ProgressRepository,
    private val sharedPrefHelper: SharedPrefHelper,
    private val fileUtil: FileUtil,
    private val engineRouter: DownloadEngineRouter,
    private val taskLogger: DownloadTaskLogger
) {
    sealed class EnqueueResult {
        data class Accepted(val task: ProgressInfo, val startedNow: Boolean) : EnqueueResult()
        data class Duplicate(
            val existing: ProgressInfo,
            val incoming: VideoInfo,
            val messageRes: Int
        ) : EnqueueResult()
        data class Rejected(val messageRes: Int) : EnqueueResult()
    }

    @Synchronized
    fun enqueue(
        videoInfo: VideoInfo,
        force: Boolean = false,
        filenameContext: DownloadFilenameTemplate.Context = DownloadFilenameTemplate.Context()
    ): EnqueueResult {
        val templatedInfo = applyFilenameTemplate(videoInfo, filenameContext)
        val allTasks = progressRepository.getProgressInfosOnce()
        val fingerprint = DownloadFingerprint.fromVideoInfo(templatedInfo)
        if (!force) {
            findDuplicate(allTasks, fingerprint)?.let { existing ->
                taskLogger.info(existing.id, "Duplicate download rejected for ${templatedInfo.name}")
                return EnqueueResult.Duplicate(
                    existing = existing,
                    incoming = templatedInfo,
                    messageRes = R.string.download_duplicate_active
                )
            }
        }
        if (!force && hasDownloadedFile(templatedInfo)) {
            return EnqueueResult.Rejected(R.string.download_duplicate_file)
        }

        val taskVideoInfo = if (force) {
            templatedInfo.copy(id = "${templatedInfo.id}-${UUID.randomUUID()}")
        } else {
            templatedInfo
        }
        val now = System.currentTimeMillis()
        val task = ProgressInfo(
            id = taskVideoInfo.id,
            downloadId = taskVideoInfo.id.hashCode().toLong(),
            videoInfo = taskVideoInfo,
            downloadStatus = VideoTaskState.PENDING,
            isM3u8 = taskVideoInfo.isM3u8,
            queuePosition = nextQueuePosition(allTasks),
            queuedAt = now,
            downloadFingerprint = fingerprint,
            logPath = taskLogger.logPath(taskVideoInfo.id)
        )

        progressRepository.saveProgressInfo(task)
        taskLogger.info(task.id, "Queued download ${task.videoInfo.name}")
        val started = scheduleNextLocked().any { it.id == task.id }
        return EnqueueResult.Accepted(task, started)
    }

    @Synchronized
    fun scheduleNext(): List<ProgressInfo> = scheduleNextLocked()

    @Synchronized
    fun pause(taskId: String) {
        val task = progressRepository.getProgressInfoById(taskId) ?: return
        val paused = task.copy(
            downloadStatus = VideoTaskState.PAUSE,
            queuedForLater = false,
            infoLine = "Paused"
        ).withLogPath()
        progressRepository.saveProgressInfo(paused)
        taskLogger.info(task.id, "Paused download")
        if (task.isActive) {
            engineRouter.pause(application, paused)
        }
        scheduleNextLocked()
    }

    @Synchronized
    fun resume(taskId: String) {
        val task = progressRepository.getProgressInfoById(taskId) ?: return
        val allTasks = progressRepository.getProgressInfosOnce()
        val resumed = task.copy(
            downloadStatus = VideoTaskState.PENDING,
            queuedForLater = false,
            infoLine = "Queued",
            queuePosition = task.queuePosition.takeIf { it > 0 } ?: nextQueuePosition(allTasks)
        ).withLogPath()
        progressRepository.saveProgressInfo(resumed)
        taskLogger.info(task.id, "Resumed download into queue")
        scheduleNextLocked()
    }

    @Synchronized
    fun cancel(taskId: String, removeFile: Boolean) {
        val task = progressRepository.getProgressInfoById(taskId) ?: return
        taskLogger.info(task.id, "Canceled download removeFile=$removeFile")
        if (task.isActive || task.downloadStatus == VideoTaskState.PAUSE) {
            engineRouter.cancel(application, task, removeFile)
        }
        progressRepository.deleteProgressInfo(task)
        scheduleNextLocked()
    }

    @Synchronized
    fun stopAndSave(taskId: String) {
        val task = progressRepository.getProgressInfoById(taskId) ?: return
        taskLogger.info(task.id, "Stop and save requested")
        engineRouter.stopAndSave(application, task)
    }

    @Synchronized
    fun markLater(taskId: String) {
        val task = progressRepository.getProgressInfoById(taskId) ?: return
        val later = task.copy(
            downloadStatus = VideoTaskState.PAUSE,
            queuedForLater = true,
            infoLine = "Saved for later"
        ).withLogPath()
        progressRepository.saveProgressInfo(later)
        taskLogger.info(task.id, "Moved download to later")
        if (task.isActive) {
            engineRouter.pause(application, later)
        }
        scheduleNextLocked()
    }

    @Synchronized
    fun moveUp(taskId: String) {
        moveBy(taskId, -1)
    }

    @Synchronized
    fun moveDown(taskId: String) {
        moveBy(taskId, 1)
    }

    @Synchronized
    fun moveToTop(taskId: String) {
        val allTasks = normalizeQueuePositions(progressRepository.getProgressInfosOnce())
        val movable = allTasks.filter { it.canMoveInQueue }.queueSorted()
        val target = movable.firstOrNull { it.id == taskId } ?: return
        val reordered = listOf(target) + movable.filterNot { it.id == taskId }
        saveReordered(allTasks, reordered)
        taskLogger.info(taskId, "Moved download to top")
    }

    @Synchronized
    fun onTaskTerminal(taskId: String, taskState: Int, errorMessage: String? = null) {
        val task = progressRepository.getProgressInfoById(taskId)
        if (task != null) {
            val now = System.currentTimeMillis()
            val finalError = errorMessage.orEmpty().ifBlank {
                if (taskState == VideoTaskState.ERROR || taskState == VideoTaskState.ENOSPC) {
                    task.infoLine
                } else {
                    ""
                }
            }
            val updated = task.copy(
                downloadStatus = taskState,
                completedAt = if (taskState.isTerminal()) now else task.completedAt,
                lastError = if (taskState.isFailure()) finalError else task.lastError,
                queuedForLater = if (taskState == VideoTaskState.PAUSE) task.queuedForLater else false,
                logPath = task.logPath.ifBlank { taskLogger.logPath(task.id) }
            )
            progressRepository.saveProgressInfo(updated)
            if (taskState.isFailure()) {
                taskLogger.error(taskId, "Download failed: ${finalError.ifBlank { "Unknown error" }}")
            } else {
                taskLogger.info(taskId, "Download finished with state $taskState")
            }
        }

        if (taskState == VideoTaskState.PAUSE || taskState.isTerminal()) {
            scheduleNextLocked()
        }
    }

    private fun scheduleNextLocked(): List<ProgressInfo> {
        val allTasks = progressRepository.getProgressInfosOnce()
        val activeCount = allTasks.count { it.isActive }
        val openSlots = (sharedPrefHelper.getMaxConcurrentDownloads() - activeCount).coerceAtLeast(0)
        if (openSlots == 0) {
            return emptyList()
        }

        val nextTasks = allTasks
            .filter { it.downloadStatus == VideoTaskState.PENDING && !it.queuedForLater }
            .queueSorted()
            .take(openSlots)

        val now = System.currentTimeMillis()
        nextTasks.forEach { task ->
            val shouldResume = task.startedAt > 0L ||
                task.progressDownloaded > 0L ||
                task.progressTotal > 0L
            val started = task.copy(
                downloadStatus = VideoTaskState.PREPARE,
                startedAt = if (task.startedAt == 0L) now else task.startedAt,
                logPath = task.logPath.ifBlank { taskLogger.logPath(task.id) }
            )
            progressRepository.saveProgressInfo(started)
            taskLogger.info(started.id, if (shouldResume) "Resuming queued download" else "Starting queued download")
            if (shouldResume) {
                engineRouter.resume(application, started)
            } else {
                engineRouter.start(application, started)
            }
        }

        return nextTasks
    }

    private fun findDuplicate(tasks: List<ProgressInfo>, fingerprint: String): ProgressInfo? {
        return tasks.firstOrNull { task ->
            !task.downloadStatus.isDuplicateAllowedTerminal() &&
                DownloadFingerprint.fromProgressInfo(task) == fingerprint
        }
    }

    private fun hasDownloadedFile(videoInfo: VideoInfo): Boolean {
        val expectedName = videoInfo.name
        return fileUtil.listFiles.keys.any { fileName ->
            fileName.equals(expectedName, ignoreCase = true)
        }
    }

    private fun applyFilenameTemplate(
        videoInfo: VideoInfo,
        filenameContext: DownloadFilenameTemplate.Context
    ): VideoInfo {
        val selectedFormat = VideoFormatUi.sortFormats(videoInfo.formats.formats).firstOrNull()
        return DownloadFilenameTemplate.apply(
            videoInfo = videoInfo,
            template = sharedPrefHelper.getDownloadFilenameTemplate(),
            selectedFormat = selectedFormat,
            context = filenameContext
        )
    }

    private fun moveBy(taskId: String, direction: Int) {
        val allTasks = normalizeQueuePositions(progressRepository.getProgressInfosOnce())
        val movable = allTasks.filter { it.canMoveInQueue }.queueSorted()
        val index = movable.indexOfFirst { it.id == taskId }
        val newIndex = index + direction
        if (index == -1 || newIndex !in movable.indices) {
            return
        }

        val reordered = movable.toMutableList().also { list ->
            val item = list.removeAt(index)
            list.add(newIndex, item)
        }
        saveReordered(allTasks, reordered)
        taskLogger.info(taskId, if (direction < 0) "Moved download up" else "Moved download down")
    }

    private fun normalizeQueuePositions(tasks: List<ProgressInfo>): List<ProgressInfo> {
        val movable = tasks.filter { it.canMoveInQueue }.queueSorted()
        if (movable.all { it.queuePosition > 0 }) {
            return tasks
        }
        val byId = movable.mapIndexed { index, task ->
            task.id to task.copy(queuePosition = (index + 1).toLong())
        }.toMap()
        val normalized = tasks.map { byId[it.id] ?: it }
        progressRepository.saveProgressInfos(normalized)
        return normalized
    }

    private fun saveReordered(allTasks: List<ProgressInfo>, reordered: List<ProgressInfo>) {
        val reorderedById = reordered.mapIndexed { index, task ->
            task.id to task.copy(queuePosition = (index + 1).toLong())
        }.toMap()
        progressRepository.saveProgressInfos(allTasks.map { reorderedById[it.id] ?: it })
    }

    private fun nextQueuePosition(tasks: List<ProgressInfo>): Long {
        return (tasks.maxOfOrNull { it.queuePosition } ?: 0L) + 1L
    }

    private fun ProgressInfo.withLogPath(): ProgressInfo {
        return if (logPath.isBlank()) copy(logPath = taskLogger.logPath(id)) else this
    }

    private fun List<ProgressInfo>.queueSorted(): List<ProgressInfo> {
        return sortedWith(
            compareBy<ProgressInfo> { if (it.queuePosition > 0) it.queuePosition else Long.MAX_VALUE }
                .thenBy { it.queuedAt }
                .thenBy { it.id }
        )
    }

    private fun Int.isFailure(): Boolean = this == VideoTaskState.ERROR || this == VideoTaskState.ENOSPC

    private fun Int.isTerminal(): Boolean =
        this == VideoTaskState.SUCCESS ||
            this == VideoTaskState.ERROR ||
            this == VideoTaskState.ENOSPC ||
            this == VideoTaskState.CANCELED

    private fun Int.isDuplicateAllowedTerminal(): Boolean =
        this == VideoTaskState.ERROR || this == VideoTaskState.CANCELED
}
