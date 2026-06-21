package com.myAllVideoBrowser.ui.main.home.browser.webTab

import com.myAllVideoBrowser.util.UrlInputNormalizer
import java.util.Locale

class WebTabFactory {
    companion object {
        fun createWebTabFromInput(
            input: String,
            locale: Locale = Locale.getDefault(),
            searchUrlPattern: String = UrlInputNormalizer.defaultSearchUrlPattern(locale)
        ): WebTab {
            if (input.isNotBlank()) {
                return WebTab(
                    UrlInputNormalizer.toLoadableUrlOrSearch(input, searchUrlPattern),
                    null,
                    null,
                    null,
                    null,
                    emptyMap()
                )
            }

            return WebTab.HOME_TAB
        }
    }
}
