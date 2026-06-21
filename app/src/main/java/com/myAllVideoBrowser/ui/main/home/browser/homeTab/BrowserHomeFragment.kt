package com.myAllVideoBrowser.ui.main.home.browser.homeTab

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.addCallback
import androidx.databinding.Observable
import androidx.recyclerview.widget.GridLayoutManager
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.data.local.room.entity.PageInfo
import com.myAllVideoBrowser.data.local.model.Suggestion
import com.myAllVideoBrowser.databinding.FragmentBrowserHomeBinding
import com.myAllVideoBrowser.ui.component.adapter.SuggestionAdapter
import com.myAllVideoBrowser.ui.component.adapter.SuggestionListener
import com.myAllVideoBrowser.ui.component.adapter.TopPageAdapter
import com.myAllVideoBrowser.ui.main.bookmarks.BookmarkEditor
import com.myAllVideoBrowser.ui.main.bookmarks.HomeBookmarkPicker
import com.myAllVideoBrowser.ui.main.home.MainViewModel
import com.myAllVideoBrowser.ui.main.home.browser.BaseWebTabFragment
import com.myAllVideoBrowser.ui.main.home.browser.BrowserServicesProvider
import com.myAllVideoBrowser.ui.main.home.browser.BrowserListener
import com.myAllVideoBrowser.ui.main.home.browser.MAX_WEB_TABS
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.ui.main.home.browser.webTab.WebTabFactory
import com.myAllVideoBrowser.util.AppUtil
import com.myAllVideoBrowser.util.UrlInputNormalizer
import java.util.Locale
import kotlinx.coroutines.launch
import javax.inject.Inject

interface BrowserHomeListener : BrowserListener {

    override fun onHomeClicked() {
    }

    override fun onBrowserGoClicked() {
    }

    override fun onBrowserReloadClicked() {
    }

    override fun onTabsOverviewClicked() {
    }

    override fun onTabCloseClicked() {
    }

    override fun onBrowserStopClicked() {
    }

    override fun onBrowserBackClicked() {
    }

    override fun onBrowserForwardClicked() {
    }
}

class BrowserHomeFragment : BaseWebTabFragment() {

    companion object {
        private const val HOME_BOOKMARK_LIMIT = 4

        fun newInstance() = BrowserHomeFragment()
    }

    @Inject
    lateinit var viewModelFactory: ViewModelProvider.Factory

    @Inject
    lateinit var appUtil: AppUtil

    lateinit var binding: FragmentBrowserHomeBinding

    private lateinit var browserServicesProvider: BrowserServicesProvider

    private lateinit var homeViewModel: BrowserHomeViewModel

    private lateinit var mainViewModel: MainViewModel

    private lateinit var suggestionAdapter: SuggestionAdapter

    private lateinit var homeSitesAdapter: TopPageAdapter

    private val tabsChangeListener = object : Observable.OnPropertyChangedCallback() {
        override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
            updateHomeTabsButtonContentDescription()
        }
    }

    private val bookmarksChangeListener = object : Observable.OnPropertyChangedCallback() {
        override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
            updateHomeSites()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        mainViewModel = mainActivity.mainViewModel
        homeViewModel = ViewModelProvider(this, viewModelFactory)[BrowserHomeViewModel::class.java]
        browserServicesProvider = mainActivity.mainViewModel.browserServicesProvider!!
        browserServicesProvider.getTabsListChangeEvent().addOnPropertyChangedCallback(tabsChangeListener)
        mainViewModel.bookmarksList.addOnPropertyChangedCallback(bookmarksChangeListener)

        suggestionAdapter = SuggestionAdapter(requireContext(), emptyList(), suggestionListener)
        homeSitesAdapter = TopPageAdapter(emptyList(), topPagesListener)

        binding = FragmentBrowserHomeBinding.inflate(inflater, container, false).apply {
            buildWebTabMenu(this.browserHomeMenuButton, true)

            this.viewModel = homeViewModel
            this.mainVModel = mainViewModel
            this.browserMenuListener = menuListener

            this.homeEtSearch.setAdapter(suggestionAdapter)
            this.homeCommonSitesGrid.layoutManager = GridLayoutManager(requireContext(), 2)
            this.homeCommonSitesGrid.adapter = homeSitesAdapter
            this.homeCommonSitesGrid.isNestedScrollingEnabled = false
            this.homeEtSearch.addTextChangedListener(onInputHomeSearchChangeListener)
            this.homeEtSearch.imeOptions = EditorInfo.IME_ACTION_DONE
            this.homeEtSearch.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    this.homeEtSearch.clearFocus()
                    viewModel?.viewModelScope?.launch {
                        val inputText = (this@apply.homeEtSearch as EditText).text.toString()
                        this@apply.homeEtSearch.text.clear()
                        openNewTab(inputText)
                    }
                    false
                } else false
            }
            this.homeTabsCard.setOnClickListener {
                openTabsOverview()
            }
            this.homePasteLinkButton.setOnClickListener {
                openClipboardLink()
            }
            this.homeHowItWorksButton.setOnClickListener {
                showSocialGuide()
            }
            this.homeSocialGuideDismiss.setOnClickListener {
                sharedPrefHelper.setHomeSocialGuideDismissed(true)
                this.homeSocialGuideCard.visibility = View.GONE
            }
            this.homeAddBookmarkCard.setOnClickListener {
                showAddBookmarkOptions()
            }
            this.homeSocialGuideCard.visibility =
                if (sharedPrefHelper.isHomeSocialGuideDismissed()) View.GONE else View.VISIBLE
            updateHomeTabsButtonContentDescription()
            updateHomeSites()
        }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            if (parentFragmentManager.backStackEntryCount > 0) {
                parentFragmentManager.popBackStack()
            } else {
                requireActivity().finish()
            }
        }

        return binding.root
    }

    override fun onDestroyView() {
        if (::browserServicesProvider.isInitialized) {
            browserServicesProvider.getTabsListChangeEvent().removeOnPropertyChangedCallback(tabsChangeListener)
        }
        if (::mainViewModel.isInitialized) {
            mainViewModel.bookmarksList.removeOnPropertyChangedCallback(bookmarksChangeListener)
        }
        super.onDestroyView()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        handleFirstStartGuide()

        homeViewModel.start()
        val openingUrl = mainViewModel.openedUrl.get()
        val openingText = mainViewModel.openedText.get()

        if (openingUrl != null) {
            openNewTab(openingUrl)
            mainViewModel.openedUrl.set(null)
        }

        if (openingText != null) {
            openNewTab(openingText)
            mainViewModel.openedText.set(null)
        }
    }

    // Bug fix for not updating home page grid after adding new bookmark
    override fun onResume() {
        super.onResume()
        updateHomeTabsButtonContentDescription()
    }

    private val suggestionListener = object : SuggestionListener {
        override fun onItemClicked(suggestion: Suggestion) {
            openNewTab(suggestion.content)
        }
    }

    private fun openNewTab(input: String) {
        if (input.isNotEmpty()) {
            browserServicesProvider.getOpenTabEvent().value =
                WebTabFactory.createWebTabFromInput(
                    input,
                    searchUrlPattern = sharedPrefHelper.getSearchUrlPattern()
                )
        }
    }

    private fun openClipboardLink() {
        val clipboard =
            requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clipboardText = clipboard?.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(requireContext())
            ?.toString()
            ?.trim()
            .orEmpty()
        val input = UrlInputNormalizer.extractBestInput(clipboardText)

        if (input.isNullOrBlank() || !UrlInputNormalizer.isProbablyWebAddress(input)) {
            AppLogger.d("Clipboard does not contain a web link: '$clipboardText'")
            homeViewModel.changeSearchFocus(true)
            binding.homeEtSearch.requestFocus()
            appUtil.showSoftKeyboard(binding.homeEtSearch)
            Toast.makeText(
                requireContext(),
                getString(R.string.home_paste_link_empty),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        binding.homeEtSearch.text?.clear()
        openNewTab(input)
    }

    private fun showSocialGuide() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.home_social_guide_title)
            .setMessage(getString(R.string.home_social_guide_message, getString(R.string.app_name)))
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private val onInputHomeSearchChangeListener = object : TextWatcher {
        override fun afterTextChanged(s: Editable) {
            val input = s.toString()
            homeViewModel.searchTextInput.set(input)
            if (!(input.startsWith("http://") || input.startsWith("https://"))) {
                homeViewModel.showSuggestions()
            }
            homeViewModel.homePublishSubject.onNext(input)
        }

        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
        }

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
        }
    }

    private val menuListener = object : BrowserHomeListener {
        override fun onBrowserMenuClicked() {
            showPopupMenu()
        }

        override fun onTabsOverviewClicked() {
            openTabsOverview()
        }

        override fun onBrowserBackClicked() {
            if (parentFragmentManager.backStackEntryCount > 0) {
                parentFragmentManager.popBackStack()
            } else {
                requireActivity().finish()
            }
        }
    }

    private fun handleFirstStartGuide() {
        if (mainActivity.sharedPrefHelper.getIsFirstStart()) {
            mainActivity.settingsViewModel.setIsFirstStart(false)
            navigateToHelp()
        }
    }

    override fun shareWebLink() {}

    override fun bookmarkCurrentUrl() {}

    private fun showAddBookmarkOptions() {
        val items = arrayOf(
            getString(R.string.home_add_bookmark_manually),
            getString(R.string.home_add_bookmark_from_saved)
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.add_bookmark)
            .setItems(items) { dialog, which ->
                when (which) {
                    0 -> showBookmarkEditor(null)
                    1 -> showSavedBookmarksPicker()
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.all_text_cancel, null)
            .show()
    }

    private fun showBookmarkEditor(bookmark: PageInfo?) {
        BookmarkEditor.show(
            context = requireContext(),
            bookmark = bookmark,
            currentBookmarks = {
                mainViewModel.bookmarksList.get().orEmpty()
            },
            onSaved = { updated ->
                mainViewModel.updateBookmarks(updated)
            }
        )
    }

    private fun showSavedBookmarksPicker() {
        val bookmarks = mainViewModel.bookmarksList.get().orEmpty()
        if (bookmarks.isEmpty()) {
            Toast.makeText(
                requireContext(),
                getString(R.string.home_add_bookmark_no_saved),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        HomeBookmarkPicker.show(
            context = requireContext(),
            bookmarks = bookmarks,
            isOnHome = ::isBookmarkOnHome,
            onSelected = ::promoteBookmarkToHome
        )
    }

    private fun isBookmarkOnHome(bookmark: PageInfo): Boolean {
        return mainViewModel.bookmarksList.get()
            .orEmpty()
            .sortedBy { it.order }
            .take(HOME_BOOKMARK_LIMIT)
            .any { it.link == bookmark.link }
    }

    private fun promoteBookmarkToHome(bookmark: PageInfo) {
        val current = mainViewModel.bookmarksList.get()
            .orEmpty()
            .sortedBy { it.order }
            .toMutableList()
        val currentIndex = current.indexOfFirst { it.link == bookmark.link }
        if (currentIndex == -1) {
            return
        }
        if (currentIndex < HOME_BOOKMARK_LIMIT) {
            Toast.makeText(
                requireContext(),
                getString(R.string.home_add_bookmark_already_on_home),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val movedBookmark = current.removeAt(currentIndex)
        val insertIndex = minOf(HOME_BOOKMARK_LIMIT - 1, current.size)
        current.add(insertIndex, movedBookmark)
        mainViewModel.updateBookmarks(current)
        Toast.makeText(
            requireContext(),
            getString(R.string.home_add_bookmark_added_to_home),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun updateHomeSites() {
        if (!::homeSitesAdapter.isInitialized) {
            return
        }

        val homeSites = mainViewModel.bookmarksList.get()
            .orEmpty()
            .sortedBy { it.order }
            .take(HOME_BOOKMARK_LIMIT)
        homeSitesAdapter.setData(homeSites)
    }

    private val topPagesListener = object : TopPageAdapter.TopPagesListener {
        override fun onItemClicked(pageInfo: PageInfo) {
            openNewTab(pageInfo.link)
        }

        override fun onItemLongClicked(view: View, pageInfo: PageInfo): Boolean {
            PopupMenu(requireContext(), view).apply {
                menu.add(R.string.open)
                menu.add(R.string.edit_bookmark)
                menu.add(R.string.delete_bookmark)
                setOnMenuItemClickListener { item ->
                    when (item.title.toString()) {
                        getString(R.string.open) -> onItemClicked(pageInfo)
                        getString(R.string.edit_bookmark) -> showBookmarkEditor(pageInfo)
                        getString(R.string.delete_bookmark) -> confirmDeleteBookmark(pageInfo)
                    }
                    true
                }
                show()
            }
            return true
        }
    }

    private fun confirmDeleteBookmark(bookmark: PageInfo) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_bookmark)
            .setMessage(bookmark.name.ifBlank { bookmark.link })
            .setPositiveButton(R.string.remove) { dialog, _ ->
                val current = mainViewModel.bookmarksList.get()?.toMutableList() ?: mutableListOf()
                val index = current.indexOfFirst { it.link == bookmark.link }
                if (index >= 0) {
                    current.removeAt(index)
                    mainViewModel.updateBookmarks(current)
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.all_text_cancel) { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun updateHomeTabsButtonContentDescription() {
        if (!::binding.isInitialized || !::browserServicesProvider.isInitialized) {
            return
        }

        val tabsCount = browserServicesProvider.getTabsListChangeEvent().get()
            .orEmpty()
            .count { !it.isHome() }
            .coerceIn(0, MAX_WEB_TABS)
        binding.browserHomeTabsCountBadge.text = String.format(Locale.getDefault(), "%d", tabsCount)
        binding.browserHomeTabsButton.contentDescription = getString(
            R.string.tabs_button_content_description,
            tabsCount
        )
        binding.homeTabsCountText.text = getString(
            R.string.tabs_subtitle_with_count,
            tabsCount,
            MAX_WEB_TABS
        )
    }
}
