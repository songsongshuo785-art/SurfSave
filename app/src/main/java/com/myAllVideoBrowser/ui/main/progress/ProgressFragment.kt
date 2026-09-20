package com.myAllVideoBrowser.ui.main.progress

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.ActivityNotFoundException
import android.app.DownloadManager
import android.os.Bundle
import android.provider.DocumentsContract
import android.os.Environment
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
import androidx.databinding.Observable
import androidx.core.content.FileProvider
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.Recycler
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.data.local.room.entity.BrowserFileDownload
import com.myAllVideoBrowser.data.local.room.entity.BrowserFileDownloadStatus
import com.myAllVideoBrowser.databinding.FragmentProgressBinding
import com.myAllVideoBrowser.ui.main.progress.DownloadTaskDetails
import com.myAllVideoBrowser.ui.component.adapter.ProgressAdapter
import com.myAllVideoBrowser.ui.component.adapter.ProgressListener
import com.myAllVideoBrowser.ui.component.adapter.BrowserFileDownloadAdapter
import com.myAllVideoBrowser.ui.component.adapter.BrowserFileDownloadListener
import com.myAllVideoBrowser.ui.main.base.BaseFragment
import com.myAllVideoBrowser.ui.main.home.MainActivity
import com.myAllVideoBrowser.ui.main.home.MainViewModel
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.ErrorLogRecorder
import com.myAllVideoBrowser.util.UserFacingError
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskState
import javax.inject.Inject
import java.io.File

//@OpenForTesting
class ProgressFragment : BaseFragment() {

    companion object {
        fun newInstance() = ProgressFragment()
    }

    @Inject
    lateinit var viewModelFactory: ViewModelProvider.Factory

    @Inject
    lateinit var mainActivity: MainActivity

    private lateinit var progressViewModel: ProgressViewModel

    private lateinit var mainViewModel: MainViewModel

    private lateinit var dataBinding: FragmentProgressBinding

    private lateinit var progressAdapter: ProgressAdapter
    private lateinit var fileDownloadAdapter: BrowserFileDownloadAdapter
    private var selectedFilter = R.id.downloads_all

    private val selectedSectionCallback = object : Observable.OnPropertyChangedCallback() {
        override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
            if (::dataBinding.isInitialized) updateDownloadSectionUi()
        }
    }

    private val fileDownloadsCallback = object : Observable.OnPropertyChangedCallback() {
        override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
            if (::dataBinding.isInitialized) updateDownloadSectionUi()
        }
    }

    private val mediaDownloadsCallback = object : Observable.OnPropertyChangedCallback() {
        override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
            if (::dataBinding.isInitialized) updateDownloadSectionUi()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        setHasOptionsMenu(true)
        mainViewModel = mainActivity.mainViewModel
        progressViewModel = mainActivity.progressViewModel
        progressAdapter = ProgressAdapter(emptyList(), progressListener)
        fileDownloadAdapter = BrowserFileDownloadAdapter(emptyList(), fileDownloadListener)

        dataBinding = FragmentProgressBinding.inflate(inflater, container, false).apply {
            val managerL =
                WrapContentLinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
            this.mainViewModel = mainActivity.mainViewModel
            this.viewModel = progressViewModel
            this.rvProgress.layoutManager = managerL
            this.rvProgress.adapter = progressAdapter
            this.rvFileDownloads.layoutManager = WrapContentLinearLayoutManager(
                context,
                LinearLayoutManager.VERTICAL,
                false
            )
            this.rvFileDownloads.adapter = fileDownloadAdapter
            this.downloadSectionToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
                if (!isChecked) return@addOnButtonCheckedListener
                if (checkedId == R.id.button_file_downloads) {
                    progressViewModel.showFileDownloads()
                } else {
                    progressViewModel.showMediaDownloads()
                }
            }
            // Empty-state CTA: jump back to the browser tab
            this.emptyActionButton.setOnClickListener {
                mainActivity.mainViewModel.currentItem.set(0)
            }
        }

        selectedFilter = savedInstanceState?.getInt("download_filter") ?: selectedFilter
        dataBinding.downloadFilters.check(selectedFilter)
        dataBinding.downloadFilters.setOnCheckedStateChangeListener { _, ids ->
            selectedFilter = ids.firstOrNull() ?: R.id.downloads_all
            updateDownloadSectionUi()
        }
        return dataBinding.root
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("download_filter", selectedFilter)
        super.onSaveInstanceState(outState)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        handleDownloadVideoEvent()
        handleTaskDetailsEvent()
        handleBrowserFileEvents()
        progressViewModel.selectedDownloadSection.addOnPropertyChangedCallback(selectedSectionCallback)
        progressViewModel.browserFileDownloads.addOnPropertyChangedCallback(fileDownloadsCallback)
        progressViewModel.progressInfos.addOnPropertyChangedCallback(mediaDownloadsCallback)
        updateDownloadSectionUi()
        progressViewModel.refreshBrowserFileDownloads()
    }

    override fun onDestroyView() {
        progressViewModel.selectedDownloadSection.removeOnPropertyChangedCallback(selectedSectionCallback)
        progressViewModel.browserFileDownloads.removeOnPropertyChangedCallback(fileDownloadsCallback)
        progressViewModel.progressInfos.removeOnPropertyChangedCallback(mediaDownloadsCallback)
        dataBinding.rvProgress.adapter = null
        dataBinding.rvFileDownloads.adapter = null
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        progressViewModel.setDownloadScreenVisible(true)
    }

    override fun onPause() {
        progressViewModel.setDownloadScreenVisible(false)
        super.onPause()
    }

    private fun handleDownloadVideoEvent() {
        mainViewModel.downloadVideoEvent.observe(viewLifecycleOwner) { videoInfo ->
            val currentOriginal = videoInfo.originalUrl
            mainViewModel.currentOriginal.set(currentOriginal)
            progressViewModel.downloadVideo(videoInfo)
        }
    }

    private val progressListener = object : ProgressListener {
        override fun onPrimaryAction(downloadId: Long) {
            val item = progressViewModel.progressInfos.get().orEmpty().find { it.downloadId == downloadId } ?: return
            when {
                item.isActive -> progressViewModel.pauseDownload(downloadId)
                item.downloadStatus == VideoTaskState.PENDING -> progressViewModel.markDownloadLater(downloadId)
                item.downloadStatus in listOf(VideoTaskState.PAUSE, VideoTaskState.ERROR, VideoTaskState.ENOSPC) -> progressViewModel.resumeDownload(downloadId)
                else -> progressViewModel.openTaskDetails(downloadId)
            }
        }

        override fun onMenuClicked(view: View, downloadId: Long, isRegular: Boolean) {
            showPopupMenu(view, downloadId)
        }
    }

    private val fileDownloadListener = object : BrowserFileDownloadListener {
        override fun onFileOpenClicked(download: BrowserFileDownload) {
            progressViewModel.openBrowserFile(download, share = false)
        }

        override fun onFileMenuClicked(anchor: View, download: BrowserFileDownload) {
            showBrowserFileMenu(anchor, download)
        }
    }

    private fun updateDownloadSectionUi() {
        val showFiles = progressViewModel.selectedDownloadSection.get() == DownloadSection.FILES
        val fileDownloads = progressViewModel.browserFileDownloads.get().orEmpty()
        val mediaDownloads = progressViewModel.progressInfos.get().orEmpty()
        dataBinding.downloadSectionToggle.check(
            if (showFiles) R.id.button_file_downloads else R.id.button_media_downloads
        )
        dataBinding.rvFileDownloads.visibility = if (showFiles) View.VISIBLE else View.GONE
        dataBinding.rvProgress.visibility = if (showFiles) View.GONE else View.VISIBLE
        val visibleMedia = mediaDownloads.filter { item ->
            when (selectedFilter) {
                R.id.downloads_active -> item.isActive || item.downloadStatus in listOf(
                    VideoTaskState.PENDING, VideoTaskState.PAUSING,
                    VideoTaskState.CANCELING, VideoTaskState.FINALIZING
                )
                R.id.downloads_paused -> item.downloadStatus == VideoTaskState.PAUSE
                R.id.downloads_failed -> item.downloadStatus == VideoTaskState.ERROR || item.downloadStatus == VideoTaskState.ENOSPC
                else -> true
            }
        }
        val visibleFiles = fileDownloads.filter { item ->
            when (selectedFilter) {
                R.id.downloads_active -> item.status == BrowserFileDownloadStatus.PENDING || item.status == BrowserFileDownloadStatus.RUNNING
                R.id.downloads_paused -> item.status == BrowserFileDownloadStatus.PAUSED
                R.id.downloads_failed -> item.status == BrowserFileDownloadStatus.FAILED || item.status == BrowserFileDownloadStatus.MISSING
                else -> true
            }
        }
        progressAdapter.setData(visibleMedia)
        fileDownloadAdapter.setData(visibleFiles)
        val count = if (showFiles) fileDownloads.size else mediaDownloads.size
        val active = if (showFiles) fileDownloads.count { it.isActive } else mediaDownloads.count {
            it.isActive || it.downloadStatus in listOf(VideoTaskState.PENDING, VideoTaskState.PAUSING, VideoTaskState.CANCELING, VideoTaskState.FINALIZING)
        }
        dataBinding.downloadsSubtitle.text = getString(R.string.surf_download_count, active, count)
        val isEmpty = if (showFiles) visibleFiles.isEmpty() else visibleMedia.isEmpty()
        dataBinding.layoutEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        dataBinding.tvEmptyText.setText(
            if (selectedFilter != R.id.downloads_all) R.string.surf_no_results else if (showFiles) R.string.empty_file_download_title else R.string.empty_progress_title
        )
        dataBinding.emptyActionButton.setText(if (selectedFilter != R.id.downloads_all) R.string.surf_clear_filters else R.string.empty_action_browse)
        dataBinding.emptyActionButton.setOnClickListener {
            if (selectedFilter != R.id.downloads_all) dataBinding.downloadFilters.check(R.id.downloads_all)
            else mainActivity.mainViewModel.currentItem.set(0)
        }
        dataBinding.tvEmptySubtitle.setText(
            if (selectedFilter != R.id.downloads_all) R.string.surf_no_results_hint else if (showFiles) R.string.empty_file_download_subtitle else R.string.empty_progress_subtitle
        )
    }

    private fun showBrowserFileMenu(anchor: View, download: BrowserFileDownload) {
        val popup = PopupMenu(anchor.context, anchor)
        popup.menuInflater.inflate(R.menu.menu_browser_file_download, popup.menu)
        popup.menu.findItem(R.id.item_file_open).isVisible = download.canOpen
        popup.menu.findItem(R.id.item_file_share).isVisible = download.canOpen
        popup.menu.findItem(R.id.item_file_retry).isVisible = download.canRetry
        popup.menu.findItem(R.id.item_file_cancel).isVisible = download.isActive
        popup.menu.findItem(R.id.item_file_delete).isVisible = !download.isActive
        popup.setForceShowIcon(true)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.item_file_open -> {
                    progressViewModel.openBrowserFile(download, share = false)
                    true
                }
                R.id.item_file_share -> {
                    progressViewModel.openBrowserFile(download, share = true)
                    true
                }
                R.id.item_file_location -> {
                    openBrowserDownloadFolder()
                    true
                }
                R.id.item_file_retry -> {
                    progressViewModel.retryBrowserFile(download)
                    true
                }
                R.id.item_file_cancel -> {
                    progressViewModel.cancelBrowserFile(download)
                    true
                }
                R.id.item_file_delete -> {
                    confirmBrowserFileDelete(download)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun handleBrowserFileEvents() {
        progressViewModel.browserFileLaunchEvent.observe(viewLifecycleOwner) { request ->
            launchBrowserFile(request)
        }
        progressViewModel.browserFileMessageEvent.observe(viewLifecycleOwner) { messageRes ->
            Snackbar.make(dataBinding.root, messageRes, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun launchBrowserFile(request: BrowserFileLaunchRequest) {
        val intent = if (request.share) {
            Intent(Intent.ACTION_SEND).apply {
                type = request.mimeType
                clipData = ClipData.newRawUri("browser_download", request.uri)
                putExtra(Intent.EXTRA_STREAM, request.uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(request.uri, request.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        try {
            startActivity(
                if (request.share) Intent.createChooser(intent, getString(R.string.browser_file_share))
                else intent
            )
        } catch (_: ActivityNotFoundException) {
            Snackbar.make(dataBinding.root, R.string.browser_file_no_handler, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun openBrowserDownloadFolder() {
        val documentId = "primary:${Environment.DIRECTORY_DOWNLOADS}/SurfSave/Files"
        val folderUri = DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents",
            documentId
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(folderUri, DocumentsContract.Document.MIME_TYPE_DIR)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val opened = runCatching { startActivity(intent) }.isSuccess ||
            runCatching { startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) }.isSuccess
        if (!opened) {
            Snackbar.make(dataBinding.root, R.string.browser_file_folder_unavailable, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun confirmBrowserFileDelete(download: BrowserFileDownload) {
        val hasLocalFile = download.status == BrowserFileDownloadStatus.SUCCESSFUL
        val builder = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.browser_file_delete_title)
            .setMessage(download.fileName)
            .setNegativeButton(R.string.all_text_cancel, null)
            .setPositiveButton(
                if (hasLocalFile) R.string.browser_file_delete_file_and_record
                else R.string.browser_file_delete_record
            ) { _, _ ->
                progressViewModel.deleteBrowserFile(download, deleteLocalFile = hasLocalFile)
            }
        if (hasLocalFile) {
            builder.setNeutralButton(R.string.browser_file_delete_record) { _, _ ->
                progressViewModel.deleteBrowserFile(download, deleteLocalFile = false)
            }
        }
        builder.show()
    }

    private fun showPopupMenu(view: View, downloadId: Long) {
        val myView = fixPopup(dataBinding.anchor, view)

        val menuCandidate =
            progressViewModel.progressInfos.get()?.find { it.downloadId == downloadId }

        val popupMenu = PopupMenu(myView.context, myView)
        popupMenu.menuInflater.inflate(R.menu.menu_progress, popupMenu.menu)

        val isActive = menuCandidate?.isActive == true
        val isPaused = menuCandidate?.downloadStatus == VideoTaskState.PAUSE
        val canRetry = menuCandidate?.downloadStatus == VideoTaskState.ERROR ||
            menuCandidate?.downloadStatus == VideoTaskState.ENOSPC
        val canMove = menuCandidate?.canMoveInQueue == true
        popupMenu.menu.findItem(R.id.item_pause).isVisible = isActive
        popupMenu.menu.findItem(R.id.item_resume).isVisible = isPaused || canRetry
        popupMenu.menu.findItem(R.id.item_resume).setTitle(
            if (canRetry) R.string.progress_menu_retry else R.string.progress_menu_resume
        )
        popupMenu.menu.findItem(R.id.item_stop_save).isVisible = menuCandidate?.isLive == true && isActive
        popupMenu.menu.findItem(R.id.item_move_top).isVisible = canMove
        popupMenu.menu.findItem(R.id.item_move_up).isVisible = canMove
        popupMenu.menu.findItem(R.id.item_move_down).isVisible = canMove
        popupMenu.menu.findItem(R.id.item_later).isVisible =
            menuCandidate?.downloadStatus == VideoTaskState.PENDING || isActive

        popupMenu.setForceShowIcon(true)
        popupMenu.show()

        popupMenu.setOnMenuItemClickListener { arg0 ->
            when (arg0.itemId) {
                R.id.item_details -> {
                    progressViewModel.openTaskDetails(downloadId)
                    true
                }

                R.id.item_cancel -> {
                    progressViewModel.cancelDownload(downloadId, true)
                    true
                }

                R.id.item_pause -> {
                    progressViewModel.pauseDownload(downloadId)
                    true
                }

                R.id.item_resume -> {
                    progressViewModel.resumeDownload(downloadId)
                    true
                }

                R.id.item_stop_save -> {
                    progressViewModel.stopAndSaveDownload(downloadId)
                    true
                }

                R.id.item_move_top -> {
                    progressViewModel.moveDownloadToTop(downloadId)
                    true
                }

                R.id.item_move_up -> {
                    progressViewModel.moveDownloadUp(downloadId)
                    true
                }

                R.id.item_move_down -> {
                    progressViewModel.moveDownloadDown(downloadId)
                    true
                }

                R.id.item_later -> {
                    progressViewModel.markDownloadLater(downloadId)
                    true
                }

                else -> false
            }
        }
    }

    private fun handleTaskDetailsEvent() {
        progressViewModel.downloadTaskDetailsEvent.observe(viewLifecycleOwner) { details ->
            showTaskDetailsDialog(details)
        }
    }

    private fun showTaskDetailsDialog(details: DownloadTaskDetails) {
        val logText = details.logText.ifBlank { getString(R.string.download_log_empty) }
        val message = buildString {
            appendLine(getString(R.string.download_detail_status, details.status))
            if (details.error.isNotBlank()) {
                appendLine()
                appendLine(getString(R.string.download_detail_reason))
                appendLine(UserFacingError.compactMessage(requireContext(), details.error))
                appendLine()
                appendLine(getString(R.string.download_detail_error))
                appendLine(UserFacingError.cleanDetail(details.error))
            }
            appendLine()
            appendLine(getString(R.string.download_detail_log_tail))
            append(logText)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(details.title)
            .setMessage(message)
            .setPositiveButton(R.string.ok, null)
            .setNeutralButton(R.string.browser_diagnostics_copy) { _, _ ->
                copyDetailsToClipboard(message)
            }
            .setNegativeButton(R.string.browser_diagnostics_share) { _, _ ->
                shareTaskLog(details, message)
            }
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.menu_progress_toolbar, menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.item_latest_error_log -> {
                showLatestErrorLog()
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    /** 展示最近一次下载发布/移动失败的系统级诊断日志（覆盖普通任务日志，定位 MediaStore 发布类问题）。 */
    private fun showLatestErrorLog() {
        val log = ErrorLogRecorder.readLatest()
        val text = log?.takeIf { it.isNotBlank() }
            ?: getString(R.string.latest_error_log_empty)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.latest_error_log_title)
            .setMessage(text)
            .setPositiveButton(R.string.browser_diagnostics_copy) { _, _ ->
                copyDetailsToClipboard(text)
            }
            .setNegativeButton(R.string.browser_diagnostics_share) { _, _ ->
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                startActivity(Intent.createChooser(intent, getString(R.string.browser_diagnostics_share)))
            }
            .show()
    }

    private fun copyDetailsToClipboard(text: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.progress_menu_details), text))
    }

    private fun shareTaskLog(details: DownloadTaskDetails, fallbackText: String) {
        val logFile = File(details.logPath)
        val intent = Intent(Intent.ACTION_SEND)
        if (logFile.exists()) {
            val uri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.provider",
                logFile
            )
            intent.type = "text/plain"
            intent.putExtra(Intent.EXTRA_STREAM, uri)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            intent.type = "text/plain"
            intent.putExtra(Intent.EXTRA_TEXT, fallbackText)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.browser_diagnostics_share)))
    }
}

class WrapContentLinearLayoutManager : LinearLayoutManager {
    constructor(context: Context?) : super(context) {}
    constructor(context: Context?, orientation: Int, reverseLayout: Boolean) : super(
        context, orientation, reverseLayout
    ) {
    }

    constructor(
        context: Context?, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int
    ) : super(context, attrs, defStyleAttr, defStyleRes) {
    }

    override fun onLayoutChildren(recycler: Recycler, state: RecyclerView.State) {
        try {
            super.onLayoutChildren(recycler, state)
        } catch (e: IndexOutOfBoundsException) {
            AppLogger.e("meet a IOOBE in RecyclerView")
        }
    }
}
