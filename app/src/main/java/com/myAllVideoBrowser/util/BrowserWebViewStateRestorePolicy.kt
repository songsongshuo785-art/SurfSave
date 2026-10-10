package com.myAllVideoBrowser.util

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Facts read from a WebView immediately after `restoreState()`. */
data class RestoredHistoryFacts(
    val entryCount: Int,
    val currentIndex: Int,
    val currentUrl: String?
)

/**
 * Decides whether a `restoreState()` result may be trusted.
 *
 * `restoreState()` returns a non-null list even for a partly broken state bundle, so a non-null
 * return value is not enough: the restored list must carry a usable entry, a valid current index
 * and an http(s) current document, and it must not have lost history compared with the snapshot.
 * A rejection is always safe: the caller falls back to loading the saved URL directly.
 */
object BrowserWebViewStateRestorePolicy {

    fun isAcceptable(
        facts: RestoredHistoryFacts,
        expectedUrl: String? = null,
        expectedEntryCount: Int? = null
    ): Boolean {
        if (facts.entryCount < 1) {
            return false
        }
        if (facts.currentIndex !in 0 until facts.entryCount) {
            return false
        }
        val currentUrl = facts.currentUrl
        if (currentUrl.isNullOrBlank() || !currentUrl.startsWith("http")) {
            return false
        }
        if (expectedEntryCount != null && expectedEntryCount >= 1 &&
            facts.entryCount < expectedEntryCount
        ) {
            // History shrank: the stack was not preserved, so a clean URL load is more honest.
            return false
        }
        if (expectedUrl != null && expectedEntryCount != null &&
            facts.entryCount == expectedEntryCount && !sameDocument(expectedUrl, currentUrl)
        ) {
            return false
        }
        return true
    }

    /**
     * Same document comparison: scheme + host + port + path, ignoring query, fragment and a
     * trailing slash. A redirect or SPA navigation changes the entry count instead, which is
     * handled by the caller's entry-count rule.
     */
    fun sameDocument(left: String?, right: String?): Boolean {
        if (left.isNullOrBlank() || right.isNullOrBlank()) {
            return false
        }
        if (left == right) {
            return true
        }
        val leftKey = documentKey(left) ?: return false
        val rightKey = documentKey(right) ?: return false
        return leftKey == rightKey
    }

    private fun documentKey(value: String): String? {
        val url = value.toHttpUrlOrNull() ?: return null
        val path = url.encodedPath.trimEnd('/')
        return "${url.scheme}://${url.host}:${url.port}$path"
    }
}
