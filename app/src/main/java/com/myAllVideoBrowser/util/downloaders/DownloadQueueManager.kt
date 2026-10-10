package com.myAllVideoBrowser.util.downloaders

import androidx.work.WorkManager
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
import com.myAllVideoBrowser.util.downloaders.youtubedl_downloader.YoutubeDlRetryAction
import com.myAllVideoBrowser.util.downloaders.youtubedl_downloader.YoutubeDlStopReason
import com.myAllVideoBrowser.util.downloaders.youtubedl_downloader.youtubeDlRetryAction
import java.io.File
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
        val fingerprint = DownloadFingerprint.fromVideoInfo(templatedInfo)
        if (!force) {
            progressRepository.findDuplicateByFingerprint(fingerprint)?.let { existing ->
                taskLogger.info(existing.id, "Duplicate download rejected for ${templatedInfo.name}")
                return EnqueueResult.Duplicate(
                    existing = existing,
                    incoming = templatedInfo,
                    messageRes = R.string.download_duplicate_active
                )
            }
        }
        if (!force && fileUtil.hasDownloadWithName(application, templatedInfo.name)) {
            return EnqueueResult.Rejected(R.string.download_duplicate_file)
        }

        val allTasks = progressRepository.getProgressInfosOnce()
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
        if (task.isYtDlpTask && task.isActive) {
            val logPath = task.logPath.ifBlank { taskLogger.logPath(taskId) }
            if (progressRepository.requestYtDlpPause(
                    taskId,
                    task.executionToken,
                    YoutubeDlStopReason.PAUSE,
                    false,
                    "Pausing",
                    logPath
                ) == 1
            ) {
                taskLogger.info(task.id, "Pause requested for yt-dlp execution")
                progressRepository.getProgressInfoById(taskId)?.let {
                    runEngineControl(it, "pause") { engineRouter.pause(application, it) }
                }
            }
            return
        }
        val paused = task.copy(
            downloadStatus = VideoTaskState.PAUSE,
            queuedForLater = false,
            infoLine = "Paused"
        ).withLogPath()
        // 只更新队列状态列，不覆盖 Worker 正在写的进度列；logPath blank 时补，保持错误详情契约
        progressRepository.updateQueueState(
            taskId,
            VideoTaskState.PAUSE,
            false,
            "Paused",
            task.logPath.ifBlank { taskLogger.logPath(taskId) }
        )
        taskLogger.info(task.id, "Paused download")
        if (task.isActive) {
            runEngineControl(paused, "pause") { engineRouter.pause(application, paused) }
        }
        scheduleNextLocked()
    }

    @Synchronized
    fun resume(taskId: String) {
        val task = progressRepository.getProgressInfoById(taskId) ?: return
        val allTasks = progressRepository.getProgressInfosOnce()
        val newPosition = task.queuePosition.takeIf { it > 0 } ?: nextQueuePosition(allTasks)
        if (task.isYtDlpTask) {
            val logPath = task.logPath.ifBlank { taskLogger.logPath(taskId) }
            val publishedTargetExists = task.finalizationTarget.isNotBlank() && runCatching {
                fileUtil.resolveMediaUri(application, File(task.finalizationTarget)) != null
            }.onFailure { error ->
                taskLogger.warn(
                    task.id,
                    "Unable to inspect the previous yt-dlp publication target; " +
                        "retry will use the remaining source or requeue the download",
                    error
                )
            }.getOrDefault(false)
            if (youtubeDlRetryAction(task, publishedTargetExists) ==
                YoutubeDlRetryAction.RETRY_PUBLICATION
            ) {
                val claimed = progressRepository.retryYtDlpFinalization(
                    taskId,
                    task.executionToken,
                    logPath
                )
                check(claimed in 0..1) { "yt-dlp publication retry updated $claimed rows" }
                if (claimed == 1) {
                    taskLogger.info(task.id, "Retrying publication from existing downloaded media")
                    progressRepository.getProgressInfoById(task.id)?.let {
                        engineRouter.recoverFinalization(application, it)
                    }
                }
                return
            }
            if (progressRepository.resumeYtDlp(
                    taskId,
                    newPosition,
                    logPath
                ) == 1
            ) {
                taskLogger.info(task.id, "Resumed yt-dlp download into queue")
                scheduleNextLocked()
            }
            return
        }
        // 队列状态 + 位置按列更新，不覆盖进度列；logPath blank 时补
        progressRepository.updateQueueState(
            taskId,
            VideoTaskState.PENDING,
            false,
            "Queued",
            task.logPath.ifBlank { taskLogger.logPath(taskId) }
        )
        progressRepository.updateQueuePosition(taskId, newPosition)
        taskLogger.info(task.id, "Resumed download into queue")
        scheduleNextLocked()
    }

    @Synchronized
    fun cancel(taskId: String, removeFile: Boolean) {
        val task = progressRepository.getProgressInfoById(taskId) ?: return
        taskLogger.info(task.id, "Canceled download removeFile=$removeFile")
        if (task.isYtDlpTask) {
            val token = task.executionToken.ifBlank { UUID.randomUUID().toString() }
            if (progressRepository.requestYtDlpCancel(
                    task.id,
                    task.executionToken,
                    token,
                    removeFile,
                    task.logPath.ifBlank { taskLogger.logPath(task.id) }
                ) == 1
            ) {
                progressRepository.getProgressInfoById(task.id)?.let {
                    runEngineControl(it, "cancel") { engineRouter.cancel(application, it, removeFile) }
                }
            }
            return
        }
        if (task.isActive || task.downloadStatus == VideoTaskState.PAUSE) {
            runEngineControl(task, "cancel") { engineRouter.cancel(application, task, removeFile) }
        }
        progressRepository.deleteProgressInfo(task)
        scheduleNextLocked()
    }

    @Synchronized
    fun stopAndSave(taskId: String) {
        val task = progressRepository.getProgressInfoById(taskId) ?: return
        taskLogger.info(task.id, "Stop and save requested")
        if (task.isYtDlpTask) {
            val logPath = task.logPath.ifBlank { taskLogger.logPath(task.id) }
            if (task.isActive && progressRepository.requestYtDlpPause(
                    task.id,
                    task.executionToken,
                    YoutubeDlStopReason.STOP_AND_SAVE,
                    false,
                    "Stopping and saving",
                    logPath
                ) == 1
            ) {
                progressRepository.getProgressInfoById(task.id)?.let {
                    runEngineControl(it, "stop-and-save") { engineRouter.stopAndSave(application, it) }
                }
            }
            return
        }
        runEngineControl(task, "stop-and-save") { engineRouter.stopAndSave(application, task) }
    }

    @Synchronized
    fun markLater(taskId: String) {
        val task = progressRepository.getProgressInfoById(taskId) ?: return
        if (task.isYtDlpTask && task.isActive) {
            val logPath = task.logPath.ifBlank { taskLogger.logPath(task.id) }
            if (progressRepository.requestYtDlpPause(
                    task.id,
                    task.executionToken,
                    YoutubeDlStopReason.PAUSE,
                    true,
                    "Saving for later",
                    logPath
                ) == 1
            ) {
                taskLogger.info(task.id, "Move to later requested for yt-dlp execution")
                progressRepository.getProgressInfoById(task.id)?.let {
                    runEngineControl(it, "pause") { engineRouter.pause(application, it) }
                }
            }
            return
        }
        val later = task.copy(
            downloadStatus = VideoTaskState.PAUSE,
            queuedForLater = true,
            infoLine = "Saved for later"
        ).withLogPath()
        // 只更新队列状态列，不覆盖进度列；logPath blank 时补
        progressRepository.updateQueueState(
            taskId,
            VideoTaskState.PAUSE,
            true,
            "Saved for later",
            task.logPath.ifBlank { taskLogger.logPath(taskId) }
        )
        taskLogger.info(task.id, "Moved download to later")
        if (task.isActive) {
            runEngineControl(later, "pause") { engineRouter.pause(application, later) }
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
        saveReordered(reordered)
        taskLogger.info(taskId, "Moved download to top")
    }

    @Synchronized
    fun onTaskTerminal(taskId: String, taskState: Int, errorMessage: String? = null) {
        markTerminalLocked(taskId, taskState, errorMessage)
        if (taskState == VideoTaskState.PAUSE || taskState.isTerminal()) {
            scheduleNextLocked()
        }
    }

    @Synchronized
    fun onYtDlpTerminal() {
        scheduleNextLocked()
    }

    /**
     * 控制类动作（pause / cancel / stop-and-save / mark-later）不得因为数据损坏把界面炸掉。
     *
     * 这些调用点在引入 `SelectedFormatSelector` 的 fail-fast 之前是不可能抛的（旧代码直接 `formats.first()`），
     * 而且它们不是下载入口，选错清晰度的代价不存在；所以失败只记日志 + 按用户意图推进状态，
     * 不静默吞：异常带 taskId 进 `DownloadTaskLogger`，用户在错误详情里能看到。
     * 真正的下载入口（[scheduleNextLocked] 里的 start/resume）仍然保持 fail fast → ERROR。
     */
    private fun runEngineControl(task: ProgressInfo, action: String, block: () -> Unit) {
        try {
            block()
        } catch (error: Throwable) {
            taskLogger.error(task.id, "Engine control $action failed", error)
        }
    }

    /**
     * 启动对账：处理「Room 里状态是 ACTIVE，但已经没有对应 Worker」的遗留任务。
     *
     * 死档的根因：升级/覆盖安装把下载进程干掉后，这些行会永久停在
     * PREPARE/START/DOWNLOADING/PROXYREADY/...；而 [scheduleNextLocked] 只挑 PENDING，
     * 于是它们既不会再被调度，又一直占着并发槽位（任务不动、新任务也进不来）。
     *
     * 只负责 Custom / SuperX：yt-dlp 由 YoutubeDlRecoveryCoordinator 对账，
     * 两处都启动同一个任务会互相 REPLACE 掉正在跑的 Worker。
     *
     * @return 实际放回队列（PENDING）的任务 id。
     */
    @Synchronized
    fun reconcileOrphans(): List<String> {
        val candidates = progressRepository.getProgressInfosOnce()
            .filter { it.occupiesQueueSlot && it.engineKind != DownloadEngineKind.YTDLP }
        if (candidates.isEmpty()) {
            return emptyList()
        }

        val resumed = mutableListOf<String>()
        var changed = false
        candidates.forEach { task ->
            if (workStillActive(task)) {
                taskLogger.info(
                    task.id,
                    "Startup reconciliation: worker is still scheduled, skipping status ${task.downloadStatus}"
                )
                return@forEach
            }

            val logPath = task.logPath.ifBlank { taskLogger.logPath(task.id) }
            // 用户明确放在“稍后”的任务不自动起跑，但必须把槽位释放掉。
            val decision = if (task.queuedForLater &&
                orphanRecoveryDecision(task.downloadStatus) == OrphanRecoveryDecision.RESUME
            ) {
                OrphanRecoveryDecision.MARK_PAUSED
            } else {
                orphanRecoveryDecision(task.downloadStatus)
            }

            when (decision) {
                OrphanRecoveryDecision.RESUME -> {
                    progressRepository.updateQueueState(
                        task.id,
                        VideoTaskState.PENDING,
                        false,
                        "Queued",
                        logPath
                    )
                    resumed += task.id
                    changed = true
                    taskLogger.info(
                        task.id,
                        "Startup reconciliation: resumed orphaned download from status ${task.downloadStatus}"
                    )
                }

                OrphanRecoveryDecision.MARK_PAUSED -> {
                    progressRepository.updateQueueState(
                        task.id,
                        VideoTaskState.PAUSE,
                        task.queuedForLater,
                        "Paused",
                        logPath
                    )
                    changed = true
                    taskLogger.info(task.id, "Startup reconciliation: restored interrupted pause")
                }

                OrphanRecoveryDecision.MARK_CANCELED -> {
                    progressRepository.updateQueueState(
                        task.id,
                        VideoTaskState.CANCELED,
                        false,
                        "Canceled",
                        logPath
                    )
                    changed = true
                    taskLogger.info(task.id, "Startup reconciliation: finished interrupted cancel")
                }

                OrphanRecoveryDecision.MARK_ERROR -> {
                    val reason = "Interrupted while publishing the file; the app was upgraded mid-download."
                    progressRepository.updateQueueState(
                        task.id,
                        VideoTaskState.ERROR,
                        false,
                        reason,
                        logPath
                    )
                    changed = true
                    taskLogger.error(task.id, "Startup reconciliation: $reason")
                }

                OrphanRecoveryDecision.SKIP -> Unit
            }
        }

        if (changed) {
            scheduleNextLocked()
        }
        return resumed
    }

    /**
     * 该任务的 WorkManager 作业是否还在排队/运行中。
     *
     * 查询失败时宁可“漏恢复”也不能“双启动”：Custom/SuperX 的 unique work 都是 REPLACE，
     * 盲目重发会把还活着的 Worker 取消掉。
     */
    private fun workStillActive(task: ProgressInfo): Boolean {
        return try {
            WorkManager.getInstance(application)
                .getWorkInfosForUniqueWork(task.videoInfo.id)
                .get()
                .any { !it.state.isFinished }
        } catch (error: Throwable) {
            taskLogger.warn(
                task.id,
                "Unable to inspect WorkManager state during startup reconciliation",
                error
            )
            true
        }
    }

    // 只更新终态并落库，不触发调度。供 scheduleNextLocked 在 forEach 内 catch 使用，
    // 避免 catch 内递归调度导致外层 forEach 旧快照里的后续任务被重复启动。
    private fun markTerminalLocked(taskId: String, taskState: Int, errorMessage: String?) {
        val task = progressRepository.getProgressInfoById(taskId) ?: return
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

    private fun scheduleNextLocked(): List<ProgressInfo> {
        val allTasks = progressRepository.getProgressInfosOnce()
        val activeCount = allTasks.count { it.occupiesQueueSlot }
        val openSlots = (sharedPrefHelper.getMaxConcurrentDownloads() - activeCount).coerceAtLeast(0)
        if (openSlots == 0) {
            return emptyList()
        }

        val nextTasks = allTasks
            .filter { it.downloadStatus == VideoTaskState.PENDING && !it.queuedForLater }
            .queueSorted()
            .take(openSlots)

        val now = System.currentTimeMillis()
        var anyStartFailed = false
        val startedSuccessfully = mutableListOf<ProgressInfo>()
        nextTasks.forEach { task ->
            val shouldResume = task.startedAt > 0L ||
                task.progressDownloaded > 0L ||
                task.progressTotal > 0L
            val logPath = task.logPath.ifBlank { taskLogger.logPath(task.id) }
            val started = if (task.isYtDlpTask) {
                val token = UUID.randomUUID().toString()
                if (progressRepository.claimYtDlpExecution(task.id, token, now, logPath) != 1) {
                    return@forEach
                }
                progressRepository.getProgressInfoById(task.id) ?: return@forEach
            } else {
                task.copy(
                    downloadStatus = VideoTaskState.PREPARE,
                    startedAt = if (task.startedAt == 0L) now else task.startedAt,
                    logPath = logPath
                ).also(progressRepository::saveProgressInfo)
            }
            taskLogger.info(started.id, if (shouldResume) "Resuming queued download" else "Starting queued download")
            try {
                if (shouldResume) {
                    engineRouter.resume(application, started)
                } else {
                    engineRouter.start(application, started)
                }
                startedSuccessfully += started
            } catch (e: Throwable) {
                // 引擎启动失败：仅标记终态（不在此处递归调度），避免外层 forEach 旧快照里的
                // 后续任务在递归 scheduleNextLocked 中被启动后又被本循环重复启动
                taskLogger.error(started.id, "Failed to start download, marking ERROR", e)
                val error = "Failed to start: ${e.message ?: "unknown"}"
                if (started.isYtDlpTask) {
                    progressRepository.commitYtDlpError(
                        started.id,
                        started.executionToken,
                        System.currentTimeMillis(),
                        error
                    )
                } else {
                    markTerminalLocked(started.id, VideoTaskState.ERROR, error)
                }
                anyStartFailed = true
            }
        }

        // 本轮有启动失败 → 其释放的并发槽位在循环结束后统一推进一次；
        // 此时 forEach 已结束，不会再触碰旧快照，因此不会重复启动后续任务
        if (anyStartFailed) {
            scheduleNextLocked()
        }

        return startedSuccessfully
    }

    private fun applyFilenameTemplate(
        videoInfo: VideoInfo,
        filenameContext: DownloadFilenameTemplate.Context
    ): VideoInfo {
        val selectedFormat = VideoFormatUi.sortFormats(videoInfo, videoInfo.formats.formats).firstOrNull()
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
        saveReordered(reordered)
        taskLogger.info(taskId, if (direction < 0) "Moved download up" else "Moved download down")
    }

    private fun normalizeQueuePositions(tasks: List<ProgressInfo>): List<ProgressInfo> {
        val movable = tasks.filter { it.canMoveInQueue }.queueSorted()
        if (movable.all { it.queuePosition > 0 }) {
            return tasks
        }
        // 只更新 movable 的 queuePosition 列，避免整行 REPLACE 覆盖 Worker 正在写的进度
        movable.forEachIndexed { index, task ->
            progressRepository.updateQueuePosition(task.id, (index + 1).toLong())
        }
        val byId = movable.mapIndexed { index, task ->
            task.id to task.copy(queuePosition = (index + 1).toLong())
        }.toMap()
        return tasks.map { byId[it.id] ?: it }
    }

    private fun saveReordered(reordered: List<ProgressInfo>) {
        // 只更新 queuePosition 列，不覆盖进度等其它列
        reordered.forEachIndexed { index, task ->
            progressRepository.updateQueuePosition(task.id, (index + 1).toLong())
        }
    }

    private fun nextQueuePosition(tasks: List<ProgressInfo>): Long {
        return (tasks.maxOfOrNull { it.queuePosition } ?: 0L) + 1L
    }

    private fun ProgressInfo.withLogPath(): ProgressInfo {
        return if (logPath.isBlank()) copy(logPath = taskLogger.logPath(id)) else this
    }

    // 引擎判据的唯一来源：与 DownloadEngineRouter / 启动对账 / 指纹保持一致。
    // 绝不能再写 !videoInfo.isRegularDownload && !videoInfo.isDetectedBySuperX。
    private val ProgressInfo.engineKind: DownloadEngineKind
        get() = DownloadEngineKindResolver.kindOf(this)

    private val ProgressInfo.isYtDlpTask: Boolean
        get() = engineKind == DownloadEngineKind.YTDLP

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

}
