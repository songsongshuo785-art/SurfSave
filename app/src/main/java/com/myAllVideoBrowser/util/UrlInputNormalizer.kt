package com.myAllVideoBrowser.util

import android.util.Patterns
import androidx.core.net.toUri
import java.net.URLEncoder
import java.util.Locale

object UrlInputNormalizer {
    private const val BAIDU_SEARCH_URL = "https://www.baidu.com/s?wd=%s"
    private const val BING_SEARCH_URL = "https://www.bing.com/search?q=%s"
    private const val DUCKDUCKGO_SEARCH_URL = "https://duckduckgo.com/?q=%s"
    private const val GOOGLE_SEARCH_URL = "https://www.google.com/search?q=%s"

    private val explicitUrlRegex = Regex("""(?i)\b((?:https?://|www\.)[^\s<>"']+)""")
    private val trailingPunctuation = setOf(
        '.', ',', ';', ':', '!', '?',
        ')', ']', '}',
        '。', '，', '；', '：', '！', '？',
        '）', '】', '》'
    )

    fun extractBestInput(rawInput: String?): String? {
        val raw = rawInput?.trim().orEmpty()
        if (raw.isBlank()) {
            return null
        }

        val explicitUrl = explicitUrlRegex.find(raw)?.value
        val webUrl = if (explicitUrl == null) {
            val matcher = Patterns.WEB_URL.matcher(raw)
            if (matcher.find()) matcher.group() else null
        } else {
            null
        }

        return cleanCandidate(explicitUrl ?: webUrl ?: raw).takeIf { it.isNotBlank() }
    }

    fun toLoadableUrlOrSearch(input: String): String {
        return toLoadableUrlOrSearch(input, defaultSearchUrlPattern())
    }

    fun toLoadableUrlOrSearch(input: String, searchUrlPattern: String): String {
        val cleaned = extractBestInput(input) ?: input.trim()
        return when {
            cleaned.startsWith("http://", ignoreCase = true) ||
                cleaned.startsWith("https://", ignoreCase = true) -> cleaned

            isProbablyWebAddress(cleaned) -> "https://$cleaned"

            else -> String.format(
                Locale.US,
                searchUrlPattern,
                URLEncoder.encode(cleaned, Charsets.UTF_8.name())
            )
        }
    }

    fun defaultSearchUrlPattern(locale: Locale = Locale.getDefault()): String {
        return BING_SEARCH_URL
    }

    fun searchUrlPatternForEngine(engine: String?): String {
        return when (engine?.trim()?.lowercase(Locale.US)) {
            "baidu" -> BAIDU_SEARCH_URL
            "duckduckgo" -> DUCKDUCKGO_SEARCH_URL
            "google" -> GOOGLE_SEARCH_URL
            else -> BING_SEARCH_URL
        }
    }

    fun isProbablyWebAddress(input: String): Boolean {
        val cleaned = cleanCandidate(input)
        if (cleaned.isBlank() || cleaned.any { it.isWhitespace() }) {
            return false
        }

        return cleaned.startsWith("http://", ignoreCase = true) ||
            cleaned.startsWith("https://", ignoreCase = true) ||
            Patterns.WEB_URL.matcher(cleaned).matches()
    }

    fun toDisplayHost(url: String): String {
        val cleaned = url.trim()
        if (cleaned.isBlank()) {
            return ""
        }

        return runCatching {
            val uri = cleaned.toUri()
            val host = uri.host?.removePrefix("www.").orEmpty()
            if (host.isBlank()) {
                cleaned
            } else {
                val port = uri.port.takeIf { it > 0 }?.let { ":$it" }.orEmpty()
                "$host$port"
            }
        }.getOrDefault(cleaned)
    }

    fun buildBrowseLabel(url: String, title: String?, maxLength: Int = 72): String {
        val normalizedTitle = title?.trim().orEmpty()
        val hostText = toDisplayHost(url)
        val label = when {
            normalizedTitle.isNotBlank() && hostText.isNotBlank() ->
                "$normalizedTitle · $hostText"

            normalizedTitle.isNotBlank() -> normalizedTitle
            else -> hostText
        }

        return compactText(label, maxLength)
    }

    private fun compactText(value: String, maxLength: Int): String {
        val normalized = value.replace(Regex("\\s+"), " ").trim()
        if (normalized.length <= maxLength) {
            return normalized
        }

        return normalized.take((maxLength - 3).coerceAtLeast(0)).trimEnd() + "..."
    }

    private fun cleanCandidate(candidate: String): String {
        var value = candidate.trim().trim('<', '>', '"', '\'')
        while (value.isNotEmpty() && trailingPunctuation.contains(value.last())) {
            value = value.dropLast(1).trimEnd()
        }
        return value
    }
}
