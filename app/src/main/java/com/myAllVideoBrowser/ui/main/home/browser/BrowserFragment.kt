package com.myAllVideoBrowser.ui.main.home.browser

//import com.allVideoDownloaderXmaster.OpenForTesting

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.*
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.getSystemService
import androidx.core.view.GravityCompat
import androidx.databinding.Observable
import androidx.databinding.ObservableField
import androidx.databinding.ObservableInt
import androidx.fragment.app.Fragment
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.databinding.FragmentBrowserBinding
import com.myAllVideoBrowser.ui.component.adapter.dispatchListDiff
import com.myAllVideoBrowser.ui.component.adapter.WebTabsAdapter
import com.myAllVideoBrowser.ui.component.adapter.WebTabsListener
import com.myAllVideoBrowser.ui.main.base.BaseFragment
import com.myAllVideoBrowser.ui.main.history.HistoryViewModel
import com.myAllVideoBrowser.ui.main.home.MainActivity
import com.myAllVideoBrowser.ui.main.home.MainViewModel
import com.myAllVideoBrowser.ui.main.home.browser.detectedVideos.GlobalVideoDetectionModel
import com.myAllVideoBrowser.ui.main.home.browser.homeTab.BrowserHomeFragment
import com.myAllVideoBrowser.ui.main.home.browser.webTab.WebTab
import com.myAllVideoBrowser.ui.main.home.browser.webTab.WebTabFactory
import com.myAllVideoBrowser.ui.main.home.browser.webTab.WebTabFragment
import com.myAllVideoBrowser.ui.main.home.browser.webTab.WebTabThumbnailCapture
import com.myAllVideoBrowser.ui.main.home.browser.webTab.WebTabNavigationPurpose
import com.myAllVideoBrowser.ui.main.settings.SettingsViewModel
import com.myAllVideoBrowser.util.*
import com.myAllVideoBrowser.util.proxy_utils.CustomProxyController
import com.myAllVideoBrowser.util.proxy_utils.OkHttpProxyClient
import io.reactivex.rxjava3.disposables.CompositeDisposable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject


interface BrowserServicesProvider : TabManagerProvider, PageTabProvider, HistoryProvider,
    WorkerEventProvider, CurrentTabIndexProvider, CurrentTabLoaderProvider

interface TabManagerProvider {
    fun getOpenTabEvent(): SingleLiveEvent<WebTab>

    fun getOpenBackgroundTabEvent(): SingleLiveEvent<WebTab>

    fun getCloseTabEvent(): SingleLiveEvent<WebTab>

    fun getUpdateTabEvent(): SingleLiveEvent<WebTab>

    fun onTabNavigationStarted(tabId: String, pageUrl: String)

    fun getTabsListChangeEvent(): ObservableField<List<WebTab>>
}

interface PageTabProvider {
    fun getPageTab(position: Int): WebTab
}

interface HistoryProvider {
    fun getHistoryVModel(): HistoryViewModel
}

interface WorkerEventProvider {
    fun getWorkerM3u8MpdEvent(): MutableLiveData<DownloadButtonState>

    fun getWorkerMP4Event(): MutableLiveData<DownloadButtonState>
}

interface CurrentTabIndexProvider {
    fun getCurrentTabIndex(): ObservableInt
}

interface CurrentTabLoaderProvider {
    fun openInCurrentTab(input: String)
}

interface BrowserListener {
    fun onBrowserMenuClicked()

    fun onHomeClicked()

    fun onTabsOverviewClicked()

    fun onBrowserGoClicked()

    fun onBrowserReloadClicked()

    fun onTabCloseClicked()

    fun onBrowserStopClicked()

    fun onBrowserBackClicked()

    fun onBrowserForwardClicked()
}

const val HOME_TAB_INDEX = 0

const val MAX_WEB_TABS = 100

private const val MAX_LIVE_WEB_TABS = 3

const val TAB_INDEX_KEY = "TAB_INDEX_KEY"

//@OpenForTesting
class BrowserFragment : BaseFragment(), BrowserServicesProvider {

    companion object {
        private val serviceWorkerClientLock = Any()
        private val installedServiceWorkerClient = AtomicReference<ServiceWorkerClient?>()

        fun newInstance() = BrowserFragment()
        var DESKTOP_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/109.0.0.0 Safari/537.36"

        // TODO different agents for different androids
        var MOBILE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 12; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/92.0.4515.131 Mobile Safari/537.36"
    }

    private lateinit var tabsAdapter: TabsFragmentStateAdapter

    private lateinit var drawerAdapter: WebTabsAdapter

    private val mainHandler = Handler(Looper.getMainLooper())

    private var tabsUiSyncScheduled = false

    private val tabsUiSyncRunnable = Runnable {
        tabsUiSyncScheduled = false
        syncBrowserTabsUiNow()
    }

    @Inject
    lateinit var viewModelFactory: ViewModelProvider.Factory

    @Inject
    lateinit var mainActivity: MainActivity

    @Inject
    lateinit var appUtil: AppUtil

    @Inject
    lateinit var proxyController: CustomProxyController

    @Inject
    lateinit var sharedPrefHelper: SharedPrefHelper

    @Inject
    lateinit var okHttpProxyClient: OkHttpProxyClient

    @VisibleForTesting
    internal lateinit var dataBinding: FragmentBrowserBinding

    private lateinit var browserViewModel: BrowserViewModel

    private lateinit var mainViewModel: MainViewModel

    private lateinit var historyModel: HistoryViewModel

    private lateinit var settingsModel: SettingsViewModel

    private lateinit var videoDetectionModel: GlobalVideoDetectionModel

    private val compositeDisposable = CompositeDisposable()

    private var backPressedOnce = false

    private var visibleUndoSnapshotId: String? = null
    private val hideTabUndoRunnable = Runnable { hideClosedTabUndo() }

    private lateinit var requestInspector: BrowserRequestInspector

    private val buttonStateCallback = object :
        Observable.OnPropertyChangedCallback() {
        override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
            lifecycleScope.launch(Dispatchers.Main) {
                browserViewModel.workerM3u8MpdEvent.value =
                    videoDetectionModel.downloadButtonState.get()
            }
        }
    }

    private val currentTabCallback = object : Observable.OnPropertyChangedCallback() {
        override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
            syncWebViewPlaybackState()
            markCurrentTabActive()
            scheduleBrowserTabsUiSync()
            browserViewModel.persistSession()
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        AppLogger.d("Permissions for writing isGranted: $isGranted")
    }

    private val serviceWorkerClient = object : ServiceWorkerClient() {
        override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? {
            val url = request.url.toString()
            val activeContext = videoDetectionModel.currentServiceWorkerContext()
                ?: return super.shouldInterceptRequest(request)
            val pageUrl = activeContext.pageUrl
            val inspection = requestInspector.inspect(url, pageUrl, request.isForMainFrame)
            val detectionContext = videoDetectionModel.serviceWorkerContextForRequest(
                request.requestHeaders,
                allowHeaderlessMedia = inspection.shouldInspectMedia
            ) ?: return super.shouldInterceptRequest(request)

            if (inspection.shouldInspectMedia) {
                val requestWithCookies = try {
                    CookieUtils.webResourceRequestToOkHttpRequest(request)
                } catch (_: Throwable) {
                    null
                }

                if (inspection.shouldCheckStream) {
                    lifecycleScope.launch(Dispatchers.Main) {
                        if (requestWithCookies != null) {
                            videoDetectionModel.verifyLinkStatus(
                                detectionContext,
                                requestWithCookies,
                                "",
                                inspection.shouldProbeAsM3u8,
                                inspection.isMpd
                            )
                        }
                    }
                } else if (inspection.shouldCheckRegular) {
                    lifecycleScope.launch(Dispatchers.Main) {
                        videoDetectionModel.checkRegularVideoOrAudio(
                            detectionContext,
                            requestWithCookies,
                            inspection.shouldCheckAudio,
                            inspection.shouldCheckVideo
                        )
                    }
                }
            }

            return super.shouldInterceptRequest(request)
        }
    }

    inner class TabsFragmentStateAdapter(private var webTabsRoutes: List<WebTab>) :
        FragmentStateAdapter(this) {
        fun setRoutes(newRoutes: List<WebTab>) {
            val oldRouteIds = webTabsRoutes.map { it.id }
            val newRouteIds = newRoutes.map { it.id }

            if (oldRouteIds != newRouteIds) {
                dispatchListDiff(
                    oldItems = webTabsRoutes,
                    newItems = newRoutes,
                    areItemsTheSame = { oldItem, newItem -> oldItem.id == newItem.id },
                    areContentsTheSame = { oldItem, newItem -> oldItem.id == newItem.id }
                ) {
                    webTabsRoutes = newRoutes
                }
            } else {
                webTabsRoutes = newRoutes
            }
        }

        override fun getItemCount(): Int = webTabsRoutes.size

        override fun getItemId(position: Int): Long {
            return webTabsRoutes[position].id.hashCode().toLong()
        }

        override fun containsItem(itemId: Long): Boolean {
            val webTab = webTabsRoutes.find { it.id.hashCode().toLong() == itemId }
            return webTab != null
        }

        override fun createFragment(position: Int): Fragment {
            if (position == HOME_TAB_INDEX) {
                return createHomeTabFragment()
            }

            return createTabFragment(position)
        }
    }

    private fun createHomeTabFragment(): Fragment {
        return BrowserHomeFragment.newInstance()
    }

    override fun getOpenTabEvent(): SingleLiveEvent<WebTab> {
        return browserViewModel.openPageEvent
    }

    override fun getOpenBackgroundTabEvent(): SingleLiveEvent<WebTab> {
        return browserViewModel.openBackgroundPageEvent
    }

    override fun getCloseTabEvent(): SingleLiveEvent<WebTab> {
        return browserViewModel.closePageEvent
    }

    override fun getUpdateTabEvent(): SingleLiveEvent<WebTab> {
        return browserViewModel.updateWebTabEvent
    }

    override fun onTabNavigationStarted(tabId: String, pageUrl: String) {
        if (!::browserViewModel.isInitialized || !::videoDetectionModel.isInitialized) {
            return
        }
        val tabs = browserViewModel.tabs.get().orEmpty()
        val currentIndex = browserViewModel.currentTab.get()
        if (tabs.getOrNull(currentIndex)?.id == tabId) {
            videoDetectionModel.activateServiceWorkerContext(
                tabId,
                pageUrl,
                forceNewGeneration = true
            )
        }
    }

    override fun getTabsListChangeEvent(): ObservableField<List<WebTab>> {
        return browserViewModel.tabs
    }

    override fun getPageTab(position: Int): WebTab {
        val list = browserViewModel.tabs.get() ?: listOf(WebTab.HOME_TAB)
        if (position in list.indices) {
            return list[position]
        }
        return WebTab("error", "error")
    }

    private fun createTabFragment(index: Int): Fragment {
        val fragment = WebTabFragment.newInstance().apply {
            val args = Bundle().apply {
                putInt(TAB_INDEX_KEY, index)
            }
            arguments = args
        }

        return fragment
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        mainViewModel = mainActivity.mainViewModel
        browserViewModel = ViewModelProvider(this, viewModelFactory)[BrowserViewModel::class.java]
        historyModel = ViewModelProvider(this, viewModelFactory)[HistoryViewModel::class.java]
        videoDetectionModel =
            ViewModelProvider(this, viewModelFactory)[GlobalVideoDetectionModel::class.java]

        videoDetectionModel.settingsModel = mainActivity.settingsViewModel
        browserViewModel.settingsModel = mainActivity.settingsViewModel
        settingsModel = mainActivity.settingsViewModel
        requestInspector = BrowserRequestInspector(settingsModel)

        mainActivity.mainViewModel.browserServicesProvider = this

        configureServiceWorkerInterception()

        tabsAdapter = TabsFragmentStateAdapter(emptyList())

        drawerAdapter = WebTabsAdapter(emptyList(), tabsListener)

        val webTabsManagerLayout = GridLayoutManager(
            context,
            if (resources.configuration.screenWidthDp >= 600) 3 else 2
        )

        val color = getThemeBackgroundColor()

        dataBinding = FragmentBrowserBinding.inflate(inflater, container, false).apply {
            this.viewPager.adapter = tabsAdapter
            this.viewPager.setSwipeThreshold(500)
            this.viewPager.setOnGoThroughListener(onGoThroughListener)
            this.viewPager.isUserInputEnabled = false
            this.tabsList.layoutManager = webTabsManagerLayout
            this.tabsList.adapter = drawerAdapter
            this.newTabButton.setOnClickListener {
                openNewTabPage()
            }
            this.recentlyClosedTabsButton.setOnClickListener {
                showRecentlyClosedTabs()
            }
            installRecentlyClosedTabsLongPress(this.tabsList)
            this.tabUndoDismiss.setOnClickListener {
                hideClosedTabUndo()
            }
            this.drawerLayoutContent.setBackgroundColor(color)

            this.viewModel = browserViewModel
        }
        syncSelectedDrawerTab()

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            onBackPressed()
        }

        videoDetectionModel.downloadButtonState.addOnPropertyChangedCallback(buttonStateCallback)
        browserViewModel.currentTab.addOnPropertyChangedCallback(currentTabCallback)


        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(
                    mainActivity, Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        return dataBinding.root
    }

    private fun installRecentlyClosedTabsLongPress(tabsList: RecyclerView) {
        val gestureDetector = GestureDetector(
            tabsList.context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(event: MotionEvent): Boolean = true

                override fun onLongPress(event: MotionEvent) {
                    if (tabsList.findChildViewUnder(event.x, event.y) != null) {
                        return
                    }
                    tabsList.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    showRecentlyClosedTabs()
                }
            }
        )
        tabsList.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(recyclerView: RecyclerView, event: MotionEvent): Boolean {
                gestureDetector.onTouchEvent(event)
                return false
            }
        })
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        browserViewModel.start()
        syncBrowserTabsUi()
        handlePressWebTabEvent()
        handleOpenTabEvent()
        handleOpenBackgroundTabEvent()
        handleCloseWebTabEventEvent()
        handleOpenNavDrawerEvent()
        handleUpdateWebTabEventEvent()
        checkIsPowerSaveMode()
    }

    override fun onPause() {
        captureCurrentTabThumbnail()
        super.onPause()
    }

    override fun onDestroyView() {
        mainHandler.removeCallbacks(hideTabUndoRunnable)
        hideClosedTabUndo()
        mainHandler.removeCallbacks(tabsUiSyncRunnable)
        tabsUiSyncScheduled = false
        clearServiceWorkerInterception()
        videoDetectionModel.activateServiceWorkerContext("", "")
        videoDetectionModel.downloadButtonState.removeOnPropertyChangedCallback(buttonStateCallback)
        browserViewModel.currentTab.removeOnPropertyChangedCallback(currentTabCallback)
        super.onDestroyView()
        browserViewModel.stop()
        videoDetectionModel.stop()
        compositeDisposable.clear()
    }

    override fun getHistoryVModel(): HistoryViewModel {
        return this.historyModel
    }

    override fun getWorkerM3u8MpdEvent(): MutableLiveData<DownloadButtonState> {
        return browserViewModel.workerM3u8MpdEvent
    }

    override fun getWorkerMP4Event(): MutableLiveData<DownloadButtonState> {
        return browserViewModel.workerMP4Event
    }

    override fun getCurrentTabIndex(): ObservableInt {
        return browserViewModel.currentTab
    }

    override fun openInCurrentTab(input: String) {
        val targetTab = WebTabFactory.createWebTabFromInput(
            input,
            searchUrlPattern = sharedPrefHelper.getSearchUrlPattern()
        )
        if (targetTab.isHome()) {
            openNewTabPage()
            return
        }

        val tabs = browserViewModel.tabs.get().orEmpty().ifEmpty { listOf(WebTab.HOME_TAB) }
            .toMutableList()
        val currentIndex = browserViewModel.currentTab.get()
            .coerceIn(HOME_TAB_INDEX, tabs.lastIndex.coerceAtLeast(HOME_TAB_INDEX))

        if (currentIndex == HOME_TAB_INDEX || currentIndex !in tabs.indices) {
            openTab(targetTab, switchToNewTab = true)
            return
        }

        val currentTab = tabs[currentIndex]
        val updatedTab = WebTab(
            targetTab.getUrl(),
            targetTab.getTitle(),
            targetTab.getFavicon(),
            resolveThumbnailBitmap(targetTab),
            targetTab.getPageThumbnailPath(),
            targetTab.getHeaders() ?: emptyMap(),
            currentTab.getWebView(),
            id = currentTab.id
        )
        updatedTab.clearSavedState()
        updatedTab.markActive()

        tabs[currentIndex] = updatedTab
        browserViewModel.tabs.set(tabs)
        updatedTab.getWebView()?.loadUrl(updatedTab.getUrl())
        syncBrowserTabsUi()
        browserViewModel.persistSession()
        dataBinding.drawerLayout.closeDrawer(GravityCompat.START)
    }

    private val tabsListener = object : WebTabsListener {
        override fun onCloseTabClicked(webTab: WebTab) {

            browserViewModel.closePageEvent.value = webTab
        }

        override fun onSelectTabClicked(webTab: WebTab) {
            browserViewModel.selectWebTabEvent.value = webTab
        }
    }

    private fun handlePressWebTabEvent() {
        browserViewModel.selectWebTabEvent.observe(viewLifecycleOwner) { webTab ->
            val index = browserViewModel.tabs.get()?.indexOfFirst { it.id == webTab.id } ?: 0
            if (index >= 0) {
                captureCurrentTabThumbnail()
                saveCurrentTabState()
                browserViewModel.currentTab.set(index)
                syncBrowserTabsUi()
                browserViewModel.persistSession()
                dataBinding.drawerLayout.closeDrawer(GravityCompat.START)
            }
        }
    }

    // TODO: Show dialog with variants: "Open in New Tab", "Load in Current Tab", "Block", "Don't show again"
    private fun handleOpenTabEvent() {
        browserViewModel.openPageEvent.observe(viewLifecycleOwner) { webTab ->
            openTab(webTab, switchToNewTab = true)
        }
    }

    private fun handleOpenBackgroundTabEvent() {
        browserViewModel.openBackgroundPageEvent.observe(viewLifecycleOwner) { webTab ->
            openTab(webTab, switchToNewTab = false)
        }
    }

    private fun openTab(webTab: WebTab, switchToNewTab: Boolean) {
            if (webTab.isHome()) {
                openNewTabPage()
                return
            }

            val currentTabs = browserViewModel.tabs.get().orEmpty().ifEmpty {
                listOf(WebTab.HOME_TAB)
            }
            val webTabsCount = currentTabs.count { !it.isHome() }
            if (webTabsCount >= MAX_WEB_TABS) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.tab_limit_reached, MAX_WEB_TABS),
                    Toast.LENGTH_SHORT
                ).show()
                return
            }

            val tabsBeforeAdd = if (switchToNewTab) {
                captureCurrentTabThumbnail(currentTabs)
            } else {
                currentTabs
            }
            val newList = tabsBeforeAdd.plus(webTab)
            browserViewModel.tabs.set(newList)
            if (switchToNewTab) {
                val index = newList.indexOf(webTab)
                browserViewModel.currentTab.set(index.coerceAtLeast(0))
            }
            syncBrowserTabsUi()
            browserViewModel.persistSession()
            dataBinding.drawerLayout.closeDrawer(GravityCompat.START)
    }

    private fun handleCloseWebTabEventEvent() {
        browserViewModel.closePageEvent.observe(viewLifecycleOwner) { webTab ->
            closeWebTab(webTab)
        }
    }

    private fun closeWebTab(webTab: WebTab) {
        val tabs =
            browserViewModel.tabs.get()?.toMutableList() ?: mutableListOf(WebTab.HOME_TAB)
        val tabToClose = tabs.find { it.id == webTab.id }
        val index = tabs.indexOf(tabToClose)
        if (index !in tabs.indices || index == HOME_TAB_INDEX) {
            return
        }

        val currentIndex = browserViewModel.currentTab.get()
        val wasSelected = currentIndex == index
        tabToClose?.saveWebViewState()
        val snapshotTab = tabToClose?.copyWith(
            pageThumbnail = null,
            webview = null,
            navigationPurpose = WebTabNavigationPurpose.NORMAL_BROWSE
        ) ?: return
        detachAndDestroyWebView(tabToClose.getWebView())
        tabToClose.setWebView(null)
        tabs.removeAt(index)

        val nextIndex = BrowserTabIndexPolicy.selectedIndexAfterClose(
            currentIndex = currentIndex,
            closedIndex = index,
            remainingTabCount = tabs.size
        )

        browserViewModel.tabs.set(tabs)
        if (browserViewModel.currentTab.get() != nextIndex) {
            browserViewModel.currentTab.set(nextIndex)
        } else {
            syncWebViewPlaybackState(tabs, nextIndex)
        }
        syncBrowserTabsUi()
        browserViewModel.persistSession()

        val snapshot = ClosedTabSnapshot(snapshotTab, index, wasSelected)
        browserViewModel.addRecentlyClosedTab(snapshot)?.let(::finalizeClosedTab)
        showClosedTabUndo(snapshot)
    }

    private fun showClosedTabUndo(snapshot: ClosedTabSnapshot) {
        if (!::dataBinding.isInitialized) {
            return
        }

        mainHandler.removeCallbacks(hideTabUndoRunnable)
        visibleUndoSnapshotId = snapshot.id
        dataBinding.tabUndoMessage.text = getString(
            R.string.closed_tab_undo_message,
            closedTabDisplayTitle(snapshot)
        )
        dataBinding.tabUndoAction.setOnClickListener {
            restoreClosedTab(snapshot.id)
        }
        dataBinding.tabUndoBar.visibility = View.VISIBLE
        mainHandler.postDelayed(hideTabUndoRunnable, BrowserTabUndoPolicy.DURATION_MS)
    }

    private fun hideClosedTabUndo(snapshotId: String? = null) {
        if (!::dataBinding.isInitialized ||
            (snapshotId != null && visibleUndoSnapshotId != snapshotId)
        ) {
            return
        }
        mainHandler.removeCallbacks(hideTabUndoRunnable)
        visibleUndoSnapshotId = null
        dataBinding.tabUndoAction.setOnClickListener(null)
        dataBinding.tabUndoBar.visibility = View.GONE
    }

    private fun restoreClosedTab(snapshotId: String) {
        val snapshot = browserViewModel.takeRecentlyClosedTab(snapshotId) ?: return
        hideClosedTabUndo(snapshotId)
        val tabs = browserViewModel.tabs.get().orEmpty().ifEmpty { listOf(WebTab.HOME_TAB) }
            .toMutableList()
        val selectedTabId = tabs.getOrNull(browserViewModel.currentTab.get())?.id
        val restoredId = UUID.randomUUID().toString()
        val restoredThumbnail = snapshot.tab.getPageThumbnail()
            ?.takeIf(BrowserThumbnailQuality::isUsable)
            ?: BrowserThumbnailStore.load(snapshot.tab.getPageThumbnailPath())
        val restoredThumbnailPath = BrowserThumbnailStore.save(restoredId, restoredThumbnail)
            ?: snapshot.tab.getPageThumbnailPath()
        if (restoredThumbnailPath != snapshot.tab.getPageThumbnailPath()) {
            BrowserThumbnailStore.delete(snapshot.tab.getPageThumbnailPath())
        }
        val restoredTab = snapshot.tab.copyWith(
            pageThumbnail = restoredThumbnail,
            pageThumbnailPath = restoredThumbnailPath,
            webview = null,
            id = restoredId
        )
        val restoredIndex = BrowserTabIndexPolicy.restoredInsertionIndex(
            originalIndex = snapshot.originalIndex,
            currentTabCount = tabs.size
        )
        tabs.add(restoredIndex, restoredTab)
        browserViewModel.tabs.set(tabs)

        val targetIndex = if (snapshot.wasSelected) {
            restoredIndex
        } else {
            tabs.indexOfFirst { it.id == selectedTabId }
                .takeIf { it >= HOME_TAB_INDEX }
                ?: browserViewModel.currentTab.get().coerceIn(HOME_TAB_INDEX, tabs.lastIndex)
        }
        browserViewModel.currentTab.set(targetIndex)
        syncBrowserTabsUi()
        browserViewModel.persistSession()
    }

    private fun finalizeClosedTab(snapshot: ClosedTabSnapshot) {
        BrowserThumbnailStore.delete(snapshot.tab.getPageThumbnailPath())
        snapshot.tab.clearSavedState()
    }

    private fun showRecentlyClosedTabs() {
        val snapshots = browserViewModel.getRecentlyClosedTabs()
        if (snapshots.isEmpty()) {
            Toast.makeText(requireContext(), R.string.no_recently_closed_tabs, Toast.LENGTH_SHORT)
                .show()
            return
        }

        val labels = snapshots.map(::closedTabDisplayTitle).toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.recently_closed_tabs)
            .setItems(labels) { dialog, which ->
                snapshots.getOrNull(which)?.let { restoreClosedTab(it.id) }
                dialog.dismiss()
            }
            .setNeutralButton(R.string.clear_recently_closed_tabs) { _, _ ->
                browserViewModel.clearRecentlyClosedTabs().forEach(::finalizeClosedTab)
                hideClosedTabUndo()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun closedTabDisplayTitle(snapshot: ClosedTabSnapshot): String {
        return snapshot.tab.getTitle().trim().ifBlank {
            UrlInputNormalizer.toDisplayHost(snapshot.tab.getUrl()).ifBlank { snapshot.tab.getUrl() }
        }
    }

    private fun openNewTabPage() {
        captureCurrentTabThumbnail()
        saveCurrentTabState()
        browserViewModel.currentTab.set(HOME_TAB_INDEX)
        syncSelectedDrawerTab()
        browserViewModel.persistSession()
        dataBinding.drawerLayout.closeDrawer(GravityCompat.START)
    }

    private fun handleUpdateWebTabEventEvent() {
        browserViewModel.updateWebTabEvent.observe(viewLifecycleOwner) { webTab ->
            val tabs = browserViewModel.tabs.get()?.toMutableList()
            val tabToUpdate = tabs?.find { it.id == webTab.id }
            val updateIndex = tabs?.indexOf(tabToUpdate)

            if (updateIndex != null && updateIndex in tabs.indices) {
                tabs[updateIndex] = webTab
            }

            browserViewModel.tabs.set(tabs ?: emptyList())
            syncBrowserTabsUi()
            browserViewModel.persistSession()
        }
    }

    private fun captureCurrentTabThumbnail(sourceTabs: List<WebTab>? = null): List<WebTab> {
        if (!::browserViewModel.isInitialized) {
            return sourceTabs.orEmpty()
        }

        val tabs = (sourceTabs ?: browserViewModel.tabs.get().orEmpty())
            .ifEmpty { listOf(WebTab.HOME_TAB) }
            .toMutableList()
        val currentIndex = browserViewModel.currentTab.get()
            .coerceIn(HOME_TAB_INDEX, tabs.lastIndex.coerceAtLeast(HOME_TAB_INDEX))
        val currentTab = tabs.getOrNull(currentIndex) ?: return tabs
        if (currentTab.isHome()) {
            return tabs
        }

        val webView = currentTab.getWebView() ?: return tabs
        val bitmap = WebTabThumbnailCapture.capture(webView) ?: return tabs
        val thumbnailPath = BrowserThumbnailStore.save(currentTab.id, bitmap)
        val updatedTab = currentTab.copyWith(
            url = webView.url ?: currentTab.getUrl(),
            title = webView.title ?: currentTab.getTitle(),
            iconBytes = webView.favicon ?: currentTab.getFavicon(),
            pageThumbnail = bitmap,
            pageThumbnailPath = thumbnailPath ?: currentTab.getPageThumbnailPath(),
            webview = webView
        )
        tabs[currentIndex] = updatedTab

        if (sourceTabs == null && ::dataBinding.isInitialized) {
            browserViewModel.tabs.set(tabs)
            browserViewModel.persistSession()
        }

        return tabs
    }

    private fun resolveThumbnailBitmap(tab: WebTab): android.graphics.Bitmap? {
        return tab.getPageThumbnail()?.takeIf(BrowserThumbnailQuality::isUsable)
            ?: BrowserThumbnailStore.load(tab.getPageThumbnailPath())
    }

    private fun syncBrowserTabsUi() {
        scheduleBrowserTabsUiSync()
    }

    private fun scheduleBrowserTabsUiSync() {
        if (tabsUiSyncScheduled) {
            return
        }
        tabsUiSyncScheduled = true
        mainHandler.post(tabsUiSyncRunnable)
    }

    private fun syncBrowserTabsUiNow() {
        if (!::tabsAdapter.isInitialized || !::drawerAdapter.isInitialized || !::browserViewModel.isInitialized) {
            return
        }
        if (!::dataBinding.isInitialized) {
            return
        }
        val tabs = browserViewModel.tabs.get().orEmpty().ifEmpty { listOf(WebTab.HOME_TAB) }
        markCurrentTabActive(tabs)
        enforceLiveWebViewBudget(tabs)
        tabsAdapter.setRoutes(tabs)
        drawerAdapter.setData(tabs)
        val webTabsCount = tabs.count { !it.isHome() }.coerceAtMost(MAX_WEB_TABS)
        browserViewModel.updateTabsBadgeText(webTabsCount)
        dataBinding.tabsToolbar.title = getString(R.string.tabs_with_count_title, webTabsCount)
        dataBinding.tabsSubtitle.text = getString(
            R.string.tabs_subtitle_with_count,
            webTabsCount,
            MAX_WEB_TABS
        )
        dataBinding.tabsSubtitle.contentDescription = getString(
            R.string.tabs_button_content_description,
            webTabsCount
        )
        val currentIndex = browserViewModel.currentTab.get().coerceIn(HOME_TAB_INDEX, tabs.lastIndex)
        updateActivePageUrlForInspection(tabs, currentIndex)
        dataBinding.viewPager.post {
            val adapterCount = dataBinding.viewPager.adapter?.itemCount ?: 0
            if (adapterCount > 0) {
                dataBinding.viewPager.currentItem = currentIndex.coerceIn(
                    HOME_TAB_INDEX,
                    adapterCount - 1
                )
            }
        }
        syncSelectedDrawerTab()
    }

    private fun markCurrentTabActive(
        tabs: List<WebTab> = browserViewModel.tabs.get().orEmpty()
    ) {
        val currentIndex = browserViewModel.currentTab.get()
            .coerceIn(HOME_TAB_INDEX, tabs.lastIndex.coerceAtLeast(HOME_TAB_INDEX))
        tabs.getOrNull(currentIndex)?.takeIf { !it.isHome() }?.markActive()
    }

    private fun saveCurrentTabState() {
        val tabs = browserViewModel.tabs.get().orEmpty()
        val currentIndex = browserViewModel.currentTab.get()
            .coerceIn(HOME_TAB_INDEX, tabs.lastIndex.coerceAtLeast(HOME_TAB_INDEX))
        tabs.getOrNull(currentIndex)
            ?.takeIf { !it.isHome() }
            ?.saveWebViewState()
    }

    private fun syncWebViewPlaybackState(
        tabs: List<WebTab> = browserViewModel.tabs.get().orEmpty(),
        currentIndex: Int = browserViewModel.currentTab.get()
    ) {
        tabs.forEachIndexed { index, tab ->
            val webView = tab.getWebView() ?: return@forEachIndexed
            if (!tab.isHome() && index == currentIndex) {
                WebViewMediaController.resume(webView)
            } else {
                WebViewMediaController.pause(webView)
            }
        }
    }

    private fun enforceLiveWebViewBudget(tabs: List<WebTab>) {
        if (tabs.count { !it.isHome() && it.getWebView() != null } <= MAX_LIVE_WEB_TABS) {
            return
        }

        val currentIndex = browserViewModel.currentTab.get()
            .coerceIn(HOME_TAB_INDEX, tabs.lastIndex.coerceAtLeast(HOME_TAB_INDEX))
        val keepIds = tabs
            .asSequence()
            .filter { !it.isHome() }
            .sortedWith(
                compareByDescending<WebTab> { if (tabs.indexOf(it) == currentIndex) 1 else 0 }
                    .thenByDescending { it.lastActiveAt }
            )
            .take(MAX_LIVE_WEB_TABS)
            .map { it.id }
            .toSet()

        tabs
            .filter { !it.isHome() && it.id !in keepIds }
            .forEach { tab ->
                tab.saveWebViewState()
                detachAndDestroyWebView(tab.getWebView())
                tab.setWebView(null)
            }
    }

    private fun detachAndDestroyWebView(webView: WebView?) {
        if (webView == null) {
            return
        }

        runCatching {
            WebViewMediaController.pause(webView)
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.stopLoading()
            webView.webChromeClient = null
            webView.webViewClient = WebViewClient()
            webView.destroy()
        }.onFailure {
            AppLogger.e("Failed to destroy inactive WebView: ${it.message}")
        }
    }

    private fun updateActivePageUrlForInspection(tabs: List<WebTab>, currentIndex: Int) {
        val currentTab = tabs.getOrNull(currentIndex)
        videoDetectionModel.activateServiceWorkerContext(
            currentTab?.id.orEmpty(),
            currentTab?.getUrl().orEmpty()
        )
    }

    private fun configureServiceWorkerInterception() {
        if (!::settingsModel.isInitialized || !::videoDetectionModel.isInitialized) {
            return
        }

        try {
            val swController = ServiceWorkerController.getInstance()
            synchronized(serviceWorkerClientLock) {
                swController.setServiceWorkerClient(serviceWorkerClient)
                installedServiceWorkerClient.set(serviceWorkerClient)
                swController.serviceWorkerWebSettings.allowContentAccess = true
            }
        } catch (e: Throwable) {
            AppLogger.e("ServiceWorker interception unavailable: ${e.message}")
        }
    }

    private fun clearServiceWorkerInterception() {
        try {
            val swController = ServiceWorkerController.getInstance()
            synchronized(serviceWorkerClientLock) {
                if (installedServiceWorkerClient.get() === serviceWorkerClient) {
                    swController.setServiceWorkerClient(null)
                    installedServiceWorkerClient.set(null)
                }
            }
        } catch (e: Throwable) {
            AppLogger.e("ServiceWorker interception cleanup failed: ${e.message}")
        }
    }

    private fun syncSelectedDrawerTab() {
        if (!::drawerAdapter.isInitialized || !::browserViewModel.isInitialized) {
            return
        }
        val tabs = browserViewModel.tabs.get().orEmpty()
        val selectedId = tabs.getOrNull(browserViewModel.currentTab.get())?.id
        drawerAdapter.setSelectedTabId(selectedId)
    }

    private fun handleOpenNavDrawerEvent() {
        mainViewModel.openNavDrawerEvent.observe(viewLifecycleOwner) {
            val isOpened = dataBinding.drawerLayout.isDrawerOpen(GravityCompat.START)
            if (isOpened) {
                dataBinding.drawerLayout.close()
            } else {
                captureCurrentTabThumbnail()
                syncBrowserTabsUi()
                dataBinding.drawerLayout.open()
            }
        }
    }

    private fun checkIsPowerSaveMode() {
        val context = this.requireContext()
        val pwManager = getSystemService(context, PowerManager::class.java)
        if (pwManager?.isPowerSaveMode == true) {
            MaterialAlertDialogBuilder(context).setTitle(R.string.warning)
                .setMessage(R.string.powerSave).setPositiveButton(
                    R.string.ok
                ) { dialog, _ ->
                    dialog.dismiss()
                }.show()
        }
    }

    private fun onBackPressed() {
        if (::dataBinding.isInitialized &&
            dataBinding.drawerLayout.isDrawerOpen(GravityCompat.START)
        ) {
            dataBinding.drawerLayout.closeDrawer(GravityCompat.START)
            return
        }

        val rootPagerIndex = mainActivity.mainViewModel.currentItem.get() ?: 0
        if (rootPagerIndex > 0) {
            mainActivity.mainViewModel.currentItem.set(HOME_TAB_INDEX)
            return
        }

        val tabs = browserViewModel.tabs.get().orEmpty()
        val currentIndex = browserViewModel.currentTab.get()
        val currentTab = tabs.getOrNull(currentIndex)
        if (currentIndex > HOME_TAB_INDEX && currentTab != null) {
            val webView = currentTab.getWebView()
            if (webView?.canGoBack() == true) {
                val history = runCatching { webView.copyBackForwardList() }.getOrNull()
                AppLogger.d(
                    "BROWSER_BACK_FALLBACK: tab=${currentTab.id} entries=${history?.size ?: 0} " +
                        "index=${history?.currentIndex ?: -1}"
                )
                webView.goBack()
            } else {
                closeWebTab(currentTab)
            }
            return
        }

        if (backPressedOnce) {
            requireActivity().finish()
            return
        }

        backPressedOnce = true
        Toast.makeText(requireContext(), R.string.press_back_again_to_exit, Toast.LENGTH_SHORT).show()

        Handler(Looper.getMainLooper()).postDelayed({
            backPressedOnce = false
        }, 2000)
    }

    private val onGoThroughListener = object : OnGoThroughListener {
        override fun onRightGoThrough() {
            val currentTabIndex = browserViewModel.currentTab.get()
            if (currentTabIndex == 0) {
                mainViewModel.currentItem.set((mainViewModel.currentItem.get() ?: 0) + 1)
            }
        }
    }
}
