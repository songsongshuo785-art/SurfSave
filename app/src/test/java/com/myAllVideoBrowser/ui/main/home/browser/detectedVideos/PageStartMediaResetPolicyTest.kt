package com.myAllVideoBrowser.ui.main.home.browser.detectedVideos

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * onStartPage must clear the detected-media list for every navigation except the
 * very first load of the tab's initial URL. In particular, navigating away and
 * back (A -> B -> A) must not leave page B's media in the list: stale entries
 * would pollute the already-detected check for page A and linger in the panel.
 */
class PageStartMediaResetPolicyTest {
    private val initialUrl = "https://page.example/a"
    private val otherUrl = "https://page.example/b"

    private fun keep(url: String, initialPageStarted: Boolean): Boolean {
        return VideoDetectionTabViewModel.shouldKeepDetectedMediaOnPageStart(
            url,
            initialUrl,
            initialPageStarted
        )
    }

    @Test
    fun firstLoadOfTheInitialUrlKeepsTheMediaList() {
        assertTrue(keep(initialUrl, initialPageStarted = false))
    }

    @Test
    fun otherUrlsClearTheMediaListEvenOnTheFirstEvent() {
        assertFalse(keep(otherUrl, initialPageStarted = false))
        assertFalse(keep("", initialPageStarted = false))
    }

    @Test
    fun secondVisitOfTheInitialUrlClearsTheMediaList() {
        assertFalse(keep(initialUrl, initialPageStarted = true))
    }

    @Test
    fun otherUrlsAlwaysClearTheMediaList() {
        assertFalse(keep(otherUrl, initialPageStarted = true))
    }

    @Test
    fun navigatingAwayAndBackClearsThePreviousPageMedia() {
        var initialPageStarted = false

        // First load of the initial page A: keep (first open / restored state).
        assertTrue(keep(initialUrl, initialPageStarted))
        initialPageStarted = true

        // Navigate to B: page A's media is cleared.
        assertFalse(keep(otherUrl, initialPageStarted))

        // Navigate back to A: page B's media must not survive into page A.
        assertFalse(keep(initialUrl, initialPageStarted))
    }
}
