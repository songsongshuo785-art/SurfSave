package com.myAllVideoBrowser.ui.component.adapter

import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.data.local.room.entity.BrowserFileDownload
import com.myAllVideoBrowser.data.local.room.entity.BrowserFileDownloadStatus
import com.myAllVideoBrowser.databinding.ItemBrowserFileDownloadBinding
import java.util.Locale

class BrowserFileDownloadAdapter(
    private var downloads: List<BrowserFileDownload>,
    private val listener: BrowserFileDownloadListener
) : RecyclerView.Adapter<BrowserFileDownloadAdapter.FileDownloadViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileDownloadViewHolder {
        return FileDownloadViewHolder(
            ItemBrowserFileDownloadBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        )
    }

    override fun getItemCount(): Int = downloads.size

    override fun onBindViewHolder(holder: FileDownloadViewHolder, position: Int) {
        holder.bind(downloads[position], listener)
    }

    fun setData(newDownloads: List<BrowserFileDownload>) {
        dispatchListDiff(
            oldItems = downloads,
            newItems = newDownloads,
            areItemsTheSame = { oldItem, newItem -> oldItem.id == newItem.id }
        ) {
            downloads = newDownloads
        }
    }

    class FileDownloadViewHolder(
        private val binding: ItemBrowserFileDownloadBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(download: BrowserFileDownload, listener: BrowserFileDownloadListener) {
            val context = binding.root.context
            binding.tvTitle.text = download.fileName
            binding.tvExtension.text = download.fileName.substringAfterLast('.', "FILE")
                .take(5)
                .uppercase(Locale.ROOT)
            binding.tvStatus.text = statusText(download)
            binding.tvMetadata.text = metadataText(download)

            binding.progressBar.visibility = if (download.isActive) View.VISIBLE else View.GONE
            if (download.isActive) {
                binding.progressBar.isIndeterminate = download.totalBytes <= 0
                if (!binding.progressBar.isIndeterminate) {
                    binding.progressBar.setProgressCompat(download.progressPercent, true)
                }
            }

            val statusColor = if (
                download.status == BrowserFileDownloadStatus.FAILED ||
                download.status == BrowserFileDownloadStatus.MISSING
            ) {
                context.getColor(R.color.colorError)
            } else {
                context.getColor(R.color.sxTextSecondary)
            }
            binding.tvStatus.setTextColor(statusColor)

            binding.ipMore.setOnClickListener { listener.onFileMenuClicked(it, download) }
            binding.cardFileDownload.setOnClickListener {
                if (download.canOpen) listener.onFileOpenClicked(download)
            }
        }

        private fun statusText(download: BrowserFileDownload): String {
            val context = binding.root.context
            return when (download.status) {
                BrowserFileDownloadStatus.PENDING -> context.getString(R.string.browser_file_status_pending)
                BrowserFileDownloadStatus.RUNNING -> if (download.totalBytes > 0) {
                    context.getString(R.string.browser_file_status_downloading_percent, download.progressPercent)
                } else {
                    context.getString(R.string.browser_file_status_downloading)
                }
                BrowserFileDownloadStatus.PAUSED -> context.getString(R.string.browser_file_status_paused)
                BrowserFileDownloadStatus.SUCCESSFUL -> context.getString(R.string.browser_file_status_complete)
                BrowserFileDownloadStatus.FAILED -> context.getString(
                    R.string.browser_file_status_failed_reason,
                    download.failureReason
                )
                BrowserFileDownloadStatus.MISSING -> context.getString(R.string.browser_file_status_missing)
                BrowserFileDownloadStatus.CANCELED -> context.getString(R.string.browser_file_status_canceled)
                else -> context.getString(R.string.browser_file_status_failed)
            }
        }

        private fun metadataText(download: BrowserFileDownload): String {
            val context = binding.root.context
            val size = when {
                download.totalBytes > 0 && download.isActive -> context.getString(
                    R.string.browser_file_size_progress,
                    Formatter.formatFileSize(context, download.downloadedBytes),
                    Formatter.formatFileSize(context, download.totalBytes)
                )
                download.totalBytes > 0 -> Formatter.formatFileSize(context, download.totalBytes)
                download.expectedSize > 0 -> Formatter.formatFileSize(context, download.expectedSize)
                else -> context.getString(R.string.browser_file_size_unknown)
            }
            val timestamp = (download.completedAt.takeIf { it > 0 } ?: download.createdAt)
            val relativeTime = DateUtils.getRelativeTimeSpanString(
                timestamp,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS
            )
            return context.getString(R.string.browser_file_metadata, size, relativeTime)
        }
    }
}

interface BrowserFileDownloadListener {
    fun onFileOpenClicked(download: BrowserFileDownload)
    fun onFileMenuClicked(anchor: View, download: BrowserFileDownload)
}
