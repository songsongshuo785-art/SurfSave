package com.myAllVideoBrowser.ui.component.adapter

import android.content.Context
import androidx.databinding.DataBindingUtil
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import com.myAllVideoBrowser.data.local.room.entity.HistoryItem
import com.myAllVideoBrowser.databinding.ItemTabSuggestionBinding

interface SuggestionTabListener {
    fun onItemClicked(suggestion: HistoryItem)
}

class TabSuggestionAdapter(
    context: Context?,
    suggestions: List<HistoryItem>,
    private val suggestionsListener: SuggestionTabListener?
) : ArrayAdapter<HistoryItem>(context!!, 0, suggestions.toMutableList()) {

    override fun getItem(position: Int): HistoryItem {
        val sug = try {
            super.getItem(position)
        } catch (e: Throwable) {
            HistoryItem(url = "")
        }

        return sug ?: HistoryItem(url = "")
    }

    override fun getItemId(position: Int) = try {
        getItem(position).hashCode().toLong()
    } catch (e: Exception) {
        0
    }

    override fun getView(position: Int, view: View?, viewGroup: ViewGroup): View {
        val binding = if (view == null) {
            val inflater = LayoutInflater.from(viewGroup.context)
            ItemTabSuggestionBinding.inflate(inflater, viewGroup, false)
        } else {
            DataBindingUtil.getBinding(view)!!
        }

        with(binding) {
            this.suggestion = getItem(position)
            this.listener = suggestionsListener
            executePendingBindings()
        }

        return binding.root
    }

    fun setData(suggestions: List<HistoryItem>) {
        clear()
        addAll(suggestions)
    }
}
