package com.myAllVideoBrowser.ui.component.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.databinding.ItemImageInfoBinding
import com.myAllVideoBrowser.ui.main.home.browser.detectedVideos.VideoDetectionTabViewModel
import com.myAllVideoBrowser.util.FileUtil

class ImageInfoAdapter(
    private var imageInfoList: List<VideoInfo>,
    private val model: VideoDetectionTabViewModel,
    private val downloadListener: DownloadTabListener
) : RecyclerView.Adapter<ImageInfoAdapter.ImageInfoViewHolder>() {

    class ImageInfoViewHolder(
        private val binding: ItemImageInfoBinding,
        private val model: VideoDetectionTabViewModel,
        private val downloadListener: DownloadTabListener
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(info: VideoInfo) {
            binding.imageInfo = info.copy(
                thumbnail = info.thumbnail.ifBlank { info.firstUrlToString }
            )
            binding.viewModel = model
            binding.imageCheckbox.setOnCheckedChangeListener(null)
            binding.imageCheckbox.isChecked = model.isImageSelected(info.id)
            binding.imageCheckbox.setOnCheckedChangeListener { _, _ ->
                model.toggleImageSelection(info)
            }
            binding.imageDownloadButton.setOnClickListener {
                downloadListener.onDownloadVideo(info, "direct", info.title)
            }
            val size = info.formats.formats.firstOrNull()?.let {
                if (it.fileSize > 0) it.fileSize else it.fileSizeApproximate
            } ?: 0L
            binding.imageMeta.text = listOf(
                info.ext.uppercase().takeIf { it.isNotBlank() },
                size.takeIf { it > 0 }?.let { FileUtil.getFileSizeReadable(it.toDouble()) }
            ).filterNotNull().joinToString(" · ")
            binding.executePendingBindings()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageInfoViewHolder {
        val binding = ItemImageInfoBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ImageInfoViewHolder(binding, model, downloadListener)
    }

    override fun onBindViewHolder(holder: ImageInfoViewHolder, position: Int) {
        holder.bind(imageInfoList[position])
    }

    override fun getItemCount(): Int = imageInfoList.size

    fun setData(items: List<VideoInfo>) {
        dispatchListDiff(
            oldItems = imageInfoList,
            newItems = items,
            areItemsTheSame = { oldItem, newItem -> oldItem.id == newItem.id }
        ) {
            imageInfoList = items
        }
    }
}
