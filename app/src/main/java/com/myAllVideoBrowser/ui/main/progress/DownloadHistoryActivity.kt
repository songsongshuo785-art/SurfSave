package com.myAllVideoBrowser.ui.main.progress

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.MimeTypeMap
import android.view.View
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.data.local.room.entity.ProgressInfo
import com.myAllVideoBrowser.data.repository.ProgressRepository
import com.myAllVideoBrowser.databinding.ActivityDownloadHistoryBinding
import com.myAllVideoBrowser.ui.main.base.BaseActivity
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.FileUtil
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject

/** 下载历史：完整记录管理页，数据源与下载页“已完成”完全一致。 */
class DownloadHistoryActivity : BaseActivity() {

    @Inject
    lateinit var progressRepository: ProgressRepository

    @Inject
    lateinit var fileUtil: FileUtil

    private lateinit var binding: ActivityDownloadHistoryBinding
    private lateinit var adapter: DownloadHistoryAdapter

    private var allInfos: List<ProgressInfo> = emptyList()
    private var fileExists: Map<String, Boolean> = emptyMap()
    private var query: String = ""
    private var pendingDelete: PendingHistoryDelete? = null

    private val deleteAuthLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        onDeleteAuthResult(result.resultCode == RESULT_OK)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDownloadHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.historyToolbar.setNavigationOnClickListener { finish() }
        adapter = DownloadHistoryAdapter(
            onOpenFile = ::openFile,
            onMenu = ::showMenu,
        )
        binding.historyList.layoutManager = LinearLayoutManager(this)
        binding.historyList.adapter = adapter
        binding.historySearchInput.doAfterTextChanged {
            query = it?.toString().orEmpty()
            render()
        }

        loadHistory(showBackfill = true)
    }

    private fun loadHistory(showBackfill: Boolean) {
        lifecycleScope.launch {
            val infos = loadSuccessInfos()
            allInfos = infos.sortedByDescending { it.completedAt }
            fileExists = checkFiles(allInfos)
            render()

            if (!showBackfill) return@launch

            withContext(Dispatchers.IO) {
                HistoryBackfillManager(applicationContext, progressRepository, fileUtil)
                    .backfillIfNeeded()
            }
            val refreshed = loadSuccessInfos()
            if (refreshed.size != allInfos.size) {
                allInfos = refreshed.sortedByDescending { it.completedAt }
                fileExists = checkFiles(allInfos)
                render()
            }
        }
    }

    private suspend fun loadSuccessInfos(): List<ProgressInfo> = withContext(Dispatchers.IO) {
        runCatching {
            progressRepository.getProgressInfosOnce()
                .filter { it.downloadStatus == VideoTaskState.SUCCESS }
        }.getOrElse { error ->
            AppLogger.e("Download history: failed to load", error)
            emptyList()
        }
    }

    private suspend fun checkFiles(infos: List<ProgressInfo>): Map<String, Boolean> =
        withContext(Dispatchers.IO) {
            infos.mapNotNull { info ->
                val uriString = info.finalMediaUri
                if (uriString.isBlank()) return@mapNotNull null
                uriString to runCatching {
                    fileUtil.isUriExists(this@DownloadHistoryActivity, Uri.parse(uriString))
                }.getOrDefault(false)
            }.toMap()
        }

    private fun render() {
        val rows = buildRows(filterInfos(allInfos, query))
        adapter.submitRows(rows)
        val isEmpty = rows.isEmpty()
        binding.historyEmptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.historyList.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    private fun filterInfos(infos: List<ProgressInfo>, rawQuery: String): List<ProgressInfo> {
        val needle = rawQuery.trim().lowercase(Locale.getDefault())
        if (needle.isBlank()) return infos
        return infos.filter { info ->
            info.videoInfo.title.lowercase(Locale.getDefault()).contains(needle) ||
                info.videoInfo.name.lowercase(Locale.getDefault()).contains(needle) ||
                sourceHost(info.videoInfo.originalUrl).lowercase(Locale.getDefault()).contains(needle)
        }
    }

    private fun buildRows(infos: List<ProgressInfo>): List<HistoryRow> {
        val today = startOfDay(0)
        val yesterday = startOfDay(-1)
        val groups = linkedMapOf<String, MutableList<HistoryRow.Entry>>()
        infos.forEach { info ->
            val key = when {
                info.completedAt >= today -> getString(R.string.download_history_section_today)
                info.completedAt >= yesterday -> getString(R.string.download_history_section_yesterday)
                else -> getString(R.string.download_history_section_earlier)
            }
            groups.getOrPut(key) { mutableListOf() }.add(
                HistoryRow.Entry(info, fileExists[info.finalMediaUri] == true)
            )
        }
        return groups.flatMap { (title, entries) ->
            listOf(HistoryRow.Header(title)) + entries
        }
    }

    private fun startOfDay(offsetDays: Int): Long {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_YEAR, offsetDays)
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    private fun sourceHost(url: String): String {
        if (url.isBlank()) return ""
        return runCatching { Uri.parse(url).host.orEmpty() }.getOrDefault("")
    }

    private fun showMenu(anchor: View, info: ProgressInfo) {
        val popup = PopupMenu(anchor.context, anchor)
        popup.menuInflater.inflate(R.menu.menu_history_item, popup.menu)
        val exists = fileExists[info.finalMediaUri] == true
        popup.menu.findItem(R.id.item_history_menu_open_file).isVisible = exists
        popup.menu.findItem(R.id.item_history_menu_open_source).isVisible =
            info.videoInfo.originalUrl.isNotBlank()
        popup.menu.findItem(R.id.item_history_menu_delete_file).isVisible = exists
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.item_history_menu_open_file -> {
                    openFile(info)
                    true
                }

                R.id.item_history_menu_open_source -> {
                    openSource(info)
                    true
                }

                R.id.item_history_menu_delete_file -> {
                    confirmDeleteFile(info)
                    true
                }

                R.id.item_history_menu_delete_record -> {
                    confirmDeleteRecord(info)
                    true
                }

                else -> false
            }
        }
        popup.show()
    }

    private fun openFile(info: ProgressInfo) {
        val uriString = info.finalMediaUri
        if (uriString.isBlank()) return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(uriString), mimeTypeFor(info))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(intent) }
            .onFailure {
                Toast.makeText(this, R.string.progress_history_open_failed, Toast.LENGTH_LONG).show()
            }
    }

    private fun mimeTypeFor(info: ProgressInfo): String {
        val ext = info.videoInfo.ext.ifBlank {
            info.finalMediaUri.substringAfterLast('.', "")
        }.lowercase(Locale.US)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
    }

    private fun openSource(info: ProgressInfo) {
        val url = info.videoInfo.originalUrl
        if (url.isBlank()) return
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure {
                Toast.makeText(this, R.string.video_source_unavailable, Toast.LENGTH_LONG).show()
            }
    }

    private fun confirmDeleteFile(info: ProgressInfo) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.progress_history_delete_file_title)
            .setMessage(R.string.progress_history_delete_file_message)
            .setPositiveButton(R.string.progress_menu_delete_file) { _, _ -> deleteFile(info) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun deleteFile(info: ProgressInfo) {
        val uriString = info.finalMediaUri
        if (uriString.isBlank()) return
        lifecycleScope.launch {
            when (val result = withContext(Dispatchers.IO) {
                fileUtil.deleteMedia(this@DownloadHistoryActivity, Uri.parse(uriString))
            }) {
                is FileUtil.DeleteMediaResult.Success -> {
                    progressRepository.clearFinalMediaUri(uriString)
                    loadHistory(showBackfill = false)
                }

                is FileUtil.DeleteMediaResult.NeedsAuth -> {
                    pendingDelete = PendingHistoryDelete(
                        originalUri = uriString,
                        retryUri = result.retryUri,
                        verificationUri = result.verificationUri,
                    )
                    deleteAuthLauncher.launch(
                        IntentSenderRequest.Builder(result.intentSender).build()
                    )
                }

                is FileUtil.DeleteMediaResult.Failed -> {
                    Toast.makeText(
                        this@DownloadHistoryActivity,
                        R.string.progress_history_delete_failed,
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    private fun onDeleteAuthResult(granted: Boolean) {
        val pending = pendingDelete
        pendingDelete = null
        if (!granted || pending == null) return
        lifecycleScope.launch {
            val deleted = withContext(Dispatchers.IO) {
                if (pending.retryUri == null) {
                    fileUtil.isUriDefinitelyAbsent(this@DownloadHistoryActivity, pending.verificationUri)
                } else {
                    fileUtil.deleteMedia(this@DownloadHistoryActivity, pending.retryUri) is
                        FileUtil.DeleteMediaResult.Success
                }
            }
            if (deleted) {
                progressRepository.clearFinalMediaUri(pending.originalUri)
                loadHistory(showBackfill = false)
            } else {
                Toast.makeText(
                    this@DownloadHistoryActivity,
                    R.string.progress_history_delete_failed,
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun confirmDeleteRecord(info: ProgressInfo) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.progress_history_delete_record_title)
            .setMessage(R.string.progress_history_delete_record_message)
            .setPositiveButton(R.string.progress_menu_delete_record) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { progressRepository.deleteProgressInfo(info) }
                    loadHistory(showBackfill = false)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private data class PendingHistoryDelete(
        val originalUri: String,
        val retryUri: Uri?,
        val verificationUri: Uri,
    )
}
