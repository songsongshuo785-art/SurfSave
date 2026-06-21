package com.myAllVideoBrowser.ui.main.bookmarks

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.net.toUri
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.data.local.room.entity.PageInfo
import com.myAllVideoBrowser.util.FaviconUtils

object HomeBookmarkPicker {
    fun show(
        context: Context,
        bookmarks: List<PageInfo>,
        isOnHome: (PageInfo) -> Boolean,
        onSelected: (PageInfo) -> Unit
    ) {
        val sortedBookmarks = bookmarks.sortedBy { it.order }
        val adapter = BookmarkPickerAdapter(context, sortedBookmarks, isOnHome)

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.home_add_bookmark_from_saved)
            .setAdapter(adapter) { dialog, which ->
                onSelected(sortedBookmarks[which])
                dialog.dismiss()
            }
            .setNegativeButton(R.string.all_text_cancel, null)
            .show()
    }

    private class BookmarkPickerAdapter(
        context: Context,
        private val items: List<PageInfo>,
        private val isOnHome: (PageInfo) -> Boolean
    ) : ArrayAdapter<PageInfo>(context, 0, items) {
        private val inflater = LayoutInflater.from(context)

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: inflater.inflate(
                R.layout.item_home_bookmark_picker,
                parent,
                false
            )
            val item = items[position]

            val iconView = view.findViewById<ImageView>(R.id.bookmark_picker_icon)
            val titleView = view.findViewById<TextView>(R.id.bookmark_picker_title)
            val hostView = view.findViewById<TextView>(R.id.bookmark_picker_host)
            val statusView = view.findViewById<TextView>(R.id.bookmark_picker_status)

            val favicon = item.faviconBitmap()
            if (favicon != null) {
                iconView.clearColorFilter()
                iconView.setImageBitmap(FaviconUtils.prepareForDisplay(favicon))
            } else {
                val drawable = AppCompatResources.getDrawable(context, R.drawable.ic_browser)
                drawable?.let {
                    DrawableCompat.setTint(
                        it,
                        ContextCompat.getColor(context, R.color.color_gray_2)
                    )
                }
                iconView.setImageDrawable(drawable)
            }

            titleView.text = item.name.ifBlank { item.getTitleFiltered() }
            hostView.text = item.link.toUri().host?.removePrefix("www.").orEmpty().ifBlank { item.link }
            statusView.isVisible = isOnHome(item)
            statusView.text = context.getString(R.string.home_bookmark_on_home_status)

            return view
        }
    }
}
