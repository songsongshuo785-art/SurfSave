package com.myAllVideoBrowser.util.site_adapters

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteScrollGesturePolicyTest {

    @Test
    fun `accepts the guarded site and its subdomains`() {
        assertTrue(SiteScrollGesturePolicy.isPanelGuardedHost("rule34video.com"))
        assertTrue(SiteScrollGesturePolicy.isPanelGuardedHost("www.rule34video.com"))
        assertTrue(SiteScrollGesturePolicy.isPanelGuardedHost("RULE34VIDEO.COM"))
        assertTrue(SiteScrollGesturePolicy.isPanelGuardedHost("Rule34Video.com."))
    }

    @Test
    fun `rejects lookalike and unrelated hosts`() {
        assertFalse(SiteScrollGesturePolicy.isPanelGuardedHost("evil-rule34video.com"))
        assertFalse(SiteScrollGesturePolicy.isPanelGuardedHost("rule34video.com.evil.tld"))
        assertFalse(SiteScrollGesturePolicy.isPanelGuardedHost("notrule34video.com"))
        assertFalse(SiteScrollGesturePolicy.isPanelGuardedHost("hanime1.me"))
        assertFalse(SiteScrollGesturePolicy.isPanelGuardedHost(null))
        assertFalse(SiteScrollGesturePolicy.isPanelGuardedHost(""))
        assertFalse(SiteScrollGesturePolicy.isPanelGuardedHost("   "))
    }

    @Test
    fun `suppresses only when the panel is open on a guarded host`() {
        assertTrue(
            SiteScrollGesturePolicy.shouldSuppressPullToRefresh("rule34video.com", panelOpen = true)
        )
        assertTrue(
            SiteScrollGesturePolicy.shouldSuppressPullToRefresh(
                "www.rule34video.com",
                panelOpen = true
            )
        )
        assertFalse(
            SiteScrollGesturePolicy.shouldSuppressPullToRefresh("rule34video.com", panelOpen = false)
        )
        assertFalse(
            SiteScrollGesturePolicy.shouldSuppressPullToRefresh("hanime1.me", panelOpen = true)
        )
        assertFalse(
            SiteScrollGesturePolicy.shouldSuppressPullToRefresh(null, panelOpen = true)
        )
    }
}
