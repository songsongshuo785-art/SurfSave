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
/** Regression coverage for the media stage after content-block decisions. */
class MediaRequestInspectorTest {

    private lateinit var settings: SettingsViewModel
    private lateinit var inspector: MediaRequestInspector

    @Before
    fun setup() {
        settings = SettingsViewModel(
            Mockito.mock(SharedPrefHelper::class.java),
            Mockito.mock(CookieProfileStore::class.java),
            Mockito.mock(YtdlpUpdateManager::class.java)
        )
        inspector = MediaRequestInspector(settings)
    }

    @Test
    fun inspect_txtOnAnyHostIsOnlyAnHlsProbeCandidate() {
        settings.isInterruptIntreceptedResources.set(true)
        val inspection = inspector.inspect(
            "https://cdn.example.org/manifests/stream.TXT?token=one#fragment",
            "https://example.org/watch/1"
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
            "https://example.org/watch/1"
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
            "https://example.org/watch/1"
        )

        assertEquals(ContentType.M3U8, inspection.contentType)
        assertTrue(inspection.isM3u8)
        assertTrue(inspection.shouldProbeAsM3u8)
        assertTrue(inspection.shouldBlockStreamRequest)
    }

}
