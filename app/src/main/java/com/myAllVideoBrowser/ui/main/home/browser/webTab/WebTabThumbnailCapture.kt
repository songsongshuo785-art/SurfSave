package com.myAllVideoBrowser.ui.main.home.browser.webTab

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.webkit.WebView
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import kotlin.math.abs
import kotlin.math.min

object WebTabThumbnailCapture {
    private const val MAX_WIDTH = 720
    private const val MAX_HEIGHT = 1280
    private const val MIN_NON_EMPTY_WIDTH = 120
    private const val MIN_NON_EMPTY_HEIGHT = 180

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

        return if (isUsable(bitmap)) bitmap else null
    }

    private fun isUsable(bitmap: Bitmap): Boolean {
        if (bitmap.width < MIN_NON_EMPTY_WIDTH || bitmap.height < MIN_NON_EMPTY_HEIGHT) {
            return false
        }

        val stepX = (bitmap.width / 24).coerceAtLeast(1)
        val stepY = (bitmap.height / 32).coerceAtLeast(1)
        var count = 0
        var dark = 0
        var light = 0
        var sum = 0.0
        var sumSquares = 0.0
        var colorSpread = 0.0

        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val color = bitmap[x, y]
                val red = Color.red(color)
                val green = Color.green(color)
                val blue = Color.blue(color)
                val luminance = 0.299 * red + 0.587 * green + 0.114 * blue

                if (luminance < 24) dark++
                if (luminance > 246) light++

                sum += luminance
                sumSquares += luminance * luminance
                colorSpread += abs(red - green) + abs(green - blue) + abs(red - blue)
                count++
                x += stepX
            }
            y += stepY
        }

        if (count == 0) {
            return false
        }

        val average = sum / count
        val variance = (sumSquares / count) - (average * average)
        val darkRatio = dark.toDouble() / count
        val lightRatio = light.toDouble() / count
        val averageColorSpread = colorSpread / count
        val flatNeutral = variance < 10.0 && averageColorSpread < 18.0

        return darkRatio < 0.97 && lightRatio < 0.985 && !flatNeutral
    }
}
