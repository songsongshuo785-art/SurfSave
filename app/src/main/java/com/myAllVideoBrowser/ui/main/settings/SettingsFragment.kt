package com.myAllVideoBrowser.ui.main.settings

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.databinding.Observable
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.FragmentTransaction
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.databinding.FragmentSettingsBinding
import com.myAllVideoBrowser.ui.main.base.BaseFragment
import com.myAllVideoBrowser.ui.main.home.MainActivity
import com.myAllVideoBrowser.ui.main.migration.MigrationCenterFragment
import com.myAllVideoBrowser.ui.main.proxies.ProxiesFragment
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.FileUtil
import com.myAllVideoBrowser.util.IntentUtil
import com.myAllVideoBrowser.util.SharedPrefHelper
import com.myAllVideoBrowser.util.SystemUtil
import java.util.Locale
import javax.inject.Inject

class SettingsFragment : BaseFragment() {

    companion object {
        fun newInstance() = SettingsFragment()
    }

    @Inject
    lateinit var fileUtil: FileUtil

    @Inject
    lateinit var intentUtil: IntentUtil

    @Inject
    lateinit var systemUtil: SystemUtil

    @Inject
    lateinit var mainActivity: MainActivity

    @Inject
    lateinit var viewModelFactory: ViewModelProvider.Factory

    private lateinit var dataBinding: FragmentSettingsBinding
    private lateinit var settingsViewModel: SettingsViewModel

    private var lastSavedRegularThreadsCount = -1
    private var detectionAdvancedExpanded = false
    private var downloadingAdvancedExpanded = false
    private var settingsSearchQuery = ""
    private var pendingCookieExportContent: String = ""

    private val pickCookieProfileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            return@registerForActivityResult
        }
        importCookieProfile(uri)
    }

    private val exportCookieProfileLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri == null) {
            return@registerForActivityResult
        }
        exportCookieProfile(uri)
    }

    private val thresholdCallback = object : Observable.OnPropertyChangedCallback() {
        @SuppressLint("SetTextI18n")
        override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
            if (!isAdded) {
                return
            }
            val currentThreshold = settingsViewModel.videoDetectionThreshold.get()
            val readableSize = FileUtil.getFileSizeReadable(currentThreshold.toDouble())
            dataBinding.videoDetectionThresholdText.text =
                getString(R.string.video_detection_threshold) + " $readableSize"
        }
    }

    private val storageTypeCallback = object : Observable.OnPropertyChangedCallback() {
        override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
            if (!isAdded) return
            val newCheckId = when (settingsViewModel.storageType.get()) {
                StorageType.SD -> R.id.option_sd_card
                StorageType.HIDDEN -> R.id.option_hidden_folder
                StorageType.HIDDEN_SD -> R.id.option_sd_app_folder
                else -> -1
            }
            if (newCheckId != -1 && dataBinding.storageOptions.checkedRadioButtonId != newCheckId) {
                dataBinding.storageOptions.check(newCheckId)
            }
        }
    }

    private val shortVideoDurationCallback = object : Observable.OnPropertyChangedCallback() {
        override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
            if (!isAdded) {
                return
            }
            val seconds = settingsViewModel.shortVideoFilterDurationSeconds.get()
            dataBinding.shortVideoDurationText.text =
                getString(R.string.short_video_filter_duration, seconds)
        }
    }

    private val searchEngineCallback = object : Observable.OnPropertyChangedCallback() {
        override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
            if (!isAdded) {
                return
            }
            dataBinding.tvSearchEngineSummary.text = getSearchEngineLabel(
                settingsViewModel.searchEngine.get() ?: SharedPrefHelper.SearchEngine.BING
            )
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        settingsViewModel = mainActivity.settingsViewModel
        dataBinding = FragmentSettingsBinding.inflate(inflater, container, false)
        dataBinding.viewModel = settingsViewModel
        dataBinding.lifecycleOwner = viewLifecycleOwner

        return dataBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupSeekBarListeners()
        setupRadioGroupListener()
        setupAdvancedSections()
        setupSettingsSearch()
        setupSecondTierSettings()
        setupTextUpdateCallbacks()
        handleUIEvents()
        dataBinding.layoutSearchEngine.setOnClickListener {
            showSearchEngineDialog()
        }
        dataBinding.layoutProxySettings.setOnClickListener {
            settingsViewModel.openProxySettings()
        }
        dataBinding.layoutMigrationCenter.setOnClickListener {
            settingsViewModel.openMigrationCenter()
        }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            parentFragmentManager.popBackStack()
        }

        settingsViewModel.start()
    }

    override fun onDestroyView() {
        settingsViewModel.stop()
        thresholdCallback.let {
            settingsViewModel.videoDetectionThreshold.removeOnPropertyChangedCallback(
                it
            )
        }
        searchEngineCallback.let {
            settingsViewModel.searchEngine.removeOnPropertyChangedCallback(it)
        }
        storageTypeCallback.let { settingsViewModel.storageType.removeOnPropertyChangedCallback(it) }
        shortVideoDurationCallback.let {
            settingsViewModel.shortVideoFilterDurationSeconds.removeOnPropertyChangedCallback(it)
        }
        super.onDestroyView()
    }

    private fun setupSeekBarListeners() {
        dataBinding.seekBarRegular.setOnSeekBarChangeListener(object :
            SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    settingsViewModel.setRegularThreadsCount(progress)

                    if (lastSavedRegularThreadsCount == 1 && progress > 1) {
                        context?.let { showDownloadWarningDialog(it) }
                    }
                    lastSavedRegularThreadsCount = progress
                }
            }

            override fun onStartTrackingTouch(p0: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar?.let { settingsViewModel.setRegularThreadsCount(it.progress) }
            }
        })

        dataBinding.seekBarM3u8.setOnSeekBarChangeListener(object :
            SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    settingsViewModel.setM3u8ThreadsCount(progress)
                }
            }

            override fun onStartTrackingTouch(p0: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar?.let { settingsViewModel.setM3u8ThreadsCount(it.progress) }
            }
        })

        dataBinding.seekBarMaxConcurrentDownloads.setOnSeekBarChangeListener(object :
            SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    settingsViewModel.setMaxConcurrentDownloads(progress)
                }
            }

            override fun onStartTrackingTouch(p0: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar?.let { settingsViewModel.setMaxConcurrentDownloads(it.progress) }
            }
        })

        dataBinding.seekBarVideoDetectionThreshold.setOnSeekBarChangeListener(object :
            SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    settingsViewModel.setVideoDetectionThreshold(progress)
                }
            }

            override fun onStartTrackingTouch(p0: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar?.let { settingsViewModel.setVideoDetectionThreshold(it.progress) }
            }
        })

        dataBinding.seekBarShortVideoDuration.setOnSeekBarChangeListener(object :
            SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    settingsViewModel.setShortVideoFilterDurationSeconds(progress + 5)
                }
            }

            override fun onStartTrackingTouch(p0: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar?.let { settingsViewModel.setShortVideoFilterDurationSeconds(it.progress + 5) }
            }
        })
    }

    private fun setupRadioGroupListener() {
        storageTypeCallback.let { settingsViewModel.storageType.addOnPropertyChangedCallback(it) }
        storageTypeCallback.onPropertyChanged(null, 0)
    }

    private fun setupAdvancedSections() {
        dataBinding.detectionAdvancedHeader.setOnClickListener {
            detectionAdvancedExpanded = !detectionAdvancedExpanded
            renderAdvancedSections()
        }
        dataBinding.detectionAdvancedToggle.setOnClickListener {
            detectionAdvancedExpanded = !detectionAdvancedExpanded
            renderAdvancedSections()
        }
        dataBinding.downloadingAdvancedHeader.setOnClickListener {
            downloadingAdvancedExpanded = !downloadingAdvancedExpanded
            renderAdvancedSections()
        }
        dataBinding.downloadingAdvancedToggle.setOnClickListener {
            downloadingAdvancedExpanded = !downloadingAdvancedExpanded
            renderAdvancedSections()
        }
        renderAdvancedSections()
    }

    private fun renderAdvancedSections() {
        if (settingsSearchQuery.isNotBlank()) {
            return
        }

        dataBinding.detectionAdvancedContainer.visibility =
            if (detectionAdvancedExpanded) View.VISIBLE else View.GONE
        dataBinding.downloadingAdvancedContainer.visibility =
            if (downloadingAdvancedExpanded) View.VISIBLE else View.GONE
        dataBinding.detectionAdvancedToggle.setText(
            if (detectionAdvancedExpanded) R.string.settings_hide_advanced else R.string.settings_show_advanced
        )
        dataBinding.downloadingAdvancedToggle.setText(
            if (downloadingAdvancedExpanded) R.string.settings_hide_advanced else R.string.settings_show_advanced
        )
    }

    private fun setupSettingsSearch() {
        dataBinding.settingsSearchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                settingsSearchQuery = s?.toString().orEmpty()
                applySettingsSearchFilter()
            }

            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun setupSecondTierSettings() {
        dataBinding.filenameTemplateInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                settingsViewModel.setFilenameTemplate(s?.toString().orEmpty())
            }

            override fun afterTextChanged(s: Editable?) {}
        })
        dataBinding.buttonResetFilenameTemplate.setOnClickListener {
            settingsViewModel.resetFilenameTemplate()
            dataBinding.filenameTemplateInput.setText(com.myAllVideoBrowser.util.DownloadFilenameTemplate.DEFAULT_TEMPLATE)
        }
        dataBinding.buttonUpdateYtdlp.setOnClickListener {
            settingsViewModel.updateYtdlpStable()
        }
        dataBinding.buttonImportCookieProfile.setOnClickListener {
            settingsViewModel.requestCookieImport()
        }
        dataBinding.buttonExportCookieProfile.setOnClickListener {
            settingsViewModel.requestCookieExport()
        }
    }

    private fun applySettingsSearchFilter() {
        val query = settingsSearchQuery.trim().lowercase(Locale.getDefault())
        if (query.isBlank()) {
            showAllSettingsRows()
            dataBinding.settingsNoResults.visibility = View.GONE
            renderAdvancedSections()
            return
        }

        fun matches(vararg values: String): Boolean {
            return values.any { it.lowercase(Locale.getDefault()).contains(query) }
        }

        fun setVisible(view: View, visible: Boolean) {
            view.visibility = if (visible) View.VISIBLE else View.GONE
        }

        val generalSection = matches(
            getString(R.string.settings_general),
            getString(R.string.settings_general_subtitle)
        )
        val clearCookies = generalSection || matches(
            getString(R.string.settings_clear_browser_cookies),
            "cookies"
        )
        val autoTheme = generalSection || matches(getString(R.string.is_auto_dark), "theme")
        val darkMode = generalSection || matches(getString(R.string.dark_mode), "theme", "night")
        val desktopMode = generalSection || matches(getString(R.string.desktop_mode), "desktop")
        val lockOrientation = generalSection || matches(
            getString(R.string.lock_screen_orientation_portrait),
            "orientation"
        )
        val drm = generalSection || matches(getString(R.string.drm_permission_title), "drm")
        val searchEngine = generalSection || matches(
            getString(R.string.search_engine),
            getString(R.string.search_engine_bing),
            getString(R.string.search_engine_duckduckgo),
            getString(R.string.search_engine_baidu),
            getString(R.string.search_engine_google)
        )
        val proxySettings = generalSection || matches(
            getString(R.string.proxies),
            getString(R.string.proxies_subtitle),
            "proxy",
            "dns"
        )
        val autoTranslate = generalSection || matches(getString(R.string.auto_translate_pages))
        setVisible(dataBinding.layoutClearCookie, clearCookies)
        setVisible(dataBinding.dividerAfterClearCookie, clearCookies)
        setVisible(dataBinding.isAutoThemeCheckBox, autoTheme)
        setVisible(dataBinding.isDarkModeCheckBox, darkMode)
        setVisible(dataBinding.isDesktopModeCheckBox, desktopMode)
        setVisible(dataBinding.lockOrientationCheckBox, lockOrientation)
        setVisible(dataBinding.drmEnabledCheckBox, drm)
        setVisible(dataBinding.layoutSearchEngine, searchEngine)
        setVisible(dataBinding.layoutProxySettings, proxySettings)
        setVisible(dataBinding.autoTranslatePagesCheckBox, autoTranslate)

        val detectionSection = matches(
            getString(R.string.settings_detection),
            getString(R.string.settings_detection_subtitle)
        )
        val videoAlert = detectionSection || matches(getString(R.string.show_video_found_alert))
        val videoButton = detectionSection || matches(getString(R.string.show_action_button))
        val findByUrl = detectionSection || matches(getString(R.string.find_videos_by_url))
        val filterShort = detectionSection || matches(
            getString(R.string.filter_short_videos),
            getString(R.string.short_video_filter_duration, settingsViewModel.shortVideoFilterDurationSeconds.get())
        )
        val detectionAdvancedSection = detectionSection || matches(
            getString(R.string.settings_detection_advanced),
            getString(R.string.settings_show_advanced),
            getString(R.string.settings_hide_advanced)
        )
        val checkMp4 = detectionAdvancedSection || matches(getString(R.string.check_every_request_on_video_mp4))
        val checkM3u8 = detectionAdvancedSection || matches(getString(R.string.check_every_request_on_video_m3u8))
        val checkAudio = detectionAdvancedSection || matches(getString(R.string.check_every_request_on_audio))
        val forceStreamDetection = detectionAdvancedSection || matches(getString(R.string.force_stream_detection))
        val legacyM3u8 = detectionAdvancedSection || matches(getString(R.string.use_legacy_m3u8_detection))
        val hasDetectionAdvanced = checkMp4 || checkM3u8 || checkAudio || forceStreamDetection || legacyM3u8
        setVisible(dataBinding.showVideoAlertCheckBox, videoAlert)
        setVisible(dataBinding.showVideoActionButtonCheckBox, videoButton)
        setVisible(dataBinding.findVideosByUrl, findByUrl)
        setVisible(dataBinding.filterShortVideos, filterShort)
        setVisible(dataBinding.shortVideoDurationContainer, filterShort)
        setVisible(dataBinding.detectionAdvancedHeader, hasDetectionAdvanced)
        setVisible(dataBinding.detectionAdvancedContainer, hasDetectionAdvanced)
        setVisible(dataBinding.isCheckEveryRequestOnMp4, checkMp4)
        setVisible(dataBinding.isCheckEveryRequestOnM3u8, checkM3u8)
        setVisible(dataBinding.isCheckEveryRequestOnAudio, checkAudio)
        setVisible(dataBinding.isForceStreamDetection, forceStreamDetection)
        setVisible(dataBinding.isUseLegacyM3u8Detection, legacyM3u8)

        val downloadingSection = matches(
            getString(R.string.settings_downloading),
            getString(R.string.settings_downloading_subtitle)
        )
        val downloadingAdvancedSection = downloadingSection || matches(
            getString(R.string.settings_downloading_advanced),
            getString(R.string.settings_show_advanced),
            getString(R.string.settings_hide_advanced)
        )
        val forceStreamDownloading = downloadingAdvancedSection || matches(getString(R.string.force_stream_downloading))
        val remuxRegular = downloadingAdvancedSection || matches(getString(R.string.is_always_remux_regular_downloads))
        val remuxLive = downloadingAdvancedSection || matches(getString(R.string.is_remux_only_live_regular_downloads))
        val interruptResources = downloadingAdvancedSection || matches(getString(R.string.is_interrupt_intrecepted_resources))
        val filenameTemplate = downloadingAdvancedSection || matches(
            getString(R.string.filename_template_title),
            getString(R.string.filename_template_summary),
            "template",
            "filename"
        )
        val ytdlpUpdate = downloadingAdvancedSection || matches(
            getString(R.string.ytdlp_update_title),
            getString(R.string.ytdlp_update_button),
            "yt-dlp"
        )
        val cookieProfiles = downloadingAdvancedSection || matches(
            getString(R.string.cookie_profiles_title),
            getString(R.string.cookie_profiles_import),
            getString(R.string.cookie_profiles_export),
            "cookie"
        )
        val maxConcurrent = downloadingAdvancedSection || matches(getString(R.string.max_concurrent_downloads), "concurrent", "queue")
        val regularThreads = downloadingAdvancedSection || matches(getString(R.string.regular_threads_count), "regular")
        val m3u8Threads = downloadingAdvancedSection || matches(getString(R.string.m3u8_threads_count), "m3u8")
        val detectionThreshold = downloadingAdvancedSection || matches(getString(R.string.video_detection_threshold))
        val hasDownloadingAdvanced = forceStreamDownloading ||
            remuxRegular ||
            remuxLive ||
            interruptResources ||
            filenameTemplate ||
            ytdlpUpdate ||
            cookieProfiles ||
            maxConcurrent ||
            regularThreads ||
            m3u8Threads ||
            detectionThreshold
        setVisible(dataBinding.downloadingAdvancedHeader, hasDownloadingAdvanced)
        setVisible(dataBinding.downloadingAdvancedContainer, hasDownloadingAdvanced)
        setVisible(dataBinding.isForceStreamDownloading, forceStreamDownloading)
        setVisible(dataBinding.isAlwaysRemuxRegularDownloads, remuxRegular)
        setVisible(dataBinding.isRemuxOnlyLiveRegularDownloads, remuxLive)
        setVisible(dataBinding.isInterruptIntreceptedResources, interruptResources)
        setVisible(dataBinding.filenameTemplateContainer, filenameTemplate)
        setVisible(dataBinding.ytdlpUpdateContainer, ytdlpUpdate)
        setVisible(dataBinding.cookieProfileContainer, cookieProfiles)
        setVisible(dataBinding.maxConcurrentDownloadsContainer, maxConcurrent)
        setVisible(dataBinding.regularContainer, regularThreads)
        setVisible(dataBinding.m3u8Container, m3u8Threads)
        setVisible(dataBinding.videoDetectionThreshold, detectionThreshold)

        val storageSection = matches(
            getString(R.string.downloads_location),
            getString(R.string.settings_storage_subtitle),
            getString(R.string.downloads_folder),
            getString(R.string.hidden_folder),
            getString(R.string.sd_app_folder)
        )
        val openDownloadFolder = storageSection || matches(
            getString(R.string.settings_open_download_folder),
            getString(R.string.settings_open_download_folder_subtitle),
            "folder"
        )
        val migrationCenter = storageSection || matches(
            getString(R.string.migration_center_title),
            getString(R.string.migration_center_subtitle),
            getString(R.string.migration_center_import_button),
            getString(R.string.migration_center_export_button),
            getString(R.string.migration_center_bridge_title),
            getString(R.string.migration_center_new_title),
            "migration",
            "backup",
            "import",
            "export",
            "legacy"
        )
        setVisible(
            dataBinding.settingsGeneralCard,
            clearCookies || autoTheme || darkMode || desktopMode || lockOrientation || drm || searchEngine || proxySettings || autoTranslate
        )
        setVisible(
            dataBinding.settingsDetectionCard,
            videoAlert || videoButton || findByUrl || filterShort || hasDetectionAdvanced
        )
        setVisible(dataBinding.settingsDownloadingCard, hasDownloadingAdvanced)
        setVisible(dataBinding.layoutMigrationCenter, migrationCenter)
        setVisible(dataBinding.layoutOpenDownloadFolder, openDownloadFolder)
        setVisible(dataBinding.settingsStorageCard, storageSection || openDownloadFolder || migrationCenter)

        val anyMatch = dataBinding.settingsGeneralCard.isVisible ||
            dataBinding.settingsDetectionCard.isVisible ||
            dataBinding.settingsDownloadingCard.isVisible ||
            dataBinding.settingsStorageCard.isVisible
        dataBinding.settingsNoResults.isVisible = !anyMatch
    }

    private fun showAllSettingsRows() {
        listOf(
            dataBinding.settingsGeneralCard,
            dataBinding.settingsDetectionCard,
            dataBinding.settingsDownloadingCard,
            dataBinding.settingsStorageCard,
            dataBinding.layoutClearCookie,
            dataBinding.dividerAfterClearCookie,
            dataBinding.isAutoThemeCheckBox,
            dataBinding.isDarkModeCheckBox,
            dataBinding.isAutoPipCheckBox,
            dataBinding.isDesktopModeCheckBox,
            dataBinding.lockOrientationCheckBox,
            dataBinding.drmEnabledCheckBox,
            dataBinding.layoutSearchEngine,
            dataBinding.layoutProxySettings,
            dataBinding.autoTranslatePagesCheckBox,
            dataBinding.showVideoAlertCheckBox,
            dataBinding.showVideoActionButtonCheckBox,
            dataBinding.findVideosByUrl,
            dataBinding.filterShortVideos,
            dataBinding.shortVideoDurationContainer,
            dataBinding.detectionAdvancedHeader,
            dataBinding.isCheckEveryRequestOnMp4,
            dataBinding.isCheckEveryRequestOnM3u8,
            dataBinding.isCheckEveryRequestOnAudio,
            dataBinding.isForceStreamDetection,
            dataBinding.isUseLegacyM3u8Detection,
            dataBinding.downloadingAdvancedHeader,
            dataBinding.isForceStreamDownloading,
            dataBinding.isAlwaysRemuxRegularDownloads,
            dataBinding.isRemuxOnlyLiveRegularDownloads,
            dataBinding.isInterruptIntreceptedResources,
            dataBinding.filenameTemplateContainer,
            dataBinding.ytdlpUpdateContainer,
            dataBinding.cookieProfileContainer,
            dataBinding.maxConcurrentDownloadsContainer,
            dataBinding.regularContainer,
            dataBinding.m3u8Container,
            dataBinding.videoDetectionThreshold,
            dataBinding.layoutMigrationCenter,
            dataBinding.layoutOpenDownloadFolder
        ).forEach { it.visibility = View.VISIBLE }
    }

    private fun handleUIEvents() {
        settingsViewModel.clearCookiesEvent.observe(viewLifecycleOwner) {
            systemUtil.clearCookies(context)
        }
        settingsViewModel.openVideoFolderEvent.observe(viewLifecycleOwner) {
            intentUtil.openVideoFolder(context, fileUtil.folderDir.path)
        }
        settingsViewModel.openProxySettingsEvent.observe(viewLifecycleOwner) {
            navigateToProxies()
        }
        settingsViewModel.openMigrationCenterEvent.observe(viewLifecycleOwner) {
            navigateToMigrationCenter()
        }
        settingsViewModel.openCookieImportEvent.observe(viewLifecycleOwner) {
            pickCookieProfileLauncher.launch(arrayOf("text/plain", "application/octet-stream", "*/*"))
        }
        settingsViewModel.openCookieExportEvent.observe(viewLifecycleOwner) { content ->
            pendingCookieExportContent = content
            exportCookieProfileLauncher.launch("surfsave-cookie-profiles.txt")
        }

        searchEngineCallback.let { settingsViewModel.searchEngine.addOnPropertyChangedCallback(it) }
        searchEngineCallback.onPropertyChanged(null, 0)
    }

    private fun setupTextUpdateCallbacks() {
        thresholdCallback.let {
            settingsViewModel.videoDetectionThreshold.addOnPropertyChangedCallback(
                it
            )
        }
        shortVideoDurationCallback.let {
            settingsViewModel.shortVideoFilterDurationSeconds.addOnPropertyChangedCallback(it)
        }

        thresholdCallback.onPropertyChanged(null, 0)
        shortVideoDurationCallback.onPropertyChanged(null, 0)
    }

    private fun showSearchEngineDialog() {
        val engines = SharedPrefHelper.SearchEngine.entries.toTypedArray()
        val labels = engines.map { getSearchEngineLabel(it) }.toTypedArray()
        val current = settingsViewModel.searchEngine.get() ?: SharedPrefHelper.SearchEngine.BING
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.search_engine)
            .setSingleChoiceItems(labels, engines.indexOf(current).coerceAtLeast(0)) { dialog, which ->
                settingsViewModel.setSearchEngine(engines[which])
                dialog.dismiss()
            }
            .setNegativeButton(R.string.all_text_cancel, null)
            .show()
    }

    private fun getSearchEngineLabel(engine: SharedPrefHelper.SearchEngine): String {
        return getString(
            when (engine) {
                SharedPrefHelper.SearchEngine.BING -> R.string.search_engine_bing
                SharedPrefHelper.SearchEngine.DUCKDUCKGO -> R.string.search_engine_duckduckgo
                SharedPrefHelper.SearchEngine.BAIDU -> R.string.search_engine_baidu
                SharedPrefHelper.SearchEngine.GOOGLE -> R.string.search_engine_google
            }
        )
    }

    private fun importCookieProfile(uri: Uri) {
        val displayName = resolveDisplayName(uri)
        val content = requireContext().contentResolver.openInputStream(uri)?.use { input ->
            input.readBytes().toString(Charsets.UTF_8)
        }.orEmpty()
        settingsViewModel.importCookieProfile(displayName, content)
    }

    private fun exportCookieProfile(uri: Uri) {
        requireContext().contentResolver.openOutputStream(uri, "w")?.use { output ->
            output.write(pendingCookieExportContent.toByteArray(Charsets.UTF_8))
        }
        pendingCookieExportContent = ""
    }

    private fun resolveDisplayName(uri: Uri): String {
        return requireContext().contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) else null
            } else {
                null
            }
        } ?: uri.lastPathSegment ?: "cookies.txt"
    }

    private fun showDownloadWarningDialog(context: Context) {
        AlertDialog.Builder(context)
            .setTitle("Download Warning")
            .setMessage("Some downloads may be corrupted in multi-thread downloading, if you experience some issues, switch back to single thread download!")
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun navigateToProxies() {
        try {
            val activityFragmentContainer =
                activity?.findViewById<FragmentContainerView>(R.id.fragment_container_view)
            activityFragmentContainer?.let {
                val transaction = requireActivity().supportFragmentManager.beginTransaction()
                transaction.add(it.id, ProxiesFragment.newInstance())
                transaction.addToBackStack("proxies")
                transaction.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
                transaction.commit()
            }
        } catch (e: ClassCastException) {
            AppLogger.d("Can't get the fragment manager with this")
        }
    }

    private fun navigateToMigrationCenter() {
        try {
            val activityFragmentContainer =
                activity?.findViewById<FragmentContainerView>(R.id.fragment_container_view)
            activityFragmentContainer?.let {
                val transaction = requireActivity().supportFragmentManager.beginTransaction()
                transaction.add(it.id, MigrationCenterFragment.newInstance())
                transaction.addToBackStack(MigrationCenterFragment.TAG)
                transaction.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
                transaction.commit()
            }
        } catch (e: ClassCastException) {
            AppLogger.d("Can't get the fragment manager with this")
        }
    }
}
