package com.myAllVideoBrowser.ui.component.adapter

import android.graphics.PorterDuff
import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.content.res.AppCompatResources
import androidx.databinding.DataBindingUtil
import androidx.recyclerview.widget.RecyclerView
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.databinding.ItemWebTabButtonBinding
import com.myAllVideoBrowser.ui.main.home.browser.webTab.WebTab
import com.myAllVideoBrowser.util.BrowserThumbnailStore
import com.myAllVideoBrowser.util.UrlInputNormalizer

interface WebTabsListener {
    fun onCloseTabClicked(webTab: WebTab)
    fun onSelectTabClicked(webTab: WebTab)
}

class WebTabsAdapter(
    private var webTabs: List<WebTab>,
    private var webTabsListener: WebTabsListener
) : RecyclerView.Adapter<WebTabsAdapter.WebTabsViewHolder>() {
    private var selectedTabId: String? = null

    class WebTabsViewHolder(val binding: ItemWebTabButtonBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(webTab: WebTab, webTabsListener: WebTabsListener, isSelected: Boolean) {
            with(binding)
            {
                val context = this.root.context

                this.webTab = webTab
                this.tabListener = webTabsListener
                this.tabBadge.visibility = if (isSelected) View.VISIBLE else View.GONE
                this.itemWebTabButton.setCardBackgroundColor(
                    context.getColor(
                        if (isSelected) R.color.sxSurfaceSelected else R.color.sxSurfaceRaised
                    )
                )
                this.itemWebTabButton.strokeColor = context.getColor(
                    if (isSelected) R.color.colorPrimary else R.color.sxOutline
                )
                this.itemWebTabButton.strokeWidth = if (isSelected) 2 else 1

                this.closeTab.visibility = if (webTab.isHome()) {
                    View.GONE
                } else {
                    View.VISIBLE
                }

                bindMetaIcon(context, webTab)
                bindPreview(context, webTab)

                if (webTab.isHome()) {
                    this.tabTitle.text = context.getString(R.string.title_browser)
                    this.tabUrl.text = context.getString(R.string.browser_primary_action)
                    this.tabUrl.visibility = View.VISIBLE
                } else {
                    val title = webTab.getTitle().trim()
                    val hostText = UrlInputNormalizer.toDisplayHost(webTab.getUrl())
                    if (title.isEmpty()) {
                        this.tabTitle.text = compactText(hostText, 90)
                        this.tabUrl.visibility = View.GONE
                    } else {
                        this.tabTitle.text = compactText(title, 90)
                        this.tabUrl.text = hostText
                        this.tabUrl.visibility = View.VISIBLE
                    }
                }

                executePendingBindings()
            }
        }

        private fun bindMetaIcon(context: android.content.Context, webTab: WebTab) {
            binding.tabMetaIcon.clearColorFilter()
            binding.tabMetaIcon.setImageDrawable(null)
            binding.tabMetaIcon.setPadding(0, 0, 0, 0)
            binding.tabMetaIcon.scaleType = ImageView.ScaleType.CENTER_INSIDE

            if (webTab.isHome()) {
                val padding = context.resources.getDimensionPixelSize(R.dimen.padding_small)
                binding.tabMetaIcon.setPadding(padding, padding, padding, padding)
                val bm = AppCompatResources.getDrawable(context, R.drawable.home_48px)
                binding.tabMetaIcon.setImageDrawable(bm)
                binding.tabMetaIcon.setColorFilter(
                    context.getColor(R.color.colorPrimary),
                    PorterDuff.Mode.SRC_IN
                )
                return
            }

            val favicon = webTab.getFavicon()
            if (favicon != null) {
                binding.tabMetaIcon.setImageBitmap(favicon)
                return
            }

            val fallback = AppCompatResources.getDrawable(context, R.drawable.public_24px)
            binding.tabMetaIcon.setImageDrawable(fallback)
            binding.tabMetaIcon.setColorFilter(
                context.getColor(R.color.colorPrimary),
                PorterDuff.Mode.SRC_IN
            )
        }

        private fun bindPreview(context: android.content.Context, webTab: WebTab) {
            binding.faviconTab.clearColorFilter()
            binding.faviconTab.setImageDrawable(null)
            binding.faviconTab.setPadding(0, 0, 0, 0)
            binding.faviconTab.scaleType = ImageView.ScaleType.FIT_CENTER

            val thumbnail = resolvePreview(webTab)
            if (thumbnail != null) {
                binding.faviconTab.setImageBitmap(thumbnail)
                return
            }

            val padding = context.resources.getDimensionPixelSize(R.dimen.padding_large)
            binding.faviconTab.scaleType = ImageView.ScaleType.CENTER_INSIDE
            binding.faviconTab.setPadding(padding, padding, padding, padding)

            val iconRes = if (webTab.isHome()) {
                R.drawable.home_48px
            } else {
                R.drawable.public_24px
            }
            val bm = AppCompatResources.getDrawable(context, iconRes)
            binding.faviconTab.setImageDrawable(bm)
            binding.faviconTab.setColorFilter(
                context.getColor(R.color.colorPrimary),
                PorterDuff.Mode.SRC_IN
            )
        }

        private fun resolvePreview(webTab: WebTab): Bitmap? {
            return webTab.getPageThumbnail()
                ?: BrowserThumbnailStore.load(webTab.getPageThumbnailPath())
        }

        private fun compactText(value: String, maxLength: Int): String {
            val normalized = value.replace(Regex("\\s+"), " ").trim()
            if (normalized.length <= maxLength) {
                return normalized
            }
            return normalized.take(maxLength - 3).trimEnd() + "..."
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WebTabsViewHolder {
        val binding = DataBindingUtil.inflate<ItemWebTabButtonBinding>(
            LayoutInflater.from(parent.context),
            R.layout.item_web_tab_button, parent, false
        )

        return WebTabsViewHolder(binding)
    }

    override fun getItemCount() = webTabs.size

    override fun onBindViewHolder(holder: WebTabsViewHolder, position: Int) =
        holder.bind(webTabs[position], webTabsListener, webTabs[position].id == selectedTabId)

    fun setData(webTabs: List<WebTab>) {
        dispatchListDiff(
            oldItems = this.webTabs,
            newItems = webTabs,
            areItemsTheSame = { oldItem, newItem -> oldItem.id == newItem.id }
        ) {
            this.webTabs = webTabs
        }
    }

    fun setSelectedTabId(tabId: String?) {
        val previousTabId = selectedTabId
        if (selectedTabId == tabId) {
            return
        }
        selectedTabId = tabId
        notifyTabSelectionChanged(previousTabId)
        notifyTabSelectionChanged(tabId)
    }

    private fun notifyTabSelectionChanged(tabId: String?) {
        val position = webTabs.indexOfFirst { it.id == tabId }
        if (position != -1) {
            notifyItemChanged(position)
        }
    }
}
