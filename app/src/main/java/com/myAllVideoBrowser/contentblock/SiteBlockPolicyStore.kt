package com.myAllVideoBrowser.contentblock

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import com.myAllVideoBrowser.util.SharedPrefHelper
import java.net.URI
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

interface SiteBlockPolicy {
    fun isContentBlockingDisabled(pageUrl: String?): Boolean

    fun setContentBlockingDisabled(pageUrl: String, disabled: Boolean): Boolean

    fun isPopupBlockingAllowed(pageUrl: String?): Boolean

    fun setPopupBlockingAllowed(pageUrl: String, allowed: Boolean): Boolean
}

@Singleton
class SiteBlockPolicyStore @Inject constructor(
    application: Application
) : SiteBlockPolicy {
    private val preferences = application.getSharedPreferences(
        SharedPrefHelper.PREF_KEY,
        Context.MODE_PRIVATE
    )

    override fun isContentBlockingDisabled(pageUrl: String?): Boolean {
        val site = siteKey(pageUrl) ?: return false
        return preferences.getStringSet(DISABLED_SITES, emptySet()).orEmpty().contains(site)
    }

    override fun setContentBlockingDisabled(pageUrl: String, disabled: Boolean): Boolean {
        val site = siteKey(pageUrl) ?: return false
        updateSet(DISABLED_SITES, site, disabled)
        return true
    }

    override fun isPopupBlockingAllowed(pageUrl: String?): Boolean {
        val site = siteKey(pageUrl) ?: return false
        return preferences.getStringSet(POPUP_ALLOWED_SITES, emptySet()).orEmpty().contains(site)
    }

    override fun setPopupBlockingAllowed(pageUrl: String, allowed: Boolean): Boolean {
        val site = siteKey(pageUrl) ?: return false
        updateSet(POPUP_ALLOWED_SITES, site, allowed)
        return true
    }

    private fun updateSet(key: String, value: String, present: Boolean) {
        val updated = preferences.getStringSet(key, emptySet()).orEmpty().toMutableSet()
        if (present) updated += value else updated -= value
        preferences.edit { putStringSet(key, updated) }
    }

    companion object {
        private const val DISABLED_SITES = "CONTENT_BLOCK_DISABLED_SITES_V1"
        private const val POPUP_ALLOWED_SITES = "CONTENT_BLOCK_POPUP_ALLOWED_SITES_V1"

        internal fun siteKey(url: String?): String? {
            val uri = runCatching { URI(url?.trim().orEmpty()) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase(Locale.US)
            if (scheme != "http" && scheme != "https") return null
            return uri.host
                ?.lowercase(Locale.US)
                ?.trimEnd('.')
                ?.takeIf { it.isNotEmpty() && it.length <= 253 }
        }
    }
}
