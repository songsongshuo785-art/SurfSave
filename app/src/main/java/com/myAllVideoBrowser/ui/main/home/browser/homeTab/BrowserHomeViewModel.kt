package com.myAllVideoBrowser.ui.main.home.browser.homeTab

import androidx.databinding.ObservableBoolean
import androidx.databinding.ObservableField
import androidx.lifecycle.viewModelScope
import com.myAllVideoBrowser.data.local.model.Suggestion
import com.myAllVideoBrowser.ui.main.base.BaseViewModel
import com.myAllVideoBrowser.util.SuggestionsUtils
import com.myAllVideoBrowser.util.proxy_utils.OkHttpProxyClient
import com.myAllVideoBrowser.util.scheduler.BaseSchedulers
import io.reactivex.rxjava3.core.BackpressureStrategy
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.subjects.PublishSubject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class BrowserHomeViewModel @Inject constructor(
    private val okHttpClient: OkHttpProxyClient,
    private val baseSchedulers: BaseSchedulers,
) :
    BaseViewModel() {
    val isSearchInputFocused = ObservableBoolean(false)
    val searchTextInput = ObservableField("")
    val listSuggestions: ObservableField<MutableList<Suggestion>> = ObservableField(mutableListOf())

    val homePublishSubject: PublishSubject<String> = PublishSubject.create()

    private var suggestionJob: Job? = null

    override fun start() {
    }

    override fun stop() {

    }

    fun changeSearchFocus(isFocus: Boolean) {
        runOnMain {
            this.isSearchInputFocused.set(isFocus)
        }
    }

    fun showSuggestions() {
        if (suggestionJob != null && suggestionJob?.isActive == true) {
            suggestionJob?.cancel()
        }
        suggestionJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val list = loadSuggestionsFlow().blockingFirst()
                val outputList = if (list.size > 50) {
                    list.subList(0, 50).toMutableList()
                } else {
                    list.toMutableList()
                }
                runOnMain {
                    listSuggestions.set(outputList)
                }
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
    }

    private fun loadSuggestionsFlow(): Flowable<List<Suggestion>> {
        return Flowable.combineLatest(
            homePublishSubject.debounce(300, TimeUnit.MILLISECONDS)
                .toFlowable(BackpressureStrategy.LATEST), SuggestionsUtils.getSuggestions(
                okHttpClient.getProxyOkHttpClient(), searchTextInput.get() ?: ""
            )
        ) { _, suggestions ->
            val listSuggestions = mutableListOf<Suggestion>()
            listSuggestions.addAll(suggestions)
            listSuggestions.toList()
        }.onErrorReturn {
            emptyList()
        }.take(1).observeOn(baseSchedulers.single)
            .subscribeOn(baseSchedulers.computation)
    }
}
