package com.myAllVideoBrowser.ui.main.progress

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.data.local.room.entity.ProgressInfo
import com.myAllVideoBrowser.databinding.ItemDownloadHistoryBinding
import com.myAllVideoBrowser.databinding.ItemHistoryHeaderBinding
import com.myAllVideoBrowser.util.FileUtil.Companion.getFileSizeReadable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 下载历史行：日期分组头或一条记录。 */
sealed class HistoryRow {
    data class Header(val title: String) : HistoryRow()
    data class Entry(val info: ProgressInfo, val fileExists: Boolean) : HistoryRow()
}

class DownloadHistoryAdapter(
    private val onOpenFile: (ProgressInfo) -> Unit,
    private val onMenu: (View, ProgressInfo) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val rows = mutableListOf<HistoryRow>()
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    fun submitRows(newRows: List<HistoryRow>) {
        rows.clear()
        rows.addAll(newRows)
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is HistoryRow.Header -> TYPE_HEADER
        is HistoryRow.Entry -> TYPE_ENTRY
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderHolder(ItemHistoryHeaderBinding.inflate(inflater, parent, false))
        } else {
            EntryHolder(ItemDownloadHistoryBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is HistoryRow.Header -> (holder as HeaderHolder).bind(row)
            is HistoryRow.Entry -> (holder as EntryHolder).bind(row)
        }
    }

    override fun getItemCount(): Int = rows.size

    private inner class HeaderHolder(
        private val binding: ItemHistoryHeaderBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: HistoryRow.Header) {
            binding.tvHistoryHeader.text = row.title
        }
    }

    private inner class EntryHolder(
        private val binding: ItemDownloadHistoryBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: HistoryRow.Entry) {
            val context = binding.root.context
            val info = row.info
            binding.tvHistoryTitle.text = info.videoInfo.title.ifBlank {
                info.videoInfo.name.ifBlank { info.finalMediaUri }
            }

            val source = sourceHost(info.videoInfo.originalUrl)
            val size = getFileSizeReadable(info.progressTotal.toDouble())
            val time = if (info.completedAt > 0L) {
                timeFormat.format(Date(info.completedAt))
            } else {
                ""
            }
            binding.tvHistorySubtitle.text = listOf(source, size, time)
                .filter { it.isNotBlank() }
                .joinToString(" · ")

            if (row.fileExists) {
                binding.tvHistoryStatus.visibility = View.GONE
            } else {
                binding.tvHistoryStatus.visibility = View.VISIBLE
                binding.tvHistoryStatus.setText(R.string.progress_history_file_missing)
            }

            binding.root.setOnClickListener {
                if (row.fileExists) onOpenFile(info)
            }
            binding.ivHistoryMore.setOnClickListener { anchor ->
                onMenu(anchor, info)
            }
        }

        private fun sourceHost(url: String): String {
            if (url.isBlank()) return binding.root.context.getString(R.string.download_history_source_unknown)
            return runCatching { Uri.parse(url).host.orEmpty() }
                .getOrDefault("")
                .ifBlank { binding.root.context.getString(R.string.download_history_source_unknown) }
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ENTRY = 1
    }
}
