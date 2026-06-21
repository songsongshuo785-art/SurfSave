package com.myAllVideoBrowser.ui.main.home.browser

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.os.Message
import android.webkit.WebResourceRequest
import android.view.View
import android.view.WindowManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.databinding.FragmentWebTabBinding
import com.myAllVideoBrowser.ui.main.home.MainActivity
import com.myAllVideoBrowser.ui.main.home.browser.webTab.WebTab
import com.myAllVideoBrowser.ui.main.home.browser.webTab.WebTabViewModel
import com.myAllVideoBrowser.ui.main.settings.SettingsViewModel
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.AppUtil
import com.myAllVideoBrowser.util.SingleLiveEvent

class CustomWebChromeClient(
    private val tabViewModel: WebTabViewModel,
    private val settingsViewModel: SettingsViewModel,
    private val updateTabEvent: SingleLiveEvent<WebTab>,
    private val pageTabProvider: PageTabProvider,
    private val dataBinding: FragmentWebTabBinding,
    private val appUtil: AppUtil,
    private val mainActivity: MainActivity
) : WebChromeClient() {
    private var fullscreenView: View? = null
    private var fullscreenCallback: CustomViewCallback? = null
    private var previousOrientation: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    override fun onPermissionRequest(request: PermissionRequest?) {
        if (request == null) {
            super.onPermissionRequest(null)
            return
        }

        val isDrmRequest =
            request.resources.any { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }

        if (isDrmRequest) {
            if (settingsViewModel.isDrmEnabled.get()) {
                AppLogger.d("DRM: Granting permission based on existing setting.")
                request.grant(arrayOf(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID))
                return
            } else {
                MaterialAlertDialogBuilder(mainActivity)
                    .setTitle(R.string.drm_permission_title)
                    .setMessage(R.string.drm_permission_message)
                    .setPositiveButton(R.string.allow) { _, _ ->
                        AppLogger.d("DRM: User granted permission via dialog.")
                        request.grant(arrayOf(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID))
                        settingsViewModel.isDrmEnabled.set(true)
                    }
                    .setNegativeButton(R.string.block) { _, _ ->
                        AppLogger.d("DRM: User denied permission via dialog.")
                        request.deny()
                    }
                    .show()
                return
            }
        }

        AppLogger.d("Permissions: Denying non-DRM request for resources: ${request.resources.joinToString()}")
        request.deny()
    }

    override fun onCreateWindow(
        view: WebView?,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: Message?
    ): Boolean {
        if (view == null || resultMsg == null) {
            return false
        }

        val hitTestUrl = view.hitTestResult?.extra
        if (!hitTestUrl.isNullOrBlank() && hitTestUrl.startsWith("http")) {
            AppLogger.d("ON_CREATE_WINDOW: Opening hit-test URL in current tab: $hitTestUrl")
            view.post {
                view.stopLoading()
                view.loadUrl(hitTestUrl)
            }
            return true
        }

        val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
        val popupWebView = WebView(view.context)
        configurePopupRedirectWebView(view, popupWebView)

        transport.webView = popupWebView
        resultMsg.sendToTarget()
        AppLogger.d("ON_CREATE_WINDOW: Accepted popup window and will redirect navigation into current tab.")
        return true
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configurePopupRedirectWebView(parentWebView: WebView, popupWebView: WebView) {
        popupWebView.settings.apply {
            // This hidden WebView only accepts script-opened popups and redirects navigation
            // into the parent browser tab, so it must mirror the parent tab's JS policy.
            javaScriptEnabled = parentWebView.settings.javaScriptEnabled
            domStorageEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
            mixedContentMode = parentWebView.settings.mixedContentMode
            userAgentString = parentWebView.settings.userAgentString
        }

        popupWebView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                popupView: WebView,
                request: WebResourceRequest
            ): Boolean {
                return redirectPopupUrl(parentWebView, popupView, request.url.toString())
            }

            @Deprecated("Deprecated in Android API")
            override fun shouldOverrideUrlLoading(popupView: WebView, url: String): Boolean {
                return redirectPopupUrl(parentWebView, popupView, url)
            }
        }

        popupWebView.webChromeClient = object : WebChromeClient() {
            override fun onCloseWindow(window: WebView?) {
                destroyPopupWindow(window ?: popupWebView)
            }
        }
    }

    private fun redirectPopupUrl(
        parentWebView: WebView,
        popupWebView: WebView,
        url: String
    ): Boolean {
        if (url.isBlank() || url == "about:blank") {
            return false
        }

        if (url.startsWith("http://") || url.startsWith("https://")) {
            AppLogger.d("ON_CREATE_WINDOW: Redirecting popup URL into current tab: $url")
            parentWebView.post { parentWebView.loadUrl(url) }
            destroyPopupWindow(popupWebView)
            return true
        }

        AppLogger.d("ON_CREATE_WINDOW: Consuming unsupported popup URL: $url")
        destroyPopupWindow(popupWebView)
        return true
    }

    private fun destroyPopupWindow(webView: WebView) {
        runCatching {
            webView.stopLoading()
            webView.webChromeClient = null
            webView.destroy()
        }
    }

    override fun onReceivedIcon(view: WebView?, icon: Bitmap?) {
        val pageTab = pageTabProvider.getPageTab(tabViewModel.thisTabIndex.get())

        val headers = pageTab.getHeaders() ?: emptyMap()
        val updateTab = WebTab(
            pageTab.getUrl(),
            pageTab.getTitle(),
            icon ?: pageTab.getFavicon(),
            pageTab.getPageThumbnail(),
            pageTab.getPageThumbnailPath(),
            headers,
            view,
            id = pageTab.id
        )
        updateTabEvent.value = updateTab
    }

    override fun onReceivedTitle(view: WebView?, title: String?) {
        super.onReceivedTitle(view, title)
        val pageTab = pageTabProvider.getPageTab(tabViewModel.thisTabIndex.get())
        val headers = pageTab.getHeaders() ?: emptyMap()
        updateTabEvent.value = WebTab(
            view?.url ?: pageTab.getUrl(),
            title ?: pageTab.getTitle(),
            pageTab.getFavicon(),
            pageTab.getPageThumbnail(),
            pageTab.getPageThumbnailPath(),
            headers,
            view,
            id = pageTab.id
        )
        tabViewModel.currentTitle.set(title.orEmpty())
        tabViewModel.refreshBrowseText(view?.url ?: pageTab.getUrl(), title)
    }

    override fun onProgressChanged(view: WebView?, newProgress: Int) {
        super.onProgressChanged(view, newProgress)
        tabViewModel.setProgress(newProgress)
        if (newProgress == 100) {
            tabViewModel.isShowProgress.set(false)
        } else {
            tabViewModel.isShowProgress.set(true)
        }
    }

    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
        if (view == null) {
            callback?.onCustomViewHidden()
            return
        }
        if (fullscreenView != null) {
            callback?.onCustomViewHidden()
            return
        }

        fullscreenView = view
        fullscreenCallback = callback
        previousOrientation = mainActivity.requestedOrientation
        mainActivity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        dataBinding.webviewContainer.visibility = View.GONE
        dataBinding.customView.rootView.findViewById<View>(R.id.bottom_bar)?.visibility = View.GONE
        (mainActivity).window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        dataBinding.customView.addView(view)
        appUtil.hideSystemUI(mainActivity.window, dataBinding.customView)
        dataBinding.customView.visibility = View.VISIBLE
        dataBinding.containerBrowser.visibility =
            View.GONE
    }

    override fun onHideCustomView() {
        if (fullscreenView == null && fullscreenCallback == null) {
            return
        }

        val callback = fullscreenCallback
        dataBinding.customView.removeAllViews()
        fullscreenView = null
        fullscreenCallback = null
        dataBinding.webviewContainer.visibility = View.VISIBLE
        dataBinding.customView.visibility = View.GONE
        (mainActivity).window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        dataBinding.customView.rootView.findViewById<View>(R.id.bottom_bar)?.visibility = View.VISIBLE
        dataBinding.containerBrowser.visibility =
            View.VISIBLE
        mainActivity.requestedOrientation = previousOrientation
        appUtil.showSystemUI(mainActivity.window, dataBinding.customView)
        callback?.onCustomViewHidden()
    }
}
