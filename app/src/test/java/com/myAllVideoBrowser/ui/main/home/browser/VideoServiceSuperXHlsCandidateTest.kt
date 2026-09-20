package com.myAllVideoBrowser.ui.main.home.browser

import android.app.Application
import com.myAllVideoBrowser.data.local.model.Proxy
import com.myAllVideoBrowser.data.remote.service.VideoServiceSuperX
import com.myAllVideoBrowser.util.proxy_utils.OkHttpProxyClient
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class VideoServiceSuperXHlsCandidateTest {

    private lateinit var server: MockWebServer
    private lateinit var crossOriginServer: MockWebServer
    private lateinit var service: VideoServiceSuperX

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        crossOriginServer = MockWebServer()
        crossOriginServer.start()
        val proxyClient = OkHttpProxyClient(
            OkHttpClient.Builder().build(),
            proxyProvider = { Proxy.noProxy() },
            proxyCredentialsProvider = { "" to "" }
        )
        service = VideoServiceSuperX(proxyClient)
    }

    @After
    fun teardown() {
        server.shutdown()
        crossOriginServer.shutdown()
    }

    @Test
    fun txtCandidate_withoutExtM3uHeaderDoesNotPublishHlsResult() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/plain")
                .setBody("ordinary text response")
        )

        val result = service.getVideoInfo(request("candidate.txt"), true, false, false)

        assertNull(result)
    }

    @Test
    fun txtCandidate_withBomWhitespaceAndExtM3uHeaderIsParsed() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/plain; charset=utf-8")
                .setBody(
                    "\uFEFF  \r\n#EXTM3U\n" +
                        "#EXT-X-TARGETDURATION:10\n" +
                        "#EXTINF:1.0,\n" +
                        "segment.ts\n" +
                        "#EXT-X-ENDLIST\n"
                )
        )

        val result = service.getVideoInfo(request("candidate.txt"), true, false, false)

        assertNotNull(result)
    }

    @Test
    fun txtCandidate_withHtmlErrorDoesNotPublishHlsResult() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html")
                .setBody("<!doctype html><html><body>Access denied</body></html>")
        )

        val result = service.getVideoInfo(request("candidate.txt"), true, false, false)

        assertNull(result)
    }

    @Test
    fun masterPlaylist_crossOriginChildDoesNotReceiveOriginCredentials() {
        val childUrl = crossOriginServer.url("/child.m3u8")
        server.enqueue(
            MockResponse().setBody(
                "#EXTM3U\n" +
                    "#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360\n" +
                    "$childUrl\n"
            )
        )
        crossOriginServer.enqueue(mediaPlaylistResponse())

        val result = service.getVideoInfo(authenticatedRequest("master.m3u8"), true, false, false)

        assertNotNull(result)
        val originRequest = server.takeRequest()
        assertEquals("session=origin", originRequest.getHeader("Cookie"))
        assertEquals("Bearer origin", originRequest.getHeader("Authorization"))
        val childRequest = crossOriginServer.takeRequest()
        assertNull(childRequest.getHeader("Cookie"))
        assertNull(childRequest.getHeader("Authorization"))
        assertEquals("SurfSave test", childRequest.getHeader("User-Agent"))
    }

    @Test
    fun manifest_crossOriginRedirectStripsCredentialsAndStoresOnlyScopedHeaders() {
        val redirectedManifestUrl = crossOriginServer.url("/redirected/master.m3u8")
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", redirectedManifestUrl)
        )
        crossOriginServer.enqueue(
            MockResponse().setBody(
                "#EXTM3U\n" +
                    "#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360\n" +
                    "child.m3u8\n"
            )
        )
        crossOriginServer.enqueue(mediaPlaylistResponse())

        val result = service.getVideoInfo(authenticatedRequest("entry.m3u8"), true, false, false)

        val videoInfo = requireNotNull(requireNotNull(result).videoInfo)
        val format = videoInfo.formats.formats.single()
        val storedHeaders = requireNotNull(format.httpHeaders)
        val requestHeaders = requireNotNull(format.manifestRequestHeaders)
        assertEquals(redirectedManifestUrl.toString(), format.manifestUrl)
        assertEquals(server.url("/entry.m3u8").toString(), format.manifestRequestUrl)
        assertEquals("SurfSave test", storedHeaders["User-Agent"])
        assertNull(storedHeaders.entries.firstOrNull { it.key.equals("Cookie", true) })
        assertNull(storedHeaders.entries.firstOrNull { it.key.equals("Authorization", true) })
        assertEquals("session=origin", requestHeaders["Cookie"])
        assertEquals("Bearer origin", requestHeaders["Authorization"])
        assertEquals("SurfSave test", requestHeaders["User-Agent"])

        val originRequest = server.takeRequest()
        assertEquals("session=origin", originRequest.getHeader("Cookie"))
        assertEquals("Bearer origin", originRequest.getHeader("Authorization"))
        repeat(2) {
            val redirectedRequest = crossOriginServer.takeRequest()
            assertNull(redirectedRequest.getHeader("Cookie"))
            assertNull(redirectedRequest.getHeader("Authorization"))
            assertEquals("SurfSave test", redirectedRequest.getHeader("User-Agent"))
        }
    }

    private fun request(path: String): Request {
        return Request.Builder().url(server.url("/$path")).get().build()
    }

    private fun authenticatedRequest(path: String): Request {
        return request(path).newBuilder()
            .header("Cookie", "session=origin")
            .header("Authorization", "Bearer origin")
            .header("User-Agent", "SurfSave test")
            .build()
    }

    private fun mediaPlaylistResponse(): MockResponse {
        return MockResponse().setBody(
            "#EXTM3U\n" +
                "#EXT-X-TARGETDURATION:10\n" +
                "#EXTINF:1.0,\n" +
                "segment.ts\n" +
                "#EXT-X-ENDLIST\n"
        )
    }
}
