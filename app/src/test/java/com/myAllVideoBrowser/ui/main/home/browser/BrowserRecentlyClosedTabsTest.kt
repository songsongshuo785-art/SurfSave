package com.myAllVideoBrowser.ui.main.home.browser

import com.myAllVideoBrowser.ui.main.home.browser.webTab.WebTab
import com.myAllVideoBrowser.util.SharedPrefHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito

class BrowserRecentlyClosedTabsTest {
    private fun viewModel(): BrowserViewModel {
        return BrowserViewModel(Mockito.mock(SharedPrefHelper::class.java))
    }

    private fun snapshot(index: Int): ClosedTabSnapshot {
        return ClosedTabSnapshot(
            tab = WebTab("https://example.com/$index", "Tab $index", id = "tab-$index"),
            originalIndex = index,
            wasSelected = false,
            id = "snapshot-$index"
        )
    }

    @Test
    fun recentlyClosedTabsKeepNewestTenAndEvictOldest() {
        val viewModel = viewModel()
        var evicted: ClosedTabSnapshot? = null

        repeat(11) { index ->
            evicted = viewModel.addRecentlyClosedTab(snapshot(index)) ?: evicted
        }

        assertEquals("snapshot-0", evicted?.id)
        assertEquals(10, viewModel.getRecentlyClosedTabs().size)
        assertEquals("snapshot-10", viewModel.getRecentlyClosedTabs().first().id)
    }

    @Test
    fun takingSnapshotRemovesOnlyRequestedEntry() {
        val viewModel = viewModel()
        viewModel.addRecentlyClosedTab(snapshot(1))
        viewModel.addRecentlyClosedTab(snapshot(2))

        assertNotNull(viewModel.takeRecentlyClosedTab("snapshot-1"))
        assertNull(viewModel.takeRecentlyClosedTab("snapshot-1"))
        assertEquals(listOf("snapshot-2"), viewModel.getRecentlyClosedTabs().map { it.id })
    }
}
