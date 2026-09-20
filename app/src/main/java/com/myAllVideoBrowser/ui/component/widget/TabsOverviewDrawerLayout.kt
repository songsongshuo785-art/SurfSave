package com.myAllVideoBrowser.ui.component.widget

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.myAllVideoBrowser.R

/** Overview is a full-width browser destination, with DrawerLayout retaining back/slide handling. */
class TabsOverviewDrawerLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : DrawerLayout(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        findViewById<View>(R.id.drawer_layout_content)?.let { drawer ->
            drawer.layoutParams.width = View.MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        }
        // MainActivity handles configuration changes itself; update columns without recreating pages.
        val widthDp = View.MeasureSpec.getSize(widthMeasureSpec) / resources.displayMetrics.density
        val manager = findViewById<RecyclerView>(R.id.tabs_list)?.layoutManager as? StaggeredGridLayoutManager
        val columns = if (widthDp >= 600) 3 else 2
        if (manager != null && manager.spanCount != columns) manager.spanCount = columns
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
