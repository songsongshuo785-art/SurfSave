package com.myAllVideoBrowser.ui.main.home.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserMediaPopupPolicyTest {
    @Test
    fun userGesture_capturesExplicitMediaUrls() {
        assertTrue(
            BrowserMediaPopupPolicy.shouldCapture(
                "https://cdn.example/video.mp4?token=temporary",
                hasUserGesture = true
            )
        )
        assertTrue(
            BrowserMediaPopupPolicy.shouldCapture(
                "https://cdn.example/master.m3u8?quality=720",
                hasUserGesture = true
            )
        )
        assertTrue(
            BrowserMediaPopupPolicy.shouldCapture(
                "HTTPS://cdn.example/audio.m4a",
                hasUserGesture = true
            )
        )
    }

    @Test
    fun scriptCreatedPopup_doesNotBypassNormalPopupPolicy() {
        assertFalse(
            BrowserMediaPopupPolicy.shouldCapture(
                "https://cdn.example/video.mp4",
                hasUserGesture = false
            )
        )
    }

    @Test
    fun ambiguousOrNonMediaUrls_keepNormalWebViewNavigation() {
        assertFalse(
            BrowserMediaPopupPolicy.shouldCapture(
                "https://cdn.example/download?id=1",
                hasUserGesture = true
            )
        )
        assertFalse(
            BrowserMediaPopupPolicy.shouldCapture(
                "https://cdn.example/ad.js",
                hasUserGesture = true
            )
        )
        assertFalse(
            BrowserMediaPopupPolicy.shouldCapture(
                "https://cdn.example/segment.ts",
                hasUserGesture = true
            )
        )
        assertFalse(
            BrowserMediaPopupPolicy.shouldCapture(
                "blob:https://page.example/id",
                hasUserGesture = true
            )
        )
    }
}
