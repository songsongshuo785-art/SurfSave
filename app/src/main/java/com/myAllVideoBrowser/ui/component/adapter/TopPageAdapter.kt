package com.myAllVideoBrowser.ui.component.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.databinding.DataBindingUtil
import androidx.recyclerview.widget.RecyclerView
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.data.local.room.entity.PageInfo
import com.myAllVideoBrowser.databinding.ItemTopPageBinding
import com.myAllVideoBrowser.util.ContextUtils
import com.myAllVideoBrowser.util.FaviconUtils

class TopPageAdapter(
    private var pageInfos: List<PageInfo>,
    private val itemListener: TopPagesListener
) : RecyclerView.Adapter<TopPageAdapter.TopPageViewHolder>() {

    class TopPageViewHolder(val binding: ItemTopPageBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(pageInfo: PageInfo, itemListener: TopPagesListener) {
            with(binding) {
                this.pageInfo = pageInfo
                this.listener = itemListener
                root.setOnLongClickListener {
                    itemListener.onItemLongClicked(it, pageInfo)
                }

                val favicon = pageInfo.faviconBitmap()
                if (favicon != null) {
                    imgIcon.clearColorFilter()
                    imgIcon.setImageBitmap(FaviconUtils.prepareForDisplay(favicon))
                } else {
                    val drawable = AppCompatResources.getDrawable(
                        ContextUtils.getApplicationContext(),
                        R.drawable.ic_browser
                    )
                    drawable?.let {
                        DrawableCompat.setTint(
                            it,
                            ContextCompat.getColor(
                                ContextUtils.getApplicationContext(),
                                R.color.color_gray_2
                            )
                        )
                    }
                    imgIcon.setImageDrawable(drawable)
                }

                executePendingBindings()
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TopPageViewHolder {
        val binding = DataBindingUtil.inflate<ItemTopPageBinding>(
            LayoutInflater.from(parent.context),
            R.layout.item_top_page,
            parent,
            false
        )
        return TopPageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TopPageViewHolder, position: Int) {
        holder.bind(pageInfos[position], itemListener)
    }

    override fun getItemCount(): Int = pageInfos.size

    fun setData(pageInfos: List<PageInfo>) {
        dispatchListDiff(
            oldItems = this.pageInfos,
            newItems = pageInfos,
            areItemsTheSame = { oldItem, newItem -> oldItem.link == newItem.link }
        ) {
            this.pageInfos = pageInfos
        }
    }

    interface TopPagesListener {
        fun onItemClicked(pageInfo: PageInfo)
        fun onItemLongClicked(view: View, pageInfo: PageInfo): Boolean
    }
}
