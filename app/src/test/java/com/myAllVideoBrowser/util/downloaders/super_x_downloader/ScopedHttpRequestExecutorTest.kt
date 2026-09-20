package com.myAllVideoBrowser.util.downloaders.super_x_downloader

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScopedHttpRequestExecutorTest {
    @Test
    fun crossOriginRedirect_stripsCookieAndAuthorizationOnRedirectedRequest() {
        val origin = MockWebServer()
        val destination = MockWebServer()
        origin.start()
        destination.start()
        try {
            origin.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", destination.url("/segment.ts"))
            )
            destination.enqueue(MockResponse().setBody("segment"))

            ScopedHttpRequestExecutor.execute(
                client = OkHttpClient(),
                targetUrl = origin.url("/master.m3u8").toString(),
                headers = mapOf(
                    "Cookie" to "session=private",
                    "Authorization" to "Bearer private",
                    "User-Agent" to "SurfSave test"
                ),
                credentialOriginUrl = origin.url("/master.m3u8").toString()
            ).use { response ->
                assertEquals("segment", response.body.string())
            }

            val first = origin.takeRequest()
            assertEquals("session=private", first.getHeader("Cookie"))
            assertEquals("Bearer private", first.getHeader("Authorization"))
            val redirected = destination.takeRequest()
            assertNull(redirected.getHeader("Cookie"))
            assertNull(redirected.getHeader("Authorization"))
            assertEquals("SurfSave test", redirected.getHeader("User-Agent"))
        } finally {
            origin.shutdown()
            destination.shutdown()
        }
    }
}
