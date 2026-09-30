package com.myAllVideoBrowser.ui.main.video

import android.content.ContentResolver
import android.content.Context
import android.content.IntentSender
import android.database.ContentObserver
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
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
import io.reactivex.rxjava3.disposables.Disposable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
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
    private val mediaDurationMillisCache = mutableMapOf<String, Long>()
    private val mediaSortTimeMillisCache = mutableMapOf<String, Long>()
    private var refreshJob: Job? = null
    private var progressSubscription: Disposable? = null
    private var mediaObserver: ContentObserver? = null
    private var started = false

    @Synchronized
    override fun start() {
        if (started) return
        started = true
        val context = ContextUtils.getApplicationContext()
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                scheduleRefresh()
            }
        }
        mediaObserver = observer
        val observedCollections = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            listOf(MediaStore.Downloads.EXTERNAL_CONTENT_URI)
        } else {
            listOf(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            )
        }
        observedCollections.forEach { uri ->
            context.contentResolver.registerContentObserver(uri, true, observer)
        }
        progressSubscription = progressRepository.getProgressInfos().subscribe(
            { scheduleRefresh() },
            { error -> AppLogger.e("Video metadata observation failed", error) }
        )
        scheduleRefresh(immediate = true)
    }

    @Synchronized
    override fun stop() {
        if (!started) return
        started = false
        refreshJob?.cancel()
        refreshJob = null
        progressSubscription?.dispose()
        progressSubscription = null
        mediaObserver?.let { observer ->
            runCatching {
                ContextUtils.getApplicationContext().contentResolver.unregisterContentObserver(observer)
            }
        }
        mediaObserver = null
    }

    private fun scheduleRefresh(immediate: Boolean = false) {
        if (!started) return
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            if (!immediate) delay(250L)
            val newList = withContext(Dispatchers.IO) {
                VideoLibraryOrdering.newestFirst(getFilesList())
            }
            if (started) localVideos.set(newList)
        }
    }

    private fun getFilesList(): List<LocalVideo> {
        val listVideos: MutableList<LocalVideo> = mutableListOf()
        val completedProgress = loadCompletedProgress()
        val metadataIndex = CompletedVideoMetadataIndex.from(completedProgress)
        val validCacheKeys = mutableSetOf<String>()
        val context = ContextUtils.getApplicationContext()
        fileUtil.listFiles.forEach { entry ->
            val fileUri = entry.uri
            val fileSize = fileUtil.getContentLength(context, fileUri)
            val readableSize = FileUtil.getFileSizeReadable(fileSize.toDouble())
            val progressInfo = metadataIndex.find(fileUri.toString(), entry.displayName)
            val cacheKey = fileUri.toString()
            validCacheKeys += cacheKey
            val video = LocalVideo(
                entry.id,
                fileUri,
                entry.displayName
            )
            video.sizeBytes = fileSize
            video.size = readableSize
            video.mimeType = try {
                context.contentResolver.getType(fileUri).orEmpty()
            } catch (error: SecurityException) {
                AppLogger.e("Media type permission changed for $fileUri", error)
                ""
            } catch (error: IllegalArgumentException) {
                AppLogger.e("Media provider cannot resolve type for $fileUri", error)
                ""
            }
            video.quality = progressInfo?.let { resolveQuality(it) }.orEmpty()
            video.sourceUrl = progressInfo?.let { resolveSourceUrl(it) }.orEmpty()
            video.originalThumbnailUrl = progressInfo?.videoInfo?.thumbnail.orEmpty()
            video.durationMillis = if (video.isImage) {
                0L
            } else {
                resolveMediaDurationMillis(context, fileUri)
            }
            video.thumbnailFrameMicros = if (video.isImage) {
                0L
            } else {
                resolveThumbnailFrameMicros(context, fileUri)
            }
            video.sortTimeMillis = resolveMediaSortTimeMillis(context, fileUri)
            listVideos.add(video)
        }
        thumbnailFrameMicrosCache.keys.retainAll(validCacheKeys)
        mediaDurationMillisCache.keys.retainAll(validCacheKeys)
        mediaSortTimeMillisCache.keys.retainAll(validCacheKeys)

        return listVideos.toList()
    }

    private fun resolveMediaSortTimeMillis(context: Context, uri: Uri): Long {
        val cacheKey = uri.toString()
        mediaSortTimeMillisCache[cacheKey]?.let { return it }

        val sortTimeMillis = runCatching {
            when (uri.scheme) {
                ContentResolver.SCHEME_FILE -> uri.path
                    ?.let(::File)
                    ?.lastModified()
                    ?.coerceAtLeast(0L)
                    ?: 0L
                ContentResolver.SCHEME_CONTENT -> queryMediaStoreTimeMillis(context, uri)
                else -> 0L
            }
        }.getOrElse { error ->
            AppLogger.w("Video sort time fallback for $uri: ${error.message}")
            0L
        }

        mediaSortTimeMillisCache[cacheKey] = sortTimeMillis
        return sortTimeMillis
    }

    private fun queryMediaStoreTimeMillis(context: Context, uri: Uri): Long {
        return context.contentResolver.query(
            uri,
            arrayOf(
                MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.DATE_MODIFIED
            ),
            null,
            null,
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use 0L
            val dateAddedSeconds = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
                .takeIf { it >= 0 }
                ?.let(cursor::getLong)
                ?: 0L
            val dateModifiedSeconds = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                .takeIf { it >= 0 }
                ?.let(cursor::getLong)
                ?: 0L
            secondsToMillis(
                dateAddedSeconds.takeIf { it > 0L } ?: dateModifiedSeconds
            )
        } ?: 0L
    }

    private fun secondsToMillis(seconds: Long): Long {
        if (seconds <= 0L) return 0L
        return runCatching { Math.multiplyExact(seconds, 1_000L) }.getOrDefault(0L)
    }

    private fun resolveThumbnailFrameMicros(context: Context, uri: Uri): Long {
        val cacheKey = uri.toString()
        thumbnailFrameMicrosCache[cacheKey]?.let { return it }

        val frameMicros = recommendedThumbnailFrameMicros(
            resolveMediaDurationMillis(context, uri).takeIf { it > 0L }
        )
        thumbnailFrameMicrosCache[cacheKey] = frameMicros
        return frameMicros
    }

    private fun resolveMediaDurationMillis(context: Context, uri: Uri): Long {
        val cacheKey = uri.toString()
        mediaDurationMillisCache[cacheKey]?.let { return it }

        val durationMillis = runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION
                )?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
            } finally {
                retriever.release()
            }
        }.getOrElse { error ->
            AppLogger.w("Video duration fallback for $uri: ${error.message}")
            0L
        }

        mediaDurationMillisCache[cacheKey] = durationMillis
        return durationMillis
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

    private fun loadCompletedProgress(): List<ProgressInfo> {
        return runCatching {
            progressRepository.getProgressInfosOnce()
                .filter { it.downloadStatus == VideoTaskState.SUCCESS }
        }.getOrElse { error ->
            AppLogger.e("Failed to load completed video metadata: ${error.message}")
            emptyList()
        }
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
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                fileUtil.deleteMedia(context, video.uri)
            }
            when (result) {
                is DeleteMediaResult.Success -> {
                    clearFinalMediaBinding(video.uri)
                    removeDeletedVideo(video)
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
        viewModelScope.launch {
            val deleted = withContext(Dispatchers.IO) {
                if (operation.retryUri == null) {
                    fileUtil.isUriDefinitelyAbsent(context, operation.verificationUri)
                } else {
                    when (fileUtil.deleteMedia(context, operation.retryUri)) {
                        is DeleteMediaResult.Success -> true
                        is DeleteMediaResult.NeedsAuth -> {
                            AppLogger.d("onDeleteAuthResult: retry still NeedsAuth, treat as failed")
                            false
                        }
                        is DeleteMediaResult.Failed -> false
                    }
                }
            }
            if (deleted) {
                clearFinalMediaBinding(operation.video.uri)
                removeDeletedVideo(operation.video)
            } else {
                deleteFailedEvent.value = Unit
            }
        }
    }

    fun renameVideo(context: Context, uri: Uri, newName: String) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                fileUtil.renameMedia(context, uri, newName)
            }
            when (result) {
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
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                fileUtil.renameMedia(
                    context,
                    operation.retryUri,
                    operation.requestedName
                )
            }
            when (result) {
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
    }

    private suspend fun applyRenameSuccess(originalUri: Uri, result: RenameMediaResult.Success) {
        withContext(Dispatchers.IO) {
            runCatching {
                progressRepository.replaceFinalMediaUri(originalUri.toString(), result.uri.toString())
            }.onFailure { error ->
                AppLogger.e("Failed to update renamed video metadata binding", error)
            }
        }
        val list = localVideos.get()?.toMutableList() ?: mutableListOf()
        list.firstOrNull { sameUri(it.uri, originalUri) }?.let { video ->
            removeCachedVideoMetadata(video.uri)
            mediaSortTimeMillisCache.remove(result.uri.toString())
            video.uri = result.uri
            video.name = result.name
        }
        localVideos.set(list)
        renameSuccessEvent.value = Unit
    }

    private suspend fun clearFinalMediaBinding(uri: Uri) {
        withContext(Dispatchers.IO) {
            runCatching {
                progressRepository.clearFinalMediaUri(uri.toString())
            }.onFailure { error ->
                AppLogger.e("Failed to clear deleted video metadata binding", error)
            }
        }
    }

    private fun removeDeletedVideo(video: LocalVideo) {
        val list = localVideos.get()?.toMutableList() ?: mutableListOf()
        list.removeAll { sameUri(it.uri, video.uri) }
        removeCachedVideoMetadata(video.uri)
        localVideos.set(list)
        deleteSuccessEvent.value = Unit
    }

    private fun sameUri(first: Uri, second: Uri): Boolean {
        val firstPath = first.path
        return first == second ||
            first.toString() == second.toString() ||
            (firstPath != null && firstPath == second.path)
    }

    private fun removeCachedVideoMetadata(uri: Uri) {
        val cacheKey = uri.toString()
        thumbnailFrameMicrosCache.remove(cacheKey)
        mediaDurationMillisCache.remove(cacheKey)
        mediaSortTimeMillisCache.remove(cacheKey)
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
