package com.myAllVideoBrowser.util

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BrowserThumbnailStoreTest {
    @Before
    fun setUp() {
        ContextUtils.initApplicationContext(RuntimeEnvironment.getApplication())
        BrowserThumbnailStore.clearAll()
    }

    @After
    fun tearDown() {
        BrowserThumbnailStore.clearAll()
    }

    @Test
    fun save_rejectsBlackBitmapWithoutReplacingCache() {
        val path = BrowserThumbnailStore.save("black", solidBitmap(Color.BLACK))

        assertNull(path)
        assertFalse(File(BrowserThumbnailStore.directory(), "black.jpg").exists())
    }

    @Test
    fun load_deletesLegacyBlackThumbnail() {
        val file = File(BrowserThumbnailStore.directory(), "legacy-black.jpg")
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { stream ->
            solidBitmap(Color.BLACK).compress(Bitmap.CompressFormat.JPEG, 88, stream)
        }

        assertNull(BrowserThumbnailStore.load(file.absolutePath))
        assertFalse(file.exists())
    }

    @Test
    fun validThumbnail_roundTripsThroughStore() {
        val path = BrowserThumbnailStore.save("valid", structuredBitmap())

        assertNotNull(path)
        assertTrue(File(path!!).length() > 0L)
        assertNotNull(BrowserThumbnailStore.load(path))
    }

    private fun solidBitmap(color: Int): Bitmap {
        return Bitmap.createBitmap(240, 320, Bitmap.Config.ARGB_8888).apply {
            eraseColor(color)
        }
    }

    private fun structuredBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(240, 320, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        val colors = intArrayOf(Color.rgb(26, 115, 232), Color.rgb(220, 68, 55), Color.rgb(52, 168, 83))
        repeat(12) { row ->
            paint.color = colors[row % colors.size]
            canvas.drawRect(0f, row * 27f, 240f, (row + 1) * 27f, paint)
        }
        return bitmap
    }
}
