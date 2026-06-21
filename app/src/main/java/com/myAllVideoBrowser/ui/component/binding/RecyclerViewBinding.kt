package com.myAllVideoBrowser.ui.component.binding

import androidx.databinding.BindingAdapter
import androidx.recyclerview.widget.RecyclerView
import com.myAllVideoBrowser.data.local.room.entity.PageInfo
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.ui.component.adapter.*

object RecyclerViewBinding {
    @BindingAdapter("items")
    @JvmStatic
    fun RecyclerView.setItems(items: Any?) {
        when (val currentAdapter = adapter) {
            is WebTabsAdapter -> currentAdapter.setData(items.asList())
            is ProgressAdapter -> currentAdapter.setData(items.asList())
            is ProxiesAdapter -> currentAdapter.setData(items.asList())
            is VideoAdapter -> currentAdapter.setData(items.asList())
            is HistoryAdapter -> currentAdapter.setData(items.asList())
            is HistorySearchAdapter -> currentAdapter.setData(items.asList())
            is VideoInfoAdapter -> currentAdapter.setData(items.asVideoInfoList())
            is BookmarksAdapter -> currentAdapter.setData(items.asMutablePageInfoList())
        }
    }

    private inline fun <reified T> Any?.asList(): List<T> {
        return (this as? Iterable<*>)?.mapNotNull { it as? T }.orEmpty()
    }

    private fun Any?.asVideoInfoList(): List<VideoInfo> {
        return when (this) {
            is Set<*> -> this.mapNotNull { it as? VideoInfo }
            is Iterable<*> -> this.mapNotNull { it as? VideoInfo }
            else -> emptyList()
        }
    }

    private fun Any?.asMutablePageInfoList(): MutableList<PageInfo> {
        return asList<PageInfo>().toMutableList()
    }
}
