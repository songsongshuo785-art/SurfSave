package com.myAllVideoBrowser.ui.component.adapter

import android.content.Context
import androidx.databinding.DataBindingUtil
import androidx.recyclerview.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.data.local.room.entity.ProgressInfo
import com.myAllVideoBrowser.databinding.ItemProgressBinding
import com.myAllVideoBrowser.util.DisplayNameFormatter
import com.myAllVideoBrowser.util.ProgressTextHumanizer
import com.myAllVideoBrowser.util.UserFacingError
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskState

class ProgressAdapter(
    private var progressInfos: List<ProgressInfo>,
    private var videoListener: ProgressListener
) : RecyclerView.Adapter<ProgressAdapter.ProgressViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProgressViewHolder {
        val binding = DataBindingUtil.inflate<ItemProgressBinding>(
            LayoutInflater.from(parent.context), R.layout.item_progress, parent, false
        )

        return ProgressViewHolder(binding)
    }

    override fun getItemCount() = progressInfos.size

    override fun onBindViewHolder(holder: ProgressViewHolder, position: Int) =
        holder.bind(progressInfos[position], videoListener)

    class ProgressViewHolder(val binding: ItemProgressBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(progressInfo: ProgressInfo, progressListener: ProgressListener) {
            val context = itemView.context
            val thumbnail = progressInfo.videoInfo.thumbnail
            val placeholder = R.drawable.surf_video_placeholder
            val size = getScreenResolution(context)
            with(binding)
            {
                this.progressInfo = progressInfo
                this.progressListener = progressListener
                this.downloadId = progressInfo.downloadId
                this.isRegular = progressInfo.videoInfo.isRegularDownload

                // Humanized title: display-only cleanup, raw name kept as fallback
                val rawName = progressInfo.videoInfo.name
                this.tvTitle.text =
                    DisplayNameFormatter.clean(rawName).ifBlank { rawName }

                // Humanized progress line: localized size + status
                this.tvProgress.text =
                    ProgressTextHumanizer.progressLine(context, progressInfo)

                // Error line: compact localized category + suggestion, only on failure
                val failed = progressInfo.downloadStatus == VideoTaskState.ERROR ||
                    progressInfo.downloadStatus == VideoTaskState.ENOSPC
                if (failed) {
                    this.infoLine.text =
                        UserFacingError.compactMessage(context, progressInfo.lastError)
                    this.infoLine.setTextColor(context.getColor(R.color.colorError))
                    this.infoLine.visibility = View.VISIBLE
                } else {
                    this.infoLine.visibility = View.GONE
                }

                Glide.with(context).load(thumbnail).centerCrop()
                    .error(placeholder)
                    .placeholder(placeholder)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .apply(RequestOptions().override(size.first / 8, size.second / 8))
                    .into(this.ivThumbnail)

                executePendingBindings()
            }
        }

        private fun getScreenResolution(context: Context): Pair<Int, Int> {
            val displayMetrics = context.resources.displayMetrics
            val widthPixels = displayMetrics.widthPixels
            val heightPixels = displayMetrics.heightPixels

            return Pair(widthPixels, heightPixels)
        }
    }

    fun setData(progressInfos: List<ProgressInfo>) {
        dispatchListDiff(
            oldItems = this.progressInfos,
            newItems = progressInfos,
            areItemsTheSame = { oldItem, newItem -> oldItem.downloadId == newItem.downloadId }
        ) {
            this.progressInfos = progressInfos
        }
    }
}

interface ProgressListener {
    fun onMenuClicked(view: View, downloadId: Long, isRegular: Boolean)
}
