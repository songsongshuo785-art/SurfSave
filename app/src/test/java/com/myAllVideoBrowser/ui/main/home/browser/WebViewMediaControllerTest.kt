package com.myAllVideoBrowser.ui.main.home.browser

import android.webkit.ValueCallback
import android.webkit.WebView
import org.junit.Assert.assertTrue
import org.junit.Test
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
                .contains("event.data !== 'surfsave:pause-media'")
        )
        assertTrue(WebViewMediaController.pauseMediaScript.contains("video,audio"))
        assertTrue(
            WebViewMediaController.pauseMediaScript
                .contains("postMessage('surfsave:pause-media', '*')")
        )
    }
}
