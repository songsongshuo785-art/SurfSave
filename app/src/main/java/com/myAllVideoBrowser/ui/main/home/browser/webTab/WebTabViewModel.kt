package com.myAllVideoBrowser.ui.main.home.browser.webTab

import android.webkit.WebView
import androidx.databinding.Observable
import androidx.databinding.ObservableBoolean
import androidx.databinding.ObservableField
import androidx.databinding.ObservableInt
import androidx.lifecycle.viewModelScope
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.data.local.room.entity.HistoryItem
import com.myAllVideoBrowser.data.repository.HistoryRepository
import com.myAllVideoBrowser.ui.main.home.browser.MAX_WEB_TABS
import com.myAllVideoBrowser.ui.main.base.BaseViewModel
import com.myAllVideoBrowser.util.SharedPrefHelper
import com.myAllVideoBrowser.util.SingleLiveEvent
import com.myAllVideoBrowser.util.UrlInputNormalizer
import com.myAllVideoBrowser.util.scheduler.BaseSchedulers
import io.reactivex.rxjava3.core.BackpressureStrategy
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.subjects.PublishSubject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class WebTabViewModel @Inject constructor(
    private val historyRepository: HistoryRepository,
    private val baseSchedulers: BaseSchedulers,
    private val sharedPrefHelper: SharedPrefHelper,
) : BaseViewModel() {
    val isTabInputFocused = ObservableBoolean(false)
    val changeTabFocusEvent = SingleLiveEvent<Boolean>()
    val thisTabIndex = ObservableInt(-1)
    val isDownloadDialogShown = ObservableBoolean(false)
    val tabPublishSubject: PublishSubject<String> = PublishSubject.create()
    var listTabSuggestions: ObservableField<MutableList<HistoryItem>> = ObservableField(
        mutableListOf()
    )
    val isShowProgress = ObservableBoolean(true)
    val progress = ObservableInt(0)
    val progressIcon = ObservableInt(R.drawable.refresh_24px)

    val currentTitle = ObservableField("")
    private val tabBrowseText = ObservableField("")
    val tabDisplayText = ObservableField("")
    val tabsBadgeText = ObservableField("0")
    var userAgent = ObservableField("")

    // This events from BrowserFragment
    lateinit var openPageEvent: SingleLiveEvent<WebTab>
    lateinit var openBackgroundPageEvent: SingleLiveEvent<WebTab>
    lateinit var closePageEvent: SingleLiveEvent<WebTab>

    val loadPageEvent = SingleLiveEvent<WebTab>()

    /**
     * 地址栏三态：
     *  - [currentPageUrl] 只由网页导航写入；
     *  - [addressDraft]   只由用户编辑写入；
     *  - [tabBrowseText]  非编辑态显示用的「标题 · host」。
     * [tabDisplayText] 是唯一对外 observable 输出，由 [refreshAddressText] 统一刷新。
     */
    private val currentPageUrl = ObservableField("")
    private val addressDraft = ObservableField("")
    private var tabSuggestionJob: Job? = null

    private val showProgressCallBack = object : Observable.OnPropertyChangedCallback() {
        override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
            updateProgressIcon()
        }
    }

    private val tabFocusCallBack = object : Observable.OnPropertyChangedCallback() {
        override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
            updateProgressIcon()
        }
    }

    override fun start() {
        isShowProgress.addOnPropertyChangedCallback(showProgressCallBack)
        isTabInputFocused.addOnPropertyChangedCallback(tabFocusCallBack)
        updateProgressIcon()
    }

    override fun stop() {
        isShowProgress.removeOnPropertyChangedCallback(showProgressCallBack)
        isTabInputFocused.removeOnPropertyChangedCallback(tabFocusCallBack)
    }

    fun finishPage(url: String) {
        runOnMain {
            currentPageUrl.set(url)
            isShowProgress.set(false)
            refreshBrowseText(url, currentTitle.get())
        }
    }

    fun onStartPage(url: String, title: String?) {
        runOnMain {
            currentPageUrl.set(url)
            isShowProgress.set(true)
            currentTitle.set(title)
            refreshBrowseText(url, title)
        }
    }

    fun onUpdateVisitedHistory(url: String, title: String?, userAgent: String?) {
        if (url.startsWith("http")) {
            runOnMain {
                currentPageUrl.set(url)
                isShowProgress.set(true)
                refreshBrowseText(url, title ?: currentTitle.get())
            }
        }
    }

    fun showTabSuggestions() {
        if (tabSuggestionJob != null && tabSuggestionJob?.isActive == true) {
            tabSuggestionJob?.cancel()
        }
        tabSuggestionJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val list = loadTabSuggestionsFlow().blockingFirst().reversed()
                val outputList = if (list.size > 50) {
                    list.subList(0, 50).toMutableList()
                } else {
                    list.toMutableList()
                }
                runOnMain {
                    listTabSuggestions.set(outputList)
                }
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
    }

    private fun loadTabSuggestionsFlow(): Flowable<List<HistoryItem>> {
        return Flowable.combineLatest(
            tabPublishSubject.debounce(300, TimeUnit.MILLISECONDS)
                .toFlowable(BackpressureStrategy.LATEST), historyRepository.getAllHistory().take(1)
        ) { input, suggestions ->
            addressDraft.set(input)

            val listSuggestions = suggestions.filter { historyItem ->
                historyItem.url.contains(
                    input
                )
            }
            listSuggestions.toList()
        }.take(1).observeOn(baseSchedulers.single)
            .subscribeOn(baseSchedulers.computation) // MAIN_TH
    }

    fun changeTabFocus(isFocus: Boolean) {
        runOnMain {
            if (isFocus && !this.isTabInputFocused.get()) {
                // 进入编辑态时把当前页面 URL 灌入草稿；后续网页导航不会再覆盖草稿。
                addressDraft.set(currentPageUrl.get().orEmpty())
            }
            this.isTabInputFocused.set(isFocus)
            refreshAddressText()
            changeTabFocusEvent.value = isFocus
            updateProgressIcon()
        }
    }


    fun openPage(input: String) {
        if (input.isNotEmpty()) {
            runOnMain {
                changeTabFocus(false)
                openPageEvent.value = createWebTab(input)
            }
        }
    }

    fun openPageInBackground(input: String) {
        if (input.isNotEmpty()) {
            runOnMain {
                openBackgroundPageEvent.value = createWebTab(input)
            }
        }
    }

    fun loadPage(input: String) {
        if (input.isNotEmpty()) {
            runOnMain {
                changeTabFocus(false)
                val tab = createWebTab(input)
                setTabTextInput(tab.getUrl())
                refreshBrowseText(tab.getUrl(), tab.getTitle())

                loadPageEvent.value = tab
            }
        }
    }

    /** 用户编辑地址栏时写草稿，不直接改当前页面 URL。 */
    fun setTabTextInput(input: String?, isForce: Boolean = false) {
        if (input.isNullOrEmpty()) {
            return
        }

        runOnMain {
            if (!isForce && addressDraft.get() == input) {
                refreshAddressText()
                return@runOnMain
            }

            addressDraft.set(input)
            refreshAddressText()
        }
    }

    /** 只读兼容入口；返回当前页面 URL，不能作为可写状态使用。 */
    fun getTabTextInput(): ObservableField<String> = currentPageUrl

    fun getCurrentPageUrl(): ObservableField<String> = currentPageUrl

    fun getAddressDraft(): ObservableField<String> = addressDraft

    fun getTabBrowseText(): ObservableField<String> {
        return tabBrowseText
    }

    fun closeTab(webTab: WebTab) {
        runOnMain {
            closePageEvent.value = webTab
        }
    }

    fun onPageReload(urlLoader: WebView?) {
        runOnMain {
            changeTabFocus(false)
            isShowProgress.set(true)

            urlLoader?.reload()
        }
    }

    fun onPageStop(urlLoader: WebView?) {
        runOnMain {
            changeTabFocus(false)
            isShowProgress.set(false)
            urlLoader?.stopLoading()
        }
    }

    fun onGoBack(webView: WebView) {
        runOnMain {
            changeTabFocus(false)
            isShowProgress.set(true)
            webView.goBack()
        }
    }

    fun onGoForward(webView: WebView) {
        runOnMain {
            changeTabFocus(false)
            isShowProgress.set(true)
            webView.goForward()
        }
    }

    fun setProgress(newProgress: Int) {
        runOnMain {
            progress.set(newProgress)
        }
    }

    fun updateTabsBadgeText(openWebTabsCount: Int) {
        runOnMain {
            tabsBadgeText.set(openWebTabsCount.coerceIn(0, MAX_WEB_TABS).toString())
        }
    }

    fun refreshBrowseText(url: String? = currentPageUrl.get(), title: String? = currentTitle.get()) {
        val safeUrl = url.orEmpty()
        if (safeUrl.isBlank()) {
            return
        }

        val browseText = buildBrowseText(safeUrl, title)
        runOnMain {
            if (tabBrowseText.get() != browseText) {
                tabBrowseText.set(browseText)
            }
            refreshAddressText()
        }
    }

    private fun updateProgressIcon() {
        val icon = when {
            isTabInputFocused.get() -> R.drawable.arrow_forward24px
            isShowProgress.get() -> R.drawable.close_24px
            else -> R.drawable.refresh_24px
        }
        progressIcon.set(icon)
    }

    private fun buildBrowseText(url: String, title: String?): String {
        val hostText = UrlInputNormalizer.toDisplayHost(url)
        val normalizedTitle = title?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        val displayTitle = normalizedTitle.takeIf {
            it.isNotBlank() &&
                !it.equals(url, ignoreCase = true) &&
                !it.equals(hostText, ignoreCase = true) &&
                !UrlInputNormalizer.isProbablyWebAddress(it)
        }.orEmpty()

        val browseText = when {
            displayTitle.isNotBlank() && hostText.isNotBlank() -> "$displayTitle · $hostText"
            displayTitle.isNotBlank() -> displayTitle
            else -> hostText
        }

        return compactText(browseText, 72)
    }

    private fun compactText(value: String, maxLength: Int): String {
        val normalized = value.replace(Regex("\\s+"), " ").trim()
        if (normalized.length <= maxLength) {
            return normalized
        }

        return normalized.take((maxLength - 3).coerceAtLeast(0)).trimEnd() + "..."
    }

    /**
     * 唯一地址栏输出：编辑态用草稿，非编辑态用「标题 · host」。
     * 任何三态字段变化后都只能通过这里刷新 [tabDisplayText]。
     */
    private fun refreshAddressText() {
        val displayText = if (isTabInputFocused.get()) {
            addressDraft.get().orEmpty()
        } else {
            tabBrowseText.get().orEmpty().ifBlank {
                buildBrowseText(currentPageUrl.get().orEmpty(), currentTitle.get())
            }
        }

        if (tabDisplayText.get() != displayText) {
            tabDisplayText.set(displayText)
        }
    }

    private fun createWebTab(input: String): WebTab {
        return WebTabFactory.createWebTabFromInput(
            input,
            searchUrlPattern = sharedPrefHelper.getSearchUrlPattern()
        )
    }
}
