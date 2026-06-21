package com.myAllVideoBrowser.ui.component.adapter

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.databinding.ObservableField
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.data.local.room.entity.VideoFormatEntity
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.databinding.DownloadCandidateItemBinding
import com.myAllVideoBrowser.util.VideoFormatUi


interface DownloadVideoListener {
    fun onPreviewVideo(
        videoInfo: VideoInfo, dialog: BottomSheetDialog?, format: String, isForce: Boolean
    )

    fun onDownloadVideo(
        videoInfo: VideoInfo, dialog: BottomSheetDialog?, format: String, videoTitle: String
    )
}

interface DownloadTabVideoListener {
    fun onPreviewVideo(
        videoInfo: VideoInfo, format: String, isForce: Boolean
    )

    fun onDownloadVideo(
        videoInfo: VideoInfo, format: String, videoTitle: String
    )
}

interface DownloadDialogListener : DownloadVideoListener, CandidateFormatListener {
    fun onCancel(dialog: BottomSheetDialog?)
}

interface DownloadTabListener : DownloadTabVideoListener, CandidateFormatListener {
    fun onCancel()
}

interface CandidateFormatListener {
    fun onSelectFormat(videoInfo: VideoInfo, format: String)

    fun onFormatUrlShare(videoInfo: VideoInfo, format: String): Boolean
}

class CandidatesListRecyclerViewAdapter(
    private val downloadCandidates: VideoInfo,
    private val selectedFormat: ObservableField<Map<String, String>>,
    private val downloadDialogListener: CandidateFormatListener
) : RecyclerView.Adapter<CandidatesListRecyclerViewAdapter.CandidatesViewHolder>() {

    private var formats: List<VideoFormatEntity> = arrayListOf()

    init {
        val allFormats = downloadCandidates.formats.formats
        formats = VideoFormatUi.sortFormats(allFormats)
    }

    class CandidatesViewHolder(val binding: DownloadCandidateItemBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CandidatesViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = DownloadCandidateItemBinding.inflate(inflater, parent, false)
        return CandidatesViewHolder(binding)
    }

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(holder: CandidatesViewHolder, position: Int) {
        val formatEntity = formats.getOrNull(position) ?: return
        val candidate = VideoFormatUi.selectionKey(formatEntity)
        val titleText = VideoFormatUi.title(holder.binding.root.context, formatEntity, position)
        val detailsText = VideoFormatUi.details(holder.binding.root.context, formatEntity, position)

        with(holder.binding) {
            val selected = selectedFormat.get()?.get(downloadCandidates.id)

            val color = if (candidate == selected) {
                root.context.getColor(R.color.sxSurfaceSelected)
            } else {
                root.context.getColor(R.color.sxSurfaceRaised)
            }
            this.cardItem.setCardBackgroundColor(color)

            this.videoInfo = downloadCandidates
            this.downloadCandidate = candidate
            this.isCandidateSelected = candidate == selected
            this.tvTitle.text = titleText

            this.listener = object : CandidateFormatListener {
                override fun onSelectFormat(videoInfo: VideoInfo, format: String) {
                    val currentPosition = holder.bindingAdapterPosition
                    if (currentPosition != RecyclerView.NO_POSITION) {
                        val previousSelection = selectedFormat.get()?.get(downloadCandidates.id)
                        downloadDialogListener.onSelectFormat(videoInfo, format)
                        notifySelectionChanged(currentPosition, previousSelection, format)
                    }
                }

                override fun onFormatUrlShare(videoInfo: VideoInfo, format: String): Boolean {
                    val currentPosition = holder.bindingAdapterPosition
                    return if (currentPosition != RecyclerView.NO_POSITION) {
                        downloadDialogListener.onFormatUrlShare(videoInfo, format)
                    } else {
                        false
                    }
                }
            }

            this.tvData.text = detailsText

            this.executePendingBindings()
        }
    }

    override fun getItemCount(): Int = formats.size

    fun setData(formats: List<VideoFormatEntity>) {
        val previousFormats = this.formats
        val newFormats = VideoFormatUi.sortFormats(formats)
        val diffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = previousFormats.size

            override fun getNewListSize(): Int = newFormats.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return VideoFormatUi.selectionKey(previousFormats[oldItemPosition]) ==
                    VideoFormatUi.selectionKey(newFormats[newItemPosition])
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return previousFormats[oldItemPosition] == newFormats[newItemPosition]
            }
        })
        this.formats = newFormats
        diffResult.dispatchUpdatesTo(this)
    }

    private fun notifySelectionChanged(
        currentPosition: Int,
        previousSelection: String?,
        requestedSelection: String
    ) {
        val positions = linkedSetOf<Int>()
        positions.add(currentPosition)
        positions.add(findFormatPosition(previousSelection))
        positions.add(findFormatPosition(selectedFormat.get()?.get(downloadCandidates.id)))
        positions.add(findFormatPosition(requestedSelection))
        positions
            .filter { it != RecyclerView.NO_POSITION }
            .forEach { notifyItemChanged(it) }
    }

    private fun findFormatPosition(selectionKey: String?): Int {
        if (selectionKey.isNullOrBlank()) {
            return RecyclerView.NO_POSITION
        }

        val position = formats.indexOfFirst { VideoFormatUi.selectionKey(it) == selectionKey }
        return if (position >= 0) position else RecyclerView.NO_POSITION
    }
}
