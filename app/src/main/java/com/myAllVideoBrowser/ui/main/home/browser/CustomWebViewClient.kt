package com.myAllVideoBrowser.ui.main.home.browser

import android.graphics.Bitmap
import android.os.Build
import android.webkit.HttpAuthHandler
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.lifecycle.viewModelScope
import com.myAllVideoBrowser.data.local.room.entity.HistoryItem
import com.myAllVideoBrowser.ui.main.history.HistoryViewModel
import com.myAllVideoBrowser.ui.main.settings.SettingsViewModel
import com.myAllVideoBrowser.util.FaviconUtils
import com.myAllVideoBrowser.util.proxy_utils.CustomProxyController
import com.myAllVideoBrowser.util.proxy_utils.OkHttpProxyClient
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.myAllVideoBrowser.ui.main.home.browser.detectedVideos.IVideoDetector
import com.myAllVideoBrowser.ui.main.home.browser.webTab.WebTab
import com.myAllVideoBrowser.ui.main.home.browser.webTab.WebTabViewModel
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.CookieUtils
import com.myAllVideoBrowser.util.SingleLiveEvent
import io.reactivex.rxjava3.disposables.Disposable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.io.ByteArrayInputStream

class CustomWebViewClient(
    private val tabViewModel: WebTabViewModel,
    private val settingsModel: SettingsViewModel,
    private val videoDetectionModel: IVideoDetector,
    private val historyModel: HistoryViewModel,
    private val okHttpProxyClient: OkHttpProxyClient,
    private val updateTabEvent: SingleLiveEvent<WebTab>,
    private val pageTabProvider: PageTabProvider,
    private val proxyController: CustomProxyController,
    private val onNavigationStateChanged: () -> Unit = {},
    private val onRenderProcessLost: (WebView?, Boolean) -> Unit = { _, _ -> },
    private val injectMediaProbe: (WebView) -> Unit = {}
) : WebViewClient() {
    var videoAlert: MaterialAlertDialogBuilder? = null
    private var lastSavedHistoryUrl: String = ""
    private var lastSavedTitleHistory: String = ""
    private var lastRegularCheckUrl = ""
    @Volatile
    private var currentPageUrl: String = ""
    private val regularJobsStorage = java.util.concurrent.ConcurrentHashMap<String, List<Disposable>>()
    private val requestInspector = BrowserRequestInspector(settingsModel)

    companion object {
        fun emptyResponse(): WebResourceResponse {
            return WebResourceResponse(
                "text/plain",
                "utf-8",
                ByteArrayInputStream("".toByteArray())
            )
        }
    }

    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
        val viewTitle = view?.title
        val title = tabViewModel.currentTitle.get()
        val userAgent = view?.settings?.userAgentString ?: tabViewModel.userAgent.get()

        if (url != null && lastSavedHistoryUrl != url) {
            videoDetectionModel.onStartPage(
                url,
                userAgent ?: BrowserFragment.MOBILE_USER_AGENT
            )
            tabViewModel.onUpdateVisitedHistory(
                url,
                title,
                userAgent
            )
            historyModel.viewModelScope.launch(historyModel.executorSingleHistory) {
                val icon = try {
                    FaviconUtils.getEncodedFaviconFromUrl(
                        okHttpProxyClient.getProxyOkHttpClient(), url
                    )
                } catch (_: Throwable) {
                    null
                }
                saveUrlToHistory(url, icon, viewTitle ?: title)
            }
        }
        onNavigationStateChanged()
        super.doUpdateVisitedHistory(view, url, isReload)
    }

    override fun onReceivedHttpAuthRequest(
        view: WebView?, handler: HttpAuthHandler?, host: String?, realm: String?
    ) {
        if (proxyController.getCurrentRunningProxy().host == host) {
            val creds = proxyController.getProxyCredentials()

            if (creds.first.isNotEmpty() || creds.second.isNotEmpty()) {
                handler?.proceed(creds.first, creds.second)
            }
        }
        super.onReceivedHttpAuthRequest(view, handler, host, realm)
    }

    override fun shouldInterceptRequest(
        view: WebView?, request: WebResourceRequest?
    ): WebResourceResponse? {
        if (request == null || request.url == null) {
            return null
        }

        val url = request.url.toString()
        val pageUrl = currentPageUrl.ifBlank { url }
        val inspection = requestInspector.inspect(url, pageUrl, request.isForMainFrame)

        if (inspection.shouldInspectMedia) {
            val requestWithCookies = try {
                CookieUtils.webResourceRequestToOkHttpRequest(request)
            } catch (_: Throwable) {
                null
            }
            when {

                inspection.shouldCheckStream -> {
                    if (requestWithCookies != null) {
                        historyModel.viewModelScope.launch(Dispatchers.Main) {
                            videoDetectionModel.verifyLinkStatus(
                                requestWithCookies,
                                tabViewModel.currentTitle.get(),
                                inspection.isM3u8,
                                inspection.isMpd
                            )
                        }
                    }
                    if (inspection.shouldInterruptResource) {
                        return emptyResponse()
                    }
                }

                else -> {
                    if (inspection.shouldCheckRegular) {
                        val currentUrl = pageUrl
                        historyModel.viewModelScope.launch(Dispatchers.Main) {
                            if (currentUrl != lastRegularCheckUrl) {
                                regularJobsStorage.remove(lastRegularCheckUrl)?.forEach {
                                    it.dispose()
                                }
                                lastRegularCheckUrl = currentUrl
                            }
                            if (requestWithCookies != null) {
                                val disposable = videoDetectionModel.checkRegularVideoOrAudio(
                                    requestWithCookies,
                                    inspection.shouldCheckAudio,
                                    inspection.shouldCheckVideo
                                )
                                if (disposable != null) {
                                    regularJobsStorage.compute(currentUrl) { _, existing ->
                                        (existing.orEmpty()) + disposable
                                    }
                                }
                            }
                        }
                        if (inspection.shouldInterruptResource) {
                            return emptyResponse()
                        }
                    }
                }
            }
        }

        return null
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        currentPageUrl = url
        onNavigationStateChanged()

        injectMediaProbe(view)
        videoAlert = null
        val pageTab = pageTabProvider.getPageTab(tabViewModel.thisTabIndex.get())
        val headers = pageTab.getHeaders() ?: emptyMap()
        val favi = pageTab.getFavicon() ?: view.favicon ?: favicon

        updateTabEvent.value = WebTab(
            url,
            view.title,
            favi,
            pageTab.getPageThumbnail(),
            pageTab.getPageThumbnailPath(),
            headers,
            view,
            id = pageTab.id
        )
        tabViewModel.onStartPage(url, view.title)
    }

    override fun shouldOverrideUrlLoading(view: WebView, url: WebResourceRequest): Boolean {
        val target = url.url.toString()
        val scheme = url.url.scheme.orEmpty().lowercase()

        return when {
            scheme == "http" || scheme == "https" -> {
                if (url.isForMainFrame && !tabViewModel.isTabInputFocused.get()) {
                    tabViewModel.setTabTextInput(target)
                }
                false
            }

            scheme == "blob" || scheme == "data" || scheme == "about" || scheme == "file" -> {
                false
            }

            else -> {
                true
            }
        }
    }

    override fun onPageFinished(view: WebView, url: String) {
        super.onPageFinished(view, url)
        currentPageUrl = url
        onNavigationStateChanged()
        injectMediaProbe(view)
        tabViewModel.finishPage(url)
    }

    override fun onRenderProcessGone(
        view: WebView?, detail: RenderProcessGoneDetail?
    ): Boolean {
        val pageTab = pageTabProvider.getPageTab(tabViewModel.thisTabIndex.get())

        val webView = pageTab.getWebView()
        if (view == webView) {
            val didCrash = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                detail?.didCrash() == true
            } else {
                true
            }
            onRenderProcessLost(view, didCrash)
            return true
        }

        return super.onRenderProcessGone(view, detail)
    }

    private suspend fun saveUrlToHistory(url: String, favicon: Bitmap?, title: String?) {
        val isTitleEmpty = title?.trim()?.isEmpty() == true

        if (!isTitleEmpty && lastSavedTitleHistory != title && lastSavedHistoryUrl != url && url.isNotEmpty() && !url.contains(
                "about:blank"
            )
        ) {
            lastSavedHistoryUrl = url
            lastSavedTitleHistory = title ?: ""

            val outputFavicon = FaviconUtils.bitmapToBytes(favicon)

            yield()

            historyModel.saveHistory(
                HistoryItem(
                    url = url, favicon = outputFavicon, title = title
                )
            )
        }
    }
}
