package com.myAllVideoBrowser.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SiteOriginTest {

    @Test
    fun stripsPathAndQuery() {
        assertEquals("https://hsex.tv", SiteOrigin.of("https://hsex.tv/video-1242438.htm?a=1&b=2"))
    }

    @Test
    fun keepsOriginWithoutTrailingSlash() {
        assertEquals("https://hsex.tv", SiteOrigin.of("https://hsex.tv/"))
    }

    @Test
    fun lowercasesSchemeAndHost() {
        assertEquals("https://hsex.tv", SiteOrigin.of("HTTPS://HSex.TV/Watch"))
    }

    @Test
    fun omitsDefaultHttpsPort() {
        assertEquals("https://hsex.tv", SiteOrigin.of("https://hsex.tv:443/video-1.htm"))
    }

    @Test
    fun omitsDefaultHttpPort() {
        assertEquals("http://example.com", SiteOrigin.of("http://example.com:80/watch"))
    }

    @Test
    fun keepsNonDefaultPort() {
        assertEquals("https://example.com:8443", SiteOrigin.of("https://example.com:8443/watch"))
        assertEquals("http://example.com:8080", SiteOrigin.of("http://example.com:8080/watch"))
    }

    @Test
    fun differentPathsOnSameHostShareOrigin() {
        assertEquals(SiteOrigin.of("https://hsex.tv/video-1.htm"), SiteOrigin.of("https://hsex.tv/video-2.htm?t=9"))
    }

    @Test
    fun wwwSubdomainIsADifferentOrigin() {
        assertEquals("https://www.hsex.tv", SiteOrigin.of("https://www.hsex.tv/video-1.htm"))
        assertNotEquals(SiteOrigin.of("https://hsex.tv/video-1.htm"), SiteOrigin.of("https://www.hsex.tv/video-1.htm"))
    }

    @Test
    fun nonHttpSchemesHaveNoOrigin() {
        assertNull(SiteOrigin.of("about:blank"))
        assertNull(SiteOrigin.of("file:///sdcard/page.html"))
        assertNull(SiteOrigin.of("data:text/html,hello"))
        assertNull(SiteOrigin.of("blob:https://hsex.tv/6f0a-uuid"))
        assertNull(SiteOrigin.of("javascript:void(0)"))
        assertNull(SiteOrigin.of("ftp://example.com/file"))
        assertNull(SiteOrigin.of("intent://scan/#Intent;scheme=zxing;end"))
    }

    @Test
    fun blankAndNullInputsReturnNull() {
        assertNull(SiteOrigin.of(null))
        assertNull(SiteOrigin.of(""))
        assertNull(SiteOrigin.of("   "))
    }

    @Test
    fun malformedUrlsReturnNull() {
        assertNull(SiteOrigin.of("https://hsex.tv/a b"))
        assertNull(SiteOrigin.of("not a url"))
        assertNull(SiteOrigin.of("https:///no-host"))
    }
}
