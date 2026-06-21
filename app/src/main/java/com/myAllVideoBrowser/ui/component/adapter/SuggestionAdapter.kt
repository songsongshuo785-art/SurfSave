package com.myAllVideoBrowser.ui.component.adapter

import android.content.Context
import androidx.databinding.DataBindingUtil
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import com.myAllVideoBrowser.data.local.model.Suggestion
import com.myAllVideoBrowser.databinding.ItemSuggestionBinding

interface SuggestionListener {
    fun onItemClicked(suggestion: Suggestion)
}
class SuggestionAdapter(
    context: Context?,
    suggestions: List<Suggestion>,
    private val suggestionListener: SuggestionListener
) : ArrayAdapter<Suggestion>(context!!, 0, suggestions.toMutableList()) {

    override fun getItem(position: Int): Suggestion {
        val sug = try {
            super.getItem(position)
        } catch (e: Throwable) {
            Suggestion()
        } catch (e: NullPointerException) {
            Suggestion()
        }

        return sug ?: Suggestion()
    }

    override fun getItemId(position: Int) = try {
        getItem(position).hashCode().toLong()
    } catch (e: Throwable) {
        0
    } catch (e: NullPointerException) {
        0
    }

    override fun getView(position: Int, view: View?, viewGroup: ViewGroup): View {
        val binding = if (view == null) {
            val inflater = LayoutInflater.from(viewGroup.context)
            ItemSuggestionBinding.inflate(inflater, viewGroup, false)
        } else {
            DataBindingUtil.getBinding(view)!!
        }

        with(binding) {
            this.suggestion = getItem(position)
            this.listener = suggestionListener
            executePendingBindings()
        }

        return binding.root
    }

    fun setData(suggestions: List<Suggestion>) {
        clear()
        addAll(suggestions)
    }
}
