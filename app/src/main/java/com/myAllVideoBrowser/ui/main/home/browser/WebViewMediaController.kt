package com.myAllVideoBrowser.ui.main.home.browser

import android.webkit.WebView
import com.myAllVideoBrowser.util.AppLogger

internal object WebViewMediaController {
    private const val PAUSE_MESSAGE = "surfsave:pause-media"

    val installPauseListenerScript = """
        (function() {
            if (window.__surfSaveMediaPauseInstalled) {
                return;
            }
            window.__surfSaveMediaPauseInstalled = true;
            window.addEventListener('message', function(event) {
                if (event.data !== '$PAUSE_MESSAGE') {
                    return;
                }
                try {
                    document.querySelectorAll('video,audio').forEach(function(media) {
                        media.pause();
                    });
                } catch (e) {}
            });
        })();
    """.trimIndent()

    internal val pauseMediaScript = """
        (function() {
            try {
                document.querySelectorAll('video,audio').forEach(function(media) {
                    media.pause();
                });
            } catch (e) {}

            function notifyFrames(target) {
                try {
                    for (var i = 0; i < target.frames.length; i++) {
                        var frame = target.frames[i];
                        frame.postMessage('$PAUSE_MESSAGE', '*');
                        notifyFrames(frame);
                    }
                } catch (e) {}
            }
            notifyFrames(window);
        })();
    """.trimIndent()

    fun pause(webView: WebView?) {
        if (webView == null) {
            return
        }

        runCatching { webView.evaluateJavascript(pauseMediaScript, null) }
            .onFailure {
                AppLogger.e("Failed to inject background media pause script: ${it.message}")
            }
        runCatching { webView.onPause() }
            .onFailure {
                AppLogger.e("Failed to pause background WebView lifecycle: ${it.message}")
            }
    }

    fun resume(webView: WebView?) {
        runCatching { webView?.onResume() }
            .onFailure { AppLogger.e("Failed to resume active WebView: ${it.message}") }
    }
}
