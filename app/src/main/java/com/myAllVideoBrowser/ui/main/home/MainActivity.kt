package com.myAllVideoBrowser.ui.main.home

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Window
import android.view.View
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.FragmentTransaction
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.databinding.DataBindingUtil
import androidx.databinding.Observable
import androidx.lifecycle.ViewModelProvider
import androidx.viewpager2.widget.ViewPager2
import com.myAllVideoBrowser.DLApplication
import com.myAllVideoBrowser.BuildConfig
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.databinding.ActivityMainBinding
import com.myAllVideoBrowser.migration.MigrationStage
import com.myAllVideoBrowser.migration.MigrationStateStore
import com.myAllVideoBrowser.ui.component.adapter.MainAdapter
import com.myAllVideoBrowser.ui.main.base.BaseActivity
import com.myAllVideoBrowser.ui.main.home.browser.webTab.WebTabFactory
import com.myAllVideoBrowser.ui.main.migration.MigrationCenterFragment
import com.myAllVideoBrowser.ui.main.progress.ProgressViewModel
import com.myAllVideoBrowser.ui.main.proxies.ProxiesViewModel
import com.myAllVideoBrowser.ui.main.settings.SettingsViewModel
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.PlaylistExtractor
import com.myAllVideoBrowser.util.SharedPrefHelper
import com.myAllVideoBrowser.util.UrlInputNormalizer
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskState
import com.myAllVideoBrowser.util.downloaders.youtubedl_downloader.YoutubeDlDownloaderWorker
import com.myAllVideoBrowser.util.fragment.FragmentFactory
import com.myAllVideoBrowser.util.scheduler.BaseSchedulers
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import javax.inject.Inject

//@OpenForTesting
class MainActivity : BaseActivity() {

    @Inject
    lateinit var fragmentFactory: FragmentFactory

    @Inject
    lateinit var viewModelFactory: ViewModelProvider.Factory

    @Inject
    lateinit var baseSchedulers: BaseSchedulers

    @Inject
    lateinit var sharedPrefHelper: SharedPrefHelper

    @Inject
    lateinit var migrationStateStore: MigrationStateStore

    @Inject
    lateinit var playlistExtractor: PlaylistExtractor

    lateinit var mainViewModel: MainViewModel

    lateinit var progressViewModel: ProgressViewModel

    lateinit var proxiesViewModel: ProxiesViewModel

    lateinit var settingsViewModel: SettingsViewModel

    private lateinit var dataBinding: ActivityMainBinding

    private lateinit var mainAdapter: MainAdapter

    private val screenOrientationCallback = object : Observable.OnPropertyChangedCallback() {
        override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
            val isLock = settingsViewModel.isLockPortrait.get()
            requestedOrientation = if (isLock) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()

        supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        super.onCreate(savedInstanceState)

        if (sharedPrefHelper.getIsProxyOn() || sharedPrefHelper.getIsDohOn()) {
            (applicationContext as? DLApplication)?.startProxyService()
        }

        dataBinding = DataBindingUtil.setContentView(this, R.layout.activity_main)

        mainViewModel = ViewModelProvider(this, viewModelFactory)[MainViewModel::class.java]
        progressViewModel = ViewModelProvider(this, viewModelFactory)[ProgressViewModel::class.java]
        proxiesViewModel = ViewModelProvider(this, viewModelFactory)[ProxiesViewModel::class.java]
        settingsViewModel = ViewModelProvider(this, viewModelFactory)[SettingsViewModel::class.java]

        mainAdapter = MainAdapter(supportFragmentManager, lifecycle, fragmentFactory)

        dataBinding.viewPager.isUserInputEnabled = false
        dataBinding.viewPager.adapter = mainAdapter
        dataBinding.viewPager.registerOnPageChangeCallback(onPageChangeListener)
        dataBinding.bottomBar.setOnItemSelectedListener { menuItem ->
            val isBrowser = mainViewModel.currentItem.get() == 0
            var goingToBrowser = false
            when (menuItem.itemId) {
                R.id.tab_browser -> {
                    mainViewModel.currentItem.set(0)
                    goingToBrowser = true
                }

                R.id.tab_progress -> mainViewModel.currentItem.set(1)
                R.id.tab_video -> mainViewModel.currentItem.set(2)
                else -> mainViewModel.currentItem.set(0)
            }
            updateBottomBarVisibility(mainViewModel.currentItem.get() ?: 0)

            if (isBrowser && goingToBrowser && mainViewModel.isBrowserCurrent.get()) {
                mainViewModel.openNavDrawerEvent.call()
            }
            return@setOnItemSelectedListener true
        }
        dataBinding.viewModel = mainViewModel

        grantPermissions()
        proxiesViewModel.start()
        settingsViewModel.start()
        mainViewModel.start()
        progressViewModel.start()

        observeDownloadEvents()

        handleScreenOrientationSettingChange()
        handleScreenOrientationSettingsInit()

        onNewIntent(intent)
        maybeOpenMigrationCenter(savedInstanceState)
    }

    @SuppressLint("MissingSuperCall")
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) {
            setIntent(intent)
        }

        if (intent?.getBooleanExtra(
                YoutubeDlDownloaderWorker.IS_FINISHED_DOWNLOAD_ACTION_KEY,
                false
            ) == true
        ) {
            if (intent.getBooleanExtra(
                    YoutubeDlDownloaderWorker.IS_FINISHED_DOWNLOAD_ACTION_ERROR_KEY,
                    false
                )
            ) {
                dataBinding.viewPager.currentItem = 1
            } else {
                dataBinding.viewPager.currentItem = 2
            }

            if (intent.hasExtra(YoutubeDlDownloaderWorker.DOWNLOAD_FILENAME_KEY)) {
                val downloadFileName =
                    intent.getStringExtra(YoutubeDlDownloaderWorker.DOWNLOAD_FILENAME_KEY)
                        .toString()

                Handler(Looper.getMainLooper()).postDelayed({
                    mainViewModel.openDownloadedVideoEvent.value = downloadFileName
                }, 1000)
            }
        } else {
            if (handleIncomingContent(intent)) {
                return
            }

            if (intent?.hasExtra(YoutubeDlDownloaderWorker.IS_FINISHED_DOWNLOAD_ACTION_KEY) == true) {
                dataBinding.viewPager.currentItem = 1
            } else {
                dataBinding.viewPager.currentItem = 0
            }
        }
    }

    private fun handleIncomingContent(intent: Intent?): Boolean {
        val input = extractIncomingInput(intent) ?: return false

        dataBinding.viewPager.currentItem = 0
        mainViewModel.currentItem.set(0)

        val provider = mainViewModel.browserServicesProvider
        if (provider != null) {
            provider.getOpenTabEvent().value = WebTabFactory.createWebTabFromInput(
                input,
                searchUrlPattern = sharedPrefHelper.getSearchUrlPattern()
            )
            mainViewModel.openedUrl.set(null)
            mainViewModel.openedText.set(null)
        } else if (isProbablyUrl(input)) {
            mainViewModel.openedUrl.set(input)
        } else {
            mainViewModel.openedText.set(input)
        }

        return true
    }

    private fun extractIncomingInput(intent: Intent?): String? {
        val rawInput = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        }
        return UrlInputNormalizer.extractBestInput(rawInput)
    }

    private fun isProbablyUrl(input: String): Boolean {
        return UrlInputNormalizer.isProbablyWebAddress(input)
    }

    private fun grantPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(
                    applicationContext,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest += Manifest.permission.POST_NOTIFICATIONS
            }
            if (ContextCompat.checkSelfPermission(
                    applicationContext,
                    Manifest.permission.READ_MEDIA_VIDEO
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest += Manifest.permission.READ_MEDIA_VIDEO
            }
            if (ContextCompat.checkSelfPermission(
                    applicationContext,
                    Manifest.permission.READ_MEDIA_AUDIO
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest += Manifest.permission.READ_MEDIA_AUDIO
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest += Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsToRequest.distinct().toTypedArray(),
                0
            )
        }
    }

    private val onPageChangeListener = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageScrollStateChanged(p0: Int) {
        }

        override fun onPageScrolled(p0: Int, p1: Float, p2: Int) {
        }

        override fun onPageSelected(postion: Int) {
            updateBottomBarVisibility(postion)
            if (postion == 0) {
                // Если без этого, дровер отркрываетс когда не надо
                Handler(Looper.getMainLooper()).postDelayed({
                    mainViewModel.isBrowserCurrent.set(true)
                }, 1000)
            } else {
                mainViewModel.isBrowserCurrent.set(false)
            }

            val childrenCount = dataBinding.fragmentContainerView.childCount
            if (childrenCount > 0) {
                supportFragmentManager.popBackStack()
            }
            if (postion > 0) {
                dataBinding.viewPager.isUserInputEnabled = true
            } else {
                dataBinding.viewPager.isUserInputEnabled = false
            }

            mainViewModel.currentItem.set(postion)
        }
    }

    private fun updateBottomBarVisibility(pageIndex: Int) {
        dataBinding.bottomBar.visibility = View.VISIBLE
    }

    private fun maybeOpenMigrationCenter(savedInstanceState: Bundle?) {
        if (!shouldAutoOpenMigrationCenter(savedInstanceState)) {
            return
        }

        val activityFragmentContainer = findViewById<FragmentContainerView>(R.id.fragment_container_view)
        if (supportFragmentManager.findFragmentByTag(MigrationCenterFragment.TAG) != null) {
            return
        }

        activityFragmentContainer?.let {
            val transaction = supportFragmentManager.beginTransaction()
            transaction.setCustomAnimations(
                R.anim.surf_fragment_enter, R.anim.surf_fragment_exit,
                R.anim.surf_fragment_pop_enter, R.anim.surf_fragment_pop_exit
            )
            transaction.add(it.id, MigrationCenterFragment.newInstance(), MigrationCenterFragment.TAG)
            transaction.addToBackStack(MigrationCenterFragment.TAG)
            transaction.commit()
        }
    }

    private fun shouldAutoOpenMigrationCenter(savedInstanceState: Bundle?): Boolean {
        if (!BuildConfig.MIGRATION_IMPORT_ENABLED || savedInstanceState != null) {
            return false
        }
        if (migrationStateStore.getStage() == MigrationStage.IMPORTED) {
            return false
        }
        val action = intent?.action
        return action != Intent.ACTION_VIEW && action != Intent.ACTION_SEND
    }

    private fun observeDownloadEvents() {
        progressViewModel.downloadStartedEvent.observe(this) { messageRes ->
            Snackbar.make(dataBinding.viewPager, getString(messageRes), Snackbar.LENGTH_LONG)
                .setAnchorView(dataBinding.bottomBar)
                .setAction(getString(R.string.action_view)) {
                    dataBinding.viewPager.currentItem = 1
                    dataBinding.bottomBar.selectedItemId = R.id.tab_progress
                }
                .show()
        }
        progressViewModel.downloadRejectedEvent.observe(this) { messageRes ->
            Snackbar.make(dataBinding.viewPager, getString(messageRes), Snackbar.LENGTH_SHORT)
                .setAnchorView(dataBinding.bottomBar)
                .show()
        }
        progressViewModel.downloadDuplicateEvent.observe(this) { event ->
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.download_duplicate_title)
                .setMessage(getString(event.messageRes))
                .setPositiveButton(R.string.action_view) { _, _ ->
                    dataBinding.viewPager.currentItem = 1
                    dataBinding.bottomBar.selectedItemId = R.id.tab_progress
                }
                .setNegativeButton(R.string.download_again) { _, _ ->
                    progressViewModel.forceDownloadVideo(event.incomingVideoInfo)
                }
                .setNeutralButton(R.string.all_text_cancel, null)
                .show()
        }
        progressViewModel.playlistEnqueueSummaryEvent.observe(this) { summary ->
            Snackbar.make(
                dataBinding.viewPager,
                getString(
                    R.string.playlist_enqueue_summary,
                    summary.accepted,
                    summary.duplicates,
                    summary.rejected
                ),
                Snackbar.LENGTH_LONG
            )
                .setAnchorView(dataBinding.bottomBar)
                .setAction(getString(R.string.action_view)) {
                    dataBinding.viewPager.currentItem = 1
                    dataBinding.bottomBar.selectedItemId = R.id.tab_progress
                }
                .show()
        }
    }

    override fun onDestroy() {
        if (isFinishing) {
            mainViewModel.stop()
            settingsViewModel.isLockPortrait.removeOnPropertyChangedCallback(
                screenOrientationCallback
            )
            val isDownloadingNow = progressViewModel.progressInfos.get()
                ?.find { it.downloadStatus == VideoTaskState.DOWNLOADING || it.downloadStatus == VideoTaskState.PREPARE || it.downloadStatus == VideoTaskState.PENDING } == null
            if (isDownloadingNow) {
                AppLogger.d("No active downloads, shutdown local proxy service...")
                proxiesViewModel.shutdownProxyService()
            }
            progressViewModel.stop()
        }
        super.onDestroy()
    }

    private fun handleScreenOrientationSettingsInit() {
        // INIT
        requestedOrientation = if (settingsViewModel.isLockPortrait.get()) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun handleScreenOrientationSettingChange() {
        // CHANGES HANDLING
        settingsViewModel.isLockPortrait.addOnPropertyChangedCallback(screenOrientationCallback)
    }
}
