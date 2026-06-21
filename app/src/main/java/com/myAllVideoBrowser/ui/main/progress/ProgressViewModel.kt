package com.myAllVideoBrowser.ui.main.progress

import androidx.annotation.VisibleForTesting
import androidx.databinding.ObservableField
import androidx.lifecycle.viewModelScope
import com.myAllVideoBrowser.data.local.room.entity.ProgressInfo
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.data.repository.ProgressRepository
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.ui.main.base.BaseViewModel
import com.myAllVideoBrowser.util.AppLogger
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
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import javax.inject.Inject

//@OpenForTesting
class ProgressViewModel @Inject constructor(
    private val fileUtil: FileUtil,
    private val progressRepository: ProgressRepository,
    private val downloadQueueManager: DownloadQueueManager,
    private val downloadTaskLogger: DownloadTaskLogger,
) : BaseViewModel() {
    @VisibleForTesting
    internal val compositeDisposable: CompositeDisposable = CompositeDisposable()

    var progressInfos: ObservableField<List<ProgressInfo>> = ObservableField(emptyList())
    val downloadRejectedEvent = SingleLiveEvent<Int>()
    val downloadStartedEvent = SingleLiveEvent<Int>()
    val downloadDuplicateEvent = SingleLiveEvent<DownloadDuplicateEvent>()
    val downloadTaskDetailsEvent = SingleLiveEvent<DownloadTaskDetails>()
    val playlistEnqueueSummaryEvent = SingleLiveEvent<PlaylistEnqueueSummary>()
    private val executor2 = Executors.newFixedThreadPool(1).asCoroutineDispatcher()

    override fun start() {
        downloadProgressStartListen()
        viewModelScope.launch(executor2) {
            downloadQueueManager.scheduleNext()
        }
    }

    override fun stop() {
        compositeDisposable.clear()
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
        if (!fileUtil.folderDir.exists() && !fileUtil.folderDir.mkdirs()) {
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
                .map { list ->
                    list.filter { info -> info.downloadStatus != VideoTaskState.SUCCESS }
                }
                .subscribeOn(io.reactivex.rxjava3.schedulers.Schedulers.io())
                .observeOn(io.reactivex.rxjava3.android.schedulers.AndroidSchedulers.mainThread())
                .subscribe({ progressInfoList ->
                    progressInfos.set(sortProgressInfos(progressInfoList))
                }, { error ->
                    AppLogger.e("Progress: failed to observe progress list", error)
                })
        )
    }

    private fun sortProgressInfos(progressInfoList: List<ProgressInfo>): List<ProgressInfo> {
        return progressInfoList
            .filter { info ->
                info.downloadStatus != VideoTaskState.SUCCESS &&
                    info.downloadStatus != VideoTaskState.CANCELED
            }
            .sortedWith(
                compareBy<ProgressInfo> { if (it.queuePosition > 0) it.queuePosition else Long.MAX_VALUE }
                    .thenBy { it.queuedAt }
                    .thenBy { it.startedAt }
                    .thenBy { it.id }
            )
    }
}

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
