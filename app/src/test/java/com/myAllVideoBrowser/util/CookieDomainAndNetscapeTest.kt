package com.myAllVideoBrowser.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CookieDomainAndNetscapeTest {

    @Test
    fun domainFilter_handlesRegistrableIpLocalAndEmptyHosts() {
        assertEquals("example.co.uk", resolveCookieDomainFilter("media.example.co.uk"))
        assertEquals("127.0.0.1", resolveCookieDomainFilter("127.0.0.1"))
        assertEquals("localhost", resolveCookieDomainFilter("localhost"))
        assertEquals("intranet", resolveCookieDomainFilter("intranet"))
        assertNull(resolveCookieDomainFilter(null))
        assertNull(resolveCookieDomainFilter("  "))
    }

    @Test
    fun webViewCookies_areWrittenAsTargetScopedNetscapeRows() {
        val content = requireNotNull(
            webViewCookiesToNetscape(
                "https://cdn.example.com/video?id=1",
                "session=abc=123; theme=dark"
            )
        )

        assertTrue(content.startsWith("# Netscape HTTP Cookie File\n"))
        assertTrue(content.contains("cdn.example.com\tFALSE\t/\tTRUE\t0\tsession\tabc=123"))
        assertTrue(content.contains("cdn.example.com\tFALSE\t/\tTRUE\t0\ttheme\tdark"))
        assertFalse(content.contains("example.com\tTRUE"))
    }

    @Test
    fun webViewCookies_rejectInvalidTargetOrEmptyCookieSet() {
        assertNull(webViewCookiesToNetscape("not a url", "session=value"))
        assertNull(webViewCookiesToNetscape("https://example.com", null))
        assertNull(webViewCookiesToNetscape("https://example.com", "broken; =value"))
    }
}
