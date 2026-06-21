package com.myAllVideoBrowser.ui.main.home.browser

import androidx.databinding.*
import androidx.lifecycle.MutableLiveData
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.ui.main.base.BaseViewModel
import com.myAllVideoBrowser.ui.main.home.browser.webTab.WebTab
import com.myAllVideoBrowser.util.SharedPrefHelper
import com.myAllVideoBrowser.util.SingleLiveEvent
import com.myAllVideoBrowser.ui.main.settings.SettingsViewModel
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

abstract class DownloadButtonState

class DownloadButtonStateLoading : DownloadButtonState()

class DownloadButtonStateCanDownload(val info: VideoInfo?) : DownloadButtonState()
class DownloadButtonStateCanNotDownload : DownloadButtonState()
