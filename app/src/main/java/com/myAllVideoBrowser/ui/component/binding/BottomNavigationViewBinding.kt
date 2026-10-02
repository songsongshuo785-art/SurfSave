package com.myAllVideoBrowser.ui.component.binding

import androidx.databinding.BindingAdapter
import com.google.android.material.navigation.NavigationBarView
import com.myAllVideoBrowser.R

object BottomNavigationViewBinding {

    @BindingAdapter("selectedItemId")
    @JvmStatic
    fun NavigationBarView.bindSelectedItemId(position: Int) {
        selectedItemId = when (position) {
            0 -> R.id.tab_browser
            1 -> R.id.tab_progress
            2 -> R.id.tab_video
            else -> R.id.tab_browser
        }
    }
}
