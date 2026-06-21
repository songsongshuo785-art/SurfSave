package com.myAllVideoBrowser.ui.main.home.browser.detectedVideos

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.addCallback
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.databinding.FragmentDetectedVideosTabBinding
import com.myAllVideoBrowser.util.PlaylistExtractor
import com.myAllVideoBrowser.ui.component.adapter.DownloadTabListener
import com.myAllVideoBrowser.ui.component.adapter.VideoInfoAdapter
import com.myAllVideoBrowser.ui.main.base.BaseFragment
import com.myAllVideoBrowser.ui.main.home.MainActivity
import com.myAllVideoBrowser.ui.main.progress.WrapContentLinearLayoutManager
import com.myAllVideoBrowser.util.AppUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI
import javax.inject.Inject

class DetectedVideosTabFragment : BaseFragment() {
    var detectedVideosTabViewModel: VideoDetectionTabViewModel? = null
    var candidateFormatListener: DownloadTabListener? = null

    @Inject
    lateinit var mainActivity: MainActivity

    @Inject
    lateinit var appUtil: AppUtil

    private lateinit var binding: FragmentDetectedVideosTabBinding

    private lateinit var layoutMngr: WrapContentLinearLayoutManager

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        if (detectedVideosTabViewModel == null || candidateFormatListener == null) {
            Toast.makeText(context, R.string.detected_videos_unavailable, Toast.LENGTH_SHORT).show()
            parentFragmentManager.popBackStack()
        }

        val adapter = detectedVideosTabViewModel?.let {
            candidateFormatListener?.let { it1 ->
                VideoInfoAdapter(
                    detectedVideosTabViewModel?.detectedVideosList?.get()?.toList() ?: emptyList(),
                    it,
                    it1,
                    appUtil,
                )
            }
        }

        layoutMngr = WrapContentLinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)

        val pageUrl = detectedVideosTabViewModel?.webTabModel?.getTabTextInput()?.get().orEmpty()
        binding = FragmentDetectedVideosTabBinding.inflate(inflater, container, false).apply {
            title.text = getString(
                R.string.found_videos_from,
                sourceLabel(pageUrl)
            )
            detectedVideosTabContainer.setBackgroundColor(getThemeBackgroundColor())
            viewModel = detectedVideosTabViewModel
            videoInfoList.layoutManager = layoutMngr
            videoInfoList.isNestedScrollingEnabled = true
            videoInfoList.adapter = adapter
            dialogListener = candidateFormatListener
            detectedBackdrop.setOnClickListener { closeDetectedVideos() }
            detectedSheet.setOnClickListener { /* Keep sheet taps from closing the overlay. */ }
            tvCancel.setOnClickListener { closeDetectedVideos() }
            buttonParsePlaylist.setOnClickListener { parsePlaylistFromCurrentPage() }
            detectedSecondaryActions.visibility =
                if (shouldShowPlaylistAction(pageUrl)) View.VISIBLE else View.GONE
        }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            closeDetectedVideos()
        }

        return binding.root
    }

    private fun parsePlaylistFromCurrentPage() {
        val pageUrl = detectedVideosTabViewModel?.webTabModel?.getTabTextInput()?.get()?.trim().orEmpty()
        if (!pageUrl.startsWith("http")) {
            detectedVideosTabViewModel?.showDetectionNotice(R.string.playlist_no_page_url)
            return
        }

        binding.buttonParsePlaylist.isEnabled = false
        binding.buttonParsePlaylist.setText(R.string.playlist_parsing)
        detectedVideosTabViewModel?.showDetectionNotice(R.string.playlist_parsing)
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { mainActivity.playlistExtractor.extract(pageUrl) }
            }
            binding.buttonParsePlaylist.isEnabled = true
            binding.buttonParsePlaylist.setText(R.string.playlist_parse_button)

            result.onSuccess { playlist ->
                detectedVideosTabViewModel?.clearDetectionStatus()
                showPlaylistSelectionDialog(playlist)
            }.onFailure { error ->
                detectedVideosTabViewModel?.showDetectionError(error)
            }
        }
    }

    private fun showPlaylistSelectionDialog(result: PlaylistExtractor.Result) {
        val items = result.items
        val checked = BooleanArray(items.size) { true }
        val labels = items.map { item ->
            getString(R.string.playlist_item_label, item.playlistIndex, item.videoInfo.title)
        }.toTypedArray()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.playlist_dialog_title, result.title))
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(R.string.playlist_enqueue_selected) { _, _ ->
                val selected = items.filterIndexed { index, _ -> checked[index] }
                if (selected.isEmpty()) {
                    detectedVideosTabViewModel?.showDetectionNotice(R.string.playlist_no_selection)
                } else {
                    mainActivity.progressViewModel.downloadPlaylistItems(selected)
                    closeDetectedVideos()
                }
            }
            .setNegativeButton(R.string.all_text_cancel, null)
            .show()
    }

    private fun closeDetectedVideos() {
        val fragmentManager = mainActivity.supportFragmentManager
        if (fragmentManager.findFragmentByTag(DOWNLOADS_TAB_TAG) != null) {
            fragmentManager.popBackStack(
                DOWNLOADS_TAB_TAG,
                FragmentManager.POP_BACK_STACK_INCLUSIVE
            )
            return
        }

        parentFragmentManager.popBackStack()
    }

    private fun sourceLabel(url: String): String {
        val host = runCatching {
            URI(url).host?.removePrefix("www.").orEmpty()
        }.getOrDefault("")

        return host.ifBlank {
            url.substringBefore("?")
                .ifBlank { getString(R.string.title_browser) }
                .take(80)
        }
    }

    private fun shouldShowPlaylistAction(url: String): Boolean {
        val hasDetectedVideo = detectedVideosTabViewModel?.detectedVideosList?.get()?.isNotEmpty() == true
        if (hasDetectedVideo) {
            return false
        }

        val lower = url.lowercase()
        if (!lower.startsWith("http")) {
            return false
        }

        return lower.contains("/playlist") ||
            lower.contains("list=") ||
            lower.contains("/channel/") ||
            Regex("""https?://([^/]+\.)?youtube\.com/@[^/?#]+""").containsMatchIn(lower)
    }

    companion object {
        const val DOWNLOADS_TAB_TAG = "DOWNLOADS_TAB"

        fun newInstance() = DetectedVideosTabFragment()
    }
}
