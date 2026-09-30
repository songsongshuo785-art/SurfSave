package com.myAllVideoBrowser.ui.main.home.browser.detectedVideos

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.myAllVideoBrowser.util.ContextUtils
import java.net.URI
import java.util.Locale

/**
 * Data-driven site exceptions for the generic media detector.
 * Site-specific thresholds belong in assets, while the detector remains generic.
 */
internal object SiteDetectionRules {
    private const val ASSET = "detection/site_rules.json"

    private data class RuleSet(
        @SerializedName("rules") val rules: List<Rule> = emptyList()
    )

    data class Rule(
        @SerializedName("hosts") val hosts: List<String> = emptyList(),
        @SerializedName("minimumContentLength") val minimumContentLength: Long = 0L
    )

    @Volatile
    private var cachedRules: List<Rule>? = null

    fun forUrl(url: String): Rule? {
        val host = runCatching { URI(url).host?.lowercase(Locale.US).orEmpty() }
            .getOrDefault("")
        if (host.isBlank()) return null
        return load().firstOrNull { rule ->
            rule.hosts.any { configured ->
                val normalized = configured.trim().lowercase(Locale.US).removePrefix("www.")
                host == normalized || host.endsWith(".$normalized")
            }
        }
    }

    private fun load(): List<Rule> {
        cachedRules?.let { return it }
        val loaded = runCatching {
            ContextUtils.getApplicationContext().assets.open(ASSET).bufferedReader(Charsets.UTF_8).use {
                Gson().fromJson(it, RuleSet::class.java)?.rules.orEmpty()
            }
        }.getOrDefault(emptyList())
        cachedRules = loaded
        return loaded
    }
}
