package com.myAllVideoBrowser.ui.component.adapter

import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.databinding.DataBindingUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.module.AppGlideModule
import com.bumptech.glide.request.RequestOptions
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.data.local.model.LocalVideo
import com.myAllVideoBrowser.databinding.ItemVideoBinding

class VideoAdapter(
    private var localVideos: List<LocalVideo>,
    private val videoListener: VideoListener
) : RecyclerView.Adapter<VideoAdapter.VideoViewHolder>() {
    companion object {
        private const val VIDEO_THUMBNAIL_WIDTH_PX = 720
        private const val VIDEO_THUMBNAIL_HEIGHT_PX = 480
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        val binding = DataBindingUtil.inflate<ItemVideoBinding>(
            LayoutInflater.from(parent.context), R.layout.item_video, parent, false
        )

        return VideoViewHolder(binding)
    }

    override fun getItemCount() = localVideos.size

    var selectionMode: Boolean = false
        private set
    private val selectedUris = mutableSetOf<String>()

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
        val video = localVideos[position]
        holder.bind(video, videoListener, selectionMode && isSelected(video.uri))
    }

    fun setSelectionMode(enabled: Boolean) {
        selectionMode = enabled
        if (!enabled) selectedUris.clear()
        notifyDataSetChanged()
    }

    fun toggleSelection(uri: Uri) {
        val key = uri.toString()
        if (!selectedUris.add(key)) selectedUris.remove(key)
        notifyDataSetChanged()
    }

    fun selectAll() {
        localVideos.forEach { selectedUris += it.uri.toString() }
        notifyDataSetChanged()
    }

    fun clearSelection() {
        selectedUris.clear()
        notifyDataSetChanged()
    }

    fun selectedCount(): Int = selectedUris.size

    fun selectedVideos(): List<LocalVideo> =
        localVideos.filter { it.uri.toString() in selectedUris }

    fun isSelected(uri: Uri): Boolean = uri.toString() in selectedUris

    class VideoViewHolder(var binding: ItemVideoBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(localVideo: LocalVideo, videoListener: VideoListener, selected: Boolean = false) {
            with(binding) {
                val context = itemView.context
                val strokeWidthPx = (3 * context.resources.displayMetrics.density).toInt()
                cardVideo.strokeWidth = if (selected) strokeWidthPx else 0
                cardVideo.strokeColor = ContextCompat.getColor(
                    context,
                    if (selected) R.color.colorPrimary else R.color.sxOutline
                )
                cardVideo.setOnLongClickListener {
                    videoListener.onItemLongClicked(localVideo)
                    true
                }
                this.localVideo = localVideo
                this.videoListener = videoListener
                // 清除残留 transitionName：保证列表中仅被点击项在 startVideo 时持有共享元素名，避免重名冲突
                this.ivThumbnail.transitionName = null
                val placeholder = R.drawable.surf_video_placeholder
                val requestManager = Glide.with(this@VideoViewHolder.itemView.context)
                val thumbnailRequest = if (localVideo.isImage) {
                    requestManager
                        // Glide has no bundled SVG decoder; keep SVG downloadable
                        // and openable, but show a safe placeholder in the library.
                        .load(if (localVideo.isSvg) null else localVideo.uri)
                        .apply(RequestOptions()
                            .override(VIDEO_THUMBNAIL_WIDTH_PX, VIDEO_THUMBNAIL_HEIGHT_PX)
                            .centerInside()
                            .diskCacheStrategy(DiskCacheStrategy.RESOURCE))
                        .error(placeholder)
                        .placeholder(placeholder)
                } else {
                    val thumbnailOptions = RequestOptions()
                        .frame(localVideo.thumbnailFrameMicros)
                        .format(DecodeFormat.PREFER_ARGB_8888)
                        .override(VIDEO_THUMBNAIL_WIDTH_PX, VIDEO_THUMBNAIL_HEIGHT_PX)
                        .centerCrop()
                        .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                    val videoFrameFallback = requestManager
                        .load(localVideo.uri)
                        .apply(thumbnailOptions)
                        .error(placeholder)
                        .placeholder(placeholder)
                    localVideo.usableOriginalThumbnailUrl
                        ?.let { thumbnailUrl ->
                            requestManager
                                .load(thumbnailUrl)
                                .error(videoFrameFallback)
                                .placeholder(placeholder)
                        }
                        ?: videoFrameFallback
                }

                thumbnailRequest
                    .into(this.ivThumbnail)

                executePendingBindings()
            }
        }
    }

    fun setData(localVideos: List<LocalVideo>) {
        dispatchListDiff(
            oldItems = this.localVideos,
            newItems = localVideos,
            areItemsTheSame = { oldItem, newItem -> oldItem.id == newItem.id },
            areContentsTheSame = { old, new ->
                old == new && old.size == new.size && old.quality == new.quality &&
                    old.sourceUrl == new.sourceUrl &&
                    old.originalThumbnailUrl == new.originalThumbnailUrl &&
                    old.thumbnailFrameMicros == new.thumbnailFrameMicros &&
                    old.sizeBytes == new.sizeBytes &&
                    old.durationMillis == new.durationMillis
            }
        ) {
            this.localVideos = localVideos
        }
    }
}

interface VideoListener {
    fun onItemClicked(localVideo: LocalVideo, sharedView: View)
    fun onMenuClicked(view: View, localVideo: LocalVideo)
    fun onSourceClicked(localVideo: LocalVideo)
    fun onItemLongClicked(localVideo: LocalVideo)
}

@GlideModule
class MyGlideModule : AppGlideModule() {
    override fun applyOptions(context: Context, builder: GlideBuilder) {
        builder.setLogLevel(Log.ERROR)
    }
}
