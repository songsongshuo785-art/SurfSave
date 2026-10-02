package com.myAllVideoBrowser.ui.main.home.browser.detectedVideos

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetBehavior
import androidx.activity.addCallback
import androidx.fragment.app.FragmentManager
import androidx.databinding.Observable
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.databinding.FragmentDetectedVideosTabBinding
import com.myAllVideoBrowser.util.PlaylistExtractor
import com.myAllVideoBrowser.ui.component.adapter.DownloadTabListener
import com.myAllVideoBrowser.ui.component.adapter.ImageInfoAdapter
import com.myAllVideoBrowser.ui.component.adapter.VideoInfoAdapter
import com.myAllVideoBrowser.ui.main.base.BaseFragment
import com.myAllVideoBrowser.ui.main.home.MainActivity
import com.myAllVideoBrowser.ui.main.progress.WrapContentLinearLayoutManager
import com.myAllVideoBrowser.util.AppUtil
import com.myAllVideoBrowser.util.telegram.TelegramPostUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI
import javax.inject.Inject

class DetectedVideosTabFragment : BaseFragment(), DetectedMediaPanelRegistry.Panel {
    var detectedVideosTabViewModel: VideoDetectionTabViewModel? = null
    var candidateFormatListener: DownloadTabListener? = null

    @Inject
    lateinit var mainActivity: MainActivity

    @Inject
    lateinit var appUtil: AppUtil

    private lateinit var binding: FragmentDetectedVideosTabBinding

    /**
     * Root shown while the runtime-only dependencies are still missing after a
     * process death. It is replaced with the real content once the host
     * registers through [DetectedMediaPanelRegistry].
     */
    private var placeholderRoot: FrameLayout? = null

    private lateinit var imageAdapter: ImageInfoAdapter
    private var imageTabVisible = false

    private val imageSelectionCallback = object : Observable.OnPropertyChangedCallback() {
        override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
            if (::binding.isInitialized) {
                binding.root.post {
                    imageAdapter.notifyDataSetChanged()
                    updateImageSelectionControls()
                }
            }
        }
    }

    private val imageContentCallback = object : Observable.OnPropertyChangedCallback() {
        override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
            if (::binding.isInitialized && imageTabVisible) {
                binding.root.post { updateImageContentVisibility() }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (tabIndex() >= 0) {
            DetectedMediaPanelRegistry.registerPanel(tabIndex(), this)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val model = detectedVideosTabViewModel
        val listener = candidateFormatListener
        if (model == null || listener == null) {
            // FragmentManager can recreate this fragment after the process has
            // been killed, before WebTabFragment has restored its runtime-only
            // callback fields. Show a harmless placeholder and let the registry
            // re-bind the panel once the host comes back, instead of crashing.
            val placeholder = FrameLayout(requireContext())
            placeholderRoot = placeholder
            val index = tabIndex()
            if (index >= 0) {
                DetectedMediaPanelRegistry.registerPanel(index, this)
                DetectedMediaPanelRegistry.resolveHost(index)?.let { host ->
                    applyRuntimeDependencies(
                        host.detectedMediaTabViewModel,
                        host.detectedMediaTabListener
                    )
                }
            }
            return placeholder
        }

        return createContentView(container, model, listener)
    }

    private fun createContentView(
        container: ViewGroup?,
        model: VideoDetectionTabViewModel,
        listener: DownloadTabListener
    ): View {
        binding = FragmentDetectedVideosTabBinding.inflate(layoutInflater, container, false)
        wireBinding(model, listener)
        return binding.root
    }

    /**
     * Binds the runtime-only dependencies. Called by the registry when the host
     * arrives after this fragment was already restored without them.
     */
    override val panelAttached: Boolean
        get() = isAdded

    override fun applyRuntimeDependencies(
        model: VideoDetectionTabViewModel?,
        listener: DownloadTabListener?
    ) {
        if (model == null || listener == null) return

        detectedVideosTabViewModel = model
        candidateFormatListener = listener

        val placeholder = placeholderRoot ?: return
        if (::binding.isInitialized) return

        // Defer inflation until the placeholder is attached so viewLifecycleOwner
        // is available for the back-press callback.
        placeholder.post {
            if (!isAdded || view == null || ::binding.isInitialized) return@post
            placeholderRoot = null
            binding = FragmentDetectedVideosTabBinding.inflate(layoutInflater, placeholder, true)
            wireBinding(model, listener)
        }
    }

    private fun wireBinding(model: VideoDetectionTabViewModel, listener: DownloadTabListener) {
        val pageUrl = model.webTabModel?.getTabTextInput()?.get().orEmpty()
        val videoAdapter = VideoInfoAdapter(
            model.sortedDetectedVideosList?.get() ?: emptyList(),
            model,
            listener,
            appUtil,
        )
        imageAdapter = ImageInfoAdapter(
            model.sortedDetectedImagesList?.get() ?: emptyList(),
            model,
            listener
        )
        val layoutMngr = WrapContentLinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
        binding.apply {
            // Title stays static; the source host moves to the subtitle row.
            val host = sourceLabel(pageUrl)
            if (host.isNotBlank()) {
                detectedSubtitle.text = getString(R.string.detected_videos_from_host, host)
                detectedSubtitle.visibility = View.VISIBLE
            }
            viewModel = model
            videoInfoList.layoutManager = layoutMngr
            videoInfoList.isNestedScrollingEnabled = true
            videoInfoList.adapter = videoAdapter
            imageInfoList.layoutManager = GridLayoutManager(requireContext(), 2)
            imageInfoList.adapter = imageAdapter
            dialogListener = listener
            detectedBackdrop.setOnClickListener { closeDetectedVideos() }
            detectedSheet.setOnClickListener { /* Keep sheet taps from closing the overlay. */ }
            tvCancel.setOnClickListener { closeDetectedVideos() }
            buttonPlayInWebpage.setOnClickListener { closeDetectedVideos() }
            buttonOpenTelegramPost.setOnClickListener { openTelegramPost() }
            buttonParsePlaylist.setOnClickListener { parsePlaylistFromCurrentPage() }
            buttonVideoTab.setOnClickListener { showVideoTab() }
            buttonImageTab.setOnClickListener { showImageTab() }
            buttonScanImages.setOnClickListener {
                detectedVideosTabViewModel?.requestImageScan()
                showImageTab()
            }
            buttonClearImageSelection.setOnClickListener {
                detectedVideosTabViewModel?.clearImageSelection()
            }
            buttonSelectAllImages.setOnClickListener {
                detectedVideosTabViewModel?.selectAllImages()
            }
            buttonDownloadSelectedImages.setOnClickListener {
                val selected = detectedVideosTabViewModel?.selectedImages().orEmpty()
                if (selected.isNotEmpty()) {
                    mainActivity.progressViewModel.downloadMediaItems(selected)
                    detectedVideosTabViewModel?.clearImageSelection()
                }
            }
            buttonVideoTab.isChecked = true
            detectedSecondaryActions.visibility =
                if (shouldShowPlaylistAction(pageUrl)) View.VISIBLE else View.GONE
            updateImageSelectionControls()
        }
        model.selectedImageIds.addOnPropertyChangedCallback(imageSelectionCallback)
        model.hasDetectedImages.addOnPropertyChangedCallback(imageContentCallback)

        BottomSheetBehavior.from(binding.detectedSheet).apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
                override fun onStateChanged(bottomSheet: View, newState: Int) {
                    if (newState == BottomSheetBehavior.STATE_HIDDEN) closeDetectedVideos()
                }

                override fun onSlide(bottomSheet: View, slideOffset: Float) {
                    binding.detectedBackdrop.alpha = (1f + slideOffset).coerceIn(0f, 1f)
                }
            })
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            closeDetectedVideos()
        }
    }

    override fun onResume() {
        super.onResume()
        if (detectedVideosTabViewModel == null || candidateFormatListener == null) {
            // The host should re-bind shortly after restoration. If no tab comes
            // back (for example the tab was closed), drop the orphaned overlay.
            view?.postDelayed({
                if (isAdded &&
                    (detectedVideosTabViewModel == null || candidateFormatListener == null)
                ) {
                    closeDetectedVideos()
                }
            }, RESTORE_BIND_TIMEOUT_MS)
        }
    }

    override fun onDestroy() {
        val index = tabIndex()
        if (index >= 0) {
            DetectedMediaPanelRegistry.unregisterPanel(index, this)
        }
        super.onDestroy()
    }

    private fun showVideoTab() {
        if (!::binding.isInitialized) return
        imageTabVisible = false
        binding.buttonVideoTab.isChecked = true
        binding.videoInfoList.visibility = View.VISIBLE
        binding.imageInfoList.visibility = View.GONE
        binding.imageEmptyState.visibility = View.GONE
        updateImageSelectionControls()
    }

    private fun showImageTab() {
        if (!::binding.isInitialized) return
        imageTabVisible = true
        binding.buttonImageTab.isChecked = true
        binding.videoInfoList.visibility = View.GONE
        updateImageContentVisibility()
        updateImageSelectionControls()
    }

    private fun updateImageContentVisibility() {
        if (!::binding.isInitialized) return
        val hasImages = detectedVideosTabViewModel?.hasDetectedImages?.get() == true
        binding.imageInfoList.visibility = if (hasImages) View.VISIBLE else View.GONE
        binding.imageEmptyState.visibility = if (hasImages) View.GONE else View.VISIBLE
    }

    private fun updateImageSelectionControls() {
        if (!::binding.isInitialized) return
        val model = detectedVideosTabViewModel ?: return
        binding.imageSelectionBar.visibility = if (model.hasSelectedImages.get()) View.VISIBLE else View.GONE
        binding.imageSelectionCount.text = getString(
            R.string.detected_images_selected,
            model.selectedImagesCount.get()
        )
        binding.buttonDownloadSelectedImages.isEnabled = model.hasSelectedImages.get()
        binding.buttonClearImageSelection.isEnabled = model.hasSelectedImages.get()
    }

    override fun onDestroyView() {
        detectedVideosTabViewModel?.selectedImageIds?.removeOnPropertyChangedCallback(imageSelectionCallback)
        detectedVideosTabViewModel?.hasDetectedImages?.removeOnPropertyChangedCallback(imageContentCallback)
        placeholderRoot = null
        super.onDestroyView()
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

    private fun openTelegramPost() {
        val url = detectedVideosTabViewModel?.telegramPostOpenUrl?.get().orEmpty()
        if (!url.startsWith("http", ignoreCase = true)) return

        val post = TelegramPostUrl.parse(url)
        val telegramIntent = post?.let {
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse("tg://resolve?domain=${it.channel}&post=${it.messageId}")
            )
        }
        try {
            if (telegramIntent != null &&
                telegramIntent.resolveActivity(requireContext().packageManager) != null
            ) {
                startActivity(telegramIntent)
            } else {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(requireContext(), R.string.telegram_open_failed, Toast.LENGTH_SHORT)
                .show()
        } catch (_: SecurityException) {
            Toast.makeText(requireContext(), R.string.telegram_open_failed, Toast.LENGTH_SHORT)
                .show()
        }
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

    private fun tabIndex(): Int =
        arguments?.getInt(ARG_TAB_INDEX, INVALID_TAB_INDEX) ?: INVALID_TAB_INDEX

    companion object {
        const val DOWNLOADS_TAB_TAG = "DOWNLOADS_TAB"
        private const val ARG_TAB_INDEX = "detected_media_tab_index"
        private const val INVALID_TAB_INDEX = -1
        private const val RESTORE_BIND_TIMEOUT_MS = 1000L

        internal fun runtimeDependenciesAvailable(
            model: VideoDetectionTabViewModel?,
            listener: DownloadTabListener?
        ): Boolean = model != null && listener != null

        fun newInstance(tabIndex: Int) = DetectedVideosTabFragment().apply {
            arguments = Bundle().apply { putInt(ARG_TAB_INDEX, tabIndex) }
        }

        /** Restoration path: FragmentManager re-applies the saved arguments. */
        internal fun createForRestore() = DetectedVideosTabFragment()
    }
}
