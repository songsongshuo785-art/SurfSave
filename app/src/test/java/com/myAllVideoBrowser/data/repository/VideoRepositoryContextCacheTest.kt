package com.myAllVideoBrowser.data.repository

import com.myAllVideoBrowser.data.local.model.Proxy
import com.myAllVideoBrowser.data.local.model.ProxyType
import com.myAllVideoBrowser.data.local.room.entity.DownloadRequestData
import com.myAllVideoBrowser.data.local.room.entity.VideFormatEntityList
import com.myAllVideoBrowser.data.local.room.entity.VideoFormatEntity
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Before
import org.junit.Test

class VideoRepositoryContextCacheTest {

    private lateinit var remote: CountingRemoteRepository
    private lateinit var repository: VideoRepositoryImpl
    private var proxy = Proxy.noProxy()

    @Before
    fun setup() {
        remote = CountingRemoteRepository()
        repository = VideoRepositoryImpl(remote) { proxy }
    }

    @Test
    fun regularDetectorBypassesCacheBecauseAuthenticationIsImplicit() {
        val request = request(cookie = "session=one", referer = "https://page.example/one")

        val first = repository.getVideoInfo(request, false, false)
        val second = repository.getVideoInfo(request, false, false)

        assertNotSame(first, second)
        assertEquals(2, remote.regularCalls)
    }

    @Test
    fun identicalSuperXRequestReusesCacheWithoutSharingInstances() {
        val request = request(cookie = "session=one", referer = "https://page.example/one")

        val first = repository.getVideoInfoBySuperXDetector(request, true, false, false)
        val second = repository.getVideoInfoBySuperXDetector(request, true, false, false)

        assertNotSame(first, second)
        assertEquals(first?.id, second?.id)
        assertEquals(1, remote.superXCalls)
    }

    @Test
    fun cookieAndPageContextChangesDoNotReuseResult() {
        val first = repository.getVideoInfoBySuperXDetector(
            request(cookie = "session=one", referer = "https://page.example/one"),
            true,
            false,
            false
        )
        val second = repository.getVideoInfoBySuperXDetector(
            request(cookie = "session=two", referer = "https://page.example/one"),
            true,
            false,
            false
        )
        val third = repository.getVideoInfoBySuperXDetector(
            request(cookie = "session=two", referer = "https://page.example/two"),
            true,
            false,
            false
        )

        assertNotSame(first, second)
        assertNotSame(second, third)
        assertEquals(3, remote.superXCalls)
    }

    @Test
    fun detectorFlagsArePartOfCacheKey() {
        val request = request()

        repository.getVideoInfoBySuperXDetector(request, true, false, false)
        repository.getVideoInfoBySuperXDetector(request, false, true, false)
        repository.getVideoInfoBySuperXDetector(request, true, false, true)

        assertEquals(3, remote.superXCalls)
    }

    @Test
    fun proxyCredentialsAndTypeArePartOfCacheKey() {
        val request = request()
        proxy = proxy(password = "first", type = ProxyType.HTTP)
        val first = repository.getVideoInfoBySuperXDetector(request, true, false, false)

        proxy = proxy(password = "second", type = ProxyType.HTTP)
        val second = repository.getVideoInfoBySuperXDetector(request, true, false, false)

        proxy = proxy(password = "second", type = ProxyType.SOCKS5)
        val third = repository.getVideoInfoBySuperXDetector(request, true, false, false)

        assertNotSame(first, second)
        assertNotSame(second, third)
        assertEquals(3, remote.superXCalls)
    }

    @Test
    fun requestsWithBodiesBypassCache() {
        val request = Request.Builder()
            .url(MEDIA_URL)
            .post("request-body".toRequestBody())
            .build()

        val first = repository.getVideoInfoBySuperXDetector(request, true, false, false)
        val second = repository.getVideoInfoBySuperXDetector(request, true, false, false)

        assertNotSame(first, second)
        assertEquals(2, remote.superXCalls)
    }

    @Test
    fun headerNamesAndDistinctHeaderOrderAreCanonicalButSameNameValueOrderIsPreserved() {
        val firstRequest = Request.Builder()
            .url(MEDIA_URL)
            .addHeader("X-B", "two")
            .addHeader("X-Multi", "first")
            .addHeader("x-multi", "second")
            .addHeader("X-A", "one")
            .get()
            .build()
        val equivalentRequest = Request.Builder()
            .url(MEDIA_URL)
            .addHeader("x-a", "one")
            .addHeader("X-MULTI", "first")
            .addHeader("x-Multi", "second")
            .addHeader("x-b", "two")
            .get()
            .build()
        val reversedMultiValueRequest = Request.Builder()
            .url(MEDIA_URL)
            .addHeader("X-A", "one")
            .addHeader("X-Multi", "second")
            .addHeader("X-Multi", "first")
            .addHeader("X-B", "two")
            .get()
            .build()

        val first = repository.getVideoInfoBySuperXDetector(firstRequest, true, false, false)
        val equivalent = repository.getVideoInfoBySuperXDetector(
            equivalentRequest,
            true,
            false,
            false
        )
        val reversed = repository.getVideoInfoBySuperXDetector(
            reversedMultiValueRequest,
            true,
            false,
            false
        )

        assertEquals(first?.id, equivalent?.id)
        assertNotSame(first, equivalent)
        assertNotSame(equivalent, reversed)
        assertEquals(2, remote.superXCalls)
    }

    @Test
    fun cachedVideoInfoIsDeeplyIsolatedFromCallerMutation() {
        val request = request()
        val first = requireNotNull(
            repository.getVideoInfoBySuperXDetector(request, true, false, false)
        )
        first.title = "mutated-first"
        (first.downloadUrls.single().headers as MutableMap<String, String>)["Cookie"] =
            "mutated-first"
        first.formats.formats.single().id = "mutated-first"
        (first.formats.formats.single().httpHeaders as MutableMap<String, String>)[
            "Authorization"
        ] = "mutated-first"

        val second = requireNotNull(
            repository.getVideoInfoBySuperXDetector(request, true, false, false)
        )
        assertEquals("Original", second.title)
        assertEquals("original-cookie", second.downloadUrls.single().headers["Cookie"])
        assertEquals("format-super-x-1", second.formats.formats.single().id)
        assertEquals(
            "original-authorization",
            second.formats.formats.single().httpHeaders?.get("Authorization")
        )

        second.title = "mutated-second"
        (second.downloadUrls.single().headers as MutableMap<String, String>)["Cookie"] =
            "mutated-second"
        second.formats.formats.single().id = "mutated-second"
        (second.formats.formats.single().httpHeaders as MutableMap<String, String>)[
            "Authorization"
        ] = "mutated-second"

        val third = requireNotNull(
            repository.getVideoInfoBySuperXDetector(request, true, false, false)
        )
        assertEquals("Original", third.title)
        assertEquals("original-cookie", third.downloadUrls.single().headers["Cookie"])
        assertEquals("format-super-x-1", third.formats.formats.single().id)
        assertEquals(
            "original-authorization",
            third.formats.formats.single().httpHeaders?.get("Authorization")
        )
        assertEquals(1, remote.superXCalls)
    }

    private fun request(cookie: String = "", referer: String = ""): Request {
        return Request.Builder()
            .url(MEDIA_URL)
            .apply {
                if (cookie.isNotEmpty()) header("Cookie", cookie)
                if (referer.isNotEmpty()) header("Referer", referer)
            }
            .get()
            .build()
    }

    private fun proxy(password: String, type: ProxyType): Proxy {
        return Proxy(
            id = "proxy-id",
            host = "127.0.0.1",
            port = "8888",
            user = "user",
            password = password,
            type = type
        )
    }

    private class CountingRemoteRepository : VideoRepository {
        var regularCalls = 0
            private set
        var superXCalls = 0
            private set

        override fun getVideoInfoBySuperXDetector(
            url: Request,
            isM3u8: Boolean,
            isMpd: Boolean,
            isAudioCheck: Boolean
        ): VideoInfo {
            superXCalls++
            return videoInfo("super-x-$superXCalls")
        }

        override fun getVideoInfo(
            url: Request,
            isM3u8OrMpd: Boolean,
            isAudioCheck: Boolean
        ): VideoInfo {
            regularCalls++
            return videoInfo("regular-$regularCalls")
        }

        override fun saveVideoInfo(videoInfo: VideoInfo) = Unit

        private fun videoInfo(id: String): VideoInfo {
            return VideoInfo(
                id = id,
                title = "Original",
                downloadUrls = listOf(
                    DownloadRequestData(
                        url = MEDIA_URL,
                        headers = linkedMapOf("Cookie" to "original-cookie")
                    )
                ),
                formats = VideFormatEntityList(
                    listOf(
                        VideoFormatEntity(
                            id = "format-$id",
                            formatId = "hls-main",
                            httpHeaders = linkedMapOf(
                                "Authorization" to "original-authorization"
                            )
                        )
                    )
                )
            )
        }
    }

    private companion object {
        const val MEDIA_URL = "https://cdn.example/media.mp4"
    }
}
