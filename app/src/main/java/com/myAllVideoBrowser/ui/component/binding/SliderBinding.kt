package com.myAllVideoBrowser.ui.component.binding

import androidx.databinding.BindingAdapter
import com.google.android.material.slider.Slider

object SliderBinding {

    @JvmStatic
    @BindingAdapter("surfOnChangeListener")
    fun setSurfOnChangeListener(slider: Slider, listener: Slider.OnChangeListener?) {
        if (listener != null) {
            slider.addOnChangeListener(listener)
        }
    }
}
