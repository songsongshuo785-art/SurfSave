package com.myAllVideoBrowser.ui.main.home.browser.detectedVideos

import android.webkit.CookieManager
import androidx.annotation.StringRes
import androidx.databinding.Observable
import androidx.databinding.Observable.OnPropertyChangedCallback
import androidx.databinding.ObservableBoolean
import androidx.databinding.ObservableField
import androidx.databinding.ObservableInt
import androidx.lifecycle.viewModelScope
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.data.local.model.VideoInfoWrapper
import com.myAllVideoBrowser.data.local.room.entity.DownloadRequestData
import com.myAllVideoBrowser.data.local.room.entity.VideFormatEntityList
import com.myAllVideoBrowser.data.local.room.entity.VideoFormatEntity
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.data.local.room.entity.toDownloadRequestData
import com.myAllVideoBrowser.data.repository.VideoRepository
import com.myAllVideoBrowser.ui.main.base.BaseViewModel
import com.myAllVideoBrowser.ui.main.home.browser.BrowserFragment
import com.myAllVideoBrowser.ui.main.home.browser.DownloadButtonState
import com.myAllVideoBrowser.ui.main.home.browser.DownloadButtonStateCanDownload
import com.myAllVideoBrowser.ui.main.home.browser.DownloadButtonStateCanNotDownload
import com.myAllVideoBrowser.ui.main.home.browser.DownloadButtonStateLoading
import com.myAllVideoBrowser.ui.main.home.browser.webTab.WebTabViewModel
import com.myAllVideoBrowser.ui.main.settings.SettingsViewModel
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.ContextUtils
import com.myAllVideoBrowser.util.CookieUtils
import com.myAllVideoBrowser.util.SingleLiveEvent
import com.myAllVideoBrowser.util.UserFacingError
import com.myAllVideoBrowser.util.VideoFormatUi
import com.myAllVideoBrowser.util.telegram.TelegramPostResolution
import com.myAllVideoBrowser.util.proxy_utils.OkHttpProxyClient
import com.myAllVideoBrowser.util.scheduler.BaseSchedulers
import io.reactivex.rxjava3.disposables.Disposable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.Headers.Companion.toHeaders
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import java.net.HttpCookie
import java.net.URI
import java.net.URL
import java.util.concurrent.Executors
import javax.inject.Inject
import kotlin.math.abs

open class VideoDetectionTabViewModel @Inject constructor(
    private val videoRepository: VideoRepository,
    private val baseSchedulers: BaseSchedulers,
    private val okHttpProxyClient: OkHttpProxyClient,
) : BaseViewModel(), IVideoDetector {
    companion object {
        private val TEMP_URL_QUERY_KEYS = setOf(
            "x-amz-signature",
            "x-amz-credential",
            "x-amz-date",
            "x-amz-expires",
            "x-amz-security-token",
            "signature",
            "sig",
            "token",
            "expires",
            "expire",
            "e",
            "st",
            "se",
            "sp",
            "sv",
            "hash",
            "key",
            "auth",
            "policy",
            "range"
        )

        internal fun mergeTelegramResolvedVideo(
            existing: VideoInfo,
            resolved: VideoInfo
        ): VideoInfo {
            return resolved.copy(
                id = existing.id,
                thumbnail = resolved.thumbnail.ifBlank { existing.thumbnail },
                duration = maxOf(existing.duration, resolved.duration)
            )
        }
    }

    // key: videoInfo.id, value: format - string
    val selectedFormats = ObservableField<Map<String, String>>()

    // key: videoInfo.id, value: title - string
    val formatsTitles = ObservableField<Map<String, String>>()

    val selectedFormatUrl = ObservableField<String>()

    var initialUrl: String = ""

    @Volatile
    var m3u8LoadingList = ObservableField<Set<String>>(emptySet())

    @Volatile
    var regularLoadingList = ObservableField<Set<String>>(emptySet())

    val showDetectedVideosEvent = SingleLiveEvent<Void?>()

    val videoPushedEvent = SingleLiveEvent<Void?>()

    val detectionFeedbackEvent = SingleLiveEvent<String>()

    @Volatile
    var downloadButtonState =
        ObservableField<DownloadButtonState>(DownloadButtonStateCanNotDownload())

    val executorReload = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    var webTabModel: WebTabViewModel? = null
    lateinit var settingsModel: SettingsViewModel
    val detectedVideosList = ObservableField(setOf<VideoInfo>())
    val sortedDetectedVideosList = ObservableField<List<VideoInfo>>(emptyList())
    val hasProtectedMedia = ObservableBoolean(false)
    val detectedPanelTitle = ObservableField<String>()
    val hasTelegramPostPreview = ObservableBoolean(false)
    val hasTelegramDescription = ObservableBoolean(false)
    val hasTelegramThumbnail = ObservableBoolean(false)
    val hasTelegramPosterOnlyMedia = ObservableBoolean(false)
    val telegramPostChannel = ObservableField("")
    val telegramPostDescription = ObservableField("")
    val telegramPostThumbnail = ObservableField("")
    val telegramPostMediaSummary = ObservableField("")
    val telegramPostOpenUrl = ObservableField("")

    private val protectedMediaPageTracker = ProtectedMediaPageTracker()

    @Volatile
    private var telegramVideoOrder: List<String> = emptyList()

    @Volatile
    private var pageMediaMetadata = PageMediaMetadata()

    val filterRegex =
        Regex("^(.*\\.(apk|html|xml|ico|css|js|png|gif|json|jpg|jpeg|svg|woff|woff2|m3u8|mpd|ts|php|ttf|otf|eot|cur|webp|bmp|tif|tiff|psd|ai|eps|pdf|doc|docx|xls|xlsx|ppt|pptx|csv|md|rtf|vtt|srt|swf|jar|log|txt|m4s))?$")
    val downloadButtonIcon = ObservableInt(R.drawable.invisible_24px)
    val detectedVideosCount = ObservableInt(0)
    val hasDetectedVideos = ObservableBoolean(false)
    val detectedVideosBadgeText = ObservableField("")
    val lastDetectionError = ObservableField<String?>()
    val detectionStatusText = ObservableField("")
    val hasDetectionStatus = ObservableBoolean(false)
    val detectionStatusIsError = ObservableBoolean(false)

    @Volatile
    var verifyVideoLinkJobStorage = com.myAllVideoBrowser.util.DisposableJobRegistry()

    private val hasCheckLoadingsM3u8 = ObservableBoolean(false)
    private val hasCheckLoadingsRegular = ObservableBoolean(false)

    private val executorRegular = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    @Volatile
    private var lastUrl = ""

    @Volatile
    private var lastManualDetectionRequestAt = 0L

    private val regularLoadingListCallback = object : OnPropertyChangedCallback() {
        override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
            val notEmpty = regularLoadingList.get()?.isNotEmpty() == true
            hasCheckLoadingsRegular.set(notEmpty)
            if (notEmpty) {
                setButtonState(DownloadButtonStateCanNotDownload())
            }
        }
    }

    private val m3u8LoadingListCallback = object : OnPropertyChangedCallback() {
        override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
            val notEmpty = m3u8LoadingList.get()?.isNotEmpty() == true
            hasCheckLoadingsM3u8.set(notEmpty)
            if (notEmpty) {
                setButtonState(DownloadButtonStateCanNotDownload())
            }
        }
    }

    private val downloadButtonStateCallback = object : OnPropertyChangedCallback() {
        override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
            runOnMain {
                when (downloadButtonState.get()) {
                    is DownloadButtonStateCanNotDownload -> downloadButtonIcon.set(R.drawable.refresh_24px)
                    is DownloadButtonStateCanDownload -> downloadButtonIcon.set(R.drawable.ic_download_24dp)
                    is DownloadButtonStateLoading -> {
                        downloadButtonIcon.set(R.drawable.invisible_24px)
                    }

                    null -> {
                        downloadButtonIcon.set(R.drawable.refresh_24px)
                    }
                }
            }
        }
    }

    override fun start() {
        AppLogger.d("START")
        if (detectedPanelTitle.get().isNullOrBlank()) {
            setMediaImportContext(false)
        }
        regularLoadingList.addOnPropertyChangedCallback(regularLoadingListCallback)
        m3u8LoadingList.addOnPropertyChangedCallback(m3u8LoadingListCallback)
        downloadButtonState.addOnPropertyChangedCallback(downloadButtonStateCallback)

        downloadButtonStateCallback.onPropertyChanged(null, 0)
    }

    override fun stop() {
        AppLogger.d("STOP")
        regularLoadingList.removeOnPropertyChangedCallback(regularLoadingListCallback)
        m3u8LoadingList.removeOnPropertyChangedCallback(m3u8LoadingListCallback)
        downloadButtonState.removeOnPropertyChangedCallback(downloadButtonStateCallback)
        cancelAllCheckJobs()
    }

    override fun onCleared() {
        executorRegular.cancel()
        executorReload.cancel()
        super.onCleared()
    }

    override fun onStartPage(url: String, userAgentString: String) {
        if (url == lastUrl) {
            AppLogger.d("onStartPage: URL is the same. Not clearing list.")
            return
        }
        lastUrl = url
        setDownloadStateNow(DownloadButtonStateCanNotDownload())
        clearDetectionStatus()

        if (url != initialUrl) {
            AppLogger.d("onStartPage: URL is not initial url. Clearing list.")
            setDetectedVideosNow(mutableSetOf())
            cancelAllCheckJobs()
        } else {
            AppLogger.d("onStartPage: URL is initial url. Skipped clearing list.")
        }

        val req = getRequestWithHeadersForUrl(
            url, url, userAgentString
        )?.build()

        if (req != null) {
            verifyLinkStatus(req)
        }
    }

    fun beginPageContext(url: String): Long {
        val snapshot = protectedMediaPageTracker.beginPage(url)
        runOnMain {
            if (protectedMediaPageTracker.snapshot().generation != snapshot.generation) {
                return@runOnMain
            }
            pageMediaMetadata = PageMediaMetadata(pageUrl = url)
            hasProtectedMedia.set(false)
            clearTelegramPostStateNow()
            sortedDetectedVideosList.set(sortDetectedVideos(detectedVideosList.get().orEmpty()))
        }
        return snapshot.generation
    }

    fun setMediaImportContext(isMediaImport: Boolean) {
        val context = ContextUtils.getApplicationContext()
        runOnMain {
            detectedPanelTitle.set(
                context.getString(
                    if (isMediaImport) R.string.media_import_panel_title
                    else R.string.detected_videos_title
                )
            )
        }
    }

    fun applyTelegramPostResolution(
        pageGeneration: Long,
        resolution: TelegramPostResolution,
        notifyVideoPushed: Boolean = true
    ) {
        if (protectedMediaPageTracker.snapshot().generation != pageGeneration) return
        runOnMain {
            if (protectedMediaPageTracker.snapshot().generation != pageGeneration) {
                return@runOnMain
            }

            val context = ContextUtils.getApplicationContext()
            val preview = resolution.preview
            telegramPostChannel.set(
                preview.channel.ifBlank {
                    runCatching { URI(preview.postUrl).path.substringBeforeLast('/') }
                        .getOrDefault("")
                        .substringAfterLast('/')
                        .let { channel -> if (channel.isBlank()) "Telegram" else "@$channel" }
                }
            )
            telegramPostDescription.set(preview.description)
            hasTelegramDescription.set(preview.description.isNotBlank())
            telegramPostThumbnail.set(preview.thumbnail)
            hasTelegramThumbnail.set(preview.thumbnail.isNotBlank())
            telegramPostOpenUrl.set(preview.postUrl)
            telegramPostMediaSummary.set(
                context.getString(
                    R.string.telegram_post_media_summary,
                    preview.items.size,
                    preview.playableCount
                )
            )
            hasTelegramPosterOnlyMedia.set(preview.posterOnlyCount > 0)
            hasTelegramPostPreview.set(true)

            var detected = detectedVideosList.get().orEmpty()
            val orderedIds = mutableListOf<String>()
            val resolvedSelections = selectedFormats.get().orEmpty().toMutableMap()
            var lastResolvedVideo: VideoInfo? = null
            resolution.videos.forEach { newInfo ->
                val duplicate = detected.firstOrNull { isVideoInfoDuplicate(it, newInfo) }
                val resolved = if (duplicate == null) {
                    newInfo
                } else {
                    mergeTelegramResolvedVideo(duplicate, newInfo).also {
                        detected = detected - duplicate
                    }
                }
                detected = detected + resolved
                orderedIds += resolved.id
                lastResolvedVideo = resolved
                VideoFormatUi.defaultSelectionKey(resolved)
                    .takeUnless { it == "unknown" }
                    ?.let { resolvedSelections[resolved.id] = it }
            }
            telegramVideoOrder = orderedIds
            selectedFormats.set(resolvedSelections)
            setDetectedVideosNow(detected)

            if (lastResolvedVideo != null) {
                setButtonState(DownloadButtonStateCanDownload(lastResolvedVideo))
                clearDetectionStatus()
                if (notifyVideoPushed) {
                    videoPushedEvent.call()
                }
            } else {
                setButtonState(DownloadButtonStateCanNotDownload())
                setDetectionStatus(
                    context.getString(R.string.telegram_post_poster_only_message),
                    isError = false
                )
            }
        }
    }

    fun markProtectedMedia(pageGeneration: Long) {
        val snapshot = protectedMediaPageTracker.markProtectedMedia(pageGeneration) ?: return
        runOnMain {
            if (protectedMediaPageTracker.snapshot().generation != snapshot.generation) {
                return@runOnMain
            }
            hasProtectedMedia.set(true)
            if (detectedVideosList.get().isNullOrEmpty()) {
                lastDetectionError.set(null)
                detectionStatusText.set("")
                hasDetectionStatus.set(false)
                detectionStatusIsError.set(false)
            }
        }
    }

    fun updatePageMediaMetadata(pageGeneration: Long, metadata: PageMediaMetadata) {
        if (protectedMediaPageTracker.snapshot().generation != pageGeneration) return
        runOnMain {
            if (protectedMediaPageTracker.snapshot().generation != pageGeneration) {
                return@runOnMain
            }
            pageMediaMetadata = metadata
            sortedDetectedVideosList.set(sortDetectedVideos(detectedVideosList.get().orEmpty()))
        }
    }

    fun displayDurationMs(videoInfo: VideoInfo): Long {
        return DetectedMediaPresentation.displayDurationMs(videoInfo, pageMediaMetadata)
    }

    fun onReloadPage(url: String, userAgentString: String) {
        lastUrl = url
        setDownloadStateNow(DownloadButtonStateCanNotDownload())
        showDetectionNotice(R.string.detection_status_checking)

        setDetectedVideosNow(mutableSetOf())
        cancelAllCheckJobs()

        val req = getRequestWithHeadersForUrl(
            url, url, userAgentString
        )?.build()

        if (req != null) {
            verifyLinkStatus(req)
        }
    }

    override fun hasCheckLoadingsRegular(): ObservableBoolean {
        return hasCheckLoadingsRegular
    }

    override fun hasCheckLoadingsM3u8(): ObservableBoolean {
        return hasCheckLoadingsM3u8
    }

    override fun showVideoInfo() {
        AppLogger.d("SHOW")
        if ((hasProtectedMedia.get() || hasTelegramPostPreview.get()) &&
            detectedVideosList.get().isNullOrEmpty()
        ) {
            runOnMain {
                showDetectedVideosEvent.call()
            }
            return
        }
        val state = downloadButtonState.get()

        if (state is DownloadButtonStateCanNotDownload) {
            webTabModel?.getTabTextInput()?.get()?.let { url ->
                if (url.startsWith("http")) {
                    lastManualDetectionRequestAt = System.currentTimeMillis()
                    showDetectionNotice(R.string.detection_status_checking)
                    emitDetectionFeedback(detectionStatusText.get().orEmpty())
                    viewModelScope.launch(executorRegular) {
                        onReloadPage(
                            url.trim(),
                            webTabModel?.userAgent?.get() ?: BrowserFragment.MOBILE_USER_AGENT
                        )
                    }
                } else {
                    showDetectionNotice(R.string.detection_no_page_url)
                    emitDetectionFeedback(detectionStatusText.get().orEmpty())
                }
            }
        }

        if (detectedVideosList.get()?.isNotEmpty() == true) {
            runOnMain {
                showDetectedVideosEvent.call()
            }
        }
    }

    override fun verifyLinkStatus(
        resourceRequest: Request, hlsTitle: String?, isM3u8: Boolean, isMpd: Boolean
    ) {
        if (resourceRequest.url.toString().contains("tiktok.")) {
            return
        }

        val urlToVerify = resourceRequest.url.toString()
        if (isM3u8 || isMpd) {
            startVerifyProcess(resourceRequest, isM3u8, isMpd, hlsTitle)
        } else {
            if (urlToVerify.contains(
                    ".txt"
                )
            ) {
                return
            }
            if (settingsModel.getIsFindVideoByUrl().get()) {
                startVerifyProcess(resourceRequest, isM3u8 = false, isMpd = false)
            }
        }
    }

    open fun startVerifyProcess(
        resourceRequest: Request, isM3u8: Boolean, isMpd: Boolean, hlsTitle: String? = null
    ) {
        val taskUrl = resourceRequest.url.toString().trim()
        if (taskUrl.isEmpty()) return

        val registered = verifyVideoLinkJobStorage.tryRegister(taskUrl) { holder ->
            updateM3u8Loading(resourceRequest.url.toString(), true)
            showDetectionNotice(R.string.detection_status_checking)
            if (detectedVideosList.get()?.isEmpty() == true) {
                setButtonState(DownloadButtonStateLoading())
            }

            io.reactivex.rxjava3.core.Observable.create { emitter ->
                val info = try {
                    val isUseLegacyDetection = settingsModel.isUseLegacyM3u8Detection.get()
                    if (!isUseLegacyDetection && (isM3u8 || isMpd)) {
                        videoRepository.getVideoInfoBySuperXDetector(
                            resourceRequest, isM3u8, isMpd, settingsModel.isCheckOnAudio.get()
                        )
                    } else {
                        videoRepository.getVideoInfo(
                            resourceRequest, false, settingsModel.isCheckOnAudio.get()
                        )
                    }
                } catch (e: Throwable) {
                    AppLogger.e("Detection: verify failed url=$taskUrl", e)
                    setDetectionError(e)
                    null
                }
                if (info != null) {
                    emitter.onNext(info)
                } else {
                    emitter.onNext(VideoInfo(id = ""))
                }
                emitter.onComplete()
            }.doOnTerminate {
                updateM3u8Loading(resourceRequest.url.toString(), false)
                verifyVideoLinkJobStorage.finish(taskUrl, holder)
            }.observeOn(baseSchedulers.mainThread).subscribeOn(baseSchedulers.videoService)
                .subscribe { info ->
                    if (info.id.isNotEmpty()) {
                        if (info.isM3u8 && !hlsTitle.isNullOrEmpty()) {
                            info.title = hlsTitle
                        }
                        pushNewVideoInfoToAll(info)
                    } else if (info.id.isEmpty()) {
                        setButtonState(DownloadButtonStateCanNotDownload())
                    }
                }
        }

        if (!registered) return
    }

    @Synchronized
    open fun pushNewVideoInfoToAll(newInfo: VideoInfo) {
        if (newInfo.formats.formats.isEmpty()) {
            return
        }

        if (newInfo.id.isEmpty()) {
            return
        }

        if (shouldSkipShortVideo(newInfo)) {
            AppLogger.d("SKIP SHORT VIDEO INFO: ${newInfo.duration}ms $newInfo")
            return
        }

        val detectedVideos = detectedVideosList.get() ?: emptySet()

        val duplicate = detectedVideos.firstOrNull { isVideoInfoDuplicate(it, newInfo) }
        if (duplicate != null) {
            val merged = mergeDuplicateVideoInfo(duplicate, newInfo)
            setDetectedVideosNow(detectedVideos - duplicate + merged)
            setButtonState(DownloadButtonStateCanDownload(merged))
            AppLogger.d("MERGED DUPLICATED VIDEO INFO: $newInfo")
            return
        }

        AppLogger.d("PUSHING $newInfo to list: \n  $detectedVideos")
        setDetectedVideosNow(detectedVideos + newInfo)
        setButtonState(DownloadButtonStateCanDownload(newInfo))
        clearDetectionStatus()
        autoSelectBestFormat(newInfo)

        runOnMain {
            videoPushedEvent.call()
        }
    }

    private fun autoSelectBestFormat(videoInfo: VideoInfo) {
        val bestKey = VideoFormatUi.defaultSelectionKey(videoInfo)
        if (bestKey == "unknown") return
        val current = selectedFormats.get().orEmpty()
        if (!current.containsKey(videoInfo.id)) {
            selectedFormats.set(current + (videoInfo.id to bestKey))
        }
    }

    fun applyThumbnailToDetectedVideos(thumbnailUrl: String) {
        if (thumbnailUrl.isBlank()) {
            return
        }

        val detectedVideos = detectedVideosList.get() ?: return
        val updatedVideos = detectedVideos.map { videoInfo ->
            if (videoInfo.thumbnail.isBlank()) {
                videoInfo.copy(thumbnail = thumbnailUrl)
            } else {
                videoInfo
            }
        }.toSet()

        setDetectedVideosNow(updatedVideos)
    }

    protected fun isVideoInfoDuplicate(existing: VideoInfo, newInfo: VideoInfo): Boolean {
        val existingUrls = mediaIdentityUrls(existing)
        val newUrls = mediaIdentityUrls(newInfo)
        if (existingUrls.isNotEmpty() && newUrls.isNotEmpty() && existingUrls.any { it in newUrls }) {
            return true
        }

        val existingPage = normalizeMediaUrl(existing.originalUrl)
        val newPage = normalizeMediaUrl(newInfo.originalUrl)
        val samePage = existingPage.isNotBlank() && existingPage == newPage
        val sameTitle = normalizeTitle(existing.title).isNotBlank() &&
            normalizeTitle(existing.title) == normalizeTitle(newInfo.title)
        val durationClose = durationsClose(existing, newInfo)

        return samePage && (sameTitle || durationClose)
    }

    private fun mergeDuplicateVideoInfo(existing: VideoInfo, newInfo: VideoInfo): VideoInfo {
        val mergedFormats = (existing.formats.formats + newInfo.formats.formats)
            .distinctBy { normalizeMediaUrl(VideoFormatUi.selectionKey(it)) }

        return existing.copy(
            title = existing.title.ifBlank { newInfo.title },
            ext = existing.ext.ifBlank { newInfo.ext },
            thumbnail = existing.thumbnail.ifBlank { newInfo.thumbnail },
            duration = maxOf(existing.duration, newInfo.duration),
            originalUrl = existing.originalUrl.ifBlank { newInfo.originalUrl },
            downloadUrls = (existing.downloadUrls + newInfo.downloadUrls)
                .distinctBy { normalizeMediaUrl(it.url) },
            formats = VideFormatEntityList(mergedFormats),
            isRegularDownload = existing.isRegularDownload && newInfo.isRegularDownload,
            isLive = existing.isLive || newInfo.isLive,
            isDetectedBySuperX = existing.isDetectedBySuperX || newInfo.isDetectedBySuperX
        )
    }

    private fun mediaIdentityUrls(info: VideoInfo): Set<String> {
        val formatUrls = info.formats.formats.flatMap {
            listOfNotNull(it.url, it.manifestUrl, it.videoOnlyUrl, it.audioOnlyUrl)
        }
        val downloadUrls = info.downloadUrls.map { it.url }
        return (formatUrls + downloadUrls)
            .map { normalizeMediaUrl(it) }
            .filter { it.isNotBlank() }
            .toSet()
    }

    private fun normalizeMediaUrl(rawUrl: String?): String {
        val value = rawUrl?.trim().orEmpty()
        if (value.isBlank()) {
            return ""
        }

        return runCatching {
            val uri = URI(value)
            val host = uri.host?.lowercase()?.removePrefix("www.").orEmpty()
            val path = uri.path.orEmpty().trimEnd('/')
            val stableQuery = uri.query
                ?.split("&")
                ?.filterNot { queryPart ->
                    val key = queryPart.substringBefore("=").lowercase()
                    key in TEMP_URL_QUERY_KEYS ||
                        key.startsWith("utm_") ||
                        key.contains("token") ||
                        key.contains("signature") ||
                        key.contains("expires") ||
                        key.contains("expire")
                }
                ?.sorted()
                ?.joinToString("&")
                .orEmpty()

            val base = "$host$path"
            if (stableQuery.isBlank()) {
                base.lowercase()
            } else {
                "$base?$stableQuery".lowercase()
            }
        }.getOrElse {
            value.substringBefore("#")
                .substringBefore("?")
                .trimEnd('/')
                .lowercase()
        }
    }

    private fun normalizeTitle(title: String): String {
        return title.lowercase()
            .replace(Regex("""\.[a-z0-9]{2,5}$"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun durationsClose(existing: VideoInfo, newInfo: VideoInfo): Boolean {
        val existingDuration = detectedDurationMs(existing)
        val newDuration = detectedDurationMs(newInfo)
        if (existingDuration <= 0 || newDuration <= 0) {
            return false
        }

        return abs(existingDuration - newDuration) <= 2_000L
    }

    protected fun shouldSkipShortVideo(info: VideoInfo): Boolean {
        if (!settingsModel.isFilterShortVideos.get() || info.isLive) {
            return false
        }

        val duration = detectedDurationMs(info)
        val minimumDurationMs =
            (settingsModel.shortVideoFilterDurationSeconds.get().coerceIn(5, 120)) * 1_000L
        return duration in 1 until minimumDurationMs
    }

    private fun detectedDurationMs(info: VideoInfo): Long {
        val formatDuration = info.formats.formats.mapNotNull { it.duration }.maxOrNull() ?: 0L
        return maxOf(info.duration, formatDuration)
    }

    override fun getDownloadBtnIcon(): ObservableInt {
        return downloadButtonIcon
    }

    override fun checkRegularVideoOrAudio(
        request: Request?, isCheckOnAudio: Boolean, isCheckOnVideo: Boolean
    ): Disposable? {
        if (request == null) {
            return null
        }

        val uriString = request.url.toString()

        if (!uriString.startsWith("http")) {
            return null
        }

        val clearedUrl = uriString.split("?").first().trim()

        if (clearedUrl.contains(filterRegex)) {
            return null
        }

        val headers = try {
            request.headers.toMap().toMutableMap()
        } catch (_: Throwable) {
            mutableMapOf()
        }

        val disposable = io.reactivex.rxjava3.core.Observable.create<Unit> {
            if (request.url.toString().contains(".mp4")) {
                setButtonState(DownloadButtonStateLoading())
            }
            updateRegularLoading(request.url.toString(), true)
            propagateCheckJob(uriString, headers, isCheckOnAudio, isCheckOnVideo)
            it.onComplete()
        }.doFinally {
            updateRegularLoading(request.url.toString(), false)
        }.doOnError { e ->
            AppLogger.e("Detection: regularCheck failed url=$clearedUrl", e)
            setDetectionError(e)
        }.onErrorComplete().subscribeOn(baseSchedulers.io).subscribe()

        return disposable
    }

    override fun cancelAllCheckJobs() {
        setRegularLoadingSet(emptySet())
        setM3u8LoadingSet(emptySet())
        verifyVideoLinkJobStorage.cancelAll()
    }


    private fun setDownloadStateNow(state: DownloadButtonState) {
        runOnMain {
            downloadButtonState.set(state)
        }
    }

    private fun setDetectedVideosNow(videos: Set<VideoInfo>) {
        runOnMain {
            detectedVideosList.set(videos)
            sortedDetectedVideosList.set(sortDetectedVideos(videos))
            detectedVideosCount.set(videos.size)
            hasDetectedVideos.set(videos.isNotEmpty())
            detectedVideosBadgeText.set(
                when {
                    videos.isEmpty() -> ""
                    videos.size > 99 -> "99+"
                    else -> videos.size.toString()
                }
            )
            if (videos.isNotEmpty()) {
                lastDetectionError.set(null)
                detectionStatusText.set("")
                hasDetectionStatus.set(false)
                detectionStatusIsError.set(false)
            }
        }
    }

    private fun sortDetectedVideos(videos: Set<VideoInfo>): List<VideoInfo> {
        val sorted = DetectedMediaPresentation.sort(videos.toList(), pageMediaMetadata)
        if (telegramVideoOrder.isEmpty()) return sorted

        val telegramRank = telegramVideoOrder.withIndex().associate { it.value to it.index }
        val fallbackRank = sorted.withIndex().associate { it.value.id to it.index }
        return sorted.sortedWith(
            compareBy<VideoInfo> { telegramRank[it.id] ?: Int.MAX_VALUE }
                .thenBy { fallbackRank[it.id] ?: Int.MAX_VALUE }
        )
    }

    private fun clearTelegramPostStateNow() {
        telegramVideoOrder = emptyList()
        hasTelegramPostPreview.set(false)
        hasTelegramDescription.set(false)
        hasTelegramThumbnail.set(false)
        hasTelegramPosterOnlyMedia.set(false)
        telegramPostChannel.set("")
        telegramPostDescription.set("")
        telegramPostThumbnail.set("")
        telegramPostMediaSummary.set("")
        telegramPostOpenUrl.set("")
    }

    private fun setRegularLoadingSet(loadings: Set<String>) {
        runOnMain {
            regularLoadingList.set(loadings)
            updateDetectionStatusAfterLoadingChange()
        }
    }

    private fun setM3u8LoadingSet(loadings: Set<String>) {
        runOnMain {
            m3u8LoadingList.set(loadings)
            updateDetectionStatusAfterLoadingChange()
        }
    }

    private fun updateRegularLoading(url: String, isLoading: Boolean) {
        runOnMain {
            val current = regularLoadingList.get().orEmpty()
            val updated = if (isLoading) current + url else current - url
            regularLoadingList.set(updated)
            updateDetectionStatusAfterLoadingChange()
        }
    }

    fun updateM3u8Loading(url: String, isLoading: Boolean) {
        runOnMain {
            val current = m3u8LoadingList.get().orEmpty()
            val updated = if (isLoading) current + url else current - url
            m3u8LoadingList.set(updated)
            updateDetectionStatusAfterLoadingChange()
        }
    }

    fun showDetectionNotice(@StringRes messageRes: Int) {
        if (detectedVideosList.get()?.isNotEmpty() == true) {
            clearDetectionStatus()
            return
        }
        val context = ContextUtils.getApplicationContext()
        setDetectionStatus(context.getString(messageRes), isError = false)
    }

    fun showDetectionError(error: Throwable) {
        setDetectionError(error, forceVisible = true)
    }

    fun clearDetectionStatus(shouldPublish: () -> Boolean = { true }) {
        runOnMain {
            if (!shouldPublish()) {
                return@runOnMain
            }
            lastDetectionError.set(null)
            detectionStatusText.set("")
            hasDetectionStatus.set(false)
            detectionStatusIsError.set(false)
        }
    }

    protected fun setDetectionError(
        error: Throwable,
        forceVisible: Boolean = false,
        shouldPublish: () -> Boolean = { true }
    ) {
        if (!shouldPublish()) {
            return
        }
        if (detectedVideosList.get()?.isNotEmpty() == true) {
            clearDetectionStatus(shouldPublish)
            return
        }
        if (!forceVisible && !shouldShowManualDetectionError()) {
            return
        }

        val context = ContextUtils.getApplicationContext()
        val message = UserFacingError.detectionMessage(context, error)
        setDetectionStatus(message, isError = true, shouldPublish = shouldPublish)
        if (!forceVisible) {
            emitDetectionFeedback(message, shouldPublish)
        }
    }

    private fun setDetectionStatus(
        message: String,
        isError: Boolean,
        shouldPublish: () -> Boolean = { true }
    ) {
        runOnMain {
            if (!shouldPublish()) {
                return@runOnMain
            }
            if (detectedVideosList.get()?.isNotEmpty() == true) {
                lastDetectionError.set(null)
                detectionStatusText.set("")
                hasDetectionStatus.set(false)
                detectionStatusIsError.set(false)
                return@runOnMain
            }
            if (isError) {
                lastDetectionError.set(message)
            } else {
                lastDetectionError.set(null)
            }
            detectionStatusText.set(message)
            hasDetectionStatus.set(message.isNotBlank())
            detectionStatusIsError.set(isError)
        }
    }

    private fun emitDetectionFeedback(
        message: String,
        shouldPublish: () -> Boolean = { true }
    ) {
        if (message.isBlank()) {
            return
        }

        runOnMain {
            if (!shouldPublish()) {
                return@runOnMain
            }
            detectionFeedbackEvent.value = message
        }
    }

    private fun shouldShowManualDetectionError(): Boolean {
        return System.currentTimeMillis() - lastManualDetectionRequestAt < 15_000L &&
            detectedVideosList.get()?.isEmpty() == true
    }

    private fun updateDetectionStatusAfterLoadingChange() {
        val hasVideos = detectedVideosList.get()?.isNotEmpty() == true
        if (hasVideos) {
            clearDetectionStatus()
            return
        }
        if (detectionStatusIsError.get()) {
            return
        }

        val hasLoading = regularLoadingList.get()?.isNotEmpty() == true ||
            m3u8LoadingList.get()?.isNotEmpty() == true
        val context = ContextUtils.getApplicationContext()
        val checking = context.getString(R.string.detection_status_checking)
        when {
            hasLoading -> setDetectionStatus(checking, isError = false)
            detectionStatusText.get() == checking -> {
                setDetectionStatus(context.getString(R.string.detection_status_empty), isError = false)
            }
        }
    }

    @Synchronized
    fun setButtonState(
        state: DownloadButtonState,
        shouldPublish: () -> Boolean = { true }
    ) {
        runOnMain {
            if (!shouldPublish()) {
                return@runOnMain
            }
            when (state) {
                is DownloadButtonStateCanDownload -> {
                    downloadButtonState.set(state)
                }

                is DownloadButtonStateCanNotDownload -> {
                    val detectedSize = detectedVideosList.get()?.size
                    if (detectedSize == null || detectedSize == 0) {
                        downloadButtonState.set(DownloadButtonStateCanNotDownload())
                    } else {
                        downloadButtonState.set(
                            DownloadButtonStateCanDownload(
                                detectedVideosList.get()?.first()
                            )
                        )
                    }
                }

                is DownloadButtonStateLoading -> {
                    val list = detectedVideosList.get() ?: emptySet()
                    if (list.isEmpty()) {
                        downloadButtonState.set(DownloadButtonStateLoading())
                    } else {
                        downloadButtonState.set(DownloadButtonStateCanDownload(list.first()))
                    }
                }
            }
        }
    }

    private fun getRequestWithHeadersForUrl(
        url: String,
        originalUrl: String,
        userAgent: String,
        alternativeHeaders: Map<String, String> = emptyMap()
    ): Request.Builder? {
        try {
            val cookies = try {
                CookieManager.getInstance().getCookie(url) ?: ""
            } catch (_: Throwable) {
                ""
            }
            val stringBuilder = StringBuilder()
            if (cookies.isNotEmpty()) {
                for (cookie in cookies.split(";")) {
                    val parsedCookies = HttpCookie.parse(cookie)

                    for (httpCookie in parsedCookies) {
                        stringBuilder.append("${httpCookie.name}=${httpCookie.value};")
                    }
                }
            }

            if (alternativeHeaders.isEmpty()) {
                val builder = try {
                    Request.Builder().url(url.trim())
                } catch (_: Exception) {
                    null
                }
                originalUrl.toHttpUrlOrNull()?.let { referer ->
                    builder?.addHeader("Referer", referer.toString())
                }

                builder?.addHeader("User-Agent", userAgent)

                try {
                    if (cookies.isNotEmpty()) {
                        builder?.addHeader("Cookie", stringBuilder.toString())
                    }
                } catch (e: Exception) {
                    AppLogger.d("Url parse error ${e.message}")
                }
                return builder

            } else {
                val builder = try {
                    Request.Builder().url(url.trim())
                } catch (_: Exception) {
                    null
                }
                val sanitizedHeaders = alternativeHeaders.toHeaders().newBuilder()
                    .removeAll("Cookie")
                    .build()
                builder?.headers(sanitizedHeaders)
                if (cookies.isNotEmpty()) {
                    builder?.addHeader("Cookie", stringBuilder.toString())
                }

                return builder
            }
        } catch (e: Throwable) {
            AppLogger.e("Detection: buildRequest failed url=$url", e)
        }

        return null
    }

    fun propagateCheckJob(
        url: String,
        headersMap: Map<String, String>,
        isCheckOnAudio: Boolean,
        isCheckOnVideo: Boolean,
        pageUrl: String? = webTabModel?.getTabTextInput()?.get(),
        shouldPublish: () -> Boolean = { true },
        onVideoDetected: (VideoInfo) -> Unit = { pushNewVideoInfoToAll(it) }
    ) {
        if (!shouldPublish()) {
            return
        }
        val threshold = settingsModel.videoDetectionThreshold.get()

        val initialUrl = url.toHttpUrlOrNull()?.toUrl() ?: return
        val finalUrlPair = runCatching {
            CookieUtils.getFinalRedirectURL(
                initialUrl,
                headersMap,
                okHttpProxyClient.getProxyOkHttpClient()
            )
        }.getOrNull() ?: return
        if (!shouldPublish()) {
            return
        }
        val finalHeaders = finalUrlPair.second.toMap()

        runCatching {
            val request =
                Request.Builder().url(finalUrlPair.first).headers(finalHeaders.toHeaders()).build()

            okHttpProxyClient.getProxyOkHttpClient().newCall(request).execute().use { response ->
                val contentType = response.body.contentType().toString()
                val contentLength = response.contentLengthOrUnknown()
                    .takeIf { it > 0 }
                    ?: probeContentLength(finalUrlPair.first, finalHeaders)

                if (response.code == 403 || response.code == 401) {
                    handleUnauthorizedResponse(
                        url,
                        pageUrl,
                        threshold,
                        isCheckOnAudio,
                        isCheckOnVideo,
                        shouldPublish,
                        onVideoDetected
                    )
                    return
                }

                val isTikTok = url.contains(".tiktok.com/")
                val isRegularStreamDetectionOn = settingsModel.isForceStreamDetection.get()

                val isVideo = contentType.contains("video", true)
                val isAudio = contentType.contains("audio", true)

                val tikTokThreshold = 1024 * 1024 / 3 // ~333KB
                val isLargeEnoughForTikTok = isTikTok && contentLength > tikTokThreshold
                val isAboveUserThreshold = contentLength > threshold
                val isStreamDetectionOn = isRegularStreamDetectionOn

                val isVideoContent =
                    isVideo && isCheckOnVideo && (isAboveUserThreshold || isLargeEnoughForTikTok || isStreamDetectionOn)

                val isAudioContent = isAudio && isCheckOnAudio

                if (!shouldPublish()) {
                    return
                }
                if (isVideoContent) {
                    setMediaInfoWrapperFromUrl(
                        finalUrlPair.first,
                        pageUrl,
                        finalHeaders,
                        contentLength,
                        shouldPublish = shouldPublish,
                        onVideoDetected = onVideoDetected
                    )
                } else if (isAudioContent) {
                    setMediaInfoWrapperFromUrl(
                        finalUrlPair.first,
                        pageUrl,
                        finalHeaders,
                        contentLength,
                        isAudio = true,
                        shouldPublish = shouldPublish,
                        onVideoDetected = onVideoDetected
                    )
                }
            }
        }.onFailure { e ->
            AppLogger.e("Detection: propagateCheck failed url=$url", e)
            setDetectionError(e, shouldPublish = shouldPublish)
        }
    }

    private fun handleUnauthorizedResponse(
        url: String,
        pageUrl: String?,
        threshold: Int,
        isCheckOnAudio: Boolean,
        isCheckOnVideo: Boolean,
        shouldPublish: () -> Boolean,
        onVideoDetected: (VideoInfo) -> Unit
    ) {
        if (!shouldPublish()) {
            return
        }
        val initialUrl = url.toHttpUrlOrNull()?.toUrl() ?: return
        val finalUrlPairEmpty = runCatching {
            CookieUtils.getFinalRedirectURL(
                initialUrl,
                emptyMap(),
                okHttpProxyClient.getProxyOkHttpClient()
            )
        }.getOrNull() ?: return
        if (!shouldPublish()) {
            return
        }
        val finalHeaders = finalUrlPairEmpty.second.toMap()

        runCatching {
            val request = Request.Builder()
                .url(finalUrlPairEmpty.first)
                .headers(finalHeaders.toHeaders())
                .build()
            okHttpProxyClient.getProxyOkHttpClient().newCall(request).execute().use { response ->
                val contentType = response.body.contentType().toString()
                val contentLength = response.contentLengthOrUnknown()
                    .takeIf { it > 0 }
                    ?: probeContentLength(finalUrlPairEmpty.first, finalHeaders)

                if (!shouldPublish()) {
                    return
                }
                when {
                    contentType.contains(
                        "video", true
                    ) && isCheckOnVideo && contentLength > threshold.toLong() -> {
                        setMediaInfoWrapperFromUrl(
                            finalUrlPairEmpty.first,
                            pageUrl,
                            finalHeaders,
                            contentLength,
                            shouldPublish = shouldPublish,
                            onVideoDetected = onVideoDetected
                        )
                    }

                    contentType.contains("audio", true) && isCheckOnAudio -> {
                        setMediaInfoWrapperFromUrl(
                            finalUrlPairEmpty.first,
                            pageUrl,
                            finalHeaders,
                            contentLength,
                            isAudio = true,
                            shouldPublish = shouldPublish,
                            onVideoDetected = onVideoDetected
                        )
                    }
                }
            }
        }.onFailure { error ->
            AppLogger.e("Detection: unauthorized retry failed url=$url", error)
            setDetectionError(error, shouldPublish = shouldPublish)
        }
    }

    private fun setMediaInfoWrapperFromUrl(
        url: URL,
        originalUrl: String?,
        alternativeHeaders: Map<String, String> = emptyMap(),
        contentLength: Long,
        isAudio: Boolean = false,
        shouldPublish: () -> Boolean = { true },
        onVideoDetected: (VideoInfo) -> Unit = { pushNewVideoInfoToAll(it) }
    ) {
        try {
            if (!url.toString().startsWith("http") || !shouldPublish()) {
                return
            }
            val urlString = url.toString()
            val sourcePageUrl = originalUrl
                ?.trim()
                ?.takeIf { it.toHttpUrlOrNull() != null }
            val inferredHeight = inferHeightFromUrl(urlString)
            val inferredWidth = inferWidthFromUrl(urlString)
            val normalizedContentLength = contentLength.takeIf { it > 0 } ?: 0L
            val qualityLabel = inferredHeight.takeIf { it > 0 }?.let { "${it}p" }

            val requestData = Request.Builder()
                .url(urlString)
                .headers(alternativeHeaders.toHeaders())
                .build()
                .toDownloadRequestData()

            val downloadUrls = listOf(requestData)

            val video = VideoInfoWrapper(
                VideoInfo(
                    downloadUrls = downloadUrls,
                    title = webTabModel?.currentTitle?.get()?.takeIf { it.isNotBlank() }
                        ?: sourcePageUrl?.toHttpUrlOrNull()?.host
                        ?: url.host.takeIf { it.isNotBlank() }
                        ?: "no_title",
                    ext = if (isAudio) "mp3" else "mp4",
                    originalUrl = sourcePageUrl ?: urlString,
                    // TODO format regular file link
                    formats = VideFormatEntityList(
                        mutableListOf(
                            VideoFormatEntity(
                                formatId = "0",
                                format = if (isAudio) "audio" else qualityLabel
                                    ?: ContextUtils.getApplicationContext()
                                    .getString(R.string.player_resolution),
                                formatNote = qualityLabel,
                                ext = if (isAudio) "mp3" else "mp4",
                                url = requestData.url,
                                httpHeaders = requestData.headers,
                                width = inferredWidth,
                                height = inferredHeight,
                                fileSize = normalizedContentLength
                            )
                        )
                    ),
                    isRegularDownload = true
                )
            )
            video.videoInfo?.takeIf { shouldPublish() }?.let(onVideoDetected)
        } catch (e: Throwable) {
            AppLogger.e("Detection: setMediaInfo failed", e)
            setDetectionError(e, shouldPublish = shouldPublish)
        }
    }

    private fun Response.contentLengthOrUnknown(): Long {
        val bodyLength = body.contentLength()
        if (bodyLength > 0) {
            return bodyLength
        }

        header("Content-Length")?.toLongOrNull()?.takeIf { it > 0 }?.let {
            return it
        }

        val rangeTotal = header("Content-Range")
            ?.substringAfterLast("/", "")
            ?.toLongOrNull()
            ?.takeIf { it > 0 }

        return rangeTotal ?: 0L
    }

    private fun probeContentLength(url: URL, headersMap: Map<String, String>): Long {
        val headers = headersMap.toHeaders()

        runCatching {
            val headRequest = Request.Builder()
                .url(url)
                .headers(headers)
                .head()
                .build()
            okHttpProxyClient.getProxyOkHttpClient().newCall(headRequest).execute().use { response ->
                response.contentLengthOrUnknown().takeIf { it > 0 }?.let {
                    return it
                }
            }
        }

        return runCatching {
            val rangeRequest = Request.Builder()
                .url(url)
                .headers(headers)
                .header("Range", "bytes=0-0")
                .build()
            okHttpProxyClient.getProxyOkHttpClient().newCall(rangeRequest).execute().use { response ->
                response.contentLengthOrUnknown()
            }
        }.getOrDefault(0L)
    }

    private fun inferHeightFromUrl(url: String): Int {
        Regex("""(\d{3,4})p""", RegexOption.IGNORE_CASE).find(url)?.let {
            return it.groupValues[1].toIntOrNull() ?: 0
        }

        Regex("""\d{3,5}x(\d{3,5})""").find(url)?.let {
            return it.groupValues[1].toIntOrNull() ?: 0
        }

        return 0
    }

    private fun inferWidthFromUrl(url: String): Int {
        Regex("""(\d{3,5})x\d{3,5}""").find(url)?.let {
            return it.groupValues[1].toIntOrNull() ?: 0
        }

        return 0
    }
}
