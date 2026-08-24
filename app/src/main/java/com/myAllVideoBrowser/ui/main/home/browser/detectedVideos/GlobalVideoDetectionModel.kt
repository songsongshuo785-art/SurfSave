package com.myAllVideoBrowser.ui.main.home.browser.detectedVideos

import android.os.Handler
import android.os.Looper
import androidx.databinding.Observable
import androidx.databinding.ObservableBoolean
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.data.repository.VideoRepository
import com.myAllVideoBrowser.ui.main.home.browser.DownloadButtonStateCanDownload
import com.myAllVideoBrowser.ui.main.home.browser.DownloadButtonStateCanNotDownload
import com.myAllVideoBrowser.ui.main.home.browser.DownloadButtonStateLoading
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.proxy_utils.OkHttpProxyClient
import com.myAllVideoBrowser.util.scheduler.BaseSchedulers
import io.reactivex.rxjava3.disposables.Disposable
import okhttp3.Request
import javax.inject.Inject

class GlobalVideoDetectionModel @Inject constructor(
    private val videoRepository: VideoRepository,
    private val baseSchedulers: BaseSchedulers,
    okHttpProxyClient: OkHttpProxyClient,
) : VideoDetectionTabViewModel(videoRepository, baseSchedulers, okHttpProxyClient), IVideoDetector {
    private var lastVerifiedLink: String = ""
    private var lastVerifiedM3u8PointUrl = Pair("", "")
    private val serviceWorkerContexts = ServiceWorkerDetectionContextTracker()

    private val butonStateCallBack = object :
        Observable.OnPropertyChangedCallback() {
        override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
            runOnMain {
                when (downloadButtonState.get()) {
                    is DownloadButtonStateCanNotDownload -> downloadButtonIcon.set(R.drawable.refresh_24px)
                    is DownloadButtonStateCanDownload -> downloadButtonIcon.set(R.drawable.ic_download_24dp)
                    is DownloadButtonStateLoading -> {
                        downloadButtonIcon.set(R.drawable.invisible_24px)
                    }
                }
            }
        }
    }

    override fun start() {
        downloadButtonState.addOnPropertyChangedCallback(butonStateCallBack)
    }

    override fun stop() {
        downloadButtonState.removeOnPropertyChangedCallback(butonStateCallBack)
        super.stop()
    }

    override fun cancelAllCheckJobs() {
        super.cancelAllCheckJobs()

        lastVerifiedLink = ""
        lastVerifiedM3u8PointUrl = Pair("", "")
    }

    @Synchronized
    fun activateServiceWorkerContext(
        tabId: String,
        pageUrl: String,
        forceNewGeneration: Boolean = false
    ) {
        val previous = serviceWorkerContexts.snapshot()
        val current = serviceWorkerContexts.activate(tabId, pageUrl, forceNewGeneration)
        if (previous != current) {
            cancelAllCheckJobs()
        }
    }

    fun currentServiceWorkerContext(): ServiceWorkerDetectionContext? =
        serviceWorkerContexts.snapshot()

    fun serviceWorkerContextForRequest(
        headers: Map<String, String>,
        allowHeaderlessMedia: Boolean = false
    ): ServiceWorkerDetectionContext? = serviceWorkerContexts.contextForRequest(
        headers,
        allowHeaderlessMedia
    )

    override fun hasCheckLoadingsRegular(): ObservableBoolean {
        return ObservableBoolean(false)
    }

    override fun hasCheckLoadingsM3u8(): ObservableBoolean {
        return ObservableBoolean(false)
    }

    override fun verifyLinkStatus(
        resourceRequest: Request, hlsTitle: String?, isM3u8: Boolean, isMpd: Boolean
    ) = verifyLinkStatusInternal(resourceRequest, hlsTitle, isM3u8, isMpd, null)

    fun verifyLinkStatus(
        context: ServiceWorkerDetectionContext,
        resourceRequest: Request,
        hlsTitle: String?,
        isM3u8: Boolean,
        isMpd: Boolean
    ) = verifyLinkStatusInternal(resourceRequest, hlsTitle, isM3u8, isMpd, context)

    private fun verifyLinkStatusInternal(
        resourceRequest: Request,
        hlsTitle: String?,
        isM3u8: Boolean,
        isMpd: Boolean,
        context: ServiceWorkerDetectionContext?
    ) {
        if (!isContextCurrent(context)) {
            return
        }
        if (resourceRequest.url.toString().contains("tiktok.")) {
            return
        }

        val urlToVerify = resourceRequest.url.toString()

        if (lastVerifiedLink != urlToVerify) {
            val currentPageUrl = "${resourceRequest.url}"

            if (isM3u8 || isMpd) {
                if ((currentPageUrl == lastVerifiedM3u8PointUrl.first && lastVerifiedM3u8PointUrl.second != urlToVerify) || currentPageUrl != lastVerifiedM3u8PointUrl.first) {
                    lastVerifiedM3u8PointUrl = Pair(currentPageUrl, urlToVerify)

                    startVerifyProcessInternal(resourceRequest, isM3u8, isMpd, hlsTitle, context)
                }
            } else {
                if (urlToVerify.contains(
                        ".txt"
                    )
                ) {
                    return
                }
                lastVerifiedLink = urlToVerify

                if (settingsModel.getIsFindVideoByUrl().get()) {
                    startVerifyProcessInternal(
                        resourceRequest,
                        isM3u8 = false,
                        isMpd = false,
                        hlsTitle = null,
                        context = context
                    )
                }
            }
        }
    }

    override fun startVerifyProcess(
        resourceRequest: Request, isM3u8: Boolean, isMpd: Boolean, hlsTitle: String?
    ) = startVerifyProcessInternal(resourceRequest, isM3u8, isMpd, hlsTitle, null)

    private fun startVerifyProcessInternal(
        resourceRequest: Request,
        isM3u8: Boolean,
        isMpd: Boolean,
        hlsTitle: String?,
        context: ServiceWorkerDetectionContext?
    ) {
        if (!isContextCurrent(context)) {
            return
        }
        val taskUrl = resourceRequest.url.toString()

        val registered = verifyVideoLinkJobStorage.tryRegister(taskUrl) { holder ->
            io.reactivex.rxjava3.core.Observable.create { emitter ->
                if (!isContextCurrent(context)) {
                    emitter.onComplete()
                    return@create
                }
                setButtonState(DownloadButtonStateLoading()) { isContextCurrent(context) }
                val info = try {
                    if (isM3u8 || isMpd) {
                        videoRepository.getVideoInfoBySuperXDetector(
                            resourceRequest,
                            isM3u8,
                            isMpd,
                            settingsModel.isCheckOnAudio.get()
                        )
                    } else {
                        videoRepository.getVideoInfo(
                            resourceRequest,
                            false,
                            settingsModel.isCheckOnAudio.get()
                        )
                    }
                } catch (e: Throwable) {
                    AppLogger.e("GlobalDetection: verify failed url=$taskUrl", e)
                    setDetectionError(e, shouldPublish = { isContextCurrent(context) })
                    null
                }
                if (info != null) {
                    emitter.onNext(info)
                } else {
                    emitter.onNext(VideoInfo(id = ""))
                }
                emitter.onComplete()
            }.doOnTerminate {
                verifyVideoLinkJobStorage.finish(taskUrl, holder)
            }.observeOn(baseSchedulers.mainThread).subscribeOn(baseSchedulers.io).subscribe { info ->
                if (!isContextCurrent(context)) {
                    return@subscribe
                }
                val isLastNotEmpty = lastVerifiedLink.isNotEmpty()

                if (info.id.isNotEmpty()) {
                    if (info.isM3u8 && !hlsTitle.isNullOrEmpty()) {
                        info.title = hlsTitle
                    }
                    val state = downloadButtonState.get()
                    if (state is DownloadButtonStateCanDownload) {
                        if (state.info?.isRegularDownload == true) {
                            AppLogger.d(
                                "Watching set new info state with Regular Download... currentState: $state skippingInfo: $info"
                            )
                        }
                    }
                    if (state is DownloadButtonStateCanDownload && state.info?.isM3u8 == true && state.info.isMaster && isLastNotEmpty || (state is DownloadButtonStateCanDownload && state.info?.isRegularDownload != true && info.isRegularDownload) || state is DownloadButtonStateLoading && info.isRegularDownload) {
                        AppLogger.d(
                            "Skipping set new info state... currentState: $state skippingInfo: $info"
                        )
                    } else {
                        AppLogger.d(
                            "Setting set new info state... state: $state info: $info"
                        )
                        pushNewVideoInfoForContext(context, info)
                    }
                } else {
                    setButtonState(DownloadButtonStateCanNotDownload()) {
                        isContextCurrent(context)
                    }
                }
            }
        }

        if (!registered) return
    }

    override fun checkRegularVideoOrAudio(
        request: Request?,
        isCheckOnAudio: Boolean,
        isCheckOnVideo: Boolean
    ): Disposable? = checkRegularVideoOrAudioInternal(
        request,
        isCheckOnAudio,
        isCheckOnVideo,
        null
    )

    fun checkRegularVideoOrAudio(
        context: ServiceWorkerDetectionContext,
        request: Request?,
        isCheckOnAudio: Boolean,
        isCheckOnVideo: Boolean
    ): Disposable? = checkRegularVideoOrAudioInternal(
        request,
        isCheckOnAudio,
        isCheckOnVideo,
        context
    )

    private fun checkRegularVideoOrAudioInternal(
        request: Request?,
        isCheckOnAudio: Boolean,
        isCheckOnVideo: Boolean,
        context: ServiceWorkerDetectionContext?
    ): Disposable? {
        if (request == null || !isContextCurrent(context)) {
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

        val shouldPublish = { isContextCurrent(context) }
        val disposable = io.reactivex.rxjava3.core.Observable.create<Unit> {
            if (shouldPublish()) {
                if (request.url.toString().contains(".mp4")) {
                    setButtonState(DownloadButtonStateLoading(), shouldPublish)
                }
                propagateCheckJob(
                    uriString,
                    headers,
                    isCheckOnAudio,
                    isCheckOnVideo,
                    pageUrl = context?.pageUrl ?: webTabModel?.getTabTextInput()?.get(),
                    shouldPublish = shouldPublish,
                    onVideoDetected = { info -> pushNewVideoInfoForContext(context, info) }
                )
            }
            it.onComplete()
        }.subscribeOn(baseSchedulers.io).doOnComplete {
            AppLogger.d("CHECK REGULAR MP4 IN BACKGROUND DONE")
        }.doOnError { e ->
            AppLogger.e("GlobalDetection: regularCheck failed url=$clearedUrl", e)
            setDetectionError(e, shouldPublish = shouldPublish)
        }.onErrorComplete().subscribe()

        return disposable
    }

    override fun pushNewVideoInfoToAll(newInfo: VideoInfo) {
        pushNewVideoInfoForContext(null, newInfo)
    }

    private fun pushNewVideoInfoForContext(
        expectedContext: ServiceWorkerDetectionContext?,
        newInfo: VideoInfo
    ) {
        if (!isContextCurrent(expectedContext)) {
            return
        }
        if (newInfo.formats.formats.isEmpty()) {
            return
        }

        if (shouldSkipShortVideo(newInfo)) {
            AppLogger.d("SKIP SHORT GLOBAL VIDEO INFO: ${newInfo.duration}ms $newInfo")
            return
        }

        setButtonState(DownloadButtonStateLoading()) {
            isContextCurrent(expectedContext)
        }
        Handler(Looper.getMainLooper()).postDelayed({
            if (isContextCurrent(expectedContext)) {
                setButtonState(DownloadButtonStateCanDownload(newInfo)) {
                    isContextCurrent(expectedContext)
                }
            }
        }, 400)
    }

    private fun isContextCurrent(context: ServiceWorkerDetectionContext?): Boolean =
        context == null || serviceWorkerContexts.isCurrent(context)

    override fun onStartPage(url: String, userAgentString: String) {

    }

    override fun showVideoInfo() {

    }
}
