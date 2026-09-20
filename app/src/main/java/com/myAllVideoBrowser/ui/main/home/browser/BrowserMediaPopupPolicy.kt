package com.myAllVideoBrowser.ui.main.home.browser

/**
 * Decides whether a user-created popup already exposes an unambiguous media URL.
 * Opaque download endpoints keep the normal WebView navigation path so response metadata can
 * reach DownloadListener instead of being guessed from the URL.
 */
internal object BrowserMediaPopupPolicy {
    fun shouldCapture(url: String, hasUserGesture: Boolean): Boolean {
        val normalizedUrl = url.trim()
        if (!hasUserGesture || !normalizedUrl.isHttpUrl()) return false

        return BrowserMediaClassifier.classify(normalizedUrl) != ContentType.OTHER
    }

    private fun String.isHttpUrl(): Boolean =
        startsWith("http://", ignoreCase = true) || startsWith("https://", ignoreCase = true)
}
