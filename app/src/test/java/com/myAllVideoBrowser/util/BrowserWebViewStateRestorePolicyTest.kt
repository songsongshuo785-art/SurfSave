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
                expectedEntryCount = 3,
                expectedCurrentIndex = 2
            )
        )
    }

    @Test
    fun `rejects a stack of the same length at a different position`() {
        // Only the position differs: the current document is the expected one, so the URL check
        // cannot catch it and the index comparison is the only guard that can.
        assertFalse(
            BrowserWebViewStateRestorePolicy.isAcceptable(
                facts = restored.copy(currentIndex = 1),
                expectedUrl = "https://example.com/c",
                expectedEntryCount = 3,
                expectedCurrentIndex = 2
            )
        )
    }

    @Test
    fun `rejects a same path document with a different query`() {
        assertFalse(
            BrowserWebViewStateRestorePolicy.isAcceptable(
                facts = restored.copy(
                    entryCount = 1,
                    currentIndex = 0,
                    currentUrl = "https://example.com/watch?v=B"
                ),
                expectedUrl = "https://example.com/watch?v=A",
                expectedEntryCount = 1,
                expectedCurrentIndex = 0
            )
        )
    }

    @Test
    fun `accepts a same path document that differs only in the fragment`() {
        assertTrue(
            BrowserWebViewStateRestorePolicy.isAcceptable(
                facts = restored.copy(
                    entryCount = 1,
                    currentIndex = 0,
                    currentUrl = "https://example.com/watch?v=A#player"
                ),
                expectedUrl = "https://example.com/watch?v=A",
                expectedEntryCount = 1,
                expectedCurrentIndex = 0
            )
        )
    }

    @Test
    fun `accepts extra entries only while the stored position and document hold`() {
        assertTrue(
            BrowserWebViewStateRestorePolicy.isAcceptable(
                facts = restored.copy(entryCount = 4),
                expectedUrl = "https://example.com/c",
                expectedEntryCount = 3,
                expectedCurrentIndex = 2
            )
        )
    }

    @Test
    fun `rejects a larger stack whose current entry moved`() {
        // Stored A→B→C at index 2 (C); restored as A→B→C→D at index 3 (D). The extra entry must
        // not bypass the position and document checks.
        assertFalse(
            BrowserWebViewStateRestorePolicy.isAcceptable(
                facts = restored.copy(
                    entryCount = 4,
                    currentIndex = 3,
                    currentUrl = "https://example.com/d"
                ),
                expectedUrl = "https://example.com/c",
                expectedEntryCount = 3,
                expectedCurrentIndex = 2
            )
        )
    }

    @Test
    fun `rejects a larger stack whose current document changed at the stored position`() {
        assertFalse(
            BrowserWebViewStateRestorePolicy.isAcceptable(
                facts = restored.copy(
                    entryCount = 4,
                    currentUrl = "https://example.com/other"
                ),
                expectedUrl = "https://example.com/c",
                expectedEntryCount = 3,
                expectedCurrentIndex = 2
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
                expectedEntryCount = 3,
                expectedCurrentIndex = 2
            )
        )
    }

    @Test
    fun `rejects a current document that does not match the snapshot`() {
        assertFalse(
            BrowserWebViewStateRestorePolicy.isAcceptable(
                facts = restored.copy(currentUrl = "https://other.test/c"),
                expectedUrl = "https://example.com/c",
                expectedEntryCount = 3,
                expectedCurrentIndex = 2
            )
        )
    }

    @Test
    fun `same document keeps the query and ignores only the fragment`() {
        assertFalse(
            BrowserWebViewStateRestorePolicy.sameDocument(
                "https://example.com/watch?v=1",
                "https://example.com/watch?utm_source=x#player"
            )
        )
        assertFalse(
            BrowserWebViewStateRestorePolicy.sameDocument(
                "https://example.com/watch?v=A",
                "https://example.com/watch?v=B"
            )
        )
        assertTrue(
            BrowserWebViewStateRestorePolicy.sameDocument(
                "https://example.com/watch?v=A#player",
                "https://example.com/watch?v=A"
            )
        )
        assertTrue(
            BrowserWebViewStateRestorePolicy.sameDocument(
                "https://example.com/a?q=1",
                "https://example.com/a?q=1"
            )
        )
        assertFalse(
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
