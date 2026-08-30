package com.myAllVideoBrowser.contentblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentBlockCoordinatorTest {
    @Test
    fun mainFrameAndNonHttp_areAllowedWithoutCallingEngine() {
        val runtime = FakeRuntime()
        val coordinator = ContentBlockCoordinator(runtime)

        assertSame(ContentBlockDecision.Allow, coordinator.evaluate(request(isMainFrame = true)))
        assertSame(
            ContentBlockDecision.Allow,
            coordinator.evaluate(request(url = "data:text/plain,hello"))
        )
        assertEquals(0, runtime.evaluated.size)
    }

    @Test
    fun serviceWorker_neverReceivesBorrowedDocumentUrl() {
        val runtime = FakeRuntime()
        val coordinator = ContentBlockCoordinator(runtime)

        coordinator.evaluate(
            request(
                source = ContentBlockRequestSource.SERVICE_WORKER,
                documentUrl = "https://active-tab.example/watch"
            )
        )

        assertNull(runtime.evaluated.single().documentUrl)
    }

    @Test
    fun popupPolicy_blocksNoGesture_butAllowsSiteExceptions() {
        val runtime = FakeRuntime()
        val coordinator = ContentBlockCoordinator(runtime)

        val blocked = coordinator.evaluatePopup(
            "https://ads.example/popup",
            "https://video.example/watch",
            hasUserGesture = false
        )
        assertTrue(blocked is ContentBlockDecision.Block)
        assertEquals(1, runtime.policyBlocks)

        runtime.popupAllowed = true
        assertSame(
            ContentBlockDecision.Allow,
            coordinator.evaluatePopup(
                "https://ads.example/popup",
                "https://video.example/watch",
                hasUserGesture = false
            )
        )
        assertEquals(1, runtime.policyBlocks)
    }

    @Test
    fun gesturePopup_isEvaluatedAsSubdocument() {
        val runtime = FakeRuntime()
        val coordinator = ContentBlockCoordinator(runtime)

        coordinator.evaluatePopup(
            "https://target.example/path",
            "https://video.example/watch",
            hasUserGesture = true
        )

        val request = runtime.evaluated.single()
        assertFalse(request.isMainFrame)
        assertEquals(BrowserResourceType.SUBDOCUMENT, request.resourceType)
    }

    private fun request(
        url: String = "https://cdn.example/script.js",
        documentUrl: String? = "https://page.example/",
        isMainFrame: Boolean = false,
        source: ContentBlockRequestSource = ContentBlockRequestSource.WEB_VIEW
    ) = ContentBlockRequest(
        url = url,
        documentUrl = documentUrl,
        method = "GET",
        resourceType = BrowserResourceType.SCRIPT,
        isMainFrame = isMainFrame,
        source = source
    )

    private class FakeRuntime : ContentBlockRuntime {
        val evaluated = mutableListOf<ContentBlockRequest>()
        var enabled = true
        var siteDisabled = false
        var popupAllowed = false
        var policyBlocks = 0

        override fun evaluate(request: ContentBlockRequest): ContentBlockDecision {
            evaluated += request
            return ContentBlockDecision.Allow
        }

        override fun isEnabled(): Boolean = enabled

        override fun isSiteDisabled(pageUrl: String?): Boolean = siteDisabled

        override fun isPopupAllowed(pageUrl: String?): Boolean = popupAllowed

        override fun recordPolicyBlock(reason: String): ContentBlockDecision.Block {
            policyBlocks++
            return ContentBlockDecision.Block("policy", reason)
        }
    }
}
