package com.myAllVideoBrowser.ui.main.home.browser

import android.app.Application
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BrowserAdFilterRuleProviderTest {
    @Test
    fun bundledRules_areLoadedFromAssets() {
        val application: Application = RuntimeEnvironment.getApplication()
        val filter = BrowserAdFilterRuleProvider(application).filter

        assertTrue(
            filter.shouldBlock(
                url = "https://securepubads.g.doubleclick.net/tag.js",
                pageUrl = "https://example.org/watch",
                isMainFrame = false,
                contentType = ContentType.OTHER
            )
        )
    }

    @Test
    fun bundledRules_blockKnownAdMediaInServiceWorkerContext() {
        val application: Application = RuntimeEnvironment.getApplication()
        val filter = BrowserAdFilterRuleProvider(application).filter

        assertTrue(
            filter.shouldBlock(
                url = "https://cdn.trafficjunky.com/preroll/master.m3u8",
                pageUrl = "",
                isMainFrame = false,
                contentType = ContentType.M3U8,
                requestSource = BrowserRequestSource.SERVICE_WORKER
            )
        )
    }
}
