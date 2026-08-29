package com.myAllVideoBrowser.ui.main.home.browser

import java.net.URI
import java.util.Locale

/**
 * Lightweight request filter. Rules are data loaded by [BrowserAdFilterRuleProvider]; this
 * class deliberately contains no website-specific branches.
 */
class BrowserAdFilter private constructor(
    private val pageAllowDomains: Set<String>,
    private val allowDomainRules: Map<String, List<Rule>>,
    private val hardBlockDomainRules: Map<String, List<Rule>>,
    private val softBlockDomainRules: Map<String, List<Rule>>,
    private val allowPathRules: List<Rule>,
    private val hardBlockPathRules: List<Rule>,
    private val softBlockPathRules: List<Rule>
) {
    fun shouldBlock(
        url: String,
        pageUrl: String,
        isMainFrame: Boolean,
        contentType: ContentType,
        requestHeaders: Map<String, String> = emptyMap(),
        requestSource: BrowserRequestSource = BrowserRequestSource.WEB_VIEW
    ): Boolean {
        if (isMainFrame) return false

        val requestUri = parseHttpUri(url) ?: return false
        val requestHost = normalizeDomain(requestUri.host) ?: return false
        val pageHost = if (requestSource == BrowserRequestSource.WEB_VIEW) {
            parseHttpUri(pageUrl)?.host?.let(::normalizeDomain)
        } else {
            null
        }
        val isThirdParty = pageHost?.let {
            registrableDomain(requestHost) != registrableDomain(it)
        }
        val rawPath = requestUri.rawPath.orEmpty().lowercase(Locale.US)
        val acceptHeader = requestHeaders.entries.firstOrNull {
            it.key.equals("Accept", ignoreCase = true)
        }?.value.orEmpty()

        // A page allowlist is a WebView-page decision. A ServiceWorker request has no
        // trustworthy tab identity, so it must never borrow the active tab's allowlist.
        if (pageHost != null && domainSuffixes(pageHost).any(pageAllowDomains::contains)) {
            return false
        }

        if (matchesDomainRules(allowDomainRules, requestHost, pageHost, isThirdParty)) {
            return false
        }
        if (matchesPathRules(allowPathRules, requestHost, rawPath, pageHost, isThirdParty)) {
            return false
        }

        // Curated hard rules are explicit advertising evidence and may block any
        // subresource type, including MP4/HLS/DASH media.
        if (matchesDomainRules(hardBlockDomainRules, requestHost, pageHost, isThirdParty)) {
            return true
        }
        if (
            matchesPathRules(
                hardBlockPathRules,
                requestHost,
                rawPath,
                pageHost,
                isThirdParty
            )
        ) {
            return true
        }

        // Resource type is only a classifier hint. With no hard ad evidence, protect
        // likely playback resources from lower-confidence path heuristics.
        if (
            contentType != ContentType.OTHER ||
            BrowserMediaClassifier.isLikelyPlaybackResource(url, acceptHeader)
        ) {
            return false
        }

        // ServiceWorker callbacks have no WebView/tab identity. V1 therefore runs only
        // context-free hard rules and never page-dependent or soft heuristic rules.
        if (requestSource == BrowserRequestSource.SERVICE_WORKER) return false

        return matchesDomainRules(softBlockDomainRules, requestHost, pageHost, isThirdParty) ||
            matchesPathRules(
                softBlockPathRules,
                requestHost,
                rawPath,
                pageHost,
                isThirdParty
            )
    }

    private fun matchesDomainRules(
        index: Map<String, List<Rule>>,
        requestHost: String,
        pageHost: String?,
        isThirdParty: Boolean?
    ): Boolean {
        return domainSuffixes(requestHost).any { suffix ->
            index[suffix].orEmpty().any {
                it.appliesTo(requestHost, pageHost, isThirdParty)
            }
        }
    }

    private fun matchesPathRules(
        rules: List<Rule>,
        requestHost: String,
        rawPath: String,
        pageHost: String?,
        isThirdParty: Boolean?
    ): Boolean {
        if (rawPath.isEmpty()) return false
        return rules.any { rule ->
            rawPath.contains(rule.value) &&
                rule.appliesTo(requestHost, pageHost, isThirdParty)
        }
    }

    private data class Rule(
        val value: String,
        val pageDomains: Set<String>,
        val requestDomains: Set<String>,
        val thirdPartyOnly: Boolean
    ) {
        fun appliesTo(
            requestHost: String,
            pageHost: String?,
            isThirdParty: Boolean?
        ): Boolean {
            if (
                requestDomains.isNotEmpty() &&
                domainSuffixes(requestHost).none(requestDomains::contains)
            ) {
                return false
            }
            if (
                pageDomains.isNotEmpty() &&
                (pageHost == null || domainSuffixes(pageHost).none(pageDomains::contains))
            ) {
                return false
            }
            if (thirdPartyOnly && isThirdParty != true) return false
            return true
        }
    }

    private data class ParsedFlags(
        val requestDomains: Set<String>,
        val thirdPartyOnly: Boolean,
        val priority: RulePriority
    )

    private enum class RulePriority { HARD, SOFT }

    private enum class Action { BLOCK, ALLOW }

    private enum class Kind { DOMAIN, PATH, PAGE }

    companion object {
        private const val MAX_RULES = 20_000
        private const val MAX_RULE_LINE_LENGTH = 2_048
        private const val REQUEST_DOMAIN_PREFIX = "request-domain="

        private val multiLabelPublicSuffixes = setOf(
            "co.uk", "org.uk", "com.au", "net.au", "org.au", "co.jp",
            "com.cn", "net.cn", "org.cn", "com.hk", "com.tw", "co.kr",
            "co.in", "com.br", "com.mx", "co.nz", "com.sg", "com.tr"
        )

        fun empty(): BrowserAdFilter = BrowserAdFilter(
            pageAllowDomains = emptySet(),
            allowDomainRules = emptyMap(),
            hardBlockDomainRules = emptyMap(),
            softBlockDomainRules = emptyMap(),
            allowPathRules = emptyList(),
            hardBlockPathRules = emptyList(),
            softBlockPathRules = emptyList()
        )

        /**
         * Parses the compact v1 rule format:
         * action|kind|value|page-domain[,page-domain]|flags
         *
         * Supported flags are third-party, soft, and request-domain=<domain>.
         */
        fun fromLines(lines: Sequence<String>): BrowserAdFilter {
            val pageAllows = linkedSetOf<String>()
            val allowDomains = linkedMapOf<String, MutableList<Rule>>()
            val hardBlockDomains = linkedMapOf<String, MutableList<Rule>>()
            val softBlockDomains = linkedMapOf<String, MutableList<Rule>>()
            val allowPaths = mutableListOf<Rule>()
            val hardBlockPaths = mutableListOf<Rule>()
            val softBlockPaths = mutableListOf<Rule>()

            lines
                .filter { it.length <= MAX_RULE_LINE_LENGTH }
                .map(String::trim)
                .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("!") }
                .take(MAX_RULES)
                .forEach { line ->
                    val fields = line.split('|', limit = 5)
                    if (fields.size != 5) return@forEach

                    val action = when (fields[0].trim().lowercase(Locale.US)) {
                        "block" -> Action.BLOCK
                        "allow" -> Action.ALLOW
                        else -> return@forEach
                    }
                    val kind = when (fields[1].trim().lowercase(Locale.US)) {
                        "domain" -> Kind.DOMAIN
                        "path" -> Kind.PATH
                        "page" -> Kind.PAGE
                        else -> return@forEach
                    }
                    val rawValue = fields[2].trim()
                    val value = when (kind) {
                        Kind.DOMAIN, Kind.PAGE -> normalizeDomain(rawValue)?.takeIf {
                            it.contains('.')
                        }
                        Kind.PATH -> rawValue.lowercase(Locale.US).takeIf {
                            it.startsWith('/') && it.length >= 3
                        }
                    } ?: return@forEach

                    if (kind == Kind.PAGE) {
                        if (
                            action == Action.ALLOW &&
                            fields[3].isBlank() &&
                            fields[4].isBlank()
                        ) {
                            pageAllows += value
                        }
                        return@forEach
                    }

                    val rawPageDomains = fields[3].trim()
                    val pageDomains = rawPageDomains
                        .split(',')
                        .mapNotNull(::normalizeDomain)
                        .toSet()
                    if (rawPageDomains.isNotEmpty() && pageDomains.isEmpty()) return@forEach

                    val parsedFlags = parseFlags(fields[4], kind, action) ?: return@forEach
                    if (
                        kind == Kind.PATH &&
                        pageDomains.isEmpty() &&
                        !parsedFlags.thirdPartyOnly &&
                        parsedFlags.requestDomains.isEmpty()
                    ) {
                        // A bare path fragment is not safe across every host. Bind it to a
                        // page, third-party context, or request-domain.
                        return@forEach
                    }

                    val rule = Rule(
                        value = value,
                        pageDomains = pageDomains,
                        requestDomains = parsedFlags.requestDomains,
                        thirdPartyOnly = parsedFlags.thirdPartyOnly
                    )

                    when {
                        kind == Kind.DOMAIN && action == Action.ALLOW ->
                            allowDomains.getOrPut(value, ::mutableListOf) += rule
                        kind == Kind.DOMAIN && parsedFlags.priority == RulePriority.HARD ->
                            hardBlockDomains.getOrPut(value, ::mutableListOf) += rule
                        kind == Kind.DOMAIN ->
                            softBlockDomains.getOrPut(value, ::mutableListOf) += rule
                        kind == Kind.PATH && action == Action.ALLOW -> allowPaths += rule
                        kind == Kind.PATH && parsedFlags.priority == RulePriority.HARD ->
                            hardBlockPaths += rule
                        kind == Kind.PATH -> softBlockPaths += rule
                    }
                }

            return BrowserAdFilter(
                pageAllowDomains = pageAllows,
                allowDomainRules = allowDomains.mapValues { it.value.toList() },
                hardBlockDomainRules = hardBlockDomains.mapValues { it.value.toList() },
                softBlockDomainRules = softBlockDomains.mapValues { it.value.toList() },
                allowPathRules = allowPaths.toList(),
                hardBlockPathRules = hardBlockPaths.toList(),
                softBlockPathRules = softBlockPaths.toList()
            )
        }

        private fun parseFlags(rawFlags: String, kind: Kind, action: Action): ParsedFlags? {
            var thirdPartyOnly = false
            var priority = RulePriority.HARD
            val requestDomains = linkedSetOf<String>()

            rawFlags
                .split(',')
                .map { it.trim().lowercase(Locale.US) }
                .filter(String::isNotEmpty)
                .forEach { flag ->
                    when {
                        flag == "third-party" -> thirdPartyOnly = true
                        flag == "soft" && action == Action.BLOCK -> priority = RulePriority.SOFT
                        flag.startsWith(REQUEST_DOMAIN_PREFIX) && kind == Kind.PATH -> {
                            val domain = normalizeDomain(flag.removePrefix(REQUEST_DOMAIN_PREFIX))
                                ?.takeIf { it.contains('.') }
                                ?: return null
                            requestDomains += domain
                        }
                        else -> return null
                    }
                }

            return ParsedFlags(
                requestDomains = requestDomains,
                thirdPartyOnly = thirdPartyOnly,
                priority = priority
            )
        }

        private fun parseHttpUri(url: String): URI? {
            val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase(Locale.US)
            return uri.takeIf { scheme == "http" || scheme == "https" }
        }

        private fun normalizeDomain(value: String?): String? {
            return value
                ?.trim()
                ?.lowercase(Locale.US)
                ?.removePrefix("*.")
                ?.trimEnd('.')
                ?.takeIf {
                    it.isNotEmpty() &&
                        it.length <= 253 &&
                        it.all { character -> character.isLetterOrDigit() || character in ".-_" }
                }
        }

        private fun domainSuffixes(host: String): Sequence<String> = sequence {
            var suffix = host
            while (suffix.isNotEmpty()) {
                yield(suffix)
                suffix = suffix.substringAfter('.', "")
            }
        }

        private fun registrableDomain(host: String): String {
            if (host.none { it == '.' } || host.all { it.isDigit() || it == '.' || it == ':' }) {
                return host
            }
            val labels = host.split('.').filter(String::isNotEmpty)
            if (labels.size <= 2) return host
            val lastTwo = labels.takeLast(2).joinToString(".")
            return if (lastTwo in multiLabelPublicSuffixes && labels.size >= 3) {
                labels.takeLast(3).joinToString(".")
            } else {
                lastTwo
            }
        }
    }
}
