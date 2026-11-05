package com.myAllVideoBrowser.util

import android.util.Patterns
import androidx.core.net.toUri
import java.net.URI
import java.net.URLEncoder
import java.util.Locale

object UrlInputNormalizer {
    private const val BAIDU_SEARCH_URL = "https://www.baidu.com/s?wd=%s"
    private const val BING_SEARCH_URL = "https://www.bing.com/search?q=%s"
    private const val DUCKDUCKGO_SEARCH_URL = "https://duckduckgo.com/?q=%s"
    private const val GOOGLE_SEARCH_URL = "https://www.google.com/search?q=%s"

    private val explicitUrlRegex = Regex("""(?i)\b((?:https?://|www\.)[^\s<>"']+)""")

    // 裸输入判定用。地址栏里的纯单词（hello/test/a）不能再被猜成域名，
    // 只有 localhost、IP 字面量和带合法后缀的域名才算网址。
    private val localhostRegex = Regex("""(?i)^localhost(?::\d+)?(?:[/?#]\S*)?$""")
    private val ipv6LiteralRegex = Regex("""^(?:\[[0-9a-fA-F:.]+\])(?::\d+)?(?:[/?#]\S*)?$""")
    private val bareHostRegex = Regex(
        """^(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?\.)+[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?(?::\d+)?(?:[/?#]\S*)?$"""
    )
    private val trailingPunctuation = setOf(
        '.', ',', ';', ':', '!', '?',
        ')', ']', '}',
        '。', '，', '；', '：', '！', '？',
        '）', '】', '》'
    )

    /**
     * 从任意文本中提取一个可打开的链接。
     * 用于剪贴板、分享文本和外部 Intent：`看看这个 https://example.com` → `https://example.com`。
     * 地址栏输入不要用这个方法，用 [parseOmniboxInput]。
     */
    fun extractUrlFromText(rawInput: String?): String? {
        val raw = rawInput?.trim().orEmpty()
        if (raw.isBlank()) {
            return null
        }

        val explicitUrl = explicitUrlRegex.find(raw)?.value
        val webUrl = if (explicitUrl == null) {
            val matcher = Patterns.WEB_URL?.matcher(raw)
            if (matcher?.find() == true) matcher.group() else null
        } else {
            null
        }

        return cleanCandidate(explicitUrl ?: webUrl ?: raw).takeIf { it.isNotBlank() }
    }

    @Deprecated(
        "Use extractUrlFromText for clipboard/share text",
        ReplaceWith("extractUrlFromText(rawInput)")
    )
    fun extractBestInput(rawInput: String?): String? = extractUrlFromText(rawInput)

    fun toLoadableUrlOrSearch(input: String): String {
        return parseOmniboxInput(input, defaultSearchUrlPattern())
    }

    fun toLoadableUrlOrSearch(input: String, searchUrlPattern: String): String {
        return parseOmniboxInput(input, searchUrlPattern)
    }

    /**
     * 地址栏/主页输入解析：只判断整段输入是网址还是搜索词，**不从整句中提取 URL**。
     * `看看这个 https://example.com` 在地址栏里按搜索处理；同样的文本从剪贴板/分享进入时
     * 走 [extractUrlFromText]，会提取出链接。
     */
    fun parseOmniboxInput(input: String, searchUrlPattern: String = defaultSearchUrlPattern()): String {
        val cleaned = cleanCandidate(input)
        if (cleaned.isBlank()) {
            return ""
        }
        return when {
            cleaned.startsWith("http://", ignoreCase = true) ||
                cleaned.startsWith("https://", ignoreCase = true) ->
                cleaned.takeIf(::hasValidAuthority) ?: buildSearchUrl(cleaned, searchUrlPattern)

            isProbablyWebAddress(cleaned) -> "https://$cleaned"

            else -> buildSearchUrl(cleaned, searchUrlPattern)
        }
    }

    private fun buildSearchUrl(input: String, searchUrlPattern: String): String {
        return String.format(
            Locale.US,
            searchUrlPattern,
            URLEncoder.encode(input, Charsets.UTF_8.name())
        )
    }

    fun defaultSearchUrlPattern(locale: Locale = Locale.getDefault()): String {
        return BAIDU_SEARCH_URL
    }

    fun searchUrlPatternForEngine(engine: String?): String {
        return when (engine?.trim()?.lowercase(Locale.US)) {
            "baidu" -> BAIDU_SEARCH_URL
            "bing" -> BING_SEARCH_URL
            "duckduckgo" -> DUCKDUCKGO_SEARCH_URL
            "google" -> GOOGLE_SEARCH_URL
            else -> BAIDU_SEARCH_URL
        }
    }

    fun isProbablyWebAddress(input: String): Boolean {
        val cleaned = cleanCandidate(input)
        if (cleaned.isBlank() || cleaned.any { it.isWhitespace() }) {
            return false
        }

        if (cleaned.startsWith("http://", ignoreCase = true) ||
            cleaned.startsWith("https://", ignoreCase = true)
        ) {
            return hasValidAuthority(cleaned)
        }

        return isBareNetworkAddress(cleaned)
    }

    private fun authorityOf(input: String): String {
        val withoutScheme = input.substringAfter("://", input)
        return withoutScheme.substringBefore('/').substringBefore('?').substringBefore('#')
    }

    private fun hasValidAuthority(input: String): Boolean {
        val authority = authorityOf(input)
        return authority.isNotBlank() && authority.none { it.isWhitespace() }
    }

    /**
     * 裸输入（无 scheme）只接受 localhost、IP 字面量和带合法后缀的域名。
     * 纯单词一律视为搜索词，避免 `hello` 被拼成 `https://hello`。
     */
    private fun isBareNetworkAddress(input: String): Boolean {
        if (localhostRegex.matches(input) || ipv6LiteralRegex.matches(input)) {
            return true
        }

        val authority = authorityOf(input)
        if (authority.isBlank()) {
            return false
        }
        val host = authority.substringBeforeLast(':', authority)
        if (isIpv4Literal(host)) {
            return true
        }

        if (!bareHostRegex.matches(input)) {
            return false
        }
        val tld = host.substringAfterLast('.', "")
        return tld.length >= 2 && tld.any { it.isLetter() }
    }

    private fun isIpv4Literal(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4) {
            return false
        }
        return parts.all { part ->
            part.isNotEmpty() &&
                part.length <= 3 &&
                part.all(Char::isDigit) &&
                part.toIntOrNull()?.let { it in 0..255 } == true
        }
    }

    /**
     * Returns true only for a source that can safely be opened as a web page.
     * Bare domains are accepted and will be normalized to HTTPS by the browser;
     * local/content/custom schemes are deliberately rejected.
     */
    fun isBrowsableWebAddress(input: String): Boolean {
        val cleaned = cleanCandidate(input)
        if (!isProbablyWebAddress(cleaned)) {
            return false
        }

        val schemeSeparator = cleaned.indexOf("://")
        if (schemeSeparator < 0) return true
        val scheme = cleaned.substring(0, schemeSeparator)
        if (!scheme.equals("http", ignoreCase = true) &&
            !scheme.equals("https", ignoreCase = true)
        ) {
            return false
        }
        // Only reject an explicit HTTP(S) scheme with no authority; this also
        // handles unusual but valid authorities consistently across platforms.
        val authority = cleaned.substring(schemeSeparator + 3)
            .substringBefore('/').substringBefore('?').substringBefore('#')
        return authority.isNotBlank()
    }

    /**
     * Validates a persisted media source. Unlike address-bar input, a stored
     * source must be an explicit HTTP(S) URL with a non-empty host.
     */
    fun isPersistedSourceUrl(input: String): Boolean {
        val cleaned = cleanCandidate(input)
        if (!cleaned.startsWith("http://", ignoreCase = true) &&
            !cleaned.startsWith("https://", ignoreCase = true)
        ) return false
        val host = runCatching { URI(cleaned).host }.getOrNull()
            ?: runCatching { cleaned.toUri().host }.getOrNull()
        return !host.isNullOrBlank()
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
