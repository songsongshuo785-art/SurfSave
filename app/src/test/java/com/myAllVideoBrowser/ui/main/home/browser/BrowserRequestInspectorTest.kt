package com.myAllVideoBrowser.ui.main.home.browser

import android.app.Application
import com.myAllVideoBrowser.ui.main.settings.SettingsViewModel
import com.myAllVideoBrowser.util.CookieProfileStore
import com.myAllVideoBrowser.util.SharedPrefHelper
import com.myAllVideoBrowser.util.YtdlpUpdateManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BrowserRequestInspectorTest {

    private lateinit var settings: SettingsViewModel
    private lateinit var inspector: BrowserRequestInspector

    @Before
    fun setup() {
        settings = SettingsViewModel(
            Mockito.mock(SharedPrefHelper::class.java),
            Mockito.mock(CookieProfileStore::class.java),
            Mockito.mock(YtdlpUpdateManager::class.java)
        )
        inspector = BrowserRequestInspector(settings)
    }

    @Test
    fun inspect_txtOnAnyHostIsOnlyAnHlsProbeCandidate() {
        settings.isInterruptIntreceptedResources.set(true)
        val inspection = inspector.inspect(
            "https://cdn.example.org/manifests/stream.TXT?token=one#fragment",
            "https://example.org/watch/1",
            false
        )

        assertEquals(ContentType.OTHER, inspection.contentType)
        assertTrue(inspection.isTxtHlsCandidate)
        assertTrue(inspection.shouldCheckStream)
        assertTrue(inspection.shouldProbeAsM3u8)
        assertFalse(inspection.isM3u8)
        assertTrue(inspection.shouldInterruptResource)
        assertFalse(inspection.shouldBlockStreamRequest)
    }

    @Test
    fun inspect_txtSubstringThatIsNotPathExtensionIsNotCandidate() {
        val inspection = inspector.inspect(
            "https://cdn.example.org/stream.txt.js",
            "https://example.org/watch/1",
            false
        )

        assertFalse(inspection.isTxtHlsCandidate)
        assertFalse(inspection.shouldProbeAsM3u8)
        assertFalse(inspection.shouldInspectMedia)
    }

    @Test
    fun inspect_knownM3u8RemainsConfirmedByUrlPath() {
        settings.isInterruptIntreceptedResources.set(true)
        val inspection = inspector.inspect(
            "https://cdn.example.org/master.m3u8?token=one",
            "https://example.org/watch/1",
            false
        )

        assertEquals(ContentType.M3U8, inspection.contentType)
        assertTrue(inspection.isM3u8)
        assertTrue(inspection.shouldProbeAsM3u8)
        assertTrue(inspection.shouldBlockStreamRequest)
    }

    @Test
    fun inspect_adFilterEnabledBlocksOrdinaryAdRequest() {
        settings.isAdBlockingEnabled.set(true)
        val filter = BrowserAdFilter.fromLines(
            sequenceOf("block|domain|ads.example.net||third-party")
        )
        val adInspector = BrowserRequestInspector(settings, filter)

        val inspection = adInspector.inspect(
            "https://ads.example.net/banner.js",
            "https://example.org/watch",
            false,
            mapOf("Accept" to "application/javascript")
        )

        assertTrue(inspection.shouldBlockAd)
        assertFalse(inspection.shouldInspectMedia)
    }

    @Test
    fun inspect_adFilterAllowsMainFrameButBlocksHardRuleMedia() {
        settings.isAdBlockingEnabled.set(true)
        val filter = BrowserAdFilter.fromLines(
            sequenceOf("block|domain|ads.example.net||")
        )
        val adInspector = BrowserRequestInspector(settings, filter)

        assertFalse(
            adInspector.inspect(
                "https://ads.example.net/landing",
                "https://example.org/",
                true
            ).shouldBlockAd
        )
        assertTrue(
            adInspector.inspect(
                "https://ads.example.net/segment.ts",
                "https://example.org/watch",
                false
            ).shouldBlockAd
        )
    }

    @Test
    fun inspect_unmatchedMediaRemainsAllowed() {
        settings.isAdBlockingEnabled.set(true)
        val filter = BrowserAdFilter.fromLines(
            sequenceOf("block|domain|ads.example.net||")
        )
        val adInspector = BrowserRequestInspector(settings, filter)

        assertFalse(
            adInspector.inspect(
                "https://media.example.org/segment.ts",
                "https://example.org/watch",
                false
            ).shouldBlockAd
        )
    }

    @Test
    fun inspect_serviceWorkerDoesNotBorrowPageContext() {
        settings.isAdBlockingEnabled.set(true)
        val filter = BrowserAdFilter.fromLines(
            sequenceOf(
                "block|domain|global-ads.example.net||",
                "block|domain|scoped-ads.example.net|video.example.org|",
                "allow|page|video.example.org||"
            )
        )
        val adInspector = BrowserRequestInspector(settings, filter)

        assertTrue(
            adInspector.inspect(
                "https://global-ads.example.net/banner.js",
                "https://video.example.org/watch",
                false,
                requestSource = BrowserRequestSource.SERVICE_WORKER
            ).shouldBlockAd
        )
        assertFalse(
            adInspector.inspect(
                "https://scoped-ads.example.net/banner.js",
                "https://video.example.org/watch",
                false,
                requestSource = BrowserRequestSource.SERVICE_WORKER
            ).shouldBlockAd
        )
    }
}
