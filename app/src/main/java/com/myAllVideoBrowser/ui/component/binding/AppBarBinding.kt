package com.myAllVideoBrowser.ui.component.binding

import androidx.databinding.BindingAdapter
import com.google.android.material.appbar.AppBarLayout

object AppBarBinding {

    @BindingAdapter("smoothExpanded")
    @JvmStatic
    fun AppBarLayout.setExpanded(isExpanded: Boolean) {
        setExpanded(isExpanded, true)
    }
}
