package com.myAllVideoBrowser.ui.main.home.browser.detectedVideos

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.atomic.AtomicLong

data class ServiceWorkerDetectionContext(
    val tabId: String,
    val pageUrl: String,
    val generation: Long
)

class ServiceWorkerDetectionContextTracker {
    private val nextGeneration = AtomicLong(0L)

    @Volatile
    private var current: ServiceWorkerDetectionContext? = null

    @Synchronized
    fun activate(
        tabId: String,
        pageUrl: String,
        forceNewGeneration: Boolean = false
    ): ServiceWorkerDetectionContext? {
        val normalizedPageUrl = normalizePageUrl(pageUrl)
        if (tabId.isBlank() || normalizedPageUrl == null) {
            current = null
            nextGeneration.incrementAndGet()
            return null
        }

        val existing = current
        if (!forceNewGeneration &&
            existing?.tabId == tabId &&
            existing.pageUrl == normalizedPageUrl
        ) {
            return existing
        }

        return ServiceWorkerDetectionContext(
            tabId = tabId,
            pageUrl = normalizedPageUrl,
            generation = nextGeneration.incrementAndGet()
        ).also { current = it }
    }

    fun snapshot(): ServiceWorkerDetectionContext? = current

    fun contextForRequest(headers: Map<String, String>): ServiceWorkerDetectionContext? {
        val expected = current ?: return null
        val referer = headers.entries
            .firstOrNull { it.key.equals("Referer", ignoreCase = true) }
            ?.value
            ?.let(::normalizePageUrl)
            ?: return null
        return expected.takeIf { it.pageUrl == referer }
    }

    fun isCurrent(expected: ServiceWorkerDetectionContext): Boolean = current == expected

    private fun normalizePageUrl(url: String): String? {
        return url.trim()
            .toHttpUrlOrNull()
            ?.newBuilder()
            ?.fragment(null)
            ?.build()
            ?.toString()
    }
}
