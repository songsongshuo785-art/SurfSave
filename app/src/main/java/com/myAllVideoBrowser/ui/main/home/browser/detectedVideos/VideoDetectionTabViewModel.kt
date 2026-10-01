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
import com.myAllVideoBrowser.ui.main.home.browser.BrowserDownloadRequest
import com.myAllVideoBrowser.ui.main.home.browser.BrowserMediaClassifier
import com.myAllVideoBrowser.ui.main.home.browser.ContentType
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
import com.myAllVideoBrowser.util.contentLengthOrUnknown
import com.myAllVideoBrowser.util.telegram.TelegramPostResolution
import com.myAllVideoBrowser.util.proxy_utils.OkHttpProxyClient
import com.myAllVideoBrowser.util.scheduler.BaseSchedulers
import io.reactivex.rxjava3.disposables.Disposable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Headers.Companion.toHeaders
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.net.HttpCookie
import java.net.URI
import java.net.URL
import java.util.LinkedHashSet
import java.util.concurrent.Executors
import javax.inject.Inject
import kotlin.math.abs

open class VideoDetectionTabViewModel @Inject constructor(
    private val videoRepository: VideoRepository,
    private val baseSchedulers: BaseSchedulers,
    private val okHttpProxyClient: OkHttpProxyClient,
) : BaseViewModel(), IVideoDetector {
    companion object {
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

        /**
         * True when an image candidate with the same URL identity is already known.
         * Used to skip the HEAD/Range probe on repeated scans of the same page.
         */
        internal fun isImageAlreadyDetected(existing: Collection<VideoInfo>, url: String): Boolean {
            val identity = normalizeMediaUrl(url)
            if (identity.isBlank()) return false

            return existing.any { info ->
                info.isImage && identity in mediaIdentityUrls(info)
            }
        }

        internal fun mediaIdentityUrls(info: VideoInfo): Set<String> {
            val formatUrls = info.formats.formats.flatMap {
                listOfNotNull(it.url, it.manifestUrl, it.videoOnlyUrl, it.audioOnlyUrl)
            }
            val downloadUrls = info.downloadUrls.map { it.url }
            return (formatUrls + downloadUrls)
                .map { normalizeMediaUrl(it) }
                .filter { it.isNotBlank() }
                .toSet()
        }

        internal fun normalizeMediaUrl(rawUrl: String?): String = MediaUrlIdentity.of(rawUrl)

        /**
         * Decides whether the detected-media list may survive an [onStartPage]
         * event for [url].
         *
         * Only the very first load of the tab's initial URL keeps the list (first
         * open, restored state). Navigating away and back (A -> B -> A) must clear
         * the other page's media: stale entries would otherwise pollute the
         * already-detected check for the initial page and linger in the panel.
         */
        internal fun shouldKeepDetectedMediaOnPageStart(
            url: String,
            initialUrl: String,
            initialPageStarted: Boolean
        ): Boolean = !initialPageStarted && url == initialUrl
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

    val imageScanRequestedEvent = SingleLiveEvent<Void?>()

    val detectionFeedbackEvent = SingleLiveEvent<String>()

    @Volatile
    var downloadButtonState =
        ObservableField<DownloadButtonState>(DownloadButtonStateCanNotDownload())

    val executorReload = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    var webTabModel: WebTabViewModel? = null
    lateinit var settingsModel: SettingsViewModel
    val detectedVideosList = ObservableField(setOf<VideoInfo>())
    val sortedDetectedVideosList = ObservableField<List<VideoInfo>>(emptyList())
    val sortedDetectedImagesList = ObservableField<List<VideoInfo>>(emptyList())
    val selectedImageIds = ObservableField<Set<String>>(emptySet())
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
    val detectedImagesCount = ObservableInt(0)
    val hasDetectedVideos = ObservableBoolean(false)
    val hasDetectedImages = ObservableBoolean(false)
    val hasDetectedMedia = ObservableBoolean(false)
    val hasSelectedImages = ObservableBoolean(false)
    val selectedImagesCount = ObservableInt(0)
    val detectedVideosBadgeText = ObservableField("")
    val detectedImagesBadgeText = ObservableField("")
    val lastDetectionError = ObservableField<String?>()
    val detectionStatusText = ObservableField("")
    val hasDetectionStatus = ObservableBoolean(false)
    val detectionStatusIsError = ObservableBoolean(false)

    @Volatile
    var verifyVideoLinkJobStorage = com.myAllVideoBrowser.util.DisposableJobRegistry()

    private val hasCheckLoadingsM3u8 = ObservableBoolean(false)
    private val hasCheckLoadingsRegular = ObservableBoolean(false)

    private val executorRegular = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val imageProbePermits = Semaphore(4)
    private val imageProbeGate = ImageProbeGate()

    @Volatile
    private var lastUrl = ""

    /** Set once the tab's initial URL has produced its first onStartPage event. */
    private var initialPageStarted = false

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

        val keepDetectedMedia =
            shouldKeepDetectedMediaOnPageStart(url, initialUrl, initialPageStarted)
        if (url == initialUrl) {
            initialPageStarted = true
        }
        if (keepDetectedMedia) {
            AppLogger.d("onStartPage: first load of the initial url. Skipped clearing list.")
        } else {
            AppLogger.d("onStartPage: clearing list for the new page.")
            setDetectedVideosNow(mutableSetOf())
            cancelAllCheckJobs()
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
            val videos = detectedVideosList.get().orEmpty().filterNot { it.isImage }.toSet()
            sortedDetectedVideosList.set(sortDetectedVideos(videos))
            clearImageSelectionNow()
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
            if (!hasDetectedVideos.get()) {
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
            val videos = detectedVideosList.get().orEmpty().filterNot { it.isImage }.toSet()
            sortedDetectedVideosList.set(sortDetectedVideos(videos))
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
            !hasDetectedVideos.get()
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

        if (hasDetectedMedia.get()) {
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
            if (!hasDetectedVideos.get()) {
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

        if (newInfo.isImage) {
            pushNewImageInfo(newInfo)
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

    @Synchronized
    fun pushNewImageInfo(newInfo: VideoInfo) {
        if (!newInfo.isImage || newInfo.id.isBlank() || newInfo.formats.formats.isEmpty()) {
            return
        }

        val detectedMedia = detectedVideosList.get().orEmpty()
        val duplicate = detectedMedia.firstOrNull { existing ->
            existing.isImage && mediaIdentityUrls(existing).intersect(mediaIdentityUrls(newInfo)).isNotEmpty()
        }
        if (duplicate != null) {
            val merged = mergeDuplicateVideoInfo(duplicate, newInfo)
            setDetectedVideosNow(detectedMedia - duplicate + merged)
            return
        }

        setDetectedVideosNow(LinkedHashSet<VideoInfo>(detectedMedia.size + 1).apply {
            addAll(detectedMedia)
            add(newInfo)
        })
        clearDetectionStatus()
    }

    /**
     * WebView image scans cannot see cross-origin response headers. Probe the
     * image with the same restricted browser headers before creating the
     * candidate so extension and MIME metadata match the actual resource.
     *
     * Candidates whose URL identity is already detected, or already being probed
     * for the same page generation by a concurrent scan, are skipped without a
     * network round-trip. Probes are keyed by page generation so an in-flight
     * probe for a stale page never suppresses the same image on the current page.
     */
    fun resolveAndPushImageInfo(
        request: BrowserDownloadRequest,
        fallbackTitle: String?,
        pageGeneration: Long? = null
    ) {
        // Repeated scans of the same page must not re-issue HEAD/Range probes for
        // images that are already in the list. The candidate is deduplicated by
        // the same URL identity pushNewImageInfo() would use, but before the
        // network round-trip.
        if (isImageCandidateAlreadyDetected(request.url)) {
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            if (pageGeneration != null && !isCurrentPageGeneration(pageGeneration)) {
                return@launch
            }
            if (isImageCandidateAlreadyDetected(request.url)) {
                return@launch
            }

            // Claim the probe identity before competing for a permit, so duplicate
            // candidates for the same image never occupy one of the probe permits.
            val probeIdentity = normalizeMediaUrl(request.url)
            if (!imageProbeGate.tryAcquire(pageGeneration, probeIdentity)) {
                return@launch
            }

            try {
                // The coroutines Semaphore suspends waiters instead of blocking a
                // Dispatchers.IO thread, so a large scan cannot park threads while
                // waiting for one of the four probe slots.
                imageProbePermits.withPermit {
                    // The waiter may have been suspended for a while, during which
                    // the page can have navigated; re-check before the round-trip so
                    // stale probes never hit the network.
                    if (pageGeneration != null && !isCurrentPageGeneration(pageGeneration)) {
                        return@withPermit
                    }

                    val resolvedRequest = resolveImageResponseMetadata(request)
                    val imageInfo = resolvedRequest.toDirectMediaVideoInfo(fallbackTitle)
                    if (imageInfo != null &&
                        (pageGeneration == null || isCurrentPageGeneration(pageGeneration))
                    ) {
                        // Publish on the main thread before releasing the probe gate, so
                        // the detected list already contains the image when the next scan
                        // asks whether it still needs to be probed.
                        withContext(Dispatchers.Main.immediate) {
                            if (pageGeneration == null || isCurrentPageGeneration(pageGeneration)) {
                                pushNewImageInfo(imageInfo)
                            }
                        }
                    }
                }
            } finally {
                imageProbeGate.release(pageGeneration, probeIdentity)
            }
        }
    }

    fun isCurrentPageGeneration(pageGeneration: Long): Boolean =
        protectedMediaPageTracker.snapshot().generation == pageGeneration

    @Synchronized
    private fun isImageCandidateAlreadyDetected(url: String): Boolean {
        return isImageAlreadyDetected(detectedVideosList.get().orEmpty(), url)
    }

    private fun resolveImageResponseMetadata(
        request: BrowserDownloadRequest
    ): BrowserDownloadRequest {
        if (!request.isHttpRequest() || request.mediaType() != ContentType.IMAGE) {
            return request
        }

        val headers = request.allowedDownloadHeaders().toHeaders()
        val client = okHttpProxyClient.getProxyOkHttpClient()
        val probeRequests = listOf(
            Request.Builder()
                .url(request.url)
                .headers(headers)
                .head()
                .build(),
            Request.Builder()
                .url(request.url)
                .headers(headers)
                .header("Range", "bytes=0-0")
                .get()
                .build()
        )

        probeRequests.forEach { probeRequest ->
            val resolved = runCatching {
                client.newCall(probeRequest).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@use null
                    }

                    val responseMimeType = response.header("Content-Type")
                    val responseContentDisposition = response.header("Content-Disposition")
                    val candidate = request.withResponseMetadata(
                        responseMimeType = responseMimeType,
                        responseContentDisposition = responseContentDisposition,
                        responseContentLength = response.contentLengthOrUnknown()
                    )
                    val hasAuthoritativeImageMime = responseMimeType
                        ?.substringBefore(';')
                        ?.trim()
                        ?.startsWith("image/", ignoreCase = true) == true
                    val hasImageDisposition = responseContentDisposition
                        ?.let {
                            BrowserMediaClassifier.classify(
                                url = request.url,
                                contentDisposition = it
                            ) == ContentType.IMAGE
                        } == true

                    if (hasAuthoritativeImageMime || hasImageDisposition) candidate else null
                }
            }.getOrNull()
            if (resolved != null) {
                return resolved
            }
        }

        // A few CDNs reject both HEAD and range requests. Keep the original
        // candidate as a last-resort fallback rather than hiding a detected
        // image solely because metadata probing was unavailable.
        return request
    }

    fun requestImageScan() {
        imageScanRequestedEvent.call()
    }

    fun toggleImageSelection(imageInfo: VideoInfo) {
        if (!imageInfo.isImage) return
        runOnMain {
            val current = selectedImageIds.get().orEmpty()
            updateImageSelectionNow(
                if (imageInfo.id in current) current - imageInfo.id else current + imageInfo.id
            )
        }
    }

    fun selectAllImages() {
        runOnMain {
            updateImageSelectionNow(sortedDetectedImagesList.get().orEmpty().map { it.id }.toSet())
        }
    }

    fun clearImageSelection() {
        runOnMain { clearImageSelectionNow() }
    }

    fun isImageSelected(imageId: String): Boolean = imageId in selectedImageIds.get().orEmpty()

    fun selectedImages(): List<VideoInfo> {
        val selected = selectedImageIds.get().orEmpty()
        return sortedDetectedImagesList.get().orEmpty().filter { it.id in selected }
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
            val images = videos.filter { it.isImage }
            val playableVideos = videos.filterNot { it.isImage }.toSet()
            detectedVideosList.set(videos)
            sortedDetectedVideosList.set(sortDetectedVideos(playableVideos))
            sortedDetectedImagesList.set(images)
            detectedVideosCount.set(playableVideos.size)
            detectedImagesCount.set(images.size)
            hasDetectedVideos.set(playableVideos.isNotEmpty())
            hasDetectedImages.set(images.isNotEmpty())
            hasDetectedMedia.set(videos.isNotEmpty())
            detectedVideosBadgeText.set(
                when {
                    playableVideos.isEmpty() -> ""
                    playableVideos.size > 99 -> "99+"
                    else -> playableVideos.size.toString()
                }
            )
            detectedImagesBadgeText.set(
                when {
                    images.isEmpty() -> ""
                    images.size > 99 -> "99+"
                    else -> images.size.toString()
                }
            )
            updateImageSelectionNow(
                selectedImageIds.get().orEmpty().intersect(images.map { it.id }.toSet())
            )
            if (videos.isNotEmpty()) {
                lastDetectionError.set(null)
                detectionStatusText.set("")
                hasDetectionStatus.set(false)
                detectionStatusIsError.set(false)
            }
        }
    }

    private fun updateImageSelectionNow(ids: Set<String>) {
        selectedImageIds.set(ids)
        selectedImagesCount.set(ids.size)
        hasSelectedImages.set(ids.isNotEmpty())
    }

    private fun clearImageSelectionNow() {
        updateImageSelectionNow(emptySet())
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
        if (hasDetectedMedia.get()) {
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
        if (hasDetectedMedia.get()) {
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
            if (hasDetectedMedia.get()) {
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
            !hasDetectedMedia.get()
    }

    private fun updateDetectionStatusAfterLoadingChange() {
        if (hasDetectedMedia.get()) {
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
                    val videos = sortedDetectedVideosList.get().orEmpty()
                    if (videos.isEmpty()) {
                        downloadButtonState.set(DownloadButtonStateCanNotDownload())
                    } else {
                        downloadButtonState.set(
                            DownloadButtonStateCanDownload(
                                videos.first()
                            )
                        )
                    }
                }

                is DownloadButtonStateLoading -> {
                    val list = sortedDetectedVideosList.get().orEmpty()
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
                val contentType = response.header("Content-Type").orEmpty()
                val mediaType = BrowserMediaClassifier.classify(
                    url = response.request.url.toString(),
                    contentType = contentType,
                    contentDisposition = response.header("Content-Disposition").orEmpty()
                )
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

                val isRegularStreamDetectionOn = settingsModel.isForceStreamDetection.get()

                val isVideo = mediaType == ContentType.VIDEO
                val isAudio = mediaType == ContentType.AUDIO

                val siteRule = SiteDetectionRules.forUrl(url)
                val isLargeEnoughForSiteRule = siteRule?.minimumContentLength
                    ?.takeIf { it > 0L }
                    ?.let { contentLength > it }
                    ?: false
                val isAboveUserThreshold = contentLength > threshold
                val isStreamDetectionOn = isRegularStreamDetectionOn

                val isVideoContent =
                    isVideo && isCheckOnVideo && (isAboveUserThreshold || isLargeEnoughForSiteRule || isStreamDetectionOn)

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
                val contentType = response.header("Content-Type").orEmpty()
                val mediaType = BrowserMediaClassifier.classify(
                    url = response.request.url.toString(),
                    contentType = contentType,
                    contentDisposition = response.header("Content-Disposition").orEmpty()
                )
                val contentLength = response.contentLengthOrUnknown()
                    .takeIf { it > 0 }
                    ?: probeContentLength(finalUrlPairEmpty.first, finalHeaders)

                if (!shouldPublish()) {
                    return
                }
                when {
                    mediaType == ContentType.VIDEO &&
                        isCheckOnVideo && contentLength > threshold.toLong() -> {
                        setMediaInfoWrapperFromUrl(
                            finalUrlPairEmpty.first,
                            pageUrl,
                            finalHeaders,
                            contentLength,
                            shouldPublish = shouldPublish,
                            onVideoDetected = onVideoDetected
                        )
                    }

                    mediaType == ContentType.AUDIO && isCheckOnAudio -> {
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

    /**
     * Android WebView 已把该请求判定为下载时，不再额外 GET 整个媒体文件。
     * 流清单继续交给既有解析器；普通音视频直接生成现有候选模型。
     */
    fun handleBrowserDownloadRequest(downloadRequest: BrowserDownloadRequest) {
        val mediaType = downloadRequest.mediaType()
        if (mediaType == ContentType.OTHER) return

        val mediaUrl = downloadRequest.url.toHttpUrlOrNull()?.toUrl() ?: return
        if (mediaType == ContentType.M3U8 || mediaType == ContentType.MPD) {
            val request = runCatching {
                Request.Builder()
                    .url(mediaUrl)
                    .headers(downloadRequest.allowedDownloadHeaders().toHeaders())
                    .build()
            }.getOrNull() ?: return
            verifyLinkStatus(
                request,
                downloadRequest.suggestedTitle(),
                mediaType == ContentType.M3U8,
                mediaType == ContentType.MPD
            )
            return
        }

        downloadRequest.toDirectMediaVideoInfo(webTabModel?.currentTitle?.get())
            ?.let(::pushNewVideoInfoToAll)
    }

    private fun setMediaInfoWrapperFromUrl(
        url: URL,
        originalUrl: String?,
        alternativeHeaders: Map<String, String> = emptyMap(),
        contentLength: Long,
        isAudio: Boolean = false,
        titleOverride: String? = null,
        extensionOverride: String? = null,
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
            val mediaExtension = extensionOverride?.takeIf { it.isNotBlank() }
                ?: if (isAudio) "mp3" else "mp4"

            val requestData = Request.Builder()
                .url(urlString)
                .headers(alternativeHeaders.toHeaders())
                .build()
                .toDownloadRequestData()

            val downloadUrls = listOf(requestData)

            val video = VideoInfoWrapper(
                VideoInfo(
                    downloadUrls = downloadUrls,
                    title = titleOverride?.takeIf { it.isNotBlank() }
                        ?: webTabModel?.currentTitle?.get()?.takeIf { it.isNotBlank() }
                        ?: sourcePageUrl?.toHttpUrlOrNull()?.host
                        ?: url.host.takeIf { it.isNotBlank() }
                        ?: "no_title",
                    ext = mediaExtension,
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
                                ext = mediaExtension,
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
