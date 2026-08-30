package com.myAllVideoBrowser.contentblock

import android.app.Application
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SiteBlockPolicyStoreTest {
    private lateinit var application: Application
    private lateinit var store: SiteBlockPolicyStore

    @Before
    fun setup() {
        application = org.robolectric.RuntimeEnvironment.getApplication()
        application.getSharedPreferences("settings_prefs", 0).edit().clear().commit()
        store = SiteBlockPolicyStore(application)
    }

    @Test
    fun sitePolicy_isNormalizedAndPersisted() {
        assertTrue(store.setContentBlockingDisabled("https://WWW.Example.org/watch", true))
        assertTrue(store.isContentBlockingDisabled("https://www.example.org/other"))
        assertTrue(SiteBlockPolicyStore(application).isContentBlockingDisabled(
            "https://www.example.org/"
        ))

        assertTrue(store.setContentBlockingDisabled("https://www.example.org/", false))
        assertFalse(store.isContentBlockingDisabled("https://www.example.org/"))
    }

    @Test
    fun serviceRejectsInvalidSiteUrls() {
        assertFalse(store.setPopupBlockingAllowed("data:text/plain,test", true))
        assertFalse(store.isPopupBlockingAllowed(null))
    }
}
