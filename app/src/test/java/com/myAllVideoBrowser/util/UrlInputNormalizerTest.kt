package com.myAllVideoBrowser.util

import org.junit.Assert.assertEquals
import org.junit.Test

class UrlInputNormalizerTest {

    @Test
    fun searchUrlPatternForEngine_supportsGoogle() {
        assertEquals(
            "https://www.google.com/search?q=%s",
            UrlInputNormalizer.searchUrlPatternForEngine("google")
        )
    }
}
