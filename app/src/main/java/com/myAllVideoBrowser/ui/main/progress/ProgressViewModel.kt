package com.myAllVideoBrowser.ui.main.progress

import androidx.annotation.VisibleForTesting
import androidx.databinding.ObservableField
import androidx.lifecycle.viewModelScope
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import com.myAllVideoBrowser.data.local.room.entity.BrowserFileDownload
import com.myAllVideoBrowser.data.local.room.entity.ProgressInfo
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.data.repository.BrowserFileDownloadRepository
import com.myAllVideoBrowser.data.repository.ProgressRepository
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.ui.main.base.BaseViewModel
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.ContextUtils
import com.myAllVideoBrowser.util.FileUtil
import com.myAllVideoBrowser.util.SingleLiveEvent
import com.myAllVideoBrowser.util.DownloadFilenameTemplate
import com.myAllVideoBrowser.util.PlaylistExtractor
import com.myAllVideoBrowser.util.downloaders.DownloadQueueManager
import com.myAllVideoBrowser.util.downloaders.DownloadTaskLogger
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskState
import io.reactivex.rxjava3.disposables.CompositeDisposable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import javax.inject.Inject

//@OpenForTesting
class ProgressViewModel @Inject constructor(
    private val fileUtil: FileUtil,
    private val progressRepository: ProgressRepository,
    private val browserFileDownloadRepository: BrowserFileDownloadRepository,
    private val downloadQueueManager: DownloadQueueManager,
    private val downloadTaskLogger: DownloadTaskLogger,
) : BaseViewModel() {
    @VisibleForTesting
    internal val compositeDisposable: CompositeDisposable = CompositeDisposable()

    var progressInfos: ObservableField<List<ProgressInfo>> = ObservableField(emptyList())
    /**
     * 下载历史：所有 `SUCCESS` 记录，永久保留。
     * 删除本地文件只清 `finalMediaUri`，记录仍留在这里；只有用户显式删除记录才移除。
     */
    var historyInfos: ObservableField<List<ProgressInfo>> = ObservableField(emptyList())
    val browserFileDownloads: ObservableField<List<BrowserFileDownload>> = ObservableField(emptyList())
    val selectedDownloadSection: ObservableField<DownloadSection> =
        ObservableField(DownloadSection.MEDIA)
    val downloadRejectedEvent = SingleLiveEvent<Int>()
    val downloadStartedEvent = SingleLiveEvent<Int>()
    val downloadDuplicateEvent = SingleLiveEvent<DownloadDuplicateEvent>()
    val downloadTaskDetailsEvent = SingleLiveEvent<DownloadTaskDetails>()
    val playlistEnqueueSummaryEvent = SingleLiveEvent<PlaylistEnqueueSummary>()
    val mediaEnqueueSummaryEvent = SingleLiveEvent<MediaEnqueueSummary>()
    val browserFileLaunchEvent = SingleLiveEvent<BrowserFileLaunchRequest>()
    val browserFileMessageEvent = SingleLiveEvent<Int>()
    val historyMessageEvent = SingleLiveEvent<Int>()
    val deleteHistoryAuthEvent = SingleLiveEvent<IntentSender>()
    private var pendingHistoryDelete: PendingHistoryDelete? = null
    private val executor2 = Executors.newFixedThreadPool(1).asCoroutineDispatcher()
    private var browserFileSyncJob: Job? = null
    @Volatile
    private var isDownloadScreenVisible = false

    override fun start() {
        downloadProgressStartListen()
        browserFileDownloadStartListen()
        startBrowserFileSync()
        viewModelScope.launch(executor2) {
            downloadQueueManager.scheduleNext()
        }
    }

    override fun stop() {
        compositeDisposable.clear()
        browserFileSyncJob?.cancel()
        executor2.cancel()
    }

    fun stopAndSaveDownload(id: Long) {
        val inf = progressInfos.get()?.find { it.downloadId == id }
        inf?.let {
            viewModelScope.launch(executor2) {
                downloadQueueManager.stopAndSave(it.id)
            }
        }
    }

    fun cancelDownload(id: Long, removeFile: Boolean) {
        val inf = progressInfos.get()?.find { it.downloadId == id }
        inf?.let {
            viewModelScope.launch(executor2) {
                downloadQueueManager.cancel(it.id, removeFile)
            }
        }
    }

    fun pauseDownload(id: Long) {
        val inf = progressInfos.get()?.find { it.downloadId == id }
        inf?.let {
            viewModelScope.launch(executor2) {
                downloadQueueManager.pause(it.id)
            }
        }
    }

    fun resumeDownload(id: Long) {
        val inf = progressInfos.get()?.find { it.downloadId == id }
        inf?.let {
            viewModelScope.launch(executor2) {
                downloadQueueManager.resume(it.id)
            }
        }
    }

    fun downloadVideo(videoInfo: VideoInfo?) {
        videoInfo?.let {
            if (!canCreateDownload()) {
                return
            }

            enqueueVideo(videoInfo, force = false)
        }
    }

    fun forceDownloadVideo(videoInfo: VideoInfo) {
        if (!canCreateDownload()) {
            return
        }
        enqueueVideo(videoInfo, force = true)
    }

    fun downloadPlaylistItems(items: List<PlaylistExtractor.PlaylistDownloadItem>) {
        if (items.isEmpty()) {
            return
        }
        if (!canCreateDownload()) {
            return
        }
        viewModelScope.launch(executor2) {
            var accepted = 0
            var duplicates = 0
            var rejected = 0
            items.forEach { item ->
                val context = DownloadFilenameTemplate.Context(
                    playlistIndex = item.playlistIndex,
                    playlistTitle = item.playlistTitle
                )
                when (downloadQueueManager.enqueue(item.videoInfo, force = false, filenameContext = context)) {
                    is DownloadQueueManager.EnqueueResult.Accepted -> accepted += 1
                    is DownloadQueueManager.EnqueueResult.Duplicate -> duplicates += 1
                    is DownloadQueueManager.EnqueueResult.Rejected -> rejected += 1
                }
            }
            viewModelScope.launch {
                playlistEnqueueSummaryEvent.value = PlaylistEnqueueSummary(
                    accepted = accepted,
                    duplicates = duplicates,
                    rejected = rejected
                )
            }
        }
    }

    fun downloadMediaItems(items: List<VideoInfo>) {
        if (items.isEmpty() || !canCreateDownload()) {
            return
        }
        viewModelScope.launch(executor2) {
            var accepted = 0
            var duplicates = 0
            var rejected = 0
            items.forEach { item ->
                when (downloadQueueManager.enqueue(item, force = false)) {
                    is DownloadQueueManager.EnqueueResult.Accepted -> accepted += 1
                    is DownloadQueueManager.EnqueueResult.Duplicate -> duplicates += 1
                    is DownloadQueueManager.EnqueueResult.Rejected -> rejected += 1
                }
            }
            viewModelScope.launch {
                mediaEnqueueSummaryEvent.value = MediaEnqueueSummary(
                    accepted = accepted,
                    duplicates = duplicates,
                    rejected = rejected
                )
            }
        }
    }

    fun moveDownloadUp(downloadId: Long) {
        progressInfos.get()?.find { it.downloadId == downloadId }?.let { task ->
            viewModelScope.launch(executor2) { downloadQueueManager.moveUp(task.id) }
        }
    }

    fun moveDownloadDown(downloadId: Long) {
        progressInfos.get()?.find { it.downloadId == downloadId }?.let { task ->
            viewModelScope.launch(executor2) { downloadQueueManager.moveDown(task.id) }
        }
    }

    fun moveDownloadToTop(downloadId: Long) {
        progressInfos.get()?.find { it.downloadId == downloadId }?.let { task ->
            viewModelScope.launch(executor2) { downloadQueueManager.moveToTop(task.id) }
        }
    }

    fun markDownloadLater(downloadId: Long) {
        progressInfos.get()?.find { it.downloadId == downloadId }?.let { task ->
            viewModelScope.launch(executor2) { downloadQueueManager.markLater(task.id) }
        }
    }

    fun showFileDownloads() {
        selectedDownloadSection.set(DownloadSection.FILES)
        startBrowserFileSync()
    }

    fun showMediaDownloads() {
        selectedDownloadSection.set(DownloadSection.MEDIA)
    }

    fun setDownloadScreenVisible(visible: Boolean) {
        isDownloadScreenVisible = visible
        if (visible) startBrowserFileSync()
    }

    fun refreshBrowserFileDownloads() {
        startBrowserFileSync()
    }

    fun openBrowserFile(download: BrowserFileDownload, share: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val uri = browserFileDownloadRepository.getContentUri(download)
            if (uri == null) {
                viewModelScope.launch {
                    browserFileMessageEvent.value = R.string.browser_file_missing
                }
                return@launch
            }
            viewModelScope.launch {
                browserFileLaunchEvent.value = BrowserFileLaunchRequest(
                    uri = uri,
                    mimeType = download.mimeType.ifBlank { "application/octet-stream" },
                    share = share
                )
            }
        }
    }

    fun cancelBrowserFile(download: BrowserFileDownload) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { browserFileDownloadRepository.cancel(download) }
                .onFailure { AppLogger.e("Downloads: failed to cancel browser file", it) }
        }
    }

    fun retryBrowserFile(download: BrowserFileDownload) {
        viewModelScope.launch(Dispatchers.IO) {
            browserFileDownloadRepository.retry(download)
                .onSuccess {
                    viewModelScope.launch {
                        browserFileMessageEvent.value = R.string.browser_file_retry_started
                    }
                }
                .onFailure {
                    AppLogger.e("Downloads: failed to retry browser file", it)
                    viewModelScope.launch {
                        browserFileMessageEvent.value = R.string.browser_download_failed
                    }
                }
        }
    }

    fun deleteBrowserFile(download: BrowserFileDownload, deleteLocalFile: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { browserFileDownloadRepository.delete(download, deleteLocalFile) }
                .onFailure {
                    AppLogger.e("Downloads: failed to delete browser file record", it)
                    viewModelScope.launch {
                        browserFileMessageEvent.value = R.string.browser_file_delete_failed
                    }
                }
        }
    }

    fun deleteHistoryRecord(task: ProgressInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { progressRepository.deleteProgressInfo(task) }
                .onFailure { AppLogger.e("Downloads: failed to delete history record", it) }
        }
    }

    /** 只删本地文件；历史记录与来源网页保留。 */
    fun deleteHistoryFile(task: ProgressInfo) {
        val uriString = task.finalMediaUri
        if (uriString.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val context = ContextUtils.getApplicationContext()
            when (val result = fileUtil.deleteMedia(context, Uri.parse(uriString))) {
                is FileUtil.DeleteMediaResult.Success -> {
                    progressRepository.clearFinalMediaUri(uriString)
                }

                is FileUtil.DeleteMediaResult.NeedsAuth -> {
                    pendingHistoryDelete = PendingHistoryDelete(
                        originalUriString = uriString,
                        retryUri = result.retryUri,
                        verificationUri = result.verificationUri
                    )
                    viewModelScope.launch {
                        deleteHistoryAuthEvent.value = result.intentSender
                    }
                }

                is FileUtil.DeleteMediaResult.Failed -> {
                    viewModelScope.launch {
                        historyMessageEvent.value = R.string.progress_history_delete_failed
                    }
                }
            }
        }
    }

    fun onDeleteHistoryAuthResult(context: Context, granted: Boolean) {
        val pending = pendingHistoryDelete
        pendingHistoryDelete = null
        if (!granted) {
            return
        }
        if (pending == null) {
            historyMessageEvent.value = R.string.progress_history_delete_failed
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val deleted = if (pending.retryUri == null) {
                fileUtil.isUriDefinitelyAbsent(context, pending.verificationUri)
            } else {
                when (fileUtil.deleteMedia(context, pending.retryUri)) {
                    is FileUtil.DeleteMediaResult.Success -> true
                    is FileUtil.DeleteMediaResult.NeedsAuth -> false
                    is FileUtil.DeleteMediaResult.Failed -> false
                }
            }
            if (deleted) {
                progressRepository.clearFinalMediaUri(pending.originalUriString)
            } else {
                viewModelScope.launch {
                    historyMessageEvent.value = R.string.progress_history_delete_failed
                }
            }
        }
    }

    private data class PendingHistoryDelete(
        val originalUriString: String,
        val retryUri: Uri?,
        val verificationUri: Uri
    )

    fun openTaskDetails(downloadId: Long) {
        val task = progressInfos.get()?.find { it.downloadId == downloadId } ?: return
        viewModelScope.launch(executor2) {
            val details = DownloadTaskDetails(
                taskId = task.id,
                title = task.videoInfo.name,
                status = task.downloadStatusFormatted,
                error = task.lastError.ifBlank { task.infoLine },
                logText = downloadTaskLogger.readTail(task.id),
                logPath = task.logPath.ifBlank { downloadTaskLogger.logPath(task.id) }
            )
            viewModelScope.launch {
                downloadTaskDetailsEvent.value = details
            }
        }
    }

    private fun enqueueVideo(videoInfo: VideoInfo, force: Boolean) {
        viewModelScope.launch(executor2) {
            when (val result = downloadQueueManager.enqueue(videoInfo, force)) {
                is DownloadQueueManager.EnqueueResult.Accepted -> {
                    viewModelScope.launch {
                        downloadStartedEvent.value =
                            if (result.startedNow) R.string.download_started else R.string.download_queued
                    }
                }

                is DownloadQueueManager.EnqueueResult.Duplicate -> {
                    viewModelScope.launch {
                        downloadDuplicateEvent.value = DownloadDuplicateEvent(
                            existingDownloadId = result.existing.downloadId,
                            incomingVideoInfo = result.incoming,
                            messageRes = result.messageRes
                        )
                    }
                }

                is DownloadQueueManager.EnqueueResult.Rejected -> {
                    viewModelScope.launch {
                        downloadRejectedEvent.value = result.messageRes
                    }
                }
            }
        }
    }

    private fun canCreateDownload(): Boolean {
        if (!fileUtil.ensureDownloadDestination()) {
            return false
        }

        if (!fileUtil.isFreeSpaceAvailable()) {
            downloadRejectedEvent.value = R.string.download_no_free_space
            return false
        }

        return true
    }

    @VisibleForTesting
    internal fun downloadProgressStartListen() {
        compositeDisposable.clear()
        compositeDisposable.add(
            progressRepository.getProgressInfos()
                .map { list -> list.partition { it.downloadStatus == VideoTaskState.SUCCESS } }
                .subscribeOn(io.reactivex.rxjava3.schedulers.Schedulers.io())
                .observeOn(io.reactivex.rxjava3.android.schedulers.AndroidSchedulers.mainThread())
                .subscribe({ (completed, pending) ->
                    progressInfos.set(sortProgressInfos(pending))
                    historyInfos.set(completed.sortedByDescending { it.completedAt })
                }, { error ->
                    AppLogger.e("Progress: failed to observe progress list", error)
                })
        )
    }

    @VisibleForTesting
    internal fun browserFileDownloadStartListen() {
        compositeDisposable.add(
            browserFileDownloadRepository.observeDownloads()
                .subscribeOn(io.reactivex.rxjava3.schedulers.Schedulers.io())
                .observeOn(io.reactivex.rxjava3.android.schedulers.AndroidSchedulers.mainThread())
                .subscribe({ downloads ->
                    browserFileDownloads.set(downloads)
                }, { error ->
                    AppLogger.e("Downloads: failed to observe browser file list", error)
                })
        )
    }

    private fun startBrowserFileSync() {
        browserFileSyncJob?.cancel()
        browserFileSyncJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                val requiresFrequentSync = runCatching { browserFileDownloadRepository.refreshAll() }
                    .onFailure { AppLogger.e("Downloads: browser file status sync failed", it) }
                    .getOrDefault(false)
                val fileSectionVisible = isDownloadScreenVisible &&
                    selectedDownloadSection.get() == DownloadSection.FILES
                delay(if (requiresFrequentSync && fileSectionVisible) 1_500L else 15_000L)
            }
        }
    }

    private fun sortProgressInfos(progressInfoList: List<ProgressInfo>): List<ProgressInfo> {
        return progressInfoList
            .filter { info ->
                info.downloadStatus != VideoTaskState.SUCCESS &&
                    info.downloadStatus != VideoTaskState.CANCELED
            }
            .sortedWith(
                compareBy<ProgressInfo> { activePriority(it.downloadStatus) }
                    .thenBy { if (it.queuePosition > 0) it.queuePosition else Long.MAX_VALUE }
                    .thenBy { it.queuedAt }
                    .thenBy { it.startedAt }
                    .thenBy { it.id }
            )
    }

    /** “进行中”内部的优先级：需处理 → 已暂停 → 正在执行 → 排队。 */
    private fun activePriority(status: Int): Int = when (status) {
        VideoTaskState.ERROR, VideoTaskState.ENOSPC -> 0
        VideoTaskState.PAUSE, VideoTaskState.PAUSING -> 1
        VideoTaskState.DOWNLOADING,
        VideoTaskState.PROXYREADY,
        VideoTaskState.PREPARE,
        VideoTaskState.START,
        VideoTaskState.FINALIZING -> 2
        VideoTaskState.PENDING -> 3
        else -> 4
    }
}

enum class DownloadSection {
    FILES,
    MEDIA
}

data class BrowserFileLaunchRequest(
    val uri: Uri,
    val mimeType: String,
    val share: Boolean
)

data class DownloadDuplicateEvent(
    val existingDownloadId: Long,
    val incomingVideoInfo: VideoInfo,
    val messageRes: Int
)

data class DownloadTaskDetails(
    val taskId: String,
    val title: String,
    val status: String,
    val error: String,
    val logText: String,
    val logPath: String
)

data class PlaylistEnqueueSummary(
    val accepted: Int,
    val duplicates: Int,
    val rejected: Int
)

data class MediaEnqueueSummary(
    val accepted: Int,
    val duplicates: Int,
    val rejected: Int
)
