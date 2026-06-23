package com.myAllVideoBrowser.ui.component.adapter

import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) =
        holder.bind(localVideos[position], videoListener)

    class VideoViewHolder(var binding: ItemVideoBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(localVideo: LocalVideo, videoListener: VideoListener) {
            with(binding) {
                this.localVideo = localVideo
                this.videoListener = videoListener
                // 清除残留 transitionName：保证列表中仅被点击项在 startVideo 时持有共享元素名，避免重名冲突
                this.ivThumbnail.transitionName = null
                this.cardVideo.setCardBackgroundColor(itemView.context.getColor(R.color.sxSurfaceRaised))
                val thumbnailOptions = RequestOptions()
                    .frame(localVideo.thumbnailFrameMicros)
                    .format(DecodeFormat.PREFER_ARGB_8888)
                    .override(VIDEO_THUMBNAIL_WIDTH_PX, VIDEO_THUMBNAIL_HEIGHT_PX)
                    .fitCenter()
                    .diskCacheStrategy(DiskCacheStrategy.RESOURCE)

                Glide.with(this@VideoViewHolder.itemView.context)
                    .load(localVideo.uri)
                    .error(R.drawable.ic_video_24dp)
                    .placeholder(R.drawable.ic_video_24dp)
                    .apply(thumbnailOptions)
                    .into(this.ivThumbnail)

                executePendingBindings()
            }
        }
    }

    fun setData(localVideos: List<LocalVideo>) {
        dispatchListDiff(
            oldItems = this.localVideos,
            newItems = localVideos,
            areItemsTheSame = { oldItem, newItem -> oldItem.id == newItem.id }
        ) {
            this.localVideos = localVideos
        }
    }
}

interface VideoListener {
    fun onItemClicked(localVideo: LocalVideo, sharedView: View)
    fun onMenuClicked(view: View, localVideo: LocalVideo)
    fun onSourceClicked(localVideo: LocalVideo)
}

@GlideModule
class MyGlideModule : AppGlideModule() {
    override fun applyOptions(context: Context, builder: GlideBuilder) {
        builder.setLogLevel(Log.ERROR)
    }
}
