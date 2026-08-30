package com.myAllVideoBrowser.contentblock

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContentBlockCoordinator private constructor(
    private val runtime: ContentBlockRuntime
) {
    @Inject
    constructor(manager: ContentBlockManager) : this(manager as ContentBlockRuntime)

    internal constructor(runtime: ContentBlockRuntime, testMarker: Unit = Unit) : this(runtime)

    fun evaluate(request: ContentBlockRequest): ContentBlockDecision {
        if (request.isMainFrame || !request.url.isHttpOrHttpsUrl()) {
            return ContentBlockDecision.Allow
        }
        val contextSafeRequest = if (request.source == ContentBlockRequestSource.SERVICE_WORKER) {
            request.copy(documentUrl = null)
        } else {
            request
        }
        return runtime.evaluate(contextSafeRequest)
    }

    fun evaluatePopup(
        targetUrl: String?,
        documentUrl: String?,
        hasUserGesture: Boolean
    ): ContentBlockDecision {
        if (!runtime.isEnabled() || runtime.isSiteDisabled(documentUrl)) {
            return ContentBlockDecision.Allow
        }
        if (runtime.isPopupAllowed(documentUrl)) return ContentBlockDecision.Allow
        if (!hasUserGesture) return runtime.recordPolicyBlock("popup-without-user-gesture")
        val target = targetUrl?.takeIf(String::isNotBlank) ?: return ContentBlockDecision.Allow
        return evaluate(
            ContentBlockRequest(
                url = target,
                documentUrl = documentUrl,
                method = "GET",
                resourceType = BrowserResourceType.SUBDOCUMENT,
                isMainFrame = false,
                source = ContentBlockRequestSource.WEB_VIEW
            )
        )
    }
}
