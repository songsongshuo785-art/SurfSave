package com.myAllVideoBrowser.ui.main.home.browser

import android.webkit.WebView
import com.myAllVideoBrowser.util.AppLogger
import java.util.concurrent.atomic.AtomicBoolean

internal object WebViewMediaController {
    private const val CONTROL_MESSAGE = "surfsave:media-control"
    private const val ACTION_PAUSE = "pause"
    private const val ACTION_RESUME = "resume"
    internal const val PLAYBACK_PAUSE_ACK_TIMEOUT_MS = 350L

    val installPauseListenerScript = """
        (function() {
            if (window.__surfSaveMediaPauseInstalled) {
                return;
            }
            window.__surfSaveMediaPauseInstalled = true;

            function pauseDocumentMedia() {
                try {
                    document.querySelectorAll('video,audio').forEach(function(media) {
                        media.pause();
                    });
                } catch (e) {}
            }

            function notifyChildFrames(action) {
                try {
                    for (var i = 0; i < window.frames.length; i++) {
                        window.frames[i].postMessage({
                            type: '$CONTROL_MESSAGE',
                            action: action
                        }, '*');
                    }
                } catch (e) {}
            }

            window.__surfSaveSetMediaBlocked = function(blocked) {
                window.__surfSaveMediaPlaybackBlocked = !!blocked;
                if (blocked) {
                    pauseDocumentMedia();
                }
                notifyChildFrames(blocked ? '$ACTION_PAUSE' : '$ACTION_RESUME');
            };

            function stopBlockedMedia(event) {
                if (!window.__surfSaveMediaPlaybackBlocked) {
                    return;
                }
                var media = event && event.target;
                if (!media || typeof media.pause !== 'function') {
                    return;
                }
                try {
                    media.pause();
                } catch (e) {}
            }

            document.addEventListener('play', stopBlockedMedia, true);
            document.addEventListener('playing', stopBlockedMedia, true);
            window.addEventListener('message', function(event) {
                var data = event && event.data;
                if (!data || data.type !== '$CONTROL_MESSAGE') {
                    return;
                }
                if (data.action === '$ACTION_PAUSE') {
                    window.__surfSaveSetMediaBlocked(true);
                } else if (data.action === '$ACTION_RESUME') {
                    window.__surfSaveSetMediaBlocked(false);
                }
            });
        })();
    """.trimIndent()

    internal val pauseMediaScript = """
        (function() {
            if (typeof window.__surfSaveSetMediaBlocked === 'function') {
                window.__surfSaveSetMediaBlocked(true);
                return true;
            }
            function notifyChildFrames() {
                try {
                    for (var i = 0; i < window.frames.length; i++) {
                        window.frames[i].postMessage({
                            type: '$CONTROL_MESSAGE',
                            action: '$ACTION_PAUSE'
                        }, '*');
                    }
                } catch (e) {}
            }
            try {
                document.querySelectorAll('video,audio').forEach(function(media) {
                    media.pause();
                });
            } catch (e) {}
            notifyChildFrames();
            return true;
        })();
    """.trimIndent()

    internal val resumeMediaScript = """
        (function() {
            if (typeof window.__surfSaveSetMediaBlocked === 'function') {
                window.__surfSaveSetMediaBlocked(false);
                return true;
            }
            try {
                for (var i = 0; i < window.frames.length; i++) {
                    window.frames[i].postMessage({
                        type: '$CONTROL_MESSAGE',
                        action: '$ACTION_RESUME'
                    }, '*');
                }
            } catch (e) {}
            return true;
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
        pauseLifecycle(webView)
    }

    fun pauseBeforeExternalPlayback(webView: WebView?, onSuspended: () -> Unit) {
        if (webView == null) {
            onSuspended()
            return
        }

        val completed = AtomicBoolean(false)
        lateinit var timeoutRunnable: Runnable
        fun completeOnce() {
            if (!completed.compareAndSet(false, true)) {
                return
            }
            runCatching { webView.removeCallbacks(timeoutRunnable) }
            pauseLifecycle(webView)
            onSuspended()
        }

        timeoutRunnable = Runnable { completeOnce() }
        val timeoutPosted = runCatching {
            webView.postDelayed(timeoutRunnable, PLAYBACK_PAUSE_ACK_TIMEOUT_MS)
        }.getOrDefault(false)

        val scriptQueued = runCatching {
            webView.evaluateJavascript(pauseMediaScript) { completeOnce() }
        }.onFailure {
            AppLogger.e("Failed to confirm WebView media pause before playback: ${it.message}")
        }.isSuccess

        if (!scriptQueued) {
            completeOnce()
        } else if (!timeoutPosted && !completed.get()) {
            val fallbackPosted = runCatching { webView.post(timeoutRunnable) }.getOrDefault(false)
            if (!fallbackPosted) {
                timeoutRunnable.run()
            }
        }
    }

    fun resume(webView: WebView?) {
        runCatching { webView?.onResume() }
            .onFailure { AppLogger.e("Failed to resume active WebView: ${it.message}") }
        runCatching { webView?.evaluateJavascript(resumeMediaScript, null) }
            .onFailure { AppLogger.e("Failed to unblock active WebView media: ${it.message}") }
    }

    private fun pauseLifecycle(webView: WebView) {
        runCatching { webView.onPause() }
            .onFailure {
                AppLogger.e("Failed to pause background WebView lifecycle: ${it.message}")
            }
    }
}
