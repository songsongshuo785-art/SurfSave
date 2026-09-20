package com.myAllVideoBrowser.ui.main.home.browser

import android.os.SystemClock
import java.net.URI

/**
 * One-shot protection for pages that open a user-requested media URL and immediately redirect the
 * original tab to an unrelated site. It is scoped to a short window and never blocks user gestures.
 */
internal class BrowserPopupRedirectGuard(
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
    private val protectionWindowMs: Long = DEFAULT_PROTECTION_WINDOW_MS
) {
    private var sourceHost: String? = null
    private var expiresAtMs: Long = 0L

    @Synchronized
    fun arm(sourcePageUrl: String?) {
        sourceHost = sourcePageUrl.httpHost()
        expiresAtMs = if (sourceHost == null) 0L else nowMs() + protectionWindowMs
    }

    @Synchronized
    fun shouldBlock(
        targetUrl: String,
        hasUserGesture: Boolean,
        isMainFrame: Boolean
    ): Boolean {
        if (!isMainFrame) return false
        if (hasUserGesture) {
            clear()
            return false
        }

        val armedHost = sourceHost ?: return false
        if (nowMs() > expiresAtMs) {
            clear()
            return false
        }

        val targetHost = targetUrl.httpHost() ?: return false
        if (targetHost.isSameHostFamily(armedHost)) return false

        clear()
        return true
    }

    @Synchronized
    fun clear() {
        sourceHost = null
        expiresAtMs = 0L
    }

    private fun String?.httpHost(): String? {
        val value = this?.trim().orEmpty()
        if (!value.startsWith("http://", true) && !value.startsWith("https://", true)) return null
        return runCatching { URI(value).host?.lowercase() }.getOrNull()
    }

    private fun String.isSameHostFamily(other: String): Boolean {
        return equals(other, ignoreCase = true) ||
            endsWith(".$other", ignoreCase = true) ||
            other.endsWith(".$this", ignoreCase = true)
    }

    private companion object {
        const val DEFAULT_PROTECTION_WINDOW_MS = 2_000L
    }
}
