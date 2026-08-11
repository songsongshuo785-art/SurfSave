package com.myAllVideoBrowser.ui.main.video

import android.content.Context
import android.content.IntentSender
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.databinding.ObservableField
import androidx.lifecycle.viewModelScope
//import com.allVideoDownloaderXmaster.OpenForTesting
import com.myAllVideoBrowser.data.local.model.LocalVideo
import com.myAllVideoBrowser.data.local.room.entity.ProgressInfo
import com.myAllVideoBrowser.data.repository.ProgressRepository
import com.myAllVideoBrowser.ui.main.base.BaseViewModel
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.ContextUtils
import com.myAllVideoBrowser.util.FileUtil
import com.myAllVideoBrowser.util.FileUtil.DeleteMediaResult
import com.myAllVideoBrowser.util.FileUtil.RenameMediaResult
import com.myAllVideoBrowser.util.SingleLiveEvent
import com.myAllVideoBrowser.util.VideoFormatUi
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskState
import io.reactivex.rxjava3.core.Observable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import javax.inject.Inject

//@OpenForTesting
class VideoViewModel @Inject constructor(
    private val fileUtil: FileUtil,
    private val progressRepository: ProgressRepository,
) : BaseViewModel() {

    companion object {
        const val FILE_EXIST_ERROR_CODE = 1
        const val FILE_INVALID_ERROR_CODE = 2
        private const val DEFAULT_VIDEO_FRAME_MICROS = 1_000_000L
        private const val LONG_VIDEO_FRAME_MICROS = 3_500_000L
        private const val SHORT_VIDEO_FRAME_MICROS = 250_000L
    }

    var localVideos: ObservableField<MutableList<LocalVideo>> = ObservableField(mutableListOf())

    val renameErrorEvent = SingleLiveEvent<Int>()
    val renameAuthEvent = SingleLiveEvent<IntentSender>()
    val renameAuthCancelledEvent = SingleLiveEvent<Unit>()
    val renameSuccessEvent = SingleLiveEvent<Unit>()
    val shareEvent = SingleLiveEvent<Uri>()
    val deleteAuthEvent = SingleLiveEvent<IntentSender>()
    val deleteFailedEvent = SingleLiveEvent<Unit>()
    val deleteSuccessEvent = SingleLiveEvent<Unit>()
    val deleteAuthCancelledEvent = SingleLiveEvent<Unit>()
    private var pendingDelete: PendingDelete? = null
    private var pendingRename: PendingRename? = null
    private val thumbnailFrameMicrosCache = mutableMapOf<String, Long>()

    override fun start() {
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                delay(1000)
                val newList = getFilesList().toMutableList()
                newList.sortBy { it.uri }
                localVideos.set(newList)
            }
        }
    }


    override fun stop() {
    }

    private fun getFilesList(): List<LocalVideo> {
        val listVideos: MutableList<LocalVideo> = mutableListOf()
        val completedProgressByName = loadCompletedProgressByName()
        val validCacheKeys = mutableSetOf<String>()
        fileUtil.listFiles.forEach { entry ->
            val fileUri = entry.uri
            val fileSize = fileUtil.getContentLength(ContextUtils.getApplicationContext(), fileUri)
            val readableSize = FileUtil.getFileSizeReadable(fileSize.toDouble())
            val progressInfo = completedProgressByName[normalizeFileName(entry.displayName)]
            val cacheKey = fileUri.toString()
            validCacheKeys += cacheKey
            val video = LocalVideo(
                entry.id,
                fileUri,
                entry.displayName
            )
            video.size = readableSize
            video.quality = progressInfo?.let { resolveQuality(it) }.orEmpty()
            video.sourceUrl = progressInfo?.let { resolveSourceUrl(it) }.orEmpty()
            video.thumbnailFrameMicros =
                resolveThumbnailFrameMicros(ContextUtils.getApplicationContext(), fileUri)
            listVideos.add(video)
        }
        thumbnailFrameMicrosCache.keys.retainAll(validCacheKeys)

        return listVideos.toList()
    }

    private fun resolveThumbnailFrameMicros(context: Context, uri: Uri): Long {
        val cacheKey = uri.toString()
        thumbnailFrameMicrosCache[cacheKey]?.let { return it }

        val frameMicros = runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val durationMillis = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION
                )?.toLongOrNull()
                recommendedThumbnailFrameMicros(durationMillis)
            } finally {
                retriever.release()
            }
        }.getOrElse { error ->
            AppLogger.w("Video thumbnail frame fallback for $uri: ${error.message}")
            DEFAULT_VIDEO_FRAME_MICROS
        }

        thumbnailFrameMicrosCache[cacheKey] = frameMicros
        return frameMicros
    }

    private fun recommendedThumbnailFrameMicros(durationMillis: Long?): Long {
        val duration = durationMillis ?: return DEFAULT_VIDEO_FRAME_MICROS
        val frameMillis = when {
            duration >= 4_500L -> LONG_VIDEO_FRAME_MICROS / 1_000L
            duration >= 1_000L -> ((duration * 0.6).toLong()).coerceAtMost(3_500L)
            duration >= 300L -> SHORT_VIDEO_FRAME_MICROS / 1_000L
            else -> 0L
        }
        return frameMillis.coerceAtLeast(0L) * 1_000L
    }

    private fun loadCompletedProgressByName(): Map<String, ProgressInfo> {
        return runCatching {
            progressRepository.getProgressInfos()
                .blockingFirst(emptyList())
                .filter { it.downloadStatus == VideoTaskState.SUCCESS }
                .flatMap { progressInfo ->
                    candidateFileNames(progressInfo).map { fileName -> fileName to progressInfo }
                }
                .toMap()
        }.getOrElse { error ->
            AppLogger.e("Failed to load completed video metadata: ${error.message}")
            emptyMap()
        }
    }

    private fun candidateFileNames(progressInfo: ProgressInfo): Set<String> {
        val videoInfo = progressInfo.videoInfo
        return listOf(
            videoInfo.name,
            File(videoInfo.name).name,
            videoInfo.title,
            "${videoInfo.title}.mp4"
        )
            .map { normalizeFileName(it) }
            .filter { it.isNotBlank() }
            .toSet()
    }

    private fun normalizeFileName(fileName: String): String {
        return File(fileName).name.trim().lowercase(Locale.US)
    }

    private fun resolveQuality(progressInfo: ProgressInfo): String {
        val format = progressInfo.videoInfo.formats.formats.firstOrNull() ?: return ""
        return listOf(
            VideoFormatUi.qualityLabel(format),
            cleanFormatMetadata(format.formatNote),
            cleanFormatMetadata(format.format)
        ).firstOrNull { it.isNotBlank() }.orEmpty()
    }

    private fun cleanFormatMetadata(value: String?): String {
        val cleaned = value?.trim().orEmpty()
        return cleaned.takeIf {
            it.isNotBlank() && !it.equals("unknown", true) && !it.equals("null", true)
        }.orEmpty()
    }

    private fun resolveSourceUrl(progressInfo: ProgressInfo): String {
        val videoInfo = progressInfo.videoInfo
        return videoInfo.originalUrl.ifBlank {
            videoInfo.firstUrlToString.ifBlank {
                videoInfo.formats.formats.firstOrNull()?.url.orEmpty()
            }
        }
    }

    fun deleteVideo(context: Context, video: LocalVideo) {
        // 直接用 video.uri 真删文件，不再依赖 localVideos 的 find 匹配（避免 path 比对失败时静默 return）。
        when (val result = fileUtil.deleteMedia(context, video.uri)) {
            is DeleteMediaResult.Success -> {
                val list = localVideos.get()?.toMutableList() ?: mutableListOf()
                list.removeAll {
                    it.uri == video.uri ||
                        it.uri.toString() == video.uri.toString() ||
                        it.uri.path == video.uri.path
                }
                localVideos.set(list)
                deleteSuccessEvent.value = Unit
            }
            is DeleteMediaResult.NeedsAuth -> {
                pendingDelete = PendingDelete(
                    video = video,
                    retryUri = result.retryUri,
                    verificationUri = result.verificationUri
                )
                deleteAuthEvent.value = result.intentSender
            }
            is DeleteMediaResult.Failed -> {
                deleteFailedEvent.value = Unit
            }
        }
    }

    fun onDeleteAuthResult(context: Context, ok: Boolean) {
        val operation = pendingDelete
        pendingDelete = null
        if (!ok) {
            deleteAuthCancelledEvent.value = Unit
            return
        }
        if (operation == null) {
            deleteFailedEvent.value = Unit
            return
        }
        val deleted = if (operation.retryUri == null) {
            fileUtil.isUriDefinitelyAbsent(context, operation.verificationUri)
        } else {
            when (val result = fileUtil.deleteMedia(context, operation.retryUri)) {
                is DeleteMediaResult.Success -> true
                is DeleteMediaResult.NeedsAuth -> {
                    AppLogger.d("onDeleteAuthResult: retry still NeedsAuth, treat as failed")
                    false
                }
                is DeleteMediaResult.Failed -> false
            }
        }
        if (deleted) {
            removeDeletedVideo(operation.video)
        } else {
            deleteFailedEvent.value = Unit
        }
    }

    fun renameVideo(context: Context, uri: Uri, newName: String) {
        when (val result = fileUtil.renameMedia(context, uri, newName)) {
            is RenameMediaResult.Success -> applyRenameSuccess(uri, result)
            is RenameMediaResult.NeedsAuth -> {
                pendingRename = PendingRename(
                    originalUri = uri,
                    retryUri = result.retryUri,
                    requestedName = result.requestedName
                )
                renameAuthEvent.value = result.intentSender
            }
            RenameMediaResult.AlreadyExists ->
                renameErrorEvent.value = FILE_EXIST_ERROR_CODE
            RenameMediaResult.Invalid ->
                renameErrorEvent.value = FILE_INVALID_ERROR_CODE
            is RenameMediaResult.Failed -> {
                AppLogger.e("Media rename failed for $uri: ${result.reason}")
                renameErrorEvent.value = FILE_INVALID_ERROR_CODE
            }
        }
    }

    fun onRenameAuthResult(context: Context, ok: Boolean) {
        val operation = pendingRename
        pendingRename = null
        if (!ok) {
            renameAuthCancelledEvent.value = Unit
            return
        }
        if (operation == null) {
            renameErrorEvent.value = FILE_INVALID_ERROR_CODE
            return
        }
        when (
            val result = fileUtil.renameMedia(
                context,
                operation.retryUri,
                operation.requestedName
            )
        ) {
            is RenameMediaResult.Success -> applyRenameSuccess(operation.originalUri, result)
            RenameMediaResult.AlreadyExists -> renameErrorEvent.value = FILE_EXIST_ERROR_CODE
            RenameMediaResult.Invalid,
            is RenameMediaResult.Failed,
            is RenameMediaResult.NeedsAuth -> {
                AppLogger.e("Media rename retry did not reach the requested final state")
                renameErrorEvent.value = FILE_INVALID_ERROR_CODE
            }
        }
    }

    private fun applyRenameSuccess(originalUri: Uri, result: RenameMediaResult.Success) {
        val list = localVideos.get()?.toMutableList() ?: mutableListOf()
        list.firstOrNull { sameUri(it.uri, originalUri) }?.let { video ->
            thumbnailFrameMicrosCache.remove(video.uri.toString())
            video.uri = result.uri
            video.name = result.name
        }
        localVideos.set(list)
        renameSuccessEvent.value = Unit
    }

    private fun removeDeletedVideo(video: LocalVideo) {
        val list = localVideos.get()?.toMutableList() ?: mutableListOf()
        list.removeAll { sameUri(it.uri, video.uri) }
        thumbnailFrameMicrosCache.remove(video.uri.toString())
        localVideos.set(list)
        deleteSuccessEvent.value = Unit
    }

    private fun sameUri(first: Uri, second: Uri): Boolean {
        val firstPath = first.path
        return first == second ||
            first.toString() == second.toString() ||
            (firstPath != null && firstPath == second.path)
    }

    fun findVideoByName(downloadFilename: String?): Observable<LocalVideo> {
        return Observable.create { emitter ->
            val videos = getFilesList()
            val requestedName = File(downloadFilename.orEmpty()).name
            if (requestedName.isBlank()) {
                emitter.onComplete()
                return@create
            }
            val exactMatches = videos.filter { it.name == requestedName }
            val context = ContextUtils.getApplicationContext()
            val found = exactMatches.firstOrNull {
                fileUtil.isManagedPublicMedia(context, it.uri)
            } ?: exactMatches.firstOrNull()
                ?: videos.firstOrNull { it.name.contains(requestedName) }
            if (found != null) {
                emitter.onNext(found)
                emitter.onComplete()
            }
        }
    }

    fun getSourceUrl(localVideo: LocalVideo): String {
        return localVideo.sourceUrl
    }

    private data class PendingDelete(
        val video: LocalVideo,
        val retryUri: Uri?,
        val verificationUri: Uri
    )

    private data class PendingRename(
        val originalUri: Uri,
        val retryUri: Uri,
        val requestedName: String
    )
}
