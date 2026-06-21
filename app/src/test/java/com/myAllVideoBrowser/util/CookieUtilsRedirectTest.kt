package com.myAllVideoBrowser.util

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
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
    fun accessControlAllowOrigin_setsRefererHeader() {
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
        assertEquals("https://example.com", result!!.second["Referer"])
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
