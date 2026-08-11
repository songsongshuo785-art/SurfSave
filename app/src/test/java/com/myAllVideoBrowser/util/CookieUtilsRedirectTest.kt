package com.myAllVideoBrowser.util

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.URL

class RedirectResolverTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder().build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun noRedirect_returnsOriginalUrl() {
        server.enqueue(MockResponse().setResponseCode(200))

        val url = server.url("/video.mp4").toUrl()
        val result = RedirectResolver.getFinalRedirectURL(url, emptyMap(), client)

        assertNotNull(result)
        assertEquals(url, result!!.first)
    }

    @Test
    fun singleRedirect_followsLocation() {
        val finalPath = server.url("/final.mp4").toString()
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", finalPath)
        )
        server.enqueue(MockResponse().setResponseCode(200))

        val url = server.url("/start").toUrl()
        val result = RedirectResolver.getFinalRedirectURL(url, emptyMap(), client)

        assertNotNull(result)
        assertEquals(URL(finalPath), result!!.first)
    }

    @Test
    fun multipleRedirects_followsChain() {
        val secondPath = server.url("/second").toString()
        val finalPath = server.url("/final.mp4").toString()

        server.enqueue(
            MockResponse()
                .setResponseCode(301)
                .setHeader("Location", secondPath)
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", finalPath)
        )
        server.enqueue(MockResponse().setResponseCode(200))

        val url = server.url("/start").toUrl()
        val result = RedirectResolver.getFinalRedirectURL(url, emptyMap(), client)

        assertNotNull(result)
        assertEquals(URL(finalPath), result!!.first)
    }

    @Test
    fun redirect303_isFollowed() {
        val finalPath = server.url("/final").toString()
        server.enqueue(
            MockResponse()
                .setResponseCode(303)
                .setHeader("Location", finalPath)
        )
        server.enqueue(MockResponse().setResponseCode(200))

        val url = server.url("/start").toUrl()
        val result = RedirectResolver.getFinalRedirectURL(url, emptyMap(), client)

        assertNotNull(result)
        assertEquals(URL(finalPath), result!!.first)
    }

    @Test
    fun relativeLocation_isResolvedCorrectly() {
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", "/resolved/video.mp4")
        )
        server.enqueue(MockResponse().setResponseCode(200))

        val url = server.url("/start").toUrl()
        val result = RedirectResolver.getFinalRedirectURL(url, emptyMap(), client)

        assertNotNull(result)
        val expected = URL("${url.protocol}://${url.host}:${url.port}/resolved/video.mp4")
        assertEquals(expected, result!!.first)
    }

    @Test
    fun redirect_setsPreviousUrlAsRefererAndIgnoresAccessControlHeader() {
        val finalPath = server.url("/final").toString()
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", finalPath)
                .setHeader("Access-Control-Allow-Origin", "https://example.com")
        )
        server.enqueue(MockResponse().setResponseCode(200))

        val url = server.url("/start").toUrl()
        val result = RedirectResolver.getFinalRedirectURL(url, emptyMap(), client)

        assertNotNull(result)
        assertEquals(url.toExternalForm(), result!!.second["Referer"])
        server.takeRequest()
        assertEquals(url.toExternalForm(), server.takeRequest().getHeader("Referer"))
    }

    @Test
    fun sameOriginRedirect_recalculatesCookieForEveryTarget() {
        val finalUrl = server.url("/final")
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", finalUrl.toString())
        )
        server.enqueue(MockResponse().setResponseCode(200))

        val startUrl = server.url("/start").toUrl()
        val cookiesByPath = mapOf(
            "/start" to "session=start",
            "/final" to "session=final"
        )
        val result = RedirectResolver.getFinalRedirectURL(
            startUrl,
            mapOf("Cookie" to "session=stale"),
            client
        ) { target -> cookiesByPath[target.path] }

        assertNotNull(result)
        assertEquals("session=start", server.takeRequest().getHeader("Cookie"))
        assertEquals("session=final", server.takeRequest().getHeader("Cookie"))
        assertEquals("session=final", result!!.second["Cookie"])
    }

    @Test
    fun crossOriginRedirect_doesNotLeakCookieOrAuthenticationHeaders() {
        val otherServer = MockWebServer()
        otherServer.start()
        try {
            val finalUrl = otherServer.url("/final")
            server.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", finalUrl.toString())
            )
            otherServer.enqueue(MockResponse().setResponseCode(200))

            val startUrl = server.url("/start").toUrl()
            val result = RedirectResolver.getFinalRedirectURL(
                startUrl,
                mapOf(
                    "Cookie" to "stale=secret",
                    "Authorization" to "Bearer secret",
                    "Proxy-Authorization" to "Basic proxy-secret",
                    "Host" to "stale.example",
                    "X-Custom" to "preserved"
                ),
                client
            ) { target ->
                if (target.port == server.port) "origin=start" else "origin=final"
            }

            assertNotNull(result)
            val firstRequest = server.takeRequest()
            assertEquals("origin=start", firstRequest.getHeader("Cookie"))
            assertEquals("Bearer secret", firstRequest.getHeader("Authorization"))
            assertEquals("Basic proxy-secret", firstRequest.getHeader("Proxy-Authorization"))

            val finalRequest = otherServer.takeRequest()
            assertEquals("origin=final", finalRequest.getHeader("Cookie"))
            assertNull(finalRequest.getHeader("Authorization"))
            assertNull(finalRequest.getHeader("Proxy-Authorization"))
            assertEquals("${finalUrl.host}:${finalUrl.port}", finalRequest.getHeader("Host"))
            assertEquals("preserved", finalRequest.getHeader("X-Custom"))
            assertEquals(startUrl.toExternalForm(), finalRequest.getHeader("Referer"))
        } finally {
            otherServer.shutdown()
        }
    }

    @Test
    fun redirectWithoutLocation_returnsNull() {
        server.enqueue(MockResponse().setResponseCode(302))

        val result = RedirectResolver.getFinalRedirectURL(
            server.url("/start").toUrl(),
            emptyMap(),
            client
        )

        assertNull(result)
    }

    @Test
    fun moreThanTenRedirects_returnsNull() {
        repeat(11) { index ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", "/hop-${index + 1}")
            )
        }

        val result = RedirectResolver.getFinalRedirectURL(
            server.url("/start").toUrl(),
            emptyMap(),
            client
        )

        assertNull(result)
        assertEquals(11, server.requestCount)
    }

    @Test
    fun requestException_returnsNull() {
        val failingClient = OkHttpClient.Builder()
            .addInterceptor { throw IOException("boom") }
            .build()

        val result = RedirectResolver.getFinalRedirectURL(
            server.url("/start").toUrl(),
            emptyMap(),
            failingClient
        )

        assertNull(result)
    }

    @Test
    fun headersAreForwardedThroughRedirects() {
        val finalPath = server.url("/final").toString()
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", finalPath)
        )
        server.enqueue(MockResponse().setResponseCode(200))

        val url = server.url("/start").toUrl()
        val headers = mapOf("X-Custom" to "test-value")
        RedirectResolver.getFinalRedirectURL(url, headers, client)

        val firstRequest = server.takeRequest()
        assertEquals("test-value", firstRequest.getHeader("X-Custom"))

        val secondRequest = server.takeRequest()
        assertEquals("test-value", secondRequest.getHeader("X-Custom"))
    }

    @Test
    fun bareRelativeLocation_isResolved() {
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", "final.mp4")
        )
        server.enqueue(MockResponse().setResponseCode(200))

        val url = server.url("/dir/start").toUrl()
        val result = RedirectResolver.getFinalRedirectURL(url, emptyMap(), client)

        assertNotNull(result)
        val expected = URL("${url.protocol}://${url.host}:${url.port}/dir/final.mp4")
        assertEquals(expected, result!!.first)
    }

    @Test
    fun dotDotRelativeLocation_isResolved() {
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", "../video.mp4")
        )
        server.enqueue(MockResponse().setResponseCode(200))

        val url = server.url("/a/b/start").toUrl()
        val result = RedirectResolver.getFinalRedirectURL(url, emptyMap(), client)

        assertNotNull(result)
        val expected = URL("${url.protocol}://${url.host}:${url.port}/a/video.mp4")
        assertEquals(expected, result!!.first)
    }

    @Test
    fun protocolRelativeLocation_isResolved() {
        val finalPath = server.url("/cdn/video.mp4").toString()
        val protocolRelative = "//" + server.hostName + ":" + server.port + "/cdn/video.mp4"

        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", protocolRelative)
        )
        server.enqueue(MockResponse().setResponseCode(200))

        val url = server.url("/start").toUrl()
        val result = RedirectResolver.getFinalRedirectURL(url, emptyMap(), client)

        assertNotNull(result)
        assertEquals(URL(finalPath), result!!.first)
    }

    @Test
    fun redirect307_isFollowed() {
        val finalPath = server.url("/final").toString()
        server.enqueue(
            MockResponse()
                .setResponseCode(307)
                .setHeader("Location", finalPath)
        )
        server.enqueue(MockResponse().setResponseCode(200))

        val url = server.url("/start").toUrl()
        val result = RedirectResolver.getFinalRedirectURL(url, emptyMap(), client)

        assertNotNull(result)
        assertEquals(URL(finalPath), result!!.first)
    }

    @Test
    fun redirect308_isFollowed() {
        val finalPath = server.url("/final").toString()
        server.enqueue(
            MockResponse()
                .setResponseCode(308)
                .setHeader("Location", finalPath)
        )
        server.enqueue(MockResponse().setResponseCode(200))

        val url = server.url("/start").toUrl()
        val result = RedirectResolver.getFinalRedirectURL(url, emptyMap(), client)

        assertNotNull(result)
        assertEquals(URL(finalPath), result!!.first)
    }
}
