package com.myAllVideoBrowser.ui.main.home.browser.webTab

import org.junit.Assert.assertEquals
import org.junit.Test

class WebTabNavigationPurposeTest {
    @Test
    fun factoryKeepsExplicitMediaImportPurposeAndInitialTitle() {
        val tab = WebTabFactory.createWebTabFromInput(
            input = "https://t.me/cctv1/17551",
            initialTitle = "Shared content",
            navigationPurpose = WebTabNavigationPurpose.MEDIA_IMPORT
        )

        assertEquals(WebTabNavigationPurpose.MEDIA_IMPORT, tab.navigationPurpose)
        assertEquals("Shared content", tab.getTitle())
        assertEquals("https://t.me/cctv1/17551", tab.getUrl())

        tab.navigationPurpose = WebTabNavigationPurpose.NORMAL_BROWSE
        assertEquals(WebTabNavigationPurpose.NORMAL_BROWSE, tab.navigationPurpose)
    }

    @Test
    fun normalFactoryCallDoesNotBecomeMediaImport() {
        val tab = WebTabFactory.createWebTabFromInput("https://example.com")

        assertEquals(WebTabNavigationPurpose.NORMAL_BROWSE, tab.navigationPurpose)
    }
}
