package com.myAllVideoBrowser.ui.main.home.browser.webTab

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.annotation.TargetApi
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.Window
import android.webkit.WebView
import androidx.core.graphics.createBitmap
import com.myAllVideoBrowser.util.BrowserThumbnailQuality
import kotlin.math.min
import java.util.concurrent.atomic.AtomicBoolean

object WebTabThumbnailCapture {
    private const val MAX_WIDTH = 720
    private const val MAX_HEIGHT = 1280
    private const val PIXEL_COPY_TIMEOUT_MS = 400L
    private val mainHandler = Handler(Looper.getMainLooper())

    fun capture(webView: WebView): Bitmap? {
        if (webView.width <= 0 || webView.height <= 0) {
            return null
        }

        val scale = min(
            MAX_WIDTH.toFloat() / webView.width.toFloat(),
            MAX_HEIGHT.toFloat() / webView.height.toFloat()
        ).coerceAtMost(1f)
        val targetWidth = (webView.width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (webView.height * scale).toInt().coerceAtLeast(1)

        val bitmap = try {
            createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888).also { output ->
                val canvas = Canvas(output)
                canvas.drawColor(Color.WHITE)
                canvas.scale(scale, scale)
                webView.draw(canvas)
            }
        } catch (_: Throwable) {
            null
        } ?: return null

        return bitmap.takeIf(BrowserThumbnailQuality::isUsable)
    }

    fun capture(window: Window?, webView: WebView, onComplete: (Bitmap?) -> Unit) {
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            window == null ||
            !webView.isAttachedToWindow ||
            webView.visibility != View.VISIBLE
        ) {
            onComplete(capture(webView))
            return
        }

        val sourceRect = visibleRectInWindow(window, webView)
        if (sourceRect == null) {
            onComplete(capture(webView))
            return
        }

        val (targetWidth, targetHeight) = targetDimensions(
            sourceRect.width(),
            sourceRect.height()
        )
        val bitmap = runCatching {
            createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        }.getOrNull()
        if (bitmap == null) {
            onComplete(capture(webView))
            return
        }

        val completed = AtomicBoolean(false)
        lateinit var timeout: Runnable
        fun finish(result: Bitmap?) {
            if (completed.compareAndSet(false, true)) {
                mainHandler.removeCallbacks(timeout)
                onComplete(result)
            }
        }
        fun fallbackToCanvas() {
            finish(capture(webView))
        }

        timeout = Runnable { fallbackToCanvas() }
        mainHandler.postDelayed(timeout, PIXEL_COPY_TIMEOUT_MS)

        requestPixelCopy(window, sourceRect, bitmap) { result ->
            if (
                result == PixelCopy.SUCCESS &&
                BrowserThumbnailQuality.isUsable(bitmap)
            ) {
                finish(bitmap)
            } else {
                fallbackToCanvas()
            }
        }
    }

    private fun visibleRectInWindow(window: Window, webView: WebView): Rect? {
        if (webView.width <= 0 || webView.height <= 0) {
            return null
        }
        val location = IntArray(2)
        webView.getLocationInWindow(location)
        val rect = Rect(
            location[0],
            location[1],
            location[0] + webView.width,
            location[1] + webView.height
        )
        val decorView = window.decorView
        val windowWidth = decorView.width
        val windowHeight = decorView.height
        if (windowWidth <= 0 || windowHeight <= 0) {
            return null
        }
        return rect.takeIf { it.intersect(0, 0, windowWidth, windowHeight) && !it.isEmpty }
    }

    private fun targetDimensions(sourceWidth: Int, sourceHeight: Int): Pair<Int, Int> {
        val scale = min(
            MAX_WIDTH.toFloat() / sourceWidth.toFloat(),
            MAX_HEIGHT.toFloat() / sourceHeight.toFloat()
        ).coerceAtMost(1f)
        return Pair(
            (sourceWidth * scale).toInt().coerceAtLeast(1),
            (sourceHeight * scale).toInt().coerceAtLeast(1)
        )
    }

    @TargetApi(Build.VERSION_CODES.O)
    @Suppress("DEPRECATION")
    private fun requestPixelCopy(
        window: Window,
        sourceRect: Rect,
        bitmap: Bitmap,
        onResult: (Int) -> Unit
    ) {
        runCatching {
            PixelCopy.request(window, sourceRect, bitmap, onResult, mainHandler)
        }.onFailure {
            onResult(PixelCopy.ERROR_UNKNOWN)
        }
    }
}
