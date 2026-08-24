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

    fun contextForRequest(
        headers: Map<String, String>,
        allowHeaderlessMedia: Boolean = false
    ): ServiceWorkerDetectionContext? {
        val expected = current ?: return null
        val referer = headers.entries
            .firstOrNull { it.key.equals("Referer", ignoreCase = true) }
            ?.value
            ?.let(::normalizePageUrl)
        if (referer != null) {
            return expected.takeIf { it.pageUrl == referer }
        }

        val origin = headers.entries
            .firstOrNull { it.key.equals("Origin", ignoreCase = true) }
            ?.value
            ?.trim()
            ?.toHttpUrlOrNull()
        val page = expected.pageUrl.toHttpUrlOrNull()
        if (origin != null && page != null) {
            return expected.takeIf {
                origin.scheme == page.scheme && origin.host == page.host && origin.port == page.port
            }
        }

        // Service Worker 的媒体请求常常不带 Referer。只允许调用方已确认是媒体 URL 时，
        // 才把无头请求归到当前活动标签，避免把普通后台请求误归类为视频。
        return expected.takeIf { allowHeaderlessMedia }
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
