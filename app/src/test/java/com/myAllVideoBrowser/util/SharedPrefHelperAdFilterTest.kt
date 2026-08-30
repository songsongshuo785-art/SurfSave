package com.myAllVideoBrowser.util

import android.app.Application
import android.content.Context
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SharedPrefHelperAdFilterTest {
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
    fun adFiltering_defaultsOnAndPersistsExplicitOptOut() {
        assertTrue(helper.isAdBlockingEnabled())

        helper.setIsAdBlockingEnabled(false)

        assertFalse(helper.isAdBlockingEnabled())
    }

    private fun preferences() = context.getSharedPreferences(
        SharedPrefHelper.PREF_KEY,
        Context.MODE_PRIVATE
    )
}
