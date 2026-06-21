package com.myAllVideoBrowser.ui.main.progress

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.FileProvider
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.Recycler
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.databinding.FragmentProgressBinding
import com.myAllVideoBrowser.ui.main.progress.DownloadTaskDetails
import com.myAllVideoBrowser.ui.component.adapter.ProgressAdapter
import com.myAllVideoBrowser.ui.component.adapter.ProgressListener
import com.myAllVideoBrowser.ui.main.base.BaseFragment
import com.myAllVideoBrowser.ui.main.home.MainActivity
import com.myAllVideoBrowser.ui.main.home.MainViewModel
import com.myAllVideoBrowser.util.AppLogger
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

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        mainViewModel = mainActivity.mainViewModel
        progressViewModel = mainActivity.progressViewModel
        progressAdapter = ProgressAdapter(emptyList(), progressListener)

        val isDark = mainActivity.settingsViewModel.isDarkMode.get()
        val color = if (isDark) {
            MaterialColors.getColor(requireContext(), R.attr.editTextColor, Color.YELLOW)
        } else {
            null
        }

        dataBinding = FragmentProgressBinding.inflate(inflater, container, false).apply {
            val managerL =
                WrapContentLinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
            this.mainViewModel = mainActivity.mainViewModel
            this.viewModel = progressViewModel
            this.rvProgress.layoutManager = managerL
            this.rvProgress.adapter = progressAdapter
            if (color != null) {
                this.ivEmptyIcon.setBackgroundColor(color)
            }
        }

        return dataBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        handleDownloadVideoEvent()
        handleTaskDetailsEvent()
    }

    private fun handleDownloadVideoEvent() {
        mainViewModel.downloadVideoEvent.observe(viewLifecycleOwner) { videoInfo ->
            val currentOriginal = videoInfo.originalUrl
            mainViewModel.currentOriginal.set(currentOriginal)
            progressViewModel.downloadVideo(videoInfo)
        }
    }

    private val progressListener = object : ProgressListener {
        override fun onMenuClicked(view: View, downloadId: Long, isRegular: Boolean) {
            showPopupMenu(view, downloadId)
        }
    }

    private fun showPopupMenu(view: View, downloadId: Long) {
        val myView = fixPopup(dataBinding.anchor, view)

        val menuCandidate =
            progressViewModel.progressInfos.get()?.find { it.downloadId == downloadId }

        val popupMenu = PopupMenu(myView.context, myView)
        popupMenu.menuInflater.inflate(R.menu.menu_progress, popupMenu.menu)

        val isActive = menuCandidate?.isActive == true
        val isPaused = menuCandidate?.downloadStatus == VideoTaskState.PAUSE
        val canMove = menuCandidate?.canMoveInQueue == true
        popupMenu.menu.findItem(R.id.item_pause).isVisible = isActive
        popupMenu.menu.findItem(R.id.item_resume).isVisible = isPaused
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
