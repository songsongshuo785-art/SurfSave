package com.myAllVideoBrowser.util

import android.app.Application
import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ParcelWebViewStateCodecTest {

    private val codec: WebViewStateCodec = ParcelWebViewStateCodec()

    @Test
    fun `bundle survives an encode decode cycle`() {
        val state = Bundle().apply {
            putString("url", "https://example.com/c")
            putInt("index", 2)
            putBoolean("forward", true)
            putBundle("nested", Bundle().apply { putString("key", "value") })
        }

        val bytes = codec.encode(state)

        assertNotNull(bytes)
        val decoded = codec.decode(bytes!!)
        assertNotNull(decoded)
        assertEquals("https://example.com/c", decoded!!.getString("url"))
        assertEquals(2, decoded.getInt("index"))
        assertEquals(true, decoded.getBoolean("forward"))
        assertEquals("value", decoded.getBundle("nested")?.getString("key"))
    }

    @Test
    fun `decoding garbage never throws`() {
        // Robolectric's Parcel is more forgiving than a real device (it may hand back an empty
        // Bundle instead of failing). What must hold on every platform is that nothing propagates:
        // the durable layer additionally protects itself with a CRC and the WebView restore
        // validation, both covered by other tests.
        val garbage = codec.decode(byteArrayOf(1, 2, 3, 4, 5, 6, 7))
        val empty = codec.decode(ByteArray(0))

        if (garbage != null) {
            assertEquals(0, garbage.size())
        }
        if (empty != null) {
            assertEquals(0, empty.size())
        }
    }

    @Test
    fun `decoding a truncated valid parcel never throws`() {
        val bytes = codec.encode(Bundle().apply { putString("url", "https://example.com") })!!

        codec.decode(bytes.copyOf(bytes.size / 2))
        codec.decode(bytes.copyOf(2))
    }
}
