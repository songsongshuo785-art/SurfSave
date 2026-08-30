package com.myAllVideoBrowser.contentblock

import android.app.Application
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class TrustedScriptletInvocation(
    val name: String,
    val argument: String,
    val javaScript: String
)

@Singleton
class TrustedScriptletRegistry internal constructor(
    private val bundledLoader: () -> String
) {
    @Inject
    constructor(application: Application) : this(
        bundledLoader = {
            application.assets.open(BUNDLED_ASSET).bufferedReader(Charsets.UTF_8).use {
                it.readText()
            }
        }
    )

    private val entries: List<Entry> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        parse(bundledLoader().lineSequence())
    }

    fun forPage(pageUrl: String): List<TrustedScriptletInvocation> {
        val host = runCatching { URI(pageUrl).host?.lowercase(Locale.US) }.getOrNull()
            ?: return emptyList()
        return entries.asSequence()
            .filter { host == it.domain || host.endsWith(".${it.domain}") }
            .take(MAX_SCRIPTLETS_PER_PAGE)
            .mapNotNull(::render)
            .toList()
    }

    internal fun acceptedEntryCount(): Int = entries.size

    private fun parse(lines: Sequence<String>): List<Entry> {
        return lines
            .take(MAX_INPUT_LINES)
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith('#') && !it.startsWith('!') }
            .mapNotNull { line ->
                if (line.length > MAX_LINE_LENGTH) return@mapNotNull null
                val fields = line.split('|', limit = 3)
                if (fields.size != 3) return@mapNotNull null
                val domain = normalizeDomain(fields[0]) ?: return@mapNotNull null
                val name = fields[1].trim().lowercase(Locale.US)
                if (name !in TRUSTED_NAMES) return@mapNotNull null
                val argument = fields[2].trim()
                if (!isValidArgument(name, argument)) return@mapNotNull null
                Entry(domain, name, argument)
            }
            .distinct()
            .take(MAX_ENTRIES)
            .toList()
    }

    private fun render(entry: Entry): TrustedScriptletInvocation? {
        val script = when (entry.name) {
            REMOVE_COOKIE -> {
                val cookie = JSON.encodeToString(entry.argument)
                "(()=>{const n=$cookie;document.cookie=n+'=; Max-Age=0; path=/';})();"
            }
            SET_LOCAL_STORAGE -> {
                val key = entry.argument.substringBefore('=')
                val value = entry.argument.substringAfter('=', "")
                "(()=>{try{localStorage.setItem(${JSON.encodeToString(key)}," +
                    "${JSON.encodeToString(value)});}catch(_){}})();"
            }
            else -> return null
        }
        return TrustedScriptletInvocation(entry.name, entry.argument, script)
    }

    private fun isValidArgument(name: String, argument: String): Boolean {
        if (argument.isEmpty() || argument.length > MAX_ARGUMENT_LENGTH) return false
        return when (name) {
            REMOVE_COOKIE -> SAFE_KEY.matches(argument)
            SET_LOCAL_STORAGE -> {
                val key = argument.substringBefore('=')
                val value = argument.substringAfter('=', missingDelimiterValue = "")
                argument.contains('=') && SAFE_KEY.matches(key) &&
                    value.length <= MAX_STORAGE_VALUE_LENGTH &&
                    value.none { it.isISOControl() }
            }
            else -> false
        }
    }

    private fun normalizeDomain(value: String): String? {
        val domain = value.trim().lowercase(Locale.US).removePrefix("*.").trimEnd('.')
        return domain.takeIf {
            it.contains('.') && it.length <= 253 && SAFE_DOMAIN.matches(it)
        }
    }

    private data class Entry(val domain: String, val name: String, val argument: String)

    companion object {
        const val BUNDLED_ASSET = "contentblock/trusted_scriptlets.txt"
        private const val REMOVE_COOKIE = "remove-cookie"
        private const val SET_LOCAL_STORAGE = "set-local-storage"
        private const val MAX_INPUT_LINES = 2_000
        private const val MAX_ENTRIES = 512
        private const val MAX_LINE_LENGTH = 512
        private const val MAX_ARGUMENT_LENGTH = 256
        private const val MAX_STORAGE_VALUE_LENGTH = 192
        private const val MAX_SCRIPTLETS_PER_PAGE = 16
        private val TRUSTED_NAMES = setOf(REMOVE_COOKIE, SET_LOCAL_STORAGE)
        private val SAFE_DOMAIN = Regex("[a-z0-9][a-z0-9._-]*[a-z0-9]")
        private val SAFE_KEY = Regex("[A-Za-z0-9_.:-]{1,64}")
        private val JSON = Json
    }
}
