package com.myAllVideoBrowser.contentblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustedScriptletRegistryTest {
    @Test
    fun onlyApkOwnedWhitelistedNamesAndSafeArgumentsAreAccepted() {
        val registry = TrustedScriptletRegistry {
            """
                # bundled data only
                example.org|remove-cookie|ad_session
                example.org|set-local-storage|ads=disabled
                example.org|unknown-scriptlet|argument
                example.org##+js(remote-source)
                example.org|remove-cookie|bad argument;alert(1)
            """.trimIndent()
        }

        val scripts = registry.forPage("https://www.example.org/watch")

        assertEquals(2, registry.acceptedEntryCount())
        assertEquals(setOf("remove-cookie", "set-local-storage"), scripts.map { it.name }.toSet())
        assertTrue(scripts.any { it.javaScript.contains("document.cookie") })
        assertTrue(scripts.any { it.javaScript.contains("localStorage.setItem") })
        assertFalse(scripts.any { it.javaScript.contains("remote-source") })
        assertFalse(scripts.any { it.javaScript.contains("alert(1)") })
    }

    @Test
    fun rulesAreDomainScoped() {
        val registry = TrustedScriptletRegistry {
            "example.org|remove-cookie|ad_session"
        }

        assertEquals(1, registry.forPage("https://sub.example.org/").size)
        assertTrue(registry.forPage("https://notexample.org/").isEmpty())
        assertTrue(registry.forPage("data:text/plain,test").isEmpty())
    }
}
