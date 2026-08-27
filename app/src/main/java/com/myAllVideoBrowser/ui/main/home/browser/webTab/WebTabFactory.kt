package com.myAllVideoBrowser.ui.main.home.browser.webTab

import com.myAllVideoBrowser.util.UrlInputNormalizer
import java.util.Locale

class WebTabFactory {
    companion object {
        fun createWebTabFromInput(
            input: String,
            locale: Locale = Locale.getDefault(),
            searchUrlPattern: String = UrlInputNormalizer.defaultSearchUrlPattern(locale),
            initialTitle: String? = null,
            navigationPurpose: WebTabNavigationPurpose = WebTabNavigationPurpose.NORMAL_BROWSE
        ): WebTab {
            if (input.isNotBlank()) {
                return WebTab(
                    UrlInputNormalizer.toLoadableUrlOrSearch(input, searchUrlPattern),
                    initialTitle,
                    null,
                    null,
                    null,
                    emptyMap(),
                    navigationPurpose = navigationPurpose
                )
            }

            return WebTab.HOME_TAB
        }
    }
}
