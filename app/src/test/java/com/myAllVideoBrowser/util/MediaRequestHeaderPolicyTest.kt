package com.myAllVideoBrowser.util

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class MediaRequestHeaderPolicyTest {
    @Test
    fun `target policy strips credentials across origin but keeps safe headers`() {
        val result = MediaRequestHeaderPolicy.forTarget(
            storedHeaders = mapOf(
                "Cookie" to "session=secret",
                "Authorization" to "Bearer secret",
                "User-Agent" to "SurfSave",
                "Referer" to "https://example.com/watch"
            ),
            credentialOriginUrl = "https://media.example.com/master.m3u8",
            targetUrl = "https://cdn.example.net/segment.ts"
        )

        assertFalse(result.keys.any { it.equals("Cookie", true) })
        assertFalse(result.keys.any { it.equals("Authorization", true) })
        assertEquals("SurfSave", result["User-Agent"])
        assertEquals("https://example.com/watch", result["Referer"])
    }

    @Test
    fun `target policy keeps credentials for same origin`() {
        val result = MediaRequestHeaderPolicy.forTarget(
            storedHeaders = mapOf("Cookie" to "session=secret"),
            credentialOriginUrl = "https://media.example.com/master.m3u8",
            targetUrl = "https://media.example.com/key.bin"
        )

        assertEquals("session=secret", result["Cookie"])
    }
    @Test
    fun crossOriginFormat_dropsInheritedCredentialsButKeepsFormatCredentials() {
        val result = MediaRequestHeaderPolicy.mergeForFormat(
            sourceHeaders = mapOf(
                "Cookie" to "page=secret",
                "Authorization" to "Bearer page",
                "Referer" to "https://page.example/watch",
                "User-Agent" to "SurfSave"
            ),
            formatHeaders = mapOf(
                "Cookie" to "cdn=allowed",
                "Authorization" to "Bearer media"
            ),
            sourceUrl = "https://page.example/watch",
            mediaUrl = "https://cdn.example/stream.m3u8"
        )

        assertEquals("cdn=allowed", result["Cookie"])
        assertEquals("Bearer media", result["Authorization"])
        assertEquals("https://page.example/watch", result["Referer"])
        assertEquals("SurfSave", result["User-Agent"])
    }

    @Test
    fun playbackHeaders_replaceCookieAndRemoveUnsafeHeaders() {
        val parsed = MediaRequestHeaderPolicy.fromJsonObject(
            JSONObject(
                mapOf(
                    "Cookie" to "old=1",
                    "Host" to "wrong.example",
                    "User-Agent" to "SurfSave"
                )
            )
        )
        val result = MediaRequestHeaderPolicy.forPlayback(parsed, "fresh=2")

        assertEquals("fresh=2", result["Cookie"])
        assertEquals("SurfSave", result["User-Agent"])
        assertFalse(result.keys.any { it.equals("Host", ignoreCase = true) })
    }
}
