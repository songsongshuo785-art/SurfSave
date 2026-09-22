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
}
