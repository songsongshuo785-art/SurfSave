package com.myAllVideoBrowser.ui.main.home.browser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Toast
import androidx.core.app.ShareCompat
import androidx.core.content.pm.PackageInfoCompat
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.FragmentTransaction
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.databinding.SheetBrowserActionsBinding
import com.myAllVideoBrowser.ui.main.base.BaseFragment
import com.myAllVideoBrowser.ui.main.bookmarks.BookmarksFragment
import com.myAllVideoBrowser.ui.main.help.HelpFragment
import com.myAllVideoBrowser.ui.main.history.HistoryFragment
import com.myAllVideoBrowser.ui.main.home.MainActivity
import com.myAllVideoBrowser.ui.main.proxies.ProxiesFragment
import com.myAllVideoBrowser.ui.main.settings.SettingsFragment
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.SharedPrefHelper


abstract class BaseWebTabFragment : BaseFragment() {
    @Inject
    lateinit var mainActivity: MainActivity

    @Inject
    lateinit var sharedPrefHelper: SharedPrefHelper

    private var actionSheet: BottomSheetDialog? = null
    private var browserMenuAnchor: View? = null
    private var browserMenuIsHomeTab: Boolean = false

    override fun onDestroyView() {
        actionSheet?.dismiss()
        actionSheet = null

        super.onDestroyView()
    }

    abstract fun shareWebLink()

    abstract fun bookmarkCurrentUrl()

    open fun translateCurrentPage() {}

    open fun refreshVideoDetection() {}

    open fun repairPagePlayer() {}

    open fun openNewTabPage() {
        mainActivity.mainViewModel.currentItem.set(HOME_TAB_INDEX)
        mainActivity.mainViewModel.browserServicesProvider
            ?.getCurrentTabIndex()
            ?.set(HOME_TAB_INDEX)
    }

    open fun openHomePage() {
        openNewTabPage()
    }

    open fun canNavigateForwardInCurrentPage(): Boolean {
        return false
    }

    open fun navigateForwardInCurrentPage() {}

    open fun openTabsOverview() {
        mainActivity.mainViewModel.openNavDrawerEvent.call()
    }

    fun buildWebTabMenu(browserMenu: View, isHomeTab: Boolean) {
        browserMenuAnchor = browserMenu
        browserMenuIsHomeTab = isHomeTab
    }

    fun showPopupMenu() {
        showActionsSheet()
    }

    open fun buildBrowserDiagnosticsReport(): String {
        val packageInfo = runCatching {
            @Suppress("DEPRECATION")
            mainActivity.packageManager.getPackageInfo(mainActivity.packageName, 0)
        }.getOrNull()
        val versionName = packageInfo?.versionName?.takeIf { it.isNotBlank() } ?: "unknown"
        val versionCode = packageInfo?.let { PackageInfoCompat.getLongVersionCode(it).toString() }
            ?: "unknown"
        val webViewPackage = runCatching {
            WebViewCompat.getCurrentWebViewPackage(mainActivity)?.versionName
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "unknown"

        return buildString {
            appendLine("App: ${getString(R.string.app_name)} $versionName ($versionCode)")
            appendLine("Fragment: ${javaClass.simpleName}")
            appendLine("Mode: ${if (browserMenuIsHomeTab) "home" else "web"}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("WebView: $webViewPackage")
            appendLine("Desktop mode: ${mainActivity.settingsViewModel.isDesktopMode.get()}")
            appendLine("Dark mode: ${mainActivity.settingsViewModel.isDarkMode.get()}")
            appendLine("Auto translate: ${mainActivity.settingsViewModel.isAutoTranslatePages.get()}")
            appendLine("Proxy enabled: ${sharedPrefHelper.getIsProxyOn() || sharedPrefHelper.getIsDohOn()}")
        }
    }

    open fun setIsDesktop(isDesktop: Boolean) {
        mainActivity.settingsViewModel.setIsDesktopMode(isDesktop)

        val text = if (isDesktop) {
            requireContext().getString(R.string.desktop_mode_on)
        } else {
            requireContext().getString(R.string.desktop_mode_off)
        }

        Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show()
    }

    private fun showActionsSheet() {
        val sheet = BottomSheetDialog(requireContext())
        val binding = SheetBrowserActionsBinding.inflate(layoutInflater)
        sheet.setContentView(binding.root)
        actionSheet = sheet

        val pageActionVisibility = if (browserMenuIsHomeTab) View.GONE else View.VISIBLE
        binding.actionBookmark.visibility = pageActionVisibility
        binding.actionShare.visibility = pageActionVisibility
        binding.actionTranslate.visibility = pageActionVisibility
        binding.actionRefreshVideoDetection.visibility = pageActionVisibility
        binding.actionRepairPagePlayer.visibility = pageActionVisibility
        binding.actionForward.isEnabled = !browserMenuIsHomeTab && canNavigateForwardInCurrentPage()
        binding.actionForward.alpha = if (binding.actionForward.isEnabled) 1f else 0.45f

        binding.actionDark.isEnabled = !mainActivity.settingsViewModel.isAutoDarkMode.get()
        binding.actionDark.alpha = if (binding.actionDark.isEnabled) 1f else 0.45f

        fun runAndDismiss(action: () -> Unit) {
            browserMenuAnchor?.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            sheet.dismiss()
            action()
        }

        binding.actionNewTab.setOnClickListener { runAndDismiss { openNewTabPage() } }
        binding.actionTabs.setOnClickListener { runAndDismiss { openTabsOverview() } }
        binding.actionHome.setOnClickListener { runAndDismiss { openHomePage() } }
        binding.actionForward.setOnClickListener { runAndDismiss { navigateForwardInCurrentPage() } }
        binding.actionTestPages.setOnClickListener { runAndDismiss { showBrowserTestPages() } }
        binding.actionBookmark.setOnClickListener { runAndDismiss { bookmarkCurrentUrl() } }
        binding.actionShare.setOnClickListener { runAndDismiss { shareWebLink() } }
        binding.actionTranslate.setOnClickListener { runAndDismiss { translateCurrentPage() } }
        binding.actionRefreshVideoDetection.setOnClickListener { runAndDismiss { refreshVideoDetection() } }
        binding.actionRepairPagePlayer.setOnClickListener { runAndDismiss { repairPagePlayer() } }
        binding.actionHistory.setOnClickListener { runAndDismiss { navigateToHistory() } }
        binding.actionBookmarks.setOnClickListener { runAndDismiss { navigateToBookMarks() } }
        binding.actionDesktop.setOnClickListener {
            runAndDismiss {
                setIsDesktop(!mainActivity.settingsViewModel.isDesktopMode.get())
            }
        }
        binding.actionSettings.setOnClickListener { runAndDismiss { navigateToSettings() } }
        binding.actionProxy.setOnClickListener { runAndDismiss { navigateToProxies() } }
        binding.actionDark.setOnClickListener {
            runAndDismiss {
                mainActivity.settingsViewModel.setIsDarkMode(!mainActivity.settingsViewModel.isDarkMode.get())
            }
        }
        binding.actionHelp.setOnClickListener { runAndDismiss { navigateToHelp() } }

        sheet.show()
    }

    protected fun navigateToHistory() {
        try {
            val currentFragment = this
            val activityFragmentContainer =
                currentFragment.activity?.findViewById<FragmentContainerView>(R.id.fragment_container_view)
            activityFragmentContainer?.let {
                val transaction =
                    currentFragment.requireActivity().supportFragmentManager.beginTransaction()
                transaction.add(it.id, HistoryFragment.newInstance())
                transaction.addToBackStack("history")
                transaction.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
                transaction.commit()
            }
        } catch (e: ClassCastException) {
            AppLogger.d("Can't get the fragment manager with this")
        }
    }

    private fun showBrowserDiagnostics() {
        val report = buildBrowserDiagnosticsReport()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.browser_diagnostics_title)
            .setMessage(report)
            .setPositiveButton(R.string.browser_diagnostics_copy) { _, _ ->
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clipboard?.setPrimaryClip(
                    ClipData.newPlainText(getString(R.string.browser_diagnostics_title), report)
                )
                Toast.makeText(
                    requireContext(),
                    R.string.browser_diagnostics_copied,
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNeutralButton(R.string.browser_diagnostics_share) { _, _ ->
                ShareCompat.IntentBuilder(mainActivity)
                    .setType("text/plain")
                    .setChooserTitle(getString(R.string.browser_diagnostics_share))
                    .setText(report)
                    .startChooser()
            }
            .setNegativeButton(R.string.all_text_cancel, null)
            .show()
    }

    protected fun showBrowserTestPages() {
        val pages = arrayOf(
            getString(R.string.browser_test_page_reddit),
            getString(R.string.browser_test_page_rule34)
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.browser_test_pages_title)
            .setItems(pages) { _, which ->
                when (which) {
                    0 -> openBrowserTestPage(REDDIT_TEST_PAGE_URL)
                    1 -> openBrowserTestPage(RULE34_TEST_PAGE_URL)
                }
            }
            .setNegativeButton(R.string.all_text_cancel, null)
            .show()
    }

    private fun openBrowserTestPage(url: String) {
        val provider = mainActivity.mainViewModel.browserServicesProvider
        if (provider == null) {
            Toast.makeText(requireContext(), R.string.browser_test_pages_title, Toast.LENGTH_SHORT).show()
            return
        }
        provider.openInCurrentTab(url)
    }

    fun shareLink(url: String?) {
        ShareCompat.IntentBuilder(mainActivity).setType("text/plain")
            .setChooserTitle(getString(R.string.share_link))
            .setText(url).startChooser()
    }

    protected fun navigateToSettings() {
        try {
            val currentFragment = this
            val activityFragmentContainer =
                currentFragment.activity?.findViewById<FragmentContainerView>(R.id.fragment_container_view)
            activityFragmentContainer?.let {
                val transaction =
                    currentFragment.requireActivity().supportFragmentManager.beginTransaction()
                transaction.add(it.id, SettingsFragment.newInstance())
                transaction.addToBackStack("settings")
                transaction.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
                transaction.commit()
            }
        } catch (e: ClassCastException) {
            AppLogger.d("Can't get the fragment manager with this")
        }
    }

    companion object {
        private const val REDDIT_TEST_PAGE_URL =
            "https://www.reddit.com/r/TikTokCringe/comments/1t4k03l/its_implied_right/"
        private const val RULE34_TEST_PAGE_URL =
            "https://rule34video.com/video/4346854/mercy-fucked-mercilessly-bbc/"
    }

    private fun navigateToProxies() {
        try {
            val currentFragment = this
            val activityFragmentContainer =
                currentFragment.activity?.findViewById<FragmentContainerView>(R.id.fragment_container_view)
            activityFragmentContainer?.let {
                val transaction =
                    currentFragment.requireActivity().supportFragmentManager.beginTransaction()
                transaction.add(it.id, ProxiesFragment.newInstance())
                transaction.addToBackStack("proxies")
                transaction.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
                transaction.commit()
            }
        } catch (e: ClassCastException) {
            AppLogger.d("Can't get the fragment manager with this")
        }
    }

    fun navigateToHelp() {
        try {
            val currentFragment = this
            val activityFragmentContainer =
                currentFragment.activity?.findViewById<FragmentContainerView>(R.id.fragment_container_view)
            activityFragmentContainer?.let {
                val transaction =
                    currentFragment.requireActivity().supportFragmentManager.beginTransaction()
                transaction.add(it.id, HelpFragment.newInstance())
                transaction.addToBackStack("help")
                transaction.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
                transaction.commit()
            }
        } catch (e: ClassCastException) {
            AppLogger.d("Can't get the fragment manager with this")
        }
    }

    protected fun navigateToBookMarks() {
        try {
            val currentFragment = this
            val activityFragmentContainer =
                currentFragment.activity?.findViewById<FragmentContainerView>(R.id.fragment_container_view)
            activityFragmentContainer?.let {
                val transaction =
                    currentFragment.requireActivity().supportFragmentManager.beginTransaction()
                transaction.add(it.id, BookmarksFragment.newInstance())
                transaction.addToBackStack("bookmarks")
                transaction.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
                transaction.commit()
            }
        } catch (e: ClassCastException) {
            AppLogger.d("Can't get the fragment manager with this")
        }
    }
}
