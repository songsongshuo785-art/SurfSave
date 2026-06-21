package com.myAllVideoBrowser.ui.main.bookmarks

import android.content.Context
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.net.toUri
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.data.local.room.entity.PageInfo

object BookmarkEditor {
    fun show(
        context: Context,
        bookmark: PageInfo?,
        currentBookmarks: () -> List<PageInfo>,
        onSaved: (List<PageInfo>) -> Unit
    ) {
        val nameInput = EditText(context).apply {
            hint = context.getString(R.string.bookmark_name)
            setText(bookmark?.name.orEmpty())
            setSingleLine(true)
        }
        val urlInput = EditText(context).apply {
            hint = context.getString(R.string.bookmark_url)
            setText(bookmark?.link.orEmpty())
            setSingleLine(true)
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = resources.getDimensionPixelSize(R.dimen.padding_normal)
            setPadding(padding, padding / 2, padding, 0)
            addView(nameInput)
            addView(urlInput)
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(if (bookmark == null) R.string.add_bookmark else R.string.edit_bookmark)
            .setView(container)
            .setPositiveButton(R.string.save_proxy, null)
            .setNegativeButton(R.string.all_text_cancel) { dialog, _ -> dialog.dismiss() }
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener {
                    val url = normalizeBookmarkUrl(urlInput.text.toString())
                    val name = nameInput.text.toString().trim().ifBlank {
                        url.toUri().host?.removePrefix("www.") ?: url
                    }
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        Toast.makeText(context, R.string.bookmark_invalid, Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }

                    val current = currentBookmarks().toMutableList()
                    val updated = if (bookmark == null) {
                        current + PageInfo(link = url, name = name, order = current.size)
                    } else {
                        current.map {
                            if (it.link == bookmark.link) {
                                it.copy(link = url, name = name, order = it.order)
                            } else {
                                it
                            }
                        }
                    }
                    onSaved(updated)
                    dialog.dismiss()
                }
        }

        dialog.show()
    }

    private fun normalizeBookmarkUrl(input: String): String {
        val value = input.trim()
        if (value.startsWith("http://") || value.startsWith("https://")) {
            return value
        }
        return if (value.isBlank()) value else "https://$value"
    }
}
