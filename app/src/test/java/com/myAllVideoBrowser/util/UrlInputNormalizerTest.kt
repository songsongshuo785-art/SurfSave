package com.myAllVideoBrowser.util

import org.junit.Assert.assertEquals
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
}
