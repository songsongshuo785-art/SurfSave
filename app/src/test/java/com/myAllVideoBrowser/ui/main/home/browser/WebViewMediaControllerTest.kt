package com.myAllVideoBrowser.ui.main.home.browser

import android.webkit.ValueCallback
import android.webkit.WebView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers
import org.mockito.Mockito

class WebViewMediaControllerTest {
    @Test
    fun pause_stopsDocumentMediaAndPausesWebViewLifecycle() {
        val webView = Mockito.mock(WebView::class.java)

        WebViewMediaController.pause(webView)

        Mockito.verify(webView).evaluateJavascript(
            Mockito.eq(WebViewMediaController.pauseMediaScript),
            ArgumentMatchers.isNull<ValueCallback<String>>()
        )
        Mockito.verify(webView).onPause()
    }

    @Test
    fun resume_resumesWebViewLifecycle() {
        val webView = Mockito.mock(WebView::class.java)

        WebViewMediaController.resume(webView)

        Mockito.verify(webView).onResume()
        Mockito.verify(webView).evaluateJavascript(
            Mockito.eq(WebViewMediaController.resumeMediaScript),
            ArgumentMatchers.isNull<ValueCallback<String>>()
        )
    }

    @Test
    fun pause_stillPausesLifecycleWhenScriptInjectionFails() {
        val webView = Mockito.mock(WebView::class.java)
        Mockito.doThrow(IllegalStateException("renderer unavailable"))
            .`when`(webView)
            .evaluateJavascript(
                Mockito.eq(WebViewMediaController.pauseMediaScript),
                ArgumentMatchers.isNull<ValueCallback<String>>()
            )

        WebViewMediaController.pause(webView)

        Mockito.verify(webView).onPause()
    }

    @Test
    fun scripts_pauseTopDocumentAndNotifyFrames() {
        assertTrue(
            WebViewMediaController.installPauseListenerScript
                .contains("document.addEventListener('play', stopBlockedMedia, true)")
        )
        assertTrue(
            WebViewMediaController.installPauseListenerScript
                .contains("window.__surfSaveMediaPlaybackBlocked = !!blocked")
        )
        assertTrue(WebViewMediaController.pauseMediaScript.contains("video,audio"))
        assertTrue(
            WebViewMediaController.pauseMediaScript
                .contains("action: 'pause'")
        )
        assertTrue(
            WebViewMediaController.resumeMediaScript
                .contains("window.__surfSaveSetMediaBlocked(false)")
        )
    }

    @Test
    fun pauseBeforeExternalPlayback_waitsForJavascriptAcknowledgement() {
        val webView = Mockito.mock(WebView::class.java)
        Mockito.`when`(
            webView.postDelayed(
                ArgumentMatchers.any(Runnable::class.java),
                Mockito.eq(WebViewMediaController.PLAYBACK_PAUSE_ACK_TIMEOUT_MS)
            )
        ).thenReturn(true)
        val callbackCaptor = valueCallbackCaptor()
        var completed = false

        WebViewMediaController.pauseBeforeExternalPlayback(webView) {
            completed = true
        }

        Mockito.verify(webView).evaluateJavascript(
            Mockito.eq(WebViewMediaController.pauseMediaScript),
            callbackCaptor.capture()
        )
        assertFalse(completed)
        Mockito.verify(webView, Mockito.never()).onPause()

        callbackCaptor.value.onReceiveValue("true")

        assertTrue(completed)
        Mockito.verify(webView).onPause()
    }

    @Test
    fun pauseBeforeExternalPlayback_timeoutAndLateAck_completeExactlyOnce() {
        val webView = Mockito.mock(WebView::class.java)
        val timeoutCaptor = ArgumentCaptor.forClass(Runnable::class.java)
        Mockito.`when`(
            webView.postDelayed(
                timeoutCaptor.capture(),
                Mockito.eq(WebViewMediaController.PLAYBACK_PAUSE_ACK_TIMEOUT_MS)
            )
        ).thenReturn(true)
        val callbackCaptor = valueCallbackCaptor()
        var completionCount = 0

        WebViewMediaController.pauseBeforeExternalPlayback(webView) {
            completionCount++
        }
        Mockito.verify(webView).evaluateJavascript(
            Mockito.eq(WebViewMediaController.pauseMediaScript),
            callbackCaptor.capture()
        )

        timeoutCaptor.value.run()
        callbackCaptor.value.onReceiveValue("true")

        assertEquals(1, completionCount)
        Mockito.verify(webView, Mockito.times(1)).onPause()
    }

    @Suppress("UNCHECKED_CAST")
    private fun valueCallbackCaptor(): ArgumentCaptor<ValueCallback<String>> =
        ArgumentCaptor.forClass(ValueCallback::class.java) as ArgumentCaptor<ValueCallback<String>>
}
