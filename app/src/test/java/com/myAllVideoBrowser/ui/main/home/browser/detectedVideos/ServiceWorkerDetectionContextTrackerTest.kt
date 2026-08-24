package com.myAllVideoBrowser.ui.main.home.browser.detectedVideos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceWorkerDetectionContextTrackerTest {

    @Test
    fun activate_reusesSamePageUntilNavigationStarts() {
        val tracker = ServiceWorkerDetectionContextTracker()
        val first = requireNotNull(tracker.activate("tab-a", "https://example.com/watch"))
        val repeated = requireNotNull(tracker.activate("tab-a", "https://example.com/watch"))

        assertSame(first, repeated)
        assertTrue(tracker.isCurrent(first))

        val reload = requireNotNull(
            tracker.activate(
                "tab-a",
                "https://example.com/watch",
                forceNewGeneration = true
            )
        )
        assertTrue(reload.generation > first.generation)
        assertFalse(tracker.isCurrent(first))
        assertTrue(tracker.isCurrent(reload))
    }

    @Test
    fun switchingTabOrPageInvalidatesCapturedContext() {
        val tracker = ServiceWorkerDetectionContextTracker()
        val first = requireNotNull(tracker.activate("tab-a", "https://example.com/a"))
        val nextPage = requireNotNull(tracker.activate("tab-a", "https://example.com/b"))
        val nextTab = requireNotNull(tracker.activate("tab-b", "https://example.com/b"))

        assertFalse(tracker.isCurrent(first))
        assertFalse(tracker.isCurrent(nextPage))
        assertTrue(tracker.isCurrent(nextTab))
        assertEquals("tab-b", tracker.snapshot()?.tabId)
    }

    @Test
    fun blankContextClearsActivePageAndInvalidatesOldGeneration() {
        val tracker = ServiceWorkerDetectionContextTracker()
        val first = requireNotNull(tracker.activate("tab-a", "https://example.com"))

        assertNull(tracker.activate("", ""))
        assertNull(tracker.snapshot())
        assertFalse(tracker.isCurrent(first))
    }

    @Test
    fun requestRequiresRefererMatchingTheFullNormalizedPageUrl() {
        val tracker = ServiceWorkerDetectionContextTracker()
        val current = requireNotNull(
            tracker.activate("tab-a", "https://EXAMPLE.com:443/watch?id=7#player")
        )

        assertEquals("https://example.com/watch?id=7", current.pageUrl)
        assertSame(
            current,
            tracker.contextForRequest(
                mapOf("referer" to "https://example.com/watch?id=7#ignored")
            )
        )
        assertNull(
            tracker.contextForRequest(
                mapOf("Referer" to "https://example.com/watch?id=8")
            )
        )
        assertSame(
            current,
            tracker.contextForRequest(
                mapOf("Origin" to "https://example.com")
            )
        )
        assertNull(tracker.contextForRequest(emptyMap()))
    }

    @Test
    fun confirmedMediaRequest_acceptsSameOriginOrMissingReferer() {
        val tracker = ServiceWorkerDetectionContextTracker()
        val current = requireNotNull(
            tracker.activate("tab-a", "https://example.com/watch?id=7")
        )

        assertSame(
            current,
            tracker.contextForRequest(mapOf("Origin" to "https://example.com"))
        )
        assertSame(
            current,
            tracker.contextForRequest(
                mapOf("User-Agent" to "WebView"),
                allowHeaderlessMedia = true
            )
        )
        assertNull(
            tracker.contextForRequest(
                mapOf("Origin" to "https://other.example"),
                allowHeaderlessMedia = true
            )
        )
    }
}
