package com.myAllVideoBrowser.contentblock

import com.myAllVideoBrowser.ui.main.home.browser.BrowserAdFilterRuleProvider
import com.myAllVideoBrowser.ui.main.home.browser.BrowserMediaClassifier
import com.myAllVideoBrowser.ui.main.home.browser.BrowserRequestSource
import com.myAllVideoBrowser.ui.main.home.browser.ContentType
import javax.inject.Inject
import javax.inject.Singleton

/** Explicit degraded engine. Its presence is always surfaced as FALLBACK state. */
@Singleton
class SurfSaveRuleEngine @Inject constructor(
    ruleProvider: BrowserAdFilterRuleProvider
) : ContentBlockEngine {
    private val filter = ruleProvider.filter

    override val version: String = "surfsave-kotlin-v1"

    override fun evaluate(request: ContentBlockRequest): ContentBlockDecision {
        if (request.isMainFrame || !request.url.isHttpOrHttpsUrl()) {
            return ContentBlockDecision.Allow
        }
        val contentType = if (request.resourceType == BrowserResourceType.MEDIA) {
            BrowserMediaClassifier.classify(request.url).takeUnless { it == ContentType.OTHER }
                ?: ContentType.VIDEO
        } else {
            ContentType.OTHER
        }
        val blocked = filter.shouldBlock(
            url = request.url,
            pageUrl = request.documentUrl.orEmpty(),
            isMainFrame = false,
            contentType = contentType,
            requestSource = when (request.source) {
                ContentBlockRequestSource.WEB_VIEW -> BrowserRequestSource.WEB_VIEW
                ContentBlockRequestSource.SERVICE_WORKER -> BrowserRequestSource.SERVICE_WORKER
            }
        )
        return if (blocked) {
            ContentBlockDecision.Block(engine = version, reason = "fallback-rule")
        } else {
            ContentBlockDecision.Allow
        }
    }

    override fun cosmeticResources(url: String): CosmeticResources = CosmeticResources()

    override fun hiddenSelectors(
        classes: Collection<String>,
        ids: Collection<String>,
        exceptions: Collection<String>
    ): List<String> = emptyList()

    override fun serialize(): ByteArray {
        throw UnsupportedOperationException("Fallback engine is not serializable")
    }

    override fun statsJson(): String = "{\"engine\":\"surfsave-kotlin-v1\"}"

    override fun close() = Unit
}
