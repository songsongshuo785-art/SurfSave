package com.myAllVideoBrowser.ui.main.home.browser.webTab

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Message
import android.webkit.WebView
import com.myAllVideoBrowser.util.BrowserWebViewStateStore
import java.util.UUID

enum class WebTabNavigationPurpose {
    NORMAL_BROWSE,
    MEDIA_IMPORT
}

class WebTab(
    private val url: String,
    private val title: String?,
    private val iconBytes: Bitmap? = null,
    private val pageThumbnail: Bitmap? = null,
    private val pageThumbnailPath: String? = null,
    private val headers: Map<String, String> = emptyMap(),
    private var webview: WebView? = null,
    private var resultMsg: Message? = null,
    private var savedState: Bundle? = null,
    var lastActiveAt: Long = System.currentTimeMillis(),
    var id: String = UUID.randomUUID().toString(),
    var navigationPurpose: WebTabNavigationPurpose = WebTabNavigationPurpose.NORMAL_BROWSE
) {

    companion object {
        @SuppressLint("StaticFieldLeak")
        val HOME_TAB = WebTab(
            "",
            "Home Tab",
            id = "home"
        )
    }

    fun getMessage(): Message? {
        return resultMsg
    }

    fun flushMessage() {
        resultMsg = null
    }

    fun getWebView(): WebView? {
        return this.webview
    }

    fun setWebView(webview: WebView?) {
        this.webview = webview
    }

    /** Result of a successful `WebView.saveState()` capture, in-memory and on disk. */
    data class WebViewStateCapture(
        val state: Bundle,
        val savedUrl: String,
        val entryCount: Int,
        val currentIndex: Int
    )

    /**
     * Captures the WebView navigation state on the WebView thread. Returns `null` when there is no
     * WebView or the state is empty. The in-memory [savedState] is refreshed as a side effect.
     */
    fun captureWebViewState(): WebViewStateCapture? {
        val currentWebView = webview ?: return null
        val state = Bundle()
        val savedList = runCatching { currentWebView.saveState(state) }.getOrNull() ?: return null
        if (state.isEmpty) {
            return null
        }
        savedState = Bundle(state)
        return WebViewStateCapture(
            state = Bundle(state),
            savedUrl = savedList.currentItem?.url ?: currentWebView.url ?: url,
            entryCount = savedList.size,
            currentIndex = savedList.currentIndex
        )
    }

    fun saveWebViewState(): Boolean {
        return captureWebViewState() != null
    }

    /**
     * Captures the current state and persists it for the next process start (cold start,
     * `force-stop`, app upgrade or background process death). Returns `false` when the tab cannot
     * be persisted: home tab, no WebView, non-http document or a state too large to store.
     */
    fun persistWebViewState(
        store: BrowserWebViewStateStore = BrowserWebViewStateStore.shared()
    ): Boolean {
        if (isHome()) {
            return false
        }
        val capture = captureWebViewState() ?: return false
        return store.saveAsync(
            id,
            capture.state,
            capture.savedUrl,
            capture.entryCount,
            capture.currentIndex
        )
    }

    fun getSavedState(): Bundle? {
        return savedState?.let { Bundle(it) }
    }

    fun setSavedState(state: Bundle?) {
        savedState = state?.let { Bundle(it) }
    }

    fun clearSavedState() {
        savedState = null
    }

    fun markActive() {
        lastActiveAt = System.currentTimeMillis()
    }

    fun getHeaders(): Map<String, String>? {
        return this.headers
    }

    fun getUrl(): String {
        return this.url
    }

    fun getTitle(): String {
        return this.title ?: ""
    }

    fun getFavicon(): Bitmap? {
        return iconBytes
    }

    fun getPageThumbnail(): Bitmap? {
        return pageThumbnail
    }

    fun getPageThumbnailPath(): String? {
        return pageThumbnailPath
    }

    fun copyWith(
        url: String = this.url,
        title: String? = this.title,
        iconBytes: Bitmap? = this.iconBytes,
        pageThumbnail: Bitmap? = this.pageThumbnail,
        pageThumbnailPath: String? = this.pageThumbnailPath,
        headers: Map<String, String> = this.headers,
        webview: WebView? = this.webview,
        resultMsg: Message? = this.resultMsg,
        savedState: Bundle? = this.savedState,
        lastActiveAt: Long = this.lastActiveAt,
        id: String = this.id,
        navigationPurpose: WebTabNavigationPurpose = this.navigationPurpose
    ): WebTab {
        val stateCopy = savedState?.let { Bundle(it) }
        return WebTab(
            url,
            title,
            iconBytes,
            pageThumbnail,
            pageThumbnailPath,
            headers,
            webview,
            resultMsg,
            stateCopy,
            lastActiveAt,
            id,
            navigationPurpose
        )
    }

    fun isHome(): Boolean {
        return this.id.contains("home")
    }


    override fun toString(): String {
        return "WebTab(url='$url', title=$title, iconBytes=$iconBytes, pageThumbnail=$pageThumbnail, headers=$headers, webview=$webview, resultMsg=$resultMsg, hasSavedState=${savedState != null}, lastActiveAt=$lastActiveAt, id='$id', navigationPurpose=$navigationPurpose)"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as WebTab

        if (url != other.url) return false
        if (title != other.title) return false
        if (iconBytes != other.iconBytes) return false
        if (pageThumbnail != other.pageThumbnail) return false
        if (pageThumbnailPath != other.pageThumbnailPath) return false
        if (headers != other.headers) return false
        if (webview != other.webview) return false
        if (resultMsg != other.resultMsg) return false
        if (lastActiveAt != other.lastActiveAt) return false
        if (id != other.id) return false
        if (navigationPurpose != other.navigationPurpose) return false

        return true
    }

    override fun hashCode(): Int {
        var result = url.hashCode()
        result = 31 * result + (title?.hashCode() ?: 0)
        result = 31 * result + (iconBytes?.hashCode() ?: 0)
        result = 31 * result + (pageThumbnail?.hashCode() ?: 0)
        result = 31 * result + (pageThumbnailPath?.hashCode() ?: 0)
        result = 31 * result + headers.hashCode()
        result = 31 * result + (webview?.hashCode() ?: 0)
        result = 31 * result + (resultMsg?.hashCode() ?: 0)
        result = 31 * result + lastActiveAt.hashCode()
        result = 31 * result + id.hashCode()
        result = 31 * result + navigationPurpose.hashCode()
        return result
    }
}
