package com.myAllVideoBrowser.ui.main.home.browser.detectedVideos

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * onStartPage must clear the detected-media list for every navigation except
 * the very first page-start of the tab, and only when that first page-start is
 * also the tab's initial URL. The first page-start can be a different URL
 * (redirects, restores), so the policy tracks "any page-start handled" rather
 * than "initial URL seen": once any page has started, reaching the initial URL
 * later must clear the previous page's media — stale entries would otherwise
 * pollute the already-detected check and linger in the panel.
 */
class PageStartMediaResetPolicyTest {
    private val initialUrl = "https://page.example/a"
    private val otherUrl = "https://page.example/b"

    private fun keep(url: String, hasHandledPageStart: Boolean): Boolean {
        return VideoDetectionTabViewModel.shouldKeepDetectedMediaOnPageStart(
            url,
            initialUrl,
            hasHandledPageStart
        )
    }

    @Test
    fun firstPageStartOnTheInitialUrlKeepsTheMediaList() {
        assertTrue(keep(initialUrl, hasHandledPageStart = false))
    }

    @Test
    fun otherUrlsClearTheMediaListEvenOnTheFirstPageStart() {
        assertFalse(keep(otherUrl, hasHandledPageStart = false))
        assertFalse(keep("", hasHandledPageStart = false))
    }

    @Test
    fun secondVisitOfTheInitialUrlClearsTheMediaList() {
        assertFalse(keep(initialUrl, hasHandledPageStart = true))
    }

    @Test
    fun otherUrlsAlwaysClearTheMediaList() {
        assertFalse(keep(otherUrl, hasHandledPageStart = true))
    }

    @Test
    fun navigatingAwayAndBackClearsThePreviousPageMedia() {
        var hasHandledPageStart = false

        // First page-start on the initial page A: keep (first open / restored state).
        assertTrue(keep(initialUrl, hasHandledPageStart))
        hasHandledPageStart = true

        // Navigate to B: page A's media is cleared.
        assertFalse(keep(otherUrl, hasHandledPageStart))

        // Navigate back to A: page B's media must not survive into page A.
        assertFalse(keep(initialUrl, hasHandledPageStart))
    }

    @Test
    fun initialUrlAfterADifferentFirstPageStillClears() {
        var hasHandledPageStart = false

        // The first page-start is a different page (redirect / restore).
        assertFalse(keep(otherUrl, hasHandledPageStart))
        hasHandledPageStart = true

        // Reaching the initial URL later must not get the first-load privilege:
        // page B's media must be cleared.
        assertFalse(keep(initialUrl, hasHandledPageStart))
    }
}
