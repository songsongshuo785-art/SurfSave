package com.myAllVideoBrowser.ui.component.binding

import android.app.Application
import android.view.ContextThemeWrapper
import com.google.android.material.card.MaterialCardView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigationrail.NavigationRailView
import com.google.android.material.slider.Slider
import com.myAllVideoBrowser.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SurfBindingAdaptersTest {

    private val context by lazy {
        ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme)
    }

    @Test
    fun strokeWidthUsesDataBindingPixelValueWithoutDensityConversion() {
        val card = MaterialCardView(context)

        ViewBinding.setSurfStrokeWidthPx(card, 7.6f)

        assertEquals(8, card.strokeWidth)
    }

    @Test
    fun sliderListenerIsNotDuplicatedAndCanBeReplacedOrRemoved() {
        val slider = Slider(context).apply {
            valueFrom = 0f
            valueTo = 10f
        }
        var firstCalls = 0
        var secondCalls = 0
        val first = Slider.OnChangeListener { _, _, _ -> firstCalls++ }
        val second = Slider.OnChangeListener { _, _, _ -> secondCalls++ }

        SliderBinding.setSurfOnChangeListener(slider, first)
        SliderBinding.setSurfOnChangeListener(slider, first)
        slider.value = 1f
        assertEquals(1, firstCalls)

        SliderBinding.setSurfOnChangeListener(slider, second)
        slider.value = 2f
        assertEquals(1, firstCalls)
        assertEquals(1, secondCalls)

        SliderBinding.setSurfOnChangeListener(slider, null)
        slider.value = 3f
        assertEquals(1, secondCalls)
    }

    @Test
    fun selectedItemIdMapsPositionForBottomNavigationAndRail() {
        val bottom = BottomNavigationView(context)
        val rail = NavigationRailView(context)
        listOf(bottom, rail).forEach { navigation ->
            navigation.menu.add(0, R.id.tab_browser, 0, "Browser")
            navigation.menu.add(0, R.id.tab_progress, 1, "Progress")
            navigation.menu.add(0, R.id.tab_video, 2, "Video")
        }

        BottomNavigationViewBinding.setSelectedItemId(bottom, 1)
        BottomNavigationViewBinding.setSelectedItemId(rail, 2)

        assertEquals(R.id.tab_progress, bottom.selectedItemId)
        assertEquals(R.id.tab_video, rail.selectedItemId)
    }
}
