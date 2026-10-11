package com.myAllVideoBrowser.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserSavedStateBudgetTest {

    @Test
    fun `allows a state inside the budget`() {
        assertTrue(BrowserSavedStateBudget.allowsSystemBundle(1))
        assertTrue(BrowserSavedStateBudget.allowsSystemBundle(1024))
        assertTrue(
            BrowserSavedStateBudget.allowsSystemBundle(
                BrowserSavedStateBudget.MAX_SYSTEM_WEBVIEW_STATE_BYTES
            )
        )
    }

    @Test
    fun `rejects a state above the budget`() {
        assertFalse(
            BrowserSavedStateBudget.allowsSystemBundle(
                BrowserSavedStateBudget.MAX_SYSTEM_WEBVIEW_STATE_BYTES + 1
            )
        )
        assertFalse(BrowserSavedStateBudget.allowsSystemBundle(2 * 1024 * 1024))
    }

    @Test
    fun `rejects an empty or unmeasurable state`() {
        assertFalse(BrowserSavedStateBudget.allowsSystemBundle(0))
        assertFalse(BrowserSavedStateBudget.allowsSystemBundle(-1))
        assertFalse(BrowserSavedStateBudget.allowsSystemBundle(null))
    }

    @Test
    fun `system budget stays below the worst case binder limit`() {
        // At most 3 live tabs contribute to one activity saved state (BrowserFragment.MAX_LIVE_WEB_TABS),
        // so the worst case must stay clear of the ~1 MiB transaction limit.
        val worstCaseBytes = 3 * BrowserSavedStateBudget.MAX_SYSTEM_WEBVIEW_STATE_BYTES
        assertTrue(worstCaseBytes < 1024 * 1024)
    }
}
