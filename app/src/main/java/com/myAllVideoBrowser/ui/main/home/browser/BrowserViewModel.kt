package com.myAllVideoBrowser.ui.main.home.browser

import androidx.databinding.*
import androidx.lifecycle.MutableLiveData
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.ui.main.base.BaseViewModel
import com.myAllVideoBrowser.ui.main.home.browser.webTab.WebTab
import com.myAllVideoBrowser.util.SharedPrefHelper
import com.myAllVideoBrowser.util.SingleLiveEvent
import com.myAllVideoBrowser.ui.main.settings.SettingsViewModel
import com.myAllVideoBrowser.util.BrowserThumbnailStore
import javax.inject.Inject

//@OpenForTesting
class BrowserViewModel @Inject constructor(
    private val sharedPrefHelper: SharedPrefHelper
) : BaseViewModel() {

    var settingsModel: SettingsViewModel? = null

    val openPageEvent = SingleLiveEvent<WebTab>()

    val openBackgroundPageEvent = SingleLiveEvent<WebTab>()

    val closePageEvent = SingleLiveEvent<WebTab>()

    val selectWebTabEvent = SingleLiveEvent<WebTab>()

    val updateWebTabEvent = SingleLiveEvent<WebTab>()

    val workerM3u8MpdEvent = MutableLiveData<DownloadButtonState>()

    val workerMP4Event = MutableLiveData<DownloadButtonState>()

    val progress = ObservableInt(0)

    val tabs = ObservableField(listOf(WebTab.HOME_TAB))

    val tabsBadgeText = ObservableField("0")

    val currentTab = ObservableInt(HOME_TAB_INDEX)

    private val recentlyClosedTabs = ArrayDeque<ClosedTabSnapshot>()

    override fun start() {
        restoreSessionIfNeeded()
        updateTabsBadgeText(tabs.get().orEmpty().count { !it.isHome() })
    }

    override fun stop() {
    }

    fun persistSession() {
        sharedPrefHelper.saveBrowserSession(tabs.get().orEmpty(), currentTab.get())
    }

    fun updateTabsBadgeText(openWebTabsCount: Int) {
        tabsBadgeText.set(openWebTabsCount.coerceIn(0, MAX_WEB_TABS).toString())
    }

    internal fun addRecentlyClosedTab(snapshot: ClosedTabSnapshot): ClosedTabSnapshot? {
        recentlyClosedTabs.addFirst(snapshot)
        return if (recentlyClosedTabs.size > BrowserTabUndoPolicy.MAX_RECENTLY_CLOSED_TABS) {
            recentlyClosedTabs.removeLast()
        } else {
            null
        }
    }

    internal fun getRecentlyClosedTabs(): List<ClosedTabSnapshot> = recentlyClosedTabs.toList()

    internal fun takeRecentlyClosedTab(snapshotId: String): ClosedTabSnapshot? {
        val snapshot = recentlyClosedTabs.firstOrNull { it.id == snapshotId } ?: return null
        recentlyClosedTabs.remove(snapshot)
        return snapshot
    }

    internal fun clearRecentlyClosedTabs(): List<ClosedTabSnapshot> {
        val snapshots = recentlyClosedTabs.toList()
        recentlyClosedTabs.clear()
        return snapshots
    }

    override fun onCleared() {
        clearRecentlyClosedTabs().forEach { snapshot ->
            BrowserThumbnailStore.delete(snapshot.tab.getPageThumbnailPath())
            snapshot.tab.clearSavedState()
        }
        super.onCleared()
    }

    private fun restoreSessionIfNeeded() {
        val currentTabs = tabs.get().orEmpty()
        if (currentTabs.any { !it.isHome() }) {
            return
        }

        val restoredTabs = sharedPrefHelper.restoreBrowserSessionTabs()
        if (restoredTabs.isEmpty()) {
            return
        }

        val restoredList = listOf(WebTab.HOME_TAB) + restoredTabs
        tabs.set(restoredList)
        val restoredIndex = sharedPrefHelper.restoreBrowserSessionCurrentIndex()
            .coerceIn(HOME_TAB_INDEX, restoredList.lastIndex)
        currentTab.set(restoredIndex)
    }
}

internal data class ClosedTabSnapshot(
    val tab: WebTab,
    val originalIndex: Int,
    val wasSelected: Boolean,
    val id: String = java.util.UUID.randomUUID().toString()
)

abstract class DownloadButtonState

class DownloadButtonStateLoading : DownloadButtonState()

class DownloadButtonStateCanDownload(val info: VideoInfo?) : DownloadButtonState()
class DownloadButtonStateCanNotDownload : DownloadButtonState()
