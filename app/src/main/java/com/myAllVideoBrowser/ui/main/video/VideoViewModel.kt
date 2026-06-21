package com.myAllVideoBrowser.ui.main.video

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.net.toFile
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
    val shareEvent = SingleLiveEvent<Uri>()
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
            val fileUri = entry.value.second
            val fileSize = fileUtil.getContentLength(ContextUtils.getApplicationContext(), fileUri)
            val readableSize = FileUtil.getFileSizeReadable(fileSize.toDouble())
            val progressInfo = completedProgressByName[normalizeFileName(entry.key)]
            val cacheKey = fileUri.toString()
            validCacheKeys += cacheKey
            val video = LocalVideo(
                entry.value.first,
                fileUri,
                entry.key
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
        localVideos.get()?.find { it.uri.path == video.uri.path }?.let {
            fileUtil.deleteMedia(context, video.uri)

            val list = localVideos.get()?.toMutableList()
            list?.remove(it)
            localVideos.set(list ?: mutableListOf())
        }
    }

    fun renameVideo(context: Context, uri: Uri, newName: String) {
        if (newName.isNotEmpty()) {
            val exists = fileUtil.isUriExists(context, uri)
            if (exists) {
                val isFileWithNameNotExists =
                    fileUtil.isFileWithNameNotExists(context, uri, newName)
                if (isFileWithNameNotExists) {
                    val newMediaNameUri = fileUtil.renameMedia(context, uri, newName)
                    if (newMediaNameUri != null) {
                        localVideos.get()?.find { it.uri.toString() == uri.toString() }?.let {
                            it.uri = newMediaNameUri.second
                            it.name = newMediaNameUri.first

                            localVideos.get().let { list ->
                                list?.set(list.indexOf(it), it)
                            }
                        }
                        return
                    }
                }

                renameErrorEvent.value = FILE_EXIST_ERROR_CODE
                return
            }
        }

        renameErrorEvent.value = FILE_INVALID_ERROR_CODE
    }

    fun findVideoByName(downloadFilename: String?): Observable<LocalVideo> {
        return Observable.create { emitter ->
            val videos = getFilesList()
            val found =
                videos.find { it.name.contains(File(downloadFilename.toString()).name) }
            if (found != null) {
                emitter.onNext(found)
                emitter.onComplete()
            }
        }
    }

    fun getSourceUrl(localVideo: LocalVideo): String {
        return localVideo.sourceUrl
    }
}
