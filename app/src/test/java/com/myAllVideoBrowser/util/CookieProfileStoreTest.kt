package com.myAllVideoBrowser.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CookieProfileStoreTest {

    @Test
    fun parseDomainsFromNetscape_extractsDistinctDomains() {
        val content = """
            # Netscape HTTP Cookie File
            .example.com	TRUE	/	TRUE	1893456000	session	abc
            #HttpOnly_.example.com	TRUE	/	TRUE	1893456000	auth	def
            sub.example.org	FALSE	/	FALSE	0	pref	1
        """.trimIndent()

        val domains = CookieProfileStore.parseDomainsFromNetscape(content)

        assertEquals(listOf("example.com", "sub.example.org"), domains)
    }

    @Test
    fun matchesHost_matchesSubdomainsOnly() {
        assertTrue(CookieProfileStore.matchesHost("video.example.com", "example.com"))
        assertTrue(CookieProfileStore.matchesHost("example.com", "example.com"))
        assertFalse(CookieProfileStore.matchesHost("badexample.com", "example.com"))
    }
}
