package com.myAllVideoBrowser.ui.component.binding

import androidx.databinding.BindingAdapter
import android.widget.AutoCompleteTextView
import com.myAllVideoBrowser.data.local.model.Suggestion
import com.myAllVideoBrowser.data.local.room.entity.HistoryItem
import com.myAllVideoBrowser.ui.component.adapter.SuggestionAdapter
import com.myAllVideoBrowser.ui.component.adapter.TabSuggestionAdapter

object AutoCompleteTextViewBinding {

    @BindingAdapter("items")
    @JvmStatic
    fun AutoCompleteTextView.setItems(items: List<*>?) {
        when (val currentAdapter = adapter) {
            is SuggestionAdapter -> {
                currentAdapter.setData(items?.mapNotNull { it as? Suggestion }.orEmpty())
            }

            is TabSuggestionAdapter -> {
                currentAdapter.setData(items?.mapNotNull { it as? HistoryItem }.orEmpty())
            }
        }
    }
}
