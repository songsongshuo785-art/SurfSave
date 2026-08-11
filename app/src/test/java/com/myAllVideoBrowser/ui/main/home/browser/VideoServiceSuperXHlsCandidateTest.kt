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
    private lateinit var service: VideoServiceSuperX

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
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

    private fun request(path: String): Request {
        return Request.Builder().url(server.url("/$path")).get().build()
    }
}
