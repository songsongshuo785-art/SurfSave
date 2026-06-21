package com.myAllVideoBrowser.ui.component.binding

import android.widget.GridView
import androidx.databinding.BindingAdapter
import com.myAllVideoBrowser.data.local.room.entity.PageInfo
import com.myAllVideoBrowser.ui.component.adapter.*

object GridViewBinding {
    @BindingAdapter("items")
    @JvmStatic
    fun GridView.setTopPages(items: List<PageInfo>) {
        with(adapter as TopPageAdapter?) {
            this?.let { setData(items) }
        }
        post { expandHeightToContent() }
    }

    private fun GridView.expandHeightToContent() {
        val currentAdapter = adapter ?: return
        val currentLayoutParams = layoutParams ?: return
        val count = currentAdapter.count
        if (count <= 0) {
            currentLayoutParams.height = 0
            layoutParams = currentLayoutParams
            return
        }

        val columns = numColumns.takeIf { it > 0 } ?: 1
        val rows = (count + columns - 1) / columns
        val itemHeight = (116 * resources.displayMetrics.density).toInt()
        val totalSpacing = (rows - 1).coerceAtLeast(0) * verticalSpacing
        val desiredHeight = paddingTop + paddingBottom + rows * itemHeight + totalSpacing
        if (currentLayoutParams.height != desiredHeight) {
            currentLayoutParams.height = desiredHeight
            layoutParams = currentLayoutParams
            requestLayout()
        }
    }
}
