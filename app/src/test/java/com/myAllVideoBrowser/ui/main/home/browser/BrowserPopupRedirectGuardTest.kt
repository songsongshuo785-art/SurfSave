package com.myAllVideoBrowser.ui.main.home.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserPopupRedirectGuardTest {
    @Test
    fun capturedMedia_blocksOneImmediateAutomaticCrossSiteNavigation() {
        var now = 1_000L
        val guard = BrowserPopupRedirectGuard(nowMs = { now }, protectionWindowMs = 2_000L)
        guard.arm("https://video.example/download?id=1")

        assertTrue(
            guard.shouldBlock(
                targetUrl = "https://ads.example/landing",
                hasUserGesture = false,
                isMainFrame = true
            )
        )
        assertFalse(
            guard.shouldBlock(
                targetUrl = "https://another.example/after-consumption",
                hasUserGesture = false,
                isMainFrame = true
            )
        )
    }

    @Test
    fun sameSiteUserGestureAndSubresourceRemainAllowed() {
        val guard = BrowserPopupRedirectGuard(nowMs = { 1_000L }, protectionWindowMs = 2_000L)
        guard.arm("https://video.example/download")

        assertFalse(guard.shouldBlock("https://video.example/next", false, true))
        assertFalse(guard.shouldBlock("https://cdn.video.example/next", false, true))
        assertFalse(guard.shouldBlock("https://ads.example/click", true, true))
        assertFalse(guard.shouldBlock("https://ads.example/script.js", false, false))
        assertFalse(guard.shouldBlock("https://after-user-gesture.example/page", false, true))
    }

    @Test
    fun expiredProtectionDoesNotChangeOrdinaryNavigation() {
        var now = 1_000L
        val guard = BrowserPopupRedirectGuard(nowMs = { now }, protectionWindowMs = 2_000L)
        guard.arm("https://video.example/download")
        now = 3_001L

        assertFalse(guard.shouldBlock("https://other.example/page", false, true))
    }
}
