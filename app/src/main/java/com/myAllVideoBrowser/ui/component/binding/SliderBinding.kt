package com.myAllVideoBrowser.ui.component.binding

import androidx.databinding.BindingAdapter
import com.myAllVideoBrowser.R
import com.google.android.material.slider.Slider

object SliderBinding {

    @JvmStatic
    @BindingAdapter("surfOnChangeListener")
    fun setSurfOnChangeListener(slider: Slider, listener: Slider.OnChangeListener?) {
        val previous = slider.getTag(R.id.surf_slider_change_listener_tag)
            as? Slider.OnChangeListener
        if (previous === listener) {
            return
        }
        previous?.let(slider::removeOnChangeListener)
        if (listener == null) {
            slider.setTag(R.id.surf_slider_change_listener_tag, null)
        } else {
            slider.addOnChangeListener(listener)
            slider.setTag(R.id.surf_slider_change_listener_tag, listener)
        }
    }
}
