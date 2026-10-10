package com.myAllVideoBrowser.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserWebViewStateRestorePolicyTest {

    private val restored = RestoredHistoryFacts(
        entryCount = 3,
        currentIndex = 2,
        currentUrl = "https://example.com/c"
    )

    @Test
    fun `accepts a matching multi entry history`() {
        assertTrue(
            BrowserWebViewStateRestorePolicy.isAcceptable(
                facts = restored,
                expectedUrl = "https://example.com/c",
                expectedEntryCount = 3
            )
        )
    }

    @Test
    fun `accepts a restored save that already grew through a redirect`() {
        assertTrue(
            BrowserWebViewStateRestorePolicy.isAcceptable(
                facts = restored.copy(
                    entryCount = 4,
                    currentIndex = 3,
                    currentUrl = "https://www.example.com/c"
                ),
                expectedUrl = "https://example.com/c",
                expectedEntryCount = 3
            )
        )
    }

    @Test
    fun `rejects an empty history`() {
        assertFalse(
            BrowserWebViewStateRestorePolicy.isAcceptable(
                facts = restored.copy(entryCount = 0, currentIndex = 0)
            )
        )
    }

    @Test
    fun `rejects an out of range index`() {
        assertFalse(
            BrowserWebViewStateRestorePolicy.isAcceptable(
                facts = restored.copy(currentIndex = 3)
            )
        )
        assertFalse(
            BrowserWebViewStateRestorePolicy.isAcceptable(
                facts = restored.copy(currentIndex = -1)
            )
        )
    }

    @Test
    fun `rejects a non http current document`() {
        assertFalse(
            BrowserWebViewStateRestorePolicy.isAcceptable(
                facts = restored.copy(currentUrl = "about:blank")
            )
        )
        assertFalse(
            BrowserWebViewStateRestorePolicy.isAcceptable(
                facts = restored.copy(currentUrl = null)
            )
        )
    }

    @Test
    fun `rejects a history that lost entries`() {
        assertFalse(
            BrowserWebViewStateRestorePolicy.isAcceptable(
                facts = restored.copy(entryCount = 1, currentIndex = 0, currentUrl = "https://example.com/c"),
                expectedUrl = "https://example.com/c",
                expectedEntryCount = 3
            )
        )
    }

    @Test
    fun `rejects a current document that does not match the snapshot`() {
        assertFalse(
            BrowserWebViewStateRestorePolicy.isAcceptable(
                facts = restored.copy(currentUrl = "https://other.test/c"),
                expectedUrl = "https://example.com/c",
                expectedEntryCount = 3
            )
        )
    }

    @Test
    fun `same document ignores query fragment and trailing slash`() {
        assertTrue(
            BrowserWebViewStateRestorePolicy.sameDocument(
                "https://example.com/watch?v=1",
                "https://example.com/watch?utm_source=x#player"
            )
        )
        assertTrue(
            BrowserWebViewStateRestorePolicy.sameDocument(
                "https://example.com/a/",
                "https://example.com/a"
            )
        )
        assertFalse(
            BrowserWebViewStateRestorePolicy.sameDocument(
                "https://example.com/a",
                "https://example.com/b"
            )
        )
        assertFalse(
            BrowserWebViewStateRestorePolicy.sameDocument(
                "https://example.com/a",
                "https://other.test/a"
            )
        )
        assertFalse(BrowserWebViewStateRestorePolicy.sameDocument(null, "https://example.com/a"))
    }
}
