package com.myAllVideoBrowser.ui.component.adapter

import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.RecyclerView

/** Half a gutter on each side: adjacent columns always have exactly 12dp between them. */
class TabGridSpacingDecoration : RecyclerView.ItemDecoration() {
    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        val half = (6 * parent.resources.displayMetrics.density).toInt()
        outRect.set(half, 0, half, half * 2)
    }
}
