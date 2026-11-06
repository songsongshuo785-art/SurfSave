package com.myAllVideoBrowser.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlInputNormalizerTest {

    @Test
    fun defaultSearchPattern_usesBaidu() {
        assertEquals(
            "https://www.baidu.com/s?wd=%s",
            UrlInputNormalizer.defaultSearchUrlPattern()
        )
    }

    @Test
    fun searchUrlPatternForEngine_supportsGoogle() {
        assertEquals(
            "https://www.google.com/search?q=%s",
            UrlInputNormalizer.searchUrlPatternForEngine("google")
        )
    }

    @Test
    fun searchUrlPatternForEngine_supportsExplicitBingSelection() {
        assertEquals(
            "https://www.bing.com/search?q=%s",
            UrlInputNormalizer.searchUrlPatternForEngine("bing")
        )
    }

    @Test
    fun searchUrlPatternForEngine_invalidValueFallsBackToBaidu() {
        assertEquals(
            "https://www.baidu.com/s?wd=%s",
            UrlInputNormalizer.searchUrlPatternForEngine("invalid")
        )
    }

    @Test
    fun browsableWebAddress_rejectsLocalAndCustomSchemes() {
        assertFalse(UrlInputNormalizer.isBrowsableWebAddress("file:///sdcard/video.mp4"))
        assertFalse(UrlInputNormalizer.isBrowsableWebAddress("content://media/external/video/1"))
        assertFalse(UrlInputNormalizer.isBrowsableWebAddress("javascript:alert(1)"))
    }

    @Test
    fun browsableWebAddress_acceptsHttpHttpsAndBareDomains() {
        assertTrue(UrlInputNormalizer.isBrowsableWebAddress("https://example.com/watch"))
        assertTrue(UrlInputNormalizer.isBrowsableWebAddress("http://example.com"))
        assertTrue(UrlInputNormalizer.isBrowsableWebAddress("www.example.com/watch"))
    }

    @Test
    fun persistedSourceUrl_requiresExplicitHttpSchemeAndHost() {
        assertTrue(UrlInputNormalizer.isPersistedSourceUrl("https://example.com/watch"))
        assertTrue(UrlInputNormalizer.isPersistedSourceUrl("http://127.0.0.1:8080/video"))
        assertFalse(UrlInputNormalizer.isPersistedSourceUrl("example.com/watch"))
        assertFalse(UrlInputNormalizer.isPersistedSourceUrl("abc"))
        assertFalse(UrlInputNormalizer.isPersistedSourceUrl("123"))
        assertFalse(UrlInputNormalizer.isPersistedSourceUrl("https://"))
        assertFalse(UrlInputNormalizer.isPersistedSourceUrl("content://media/video/1"))
    }

    @Test
    fun omnibox_bareEnglishWordGoesToSearch() {
        assertEquals(
            "https://www.baidu.com/s?wd=hello",
            UrlInputNormalizer.parseOmniboxInput("hello")
        )
        assertEquals(
            "https://www.baidu.com/s?wd=test",
            UrlInputNormalizer.parseOmniboxInput("test")
        )
        assertEquals(
            "https://www.baidu.com/s?wd=a",
            UrlInputNormalizer.parseOmniboxInput("a")
        )
    }

    @Test
    fun omnibox_domainsLocalhostAndIpStayUrls() {
        assertEquals(
            "https://example.com",
            UrlInputNormalizer.parseOmniboxInput("example.com")
        )
        assertEquals(
            "https://www.bilibili.com/video/BV1",
            UrlInputNormalizer.parseOmniboxInput("www.bilibili.com/video/BV1")
        )
        assertEquals(
            "https://localhost:8080/admin",
            UrlInputNormalizer.parseOmniboxInput("localhost:8080/admin")
        )
        assertEquals(
            "https://192.168.1.1",
            UrlInputNormalizer.parseOmniboxInput("192.168.1.1")
        )
        assertEquals(
            "https://[::1]:8080",
            UrlInputNormalizer.parseOmniboxInput("[::1]:8080")
        )
    }

    @Test
    fun omnibox_doesNotExtractUrlFromSentence() {
        val result = UrlInputNormalizer.parseOmniboxInput("看看这个 https://example.com")

        assertTrue(result.startsWith("https://www.baidu.com/s?wd="))
        assertFalse(result.contains("https://example.com"))
    }

    @Test
    fun clipboardText_extractsUrlFromSentence() {
        assertEquals(
            "https://example.com/watch?v=1",
            UrlInputNormalizer.extractUrlFromText("看看这个 https://example.com/watch?v=1")
        )
        assertEquals(
            "example.com",
            UrlInputNormalizer.extractUrlFromText("example.com")
        )
    }

    @Test
    fun bareWord_isNotTreatedAsWebAddress() {
        assertFalse(UrlInputNormalizer.isProbablyWebAddress("hello"))
        assertFalse(UrlInputNormalizer.isProbablyWebAddress("test"))
        assertFalse(UrlInputNormalizer.isProbablyWebAddress("a"))
        assertFalse(UrlInputNormalizer.isProbablyWebAddress("1.2.3"))
        assertFalse(UrlInputNormalizer.isProbablyWebAddress("123"))
    }

    @Test
    fun dottedHostAndIp_areTreatedAsWebAddress() {
        assertTrue(UrlInputNormalizer.isProbablyWebAddress("example.com"))
        assertTrue(UrlInputNormalizer.isProbablyWebAddress("xn--fiqs8s.xn--fiqz9s"))
        assertTrue(UrlInputNormalizer.isProbablyWebAddress("mydevice.local"))
        assertTrue(UrlInputNormalizer.isProbablyWebAddress("127.0.0.1:8080"))
        assertTrue(UrlInputNormalizer.isProbablyWebAddress("file.txt"))
    }

    @Test
    fun explicitSchemeWithoutAuthority_goesToSearch() {
        assertEquals(
            "https://www.baidu.com/s?wd=https%3A%2F%2F",
            UrlInputNormalizer.parseOmniboxInput("https://")
        )
    }
}
