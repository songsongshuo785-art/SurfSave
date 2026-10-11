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
        expectedEntryCount: Int? = null,
        expectedCurrentIndex: Int? = null
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
        if (expectedEntryCount == null || expectedEntryCount < 1) {
            return true
        }
        if (facts.entryCount < expectedEntryCount) {
            // History shrank: the stack was not preserved, so a clean URL load is more honest.
            return false
        }
        if (facts.entryCount > expectedEntryCount) {
            // The restored document navigated on its own and only appended entries; that never
            // replaces the stored current document, so the snapshot position is still valid.
            return true
        }
        if (expectedCurrentIndex != null && facts.currentIndex != expectedCurrentIndex) {
            // Same length but a different position: the stack is not the stored one.
            return false
        }
        if (expectedUrl != null && !sameDocument(expectedUrl, currentUrl)) {
            return false
        }
        return true
    }

    /**
     * Same document comparison. Everything except the fragment is significant: page identity
     * routinely lives in the query (`watch?v=`, `?id=`, `?q=`), so ignoring it would let a restore
     * accept a different page of the same path. Both sides come from one save/restore pair, so the
     * normal case compares equal strings.
     */
    fun sameDocument(left: String?, right: String?): Boolean {
        if (left.isNullOrBlank() || right.isNullOrBlank()) {
            return false
        }
        if (left == right) {
            return true
        }
        return withoutFragment(left) == withoutFragment(right)
    }

    private fun withoutFragment(value: String): String {
        val url = value.toHttpUrlOrNull() ?: return value.substringBefore('#')
        return url.newBuilder().fragment(null).build().toString()
    }
}
