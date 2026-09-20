package com.myAllVideoBrowser.ui.component.adapter

import android.graphics.PorterDuff
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import android.graphics.drawable.Drawable
import androidx.constraintlayout.widget.ConstraintLayout
import java.io.File
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
    private val stableIds = mutableMapOf<String, Long>()
    private var nextStableId = 0L
    init {
        setHasStableIds(true)
    }

    class WebTabsViewHolder(val binding: ItemWebTabButtonBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private var previewTarget: CustomTarget<Bitmap>? = null
        private var previewGeneration = 0

        fun clearPreview(keepMemoryImage: Boolean = false) {
            previewGeneration++
            if (!keepMemoryImage) binding.faviconTab.setImageDrawable(null)
            previewTarget?.let { Glide.with(binding.faviconTab).clear(it) }
            previewTarget = null
        }

        fun bindSelection(isSelected: Boolean) {
            val context = binding.root.context
            binding.itemWebTabButton.setCardBackgroundColor(context.getColor(if (isSelected) R.color.sxSurfaceSelected else R.color.sxSurfaceRaised))
            binding.itemWebTabButton.isSelected = isSelected
            binding.itemWebTabButton.strokeColor = context.getColor(if (isSelected) R.color.colorPrimary else R.color.sxOutline)
            binding.itemWebTabButton.strokeWidth = ((if (isSelected) 2 else 1) * context.resources.displayMetrics.density).toInt()
        }

        fun bind(webTab: WebTab, webTabsListener: WebTabsListener, isSelected: Boolean) {
            with(binding)
            {
                val context = this.root.context

                val keepMemoryImage = this.webTab?.id == webTab.id && this.webTab?.getUrl() == webTab.getUrl() &&
                    webTab.getPageThumbnailPath() != null && previewTarget == null && faviconTab.drawable != null
                this.webTab = webTab
                this.tabListener = webTabsListener
                executePendingBindings()
                bindSelection(isSelected)

                this.closeTab.visibility = if (webTab.isHome()) {
                    View.GONE
                } else {
                    View.VISIBLE
                }

                bindMetaIcon(context, webTab)
                bindPreview(context, webTab, keepMemoryImage)

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

        private fun bindPreview(context: android.content.Context, webTab: WebTab, keepMemoryImage: Boolean) {
            clearPreview(keepMemoryImage)
            val generation = previewGeneration
            val savedSize = BrowserThumbnailStore.dimensions(webTab.getPageThumbnailPath())
            savedSize?.let { setPreviewRatio(it.first, it.second) }
            val inMemory = webTab.getPageThumbnail()?.takeUnless { it.isRecycled }
            if (inMemory != null) {
                showBitmap(inMemory)
                return
            }
            if (!keepMemoryImage) showPreviewFallback(context, webTab, savedSize)
            val path = webTab.getPageThumbnailPath()?.takeIf { it.isNotBlank() } ?: return
            // Each persisted snapshot has its own path. Glide owns decode, caching and cancellation.
            val target = object : CustomTarget<Bitmap>(480, 960) {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    if (previewGeneration == generation) showBitmap(resource)
                }
                override fun onLoadCleared(placeholder: Drawable?) {
                    if (previewGeneration == generation) binding.faviconTab.setImageDrawable(null)
                }
            }
            previewTarget = target
            Glide.with(binding.faviconTab).asBitmap().load(File(path)).fitCenter().into(target)
        }

        private fun setPreviewRatio(width: Int, height: Int) {
            val params = binding.faviconTab.layoutParams as ConstraintLayout.LayoutParams
            val next = "H,$width:$height"
            if (params.dimensionRatio != next) {
                params.dimensionRatio = next
                binding.faviconTab.requestLayout()
            }
        }

        private fun showBitmap(bitmap: Bitmap) {
            binding.faviconTab.apply {
                clearColorFilter()
                setPadding(0, 0, 0, 0)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPreviewRatio(bitmap.width, bitmap.height)
                setImageBitmap(bitmap)
                requestLayout()
            }
        }

        private fun showPreviewFallback(context: android.content.Context, webTab: WebTab, size: Pair<Int, Int>? = null) {
            setPreviewRatio(size?.first ?: 4, size?.second ?: 3)
            val padding = context.resources.getDimensionPixelSize(R.dimen.padding_large)
            binding.faviconTab.scaleType = ImageView.ScaleType.CENTER_INSIDE
            binding.faviconTab.setPadding(padding, padding, padding, padding)
            val iconRes = if (webTab.isHome()) R.drawable.home_48px else R.drawable.public_24px
            binding.faviconTab.setImageDrawable(AppCompatResources.getDrawable(context, iconRes))
            binding.faviconTab.setColorFilter(context.getColor(R.color.colorPrimary), PorterDuff.Mode.SRC_IN)
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

    override fun getItemId(position: Int): Long = stableIds.getOrPut(webTabs[position].id) { nextStableId++ }

    override fun onBindViewHolder(holder: WebTabsViewHolder, position: Int) =
        holder.bind(webTabs[position], webTabsListener, webTabs[position].id == selectedTabId)

    override fun onViewRecycled(holder: WebTabsViewHolder) {
        holder.clearPreview()
        holder.binding.webTab = null
        holder.binding.tabListener = null
        super.onViewRecycled(holder)
    }

    override fun onBindViewHolder(holder: WebTabsViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty()) holder.bindSelection(webTabs[position].id == selectedTabId)
        else onBindViewHolder(holder, position)
    }

    fun setData(webTabs: List<WebTab>) {
        dispatchListDiff(
            oldItems = this.webTabs,
            newItems = webTabs,
            areItemsTheSame = { oldItem, newItem -> oldItem.id == newItem.id },
            areContentsTheSame = { oldItem, newItem ->
                oldItem.id == newItem.id &&
                    oldItem.getUrl() == newItem.getUrl() &&
                    oldItem.getTitle() == newItem.getTitle() &&
                    oldItem.getPageThumbnailPath() == newItem.getPageThumbnailPath() &&
                    oldItem.getPageThumbnail() === newItem.getPageThumbnail() &&
                    oldItem.getFavicon() === newItem.getFavicon()
            }
        ) {
            this.webTabs = webTabs
            stableIds.keys.retainAll(webTabs.map { it.id }.toSet())
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
            notifyItemChanged(position, "selection")
        }
    }
}
