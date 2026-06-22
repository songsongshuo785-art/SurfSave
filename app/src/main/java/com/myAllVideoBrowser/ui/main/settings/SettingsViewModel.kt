package com.myAllVideoBrowser.ui.main.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.databinding.ObservableBoolean
import androidx.databinding.ObservableField
import androidx.databinding.ObservableInt
import androidx.lifecycle.viewModelScope
//import com.allVideoDownloaderXmaster.OpenForTesting
import com.myAllVideoBrowser.ui.main.base.BaseViewModel
import com.myAllVideoBrowser.util.FileUtil
import com.myAllVideoBrowser.util.CookieProfileStore
import com.myAllVideoBrowser.util.DownloadFilenameTemplate
import com.myAllVideoBrowser.util.SharedPrefHelper
import com.myAllVideoBrowser.util.SingleLiveEvent
import com.myAllVideoBrowser.util.YtdlpUpdateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.max

enum class StorageType {
    SD, HIDDEN, HIDDEN_SD
}

//@OpenForTesting
class SettingsViewModel @Inject constructor(
    private val sharedPrefHelper: SharedPrefHelper,
    private val cookieProfileStore: CookieProfileStore,
    private val ytdlpUpdateManager: YtdlpUpdateManager,
) :
    BaseViewModel() {
    val isDrmEnabled = ObservableBoolean(false)
    val regularThreadsCount = ObservableInt(1)
    val m3u8ThreadsCount = ObservableInt(4)
    val maxConcurrentDownloads = ObservableInt(2)
    val videoDetectionThreshold = ObservableInt(4 * 1024 * 1024)
    val storageType = ObservableField(StorageType.SD)

    val clearCookiesEvent = SingleLiveEvent<Void?>()
    val openVideoFolderEvent = SingleLiveEvent<Void?>()
    val openProxySettingsEvent = SingleLiveEvent<Void?>()
    val openMigrationCenterEvent = SingleLiveEvent<Void?>()
    val openCookieImportEvent = SingleLiveEvent<Void?>()
    val openCookieExportEvent = SingleLiveEvent<String>()
    val isDesktopMode = ObservableBoolean(false)
    val isDarkMode = ObservableBoolean(false)
    val isAutoPipEnabled = ObservableBoolean(false)
    val isAutoDarkMode = ObservableBoolean(true)
    val isLockPortrait = ObservableBoolean(false)
    val isCheckIfEveryRequestOnM3u8 = ObservableBoolean(true)
    val isCheckOnAudio = ObservableBoolean(true)
    val isForceStreamDownloading = ObservableBoolean(false)
    val isForceStreamDetection = ObservableBoolean(false)
    val isAlwaysRemuxRegularDownloads = ObservableBoolean(false)
    val isRemuxOnlyLiveRegularDownloads = ObservableBoolean(false)
    val isInterruptIntreceptedResources = ObservableBoolean(false)
    val isUseLegacyM3u8Detection = ObservableBoolean(false)
    val searchEngine = ObservableField(SharedPrefHelper.SearchEngine.BING)
    val filenameTemplate = ObservableField(DownloadFilenameTemplate.DEFAULT_TEMPLATE)
    val cookieProfileSummary = ObservableField("")
    val ytdlpVersion = ObservableField("")
    val ytdlpUpdateStatus = ObservableField("")
    val isYtdlpUpdating = ObservableBoolean(false)
    val isAutoTranslatePages = ObservableBoolean(false)
    val shortVideoFilterDurationSeconds = ObservableInt(40)
    private val isShowVideoActionButton = ObservableBoolean(true)
    private val isShowVideoAlert = ObservableBoolean(true)
    private val isCheckEveryRequestOnVideo = ObservableBoolean(true)
    private val isFindVideoByUrl = ObservableBoolean(true)
    val isFilterShortVideos = ObservableBoolean(true)

    override fun start() {
        viewModelScope.launch(Dispatchers.IO) {
            // 2. INITIALIZE ITS VALUE FROM SHARED PREFERENCES
            isUseLegacyM3u8Detection.set(sharedPrefHelper.getIsUseLegacyM3u8Detection())
            isAlwaysRemuxRegularDownloads.set(sharedPrefHelper.getIsProcessDownloadFfmpeg())
            isRemuxOnlyLiveRegularDownloads.set(sharedPrefHelper.getIsProcessOnlyLiveDownloadFfmpeg())
            isForceStreamDownloading.set(sharedPrefHelper.getIsForceStreamDownload())
            isForceStreamDetection.set(sharedPrefHelper.getIsForceStreamDetection())
            isInterruptIntreceptedResources.set(sharedPrefHelper.getIsInterruptInterceptedResources())
            isCheckIfEveryRequestOnM3u8.set(sharedPrefHelper.getIsCheckEveryOnM3u8())
            isDesktopMode.set(sharedPrefHelper.getIsDesktop())
            isShowVideoAlert.set(sharedPrefHelper.isShowVideoAlert())
            isShowVideoActionButton.set(sharedPrefHelper.isShowActionButton())
            isCheckEveryRequestOnVideo.set(sharedPrefHelper.isCheckEveryRequestOnVideo())
            isFindVideoByUrl.set(sharedPrefHelper.isFindVideoByUrl())
            isFilterShortVideos.set(sharedPrefHelper.isFilterShortVideos())
            shortVideoFilterDurationSeconds.set(sharedPrefHelper.getShortVideoFilterDurationSeconds())
            isAutoTranslatePages.set(sharedPrefHelper.isAutoTranslatePages())
            searchEngine.set(sharedPrefHelper.getSearchEngine())
            filenameTemplate.set(sharedPrefHelper.getDownloadFilenameTemplate())
            refreshCookieProfileSummary()
            refreshYtdlpState()
            isAutoDarkMode.set(sharedPrefHelper.isAutoTheme())
            val isDark = sharedPrefHelper.isDarkMode()
            setDarkMode(isDark)
            isDarkMode.set(isDark)
            isAutoPipEnabled.set(sharedPrefHelper.getIsAutoPipEnabled())
            regularThreadsCount.set(sharedPrefHelper.getRegularDownloaderThreadCount())
            m3u8ThreadsCount.set(sharedPrefHelper.getM3u8DownloaderThreadCount())
            maxConcurrentDownloads.set(sharedPrefHelper.getMaxConcurrentDownloads())
            isCheckOnAudio.set(sharedPrefHelper.getIsCheckOnAudio())
            videoDetectionThreshold.set(sharedPrefHelper.getVideoDetectionThreshold())
            isLockPortrait.set(sharedPrefHelper.getIsLockPortrait())
            isDrmEnabled.set(sharedPrefHelper.getIsDrmEnabled())
            if (sharedPrefHelper.getIsExternalUse() && !sharedPrefHelper.getIsAppDirUse()) {
                storageType.set(StorageType.SD)
            } else if (sharedPrefHelper.getIsAppDirUse() && sharedPrefHelper.getIsExternalUse()) {
                storageType.set(StorageType.HIDDEN_SD)
            } else {
                storageType.set(StorageType.HIDDEN)
            }
        }
    }

    override fun stop() {
    }

    fun setIsDrmEnabled(isEnabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            if (isDrmEnabled.get() != isEnabled) {
                isDrmEnabled.set(isEnabled)
                sharedPrefHelper.setIsDrmEnabled(isEnabled)
            }
        }
    }

    fun setSearchEngine(engine: SharedPrefHelper.SearchEngine) {
        viewModelScope.launch(Dispatchers.IO) {
            searchEngine.set(engine)
            sharedPrefHelper.setSearchEngine(engine)
        }
    }

    fun setFilenameTemplate(template: String) {
        viewModelScope.launch(Dispatchers.IO) {
            filenameTemplate.set(template)
            sharedPrefHelper.setDownloadFilenameTemplate(template)
        }
    }

    fun resetFilenameTemplate() {
        setFilenameTemplate(DownloadFilenameTemplate.DEFAULT_TEMPLATE)
    }

    fun requestCookieImport() {
        openCookieImportEvent.call()
    }

    fun requestCookieExport() {
        viewModelScope.launch(Dispatchers.IO) {
            val exported = cookieProfileStore.exportAllProfiles()
            runOnMain {
                openCookieExportEvent.value = exported
            }
        }
    }

    fun importCookieProfile(displayName: String, content: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                cookieProfileStore.importNetscapeProfile(displayName, content)
            }.onSuccess {
                refreshCookieProfileSummary()
            }.onFailure {
                cookieProfileSummary.set(it.message ?: "Cookie profile import failed")
            }
        }
    }

    fun updateYtdlpStable() {
        viewModelScope.launch(Dispatchers.IO) {
            if (isYtdlpUpdating.get()) {
                return@launch
            }
            isYtdlpUpdating.set(true)
            ytdlpUpdateStatus.set("Updating...")
            val state = ytdlpUpdateManager.updateStable()
            ytdlpVersion.set(state.versionName)
            ytdlpUpdateStatus.set(state.lastResult)
            isYtdlpUpdating.set(false)
        }
    }

    fun setUseLegacyM3u8Detection(isTurnedOn: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            isUseLegacyM3u8Detection.set(isTurnedOn)
            sharedPrefHelper.setIsUseLegacyM3u8Detection(isTurnedOn)
        }
    }

    fun setIsRemuxOnlyLiveRegularDownloads(isTurnedOn: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            isRemuxOnlyLiveRegularDownloads.set(isTurnedOn)
            sharedPrefHelper.setIsProcessOnlyLiveDownloadFfmpeg(isTurnedOn)
        }
    }

    fun setIsRemuxOnlyRegularDownloads(isTurnedOn: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            isAlwaysRemuxRegularDownloads.set(isTurnedOn)
            sharedPrefHelper.setIsProcessDownloadFfmpeg(isTurnedOn)
        }
    }


    fun setForceStreamDownloading(isTurnedOn: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            isForceStreamDownloading.set(isTurnedOn)
            sharedPrefHelper.setIsForceStreamDownload(isTurnedOn)
        }
    }

    fun setForceStreamDetection(isTurnedOn: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            isForceStreamDetection.set(isTurnedOn)
            sharedPrefHelper.setIsForceStreamDetection(isTurnedOn)
        }
    }

    fun setIsLockPortrait(isLock: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            isLockPortrait.set(isLock)
            sharedPrefHelper.setIsLockPortrait(isLock)
        }
    }

    fun clearCookies() {
        clearCookiesEvent.call()
    }

    fun setIsInterruptInterceptedResources(isTurnedOn: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            isInterruptIntreceptedResources.set(isTurnedOn)
            sharedPrefHelper.setIsInterruptInterceptedResources(isTurnedOn)
        }
    }


    fun openVideoFolder() {
        openVideoFolderEvent.call()
    }

    fun openProxySettings() {
        openProxySettingsEvent.call()
    }

    fun openMigrationCenter() {
        openMigrationCenterEvent.call()
    }

    private fun refreshCookieProfileSummary() {
        cookieProfileSummary.set(cookieProfileStore.summary())
    }

    private fun refreshYtdlpState() {
        val state = ytdlpUpdateManager.currentState()
        ytdlpVersion.set(state.versionName)
        ytdlpUpdateStatus.set(state.lastResult)
    }

    fun getIsFindVideoByUrl(): ObservableBoolean {
        return isFindVideoByUrl
    }

    fun setIsFindVideoByUrl(isFind: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            isFindVideoByUrl.set(isFind)
            sharedPrefHelper.saveIsFindByUrl(isFind)
        }
    }

    fun setIsFilterShortVideos(isFilter: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            isFilterShortVideos.set(isFilter)
            sharedPrefHelper.saveIsFilterShortVideos(isFilter)
        }
    }

    fun setShortVideoFilterDurationSeconds(seconds: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val value = seconds.coerceIn(5, 120)
            shortVideoFilterDurationSeconds.set(value)
            sharedPrefHelper.saveShortVideoFilterDurationSeconds(value)
        }
    }

    fun setIsAutoTranslatePages(isAutoTranslate: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            isAutoTranslatePages.set(isAutoTranslate)
            sharedPrefHelper.saveIsAutoTranslatePages(isAutoTranslate)
        }
    }

    fun setIsCheckIfEveryUrlOnM3u8(isCheck: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            isCheckIfEveryRequestOnM3u8.set(isCheck)
            sharedPrefHelper.saveIsCheckEveryOnM3u8(isCheck)
        }
    }

    fun setIsCheckOnAudio(isCheck: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            isCheckOnAudio.set(isCheck)
            sharedPrefHelper.saveIsCheckOnAudio(isCheck)
        }
    }

    fun setIsAutoTheme(isChecked: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            isAutoDarkMode.set(isChecked)
            sharedPrefHelper.setIsAutoTheme(isChecked)

            val isDark = sharedPrefHelper.isDarkMode()
            setIsDarkMode(isDark)
        }
    }

    fun setIsDarkMode(isDark: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            isDarkMode.set(isDark)
            delay(300)
            sharedPrefHelper.setIsDarkMode(isDark)
            setDarkMode(isDark)
        }
    }

    fun setIsAutoPipEnabled(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            isAutoPipEnabled.set(enabled)
            delay(300)
            sharedPrefHelper.setIsAutoPipEnabled(enabled)
        }
    }

    fun getIsCheckEveryRequestOnMp4Video(): ObservableBoolean {
        return isCheckEveryRequestOnVideo
    }

    fun setIsCheckEveryRequestOnVideo(isCheck: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            isCheckEveryRequestOnVideo.set(isCheck)
            sharedPrefHelper.saveIsCheck(isCheck)
        }
    }

    fun setIsDesktopMode(isDesktop: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            isDesktopMode.set(isDesktop)

            sharedPrefHelper.saveIsDesktop(isDesktop)
        }
    }

    fun getVideoAlertState(): ObservableBoolean {
        return isShowVideoAlert
    }

    fun getVideoButtonState(): ObservableBoolean {
        return isShowVideoActionButton
    }

    fun setShowVideoAlertOn() {
        viewModelScope.launch(Dispatchers.IO) {
            isShowVideoAlert.set(true)

            sharedPrefHelper.setIsShowVideoAlert(true)
        }
    }

    fun setShowVideoAlertOff() {
        viewModelScope.launch(Dispatchers.IO) {
            isShowVideoAlert.set(false)

            sharedPrefHelper.setIsShowVideoAlert(false)
        }
    }

    fun setShowVideoActionButtonOn() {
        viewModelScope.launch(Dispatchers.IO) {
            isShowVideoActionButton.set(true)

            sharedPrefHelper.setIsShowActionButton(true)
        }
    }

    fun setShowVideoActionButtonOff() {
        viewModelScope.launch(Dispatchers.IO) {
            isShowVideoActionButton.set(false)

            sharedPrefHelper.setIsShowActionButton(false)
        }
    }

    fun setIsFirstStart(isFirstStart: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            sharedPrefHelper.setIsFirstStart(isFirstStart)
        }
    }

    private fun setDarkMode(isDark: Boolean) {
        viewModelScope.launch {
            if (isDark) {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            } else {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            }
        }
    }

    fun setM3u8ThreadsCount(progress: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val finalCount = max(1, progress)
            m3u8ThreadsCount.set(finalCount)
            sharedPrefHelper.setM3u8DownloaderThreadCount(finalCount)
        }
    }

    fun setRegularThreadsCount(progress: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val finalCount = max(1, progress)
            regularThreadsCount.set(finalCount)
            sharedPrefHelper.setRegularDownloaderThreadCount(finalCount)
        }
    }

    fun setMaxConcurrentDownloads(progress: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val finalCount = progress.coerceIn(1, 5)
            maxConcurrentDownloads.set(finalCount)
            sharedPrefHelper.setMaxConcurrentDownloads(finalCount)
        }
    }

    fun setDownloadsFolderSdCard() {
        FileUtil.IS_APP_DATA_DIR_USE = false
        FileUtil.IS_EXTERNAL_STORAGE_USE = true

        viewModelScope.launch(Dispatchers.IO) {
            storageType.set(StorageType.SD)
            sharedPrefHelper.setIsExternalUse(true)
            sharedPrefHelper.setIsAppDirUse(false)
        }
    }

    fun setDownloadsFolderHidden() {
        FileUtil.IS_APP_DATA_DIR_USE = true
        FileUtil.IS_EXTERNAL_STORAGE_USE = false

        viewModelScope.launch(Dispatchers.IO) {
            storageType.set(StorageType.HIDDEN)
            sharedPrefHelper.setIsExternalUse(false)
            sharedPrefHelper.setIsAppDirUse(true)
        }
    }

    fun setDownloadsFolderHiddenSdCard() {
        FileUtil.IS_APP_DATA_DIR_USE = true
        FileUtil.IS_EXTERNAL_STORAGE_USE = true

        viewModelScope.launch(Dispatchers.IO) {
            storageType.set(StorageType.HIDDEN_SD)
            sharedPrefHelper.setIsExternalUse(true)
            sharedPrefHelper.setIsAppDirUse(true)
        }
    }

    fun setVideoDetectionThreshold(progress: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val finalResult = max(0, progress)
            videoDetectionThreshold.set(finalResult)
            sharedPrefHelper.setVideoDetectionThreshold(finalResult)
        }
    }
}
