package com.myAllVideoBrowser.ui.main.home.browser

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Locale

/** Small RFC 5987-aware parser used instead of relying on platform URLUtil behavior. */
internal object BrowserContentDisposition {
    fun fileName(headerValue: String?): String? {
        val parameters = splitParameters(headerValue.orEmpty())
            .mapNotNull { parameter ->
                val separator = parameter.indexOf('=')
                if (separator <= 0) return@mapNotNull null
                parameter.substring(0, separator).trim().lowercase(Locale.US) to
                    parameter.substring(separator + 1).trim()
            }

        parameters.firstOrNull { it.first == "filename*" }
            ?.second
            ?.let(::decodeExtendedValue)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        return parameters.firstOrNull { it.first == "filename" }
            ?.second
            ?.let(::unquote)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun splitParameters(headerValue: String): List<String> {
        if (headerValue.isBlank()) return emptyList()

        val parameters = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var escaped = false
        headerValue.forEach { character ->
            when {
                escaped -> {
                    current.append(character)
                    escaped = false
                }

                quoted && character == '\\' -> {
                    current.append(character)
                    escaped = true
                }

                character == '"' -> {
                    current.append(character)
                    quoted = !quoted
                }

                character == ';' && !quoted -> {
                    parameters += current.toString()
                    current.clear()
                }

                else -> current.append(character)
            }
        }
        parameters += current.toString()
        return parameters
    }

    private fun decodeExtendedValue(rawValue: String): String {
        val value = unquote(rawValue)
        val firstQuote = value.indexOf('\'')
        val secondQuote = if (firstQuote >= 0) value.indexOf('\'', firstQuote + 1) else -1
        val charset = if (firstQuote > 0 && secondQuote > firstQuote) {
            runCatching { Charset.forName(value.substring(0, firstQuote)) }
                .getOrDefault(StandardCharsets.UTF_8)
        } else {
            StandardCharsets.UTF_8
        }
        val encodedName = if (secondQuote >= 0) value.substring(secondQuote + 1) else value
        return percentDecode(encodedName, charset)
    }

    /** RFC 5987 uses percent encoding, not form encoding; a literal '+' must stay a plus. */
    private fun percentDecode(value: String, charset: Charset): String {
        val result = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            if (value[index] != '%' || index + 2 >= value.length) {
                result.append(value[index])
                index += 1
                continue
            }

            val bytes = mutableListOf<Byte>()
            while (index + 2 < value.length && value[index] == '%') {
                val high = value[index + 1].digitToIntOrNull(16) ?: break
                val low = value[index + 2].digitToIntOrNull(16) ?: break
                bytes += ((high shl 4) or low).toByte()
                index += 3
            }
            if (bytes.isEmpty()) {
                result.append(value[index])
                index += 1
            } else {
                result.append(bytes.toByteArray().toString(charset))
            }
        }
        return result.toString()
    }

    private fun unquote(rawValue: String): String {
        val value = rawValue.trim()
        if (value.length < 2 || value.first() != '"' || value.last() != '"') return value

        val unescaped = StringBuilder(value.length - 2)
        var escaped = false
        value.substring(1, value.length - 1).forEach { character ->
            when {
                escaped -> {
                    unescaped.append(character)
                    escaped = false
                }

                character == '\\' -> escaped = true
                else -> unescaped.append(character)
            }
        }
        if (escaped) unescaped.append('\\')
        return unescaped.toString()
    }
}
