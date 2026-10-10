package com.myAllVideoBrowser.ui.component.adapter

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.databinding.ObservableField
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
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
        videoInfo: VideoInfo, sharedView: View, format: String, isForce: Boolean
    )

    fun onChoosePlayer(
        videoInfo: VideoInfo,
        sharedView: View,
        anchorView: View,
        format: String,
        isForce: Boolean
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

    private var formats = VideoFormatUi.sortFormats(downloadCandidates, downloadCandidates.formats.formats)
    private var displayedFormats: List<VideoFormatEntity> = collapsedFormats()
    var isExpanded = false
        private set

    fun setExpanded(value: Boolean) {
        isExpanded = value
        updateDisplayedFormats()
    }

    // Keep the selected quality visible even when it is outside the first three options.
    private fun collapsedFormats(): List<VideoFormatEntity> {
        val selected = selectedFormat.get()?.get(downloadCandidates.id)
        val selectedIndex = formats.indexOfFirst {
            VideoFormatUi.selectionKey(downloadCandidates, it) == selected
        }
        return if (formats.size > 3 && selectedIndex >= 3) {
            formats.take(2) + formats[selectedIndex]
        } else formats.take(3)
    }

    fun refreshSelection() {
        updateDisplayedFormats()
        notifyItemRangeChanged(0, itemCount, "selection")
    }

    private fun updateDisplayedFormats() {
        val next = if (isExpanded) formats else collapsedFormats()
        dispatchListDiff(
            oldItems = displayedFormats,
            newItems = next,
            areItemsTheSame = { old, new ->
                VideoFormatUi.selectionKey(downloadCandidates, old) ==
                    VideoFormatUi.selectionKey(downloadCandidates, new)
            }
        ) { displayedFormats = next }
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
        val formatEntity = displayedFormats.getOrNull(position) ?: return
        val candidate = VideoFormatUi.selectionKey(downloadCandidates, formatEntity)
        val titleText = VideoFormatUi.title(holder.binding.root.context, formatEntity, position)
        val detailsText = VideoFormatUi.compactDetails(holder.binding.root.context, formatEntity)

        with(holder.binding) {
            val selected = selectedFormat.get()?.get(downloadCandidates.id)

            // Card background/stroke are driven by the is_candidate_selected binding
            this.videoInfo = downloadCandidates
            this.downloadCandidate = candidate
            this.isCandidateSelected = candidate == selected
            this.tvTitle.text = titleText

            this.listener = object : CandidateFormatListener {
                override fun onSelectFormat(videoInfo: VideoInfo, format: String) {
                    val currentPosition = holder.bindingAdapterPosition
                    if (currentPosition != RecyclerView.NO_POSITION) {
                        downloadDialogListener.onSelectFormat(videoInfo, format)
                        refreshSelection()
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

    override fun getItemCount(): Int = displayedFormats.size

    fun setData(formats: List<VideoFormatEntity>) {
        this.formats = VideoFormatUi.sortFormats(downloadCandidates, formats)
        updateDisplayedFormats()
    }
}
