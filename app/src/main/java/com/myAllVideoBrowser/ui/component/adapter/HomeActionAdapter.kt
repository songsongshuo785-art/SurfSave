package com.myAllVideoBrowser.ui.component.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.databinding.DataBindingUtil
import androidx.recyclerview.widget.RecyclerView
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.databinding.ItemBrowserHomeActionBinding

enum class BrowserHomeActionType {
    CONTINUE_SESSION,
    PASTE_LINK,
    DOWNLOADS,
    VIDEOS,
    BOOKMARKS,
    HISTORY,
    TOOLS,
    SETTINGS,
    TEST_PAGES
}

data class BrowserHomeAction(
    val type: BrowserHomeActionType,
    @param:StringRes val titleRes: Int,
    @param:DrawableRes val iconRes: Int
)

class BrowserHomeActionAdapter(
    private val actions: List<BrowserHomeAction>,
    private val onActionClicked: (BrowserHomeActionType) -> Unit
) : RecyclerView.Adapter<BrowserHomeActionAdapter.ViewHolder>() {

    class ViewHolder(
        val binding: ItemBrowserHomeActionBinding
    ) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = DataBindingUtil.inflate<ItemBrowserHomeActionBinding>(
            LayoutInflater.from(parent.context),
            R.layout.item_browser_home_action,
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val action = actions[position]
        with(holder.binding) {
            homeActionTitle.setText(action.titleRes)
            homeActionIcon.setImageResource(action.iconRes)
            homeActionCard.contentDescription = root.context.getString(action.titleRes)
            homeActionCard.setOnClickListener { onActionClicked(action.type) }
        }
    }

    override fun getItemCount(): Int = actions.size
}
