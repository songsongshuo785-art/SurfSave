package com.myAllVideoBrowser.util

import android.app.Application
import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SharedPrefHelperSearchEngineTest {
    private val context: Application
        get() = RuntimeEnvironment.getApplication()

    private lateinit var helper: SharedPrefHelper

    @Before
    fun setUp() {
        preferences().edit().clear().commit()
        helper = SharedPrefHelper(context, AppUtil())
    }

    @After
    fun tearDown() {
        preferences().edit().clear().commit()
    }

    @Test
    fun missingPreference_defaultsToBaidu() {
        assertEquals(SharedPrefHelper.SearchEngine.BAIDU, helper.getSearchEngine())
    }

    @Test
    fun existingUserSelection_isPreserved() {
        helper.setSearchEngine(SharedPrefHelper.SearchEngine.BING)

        assertEquals(SharedPrefHelper.SearchEngine.BING, helper.getSearchEngine())
    }

    @Test
    fun invalidPreference_fallsBackToBaidu() {
        preferences().edit().putString("SEARCH_ENGINE", "invalid").commit()

        assertEquals(SharedPrefHelper.SearchEngine.BAIDU, helper.getSearchEngine())
    }

    private fun preferences() = context.getSharedPreferences(
        SharedPrefHelper.PREF_KEY,
        Context.MODE_PRIVATE
    )
}
