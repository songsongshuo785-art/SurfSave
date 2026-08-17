package com.myAllVideoBrowser.util

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BrowserThumbnailQualityTest {
    @Test
    fun fullyBlackBitmap_isRejected() {
        assertFalse(BrowserThumbnailQuality.isUsable(solidBitmap(Color.BLACK)))
    }

    @Test
    fun mostlyBlackBitmapWithSmallWhiteControls_isRejected() {
        val bitmap = solidBitmap(Color.BLACK)
        Canvas(bitmap).drawRect(0f, 0f, bitmap.width.toFloat(), 16f, android.graphics.Paint().apply {
            color = Color.WHITE
        })

        assertFalse(BrowserThumbnailQuality.isUsable(bitmap))
    }

    @Test
    fun flatWhiteAndGrayBitmaps_areRejected() {
        assertFalse(BrowserThumbnailQuality.isUsable(solidBitmap(Color.WHITE)))
        assertFalse(BrowserThumbnailQuality.isUsable(solidBitmap(Color.GRAY)))
    }

    @Test
    fun structuredColorBitmap_isAccepted() {
        assertTrue(BrowserThumbnailQuality.isUsable(structuredBitmap()))
    }

    private fun solidBitmap(color: Int): Bitmap {
        return Bitmap.createBitmap(240, 320, Bitmap.Config.ARGB_8888).apply {
            eraseColor(color)
        }
    }

    private fun structuredBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(240, 320, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = android.graphics.Paint()
        val colors = intArrayOf(Color.rgb(26, 115, 232), Color.rgb(220, 68, 55), Color.rgb(52, 168, 83))
        repeat(12) { row ->
            paint.color = colors[row % colors.size]
            canvas.drawRect(0f, row * 27f, 240f, (row + 1) * 27f, paint)
        }
        return bitmap
    }
}
