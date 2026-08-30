package com.myAllVideoBrowser.contentblock.web

import com.myAllVideoBrowser.contentblock.ProceduralCosmeticOperator
import com.myAllVideoBrowser.contentblock.ProceduralCosmeticRule

internal object ExtendedCosmeticSelectorParser {
    data class Result(
        val staticSelectors: List<String>,
        val proceduralRules: List<ProceduralCosmeticRule>
    )

    fun partition(selectors: Collection<String>): Result {
        val staticSelectors = ArrayList<String>(selectors.size)
        val proceduralRules = ArrayList<ProceduralCosmeticRule>()
        selectors.forEach { selector ->
            when (val parsed = parse(selector)) {
                ParseResult.NotExtended -> staticSelectors += selector
                ParseResult.Invalid -> Unit
                is ParseResult.Rule -> proceduralRules += parsed.value
            }
        }
        return Result(staticSelectors, proceduralRules)
    }

    private fun parse(selector: String): ParseResult {
        val hasText = findFunction(selector, HAS_TEXT_NAMES)
        if (hasText != null) return parseHasText(selector, hasText)

        val properties = findFunction(selector, ABP_PROPERTIES_NAMES)
        if (properties != null) return parseProperties(selector, properties)

        if (findFunction(selector, NATIVE_HAS_NAMES) != null) {
            return ParseResult.Rule(
                ProceduralCosmeticRule(
                    selector = listOf(ProceduralCosmeticOperator("css-selector", selector))
                )
            )
        }

        return ParseResult.NotExtended
    }

    private fun parseHasText(selector: String, function: FunctionCall): ParseResult {
        val outerHas = selector.lastIndexOf(":has(", startIndex = function.start)
        val suffix = selector.substring(function.endExclusive).trim()
        if (suffix.isNotEmpty() &&
            (outerHas < 0 || suffix.any { it != ')' })
        ) {
            return ParseResult.Invalid
        }
        val base = selector.substring(0, if (outerHas >= 0) outerHas else function.start).trim()
        if (base.isEmpty()) return ParseResult.Invalid
        val argument = function.argument.trim()
        if (argument.isEmpty()) return ParseResult.Invalid
        return ParseResult.Rule(
            ProceduralCosmeticRule(
                selector = listOf(
                    ProceduralCosmeticOperator("css-selector", base),
                    ProceduralCosmeticOperator("has-text", argument)
                )
            )
        )
    }

    private fun parseProperties(selector: String, function: FunctionCall): ParseResult {
        if (selector.substring(function.endExclusive).isNotBlank()) return ParseResult.Invalid
        // Legacy ABP properties rules inspect stylesheet declarations. Approximating an
        // unscoped rule with `body * + getComputedStyle` is both semantically incorrect and
        // prone to hiding ordinary layout nodes that merely share the same dimensions.
        val base = selector.substring(0, function.start).trim()
        if (base.isEmpty()) return ParseResult.Invalid
        val argument = function.argument.trim()
        if (argument.isEmpty()) return ParseResult.Invalid

        val propertyMatchers = argument.split(';').mapNotNull { declaration ->
            val trimmed = declaration.trim()
            if (trimmed.isEmpty()) return@mapNotNull null
            val separator = trimmed.indexOf(':')
            if (separator <= 0) return@mapNotNull null
            val property = trimmed.substring(0, separator).trim()
            val value = trimmed.substring(separator + 1).trim()
            "$property:$value".takeIf {
                property.matches(CSS_PROPERTY_NAME) && value.isNotEmpty()
            }
        }
        val operators = if (propertyMatchers.isNotEmpty()) {
            propertyMatchers.map { ProceduralCosmeticOperator("matches-css", it) }
        } else {
            listOf(
                ProceduralCosmeticOperator(
                    "matches-css",
                    argument.trim('*').takeIf(String::isNotEmpty) ?: return ParseResult.Invalid
                )
            )
        }
        return ParseResult.Rule(
            ProceduralCosmeticRule(
                selector = listOf(ProceduralCosmeticOperator("css-selector", base)) + operators
            )
        )
    }

    private fun findFunction(selector: String, names: Collection<String>): FunctionCall? {
        val found = names.mapNotNull { name ->
            selector.indexOf(name).takeIf { it >= 0 }?.let { it to name }
        }.minByOrNull { it.first } ?: return null
        val start = found.first
        val name = found.second
        val opening = start + name.length - 1
        var depth = 0
        var quote: Char? = null
        var escaped = false
        for (index in opening until selector.length) {
            val character = selector[index]
            if (escaped) {
                escaped = false
                continue
            }
            if (character == '\\') {
                escaped = true
                continue
            }
            if (quote != null) {
                if (character == quote) quote = null
                continue
            }
            if (character == '\'' || character == '"') {
                quote = character
                continue
            }
            when (character) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) {
                        return FunctionCall(
                            start = start,
                            endExclusive = index + 1,
                            argument = selector.substring(opening + 1, index)
                        )
                    }
                }
            }
        }
        return FunctionCall(start, selector.length, "")
    }

    private data class FunctionCall(
        val start: Int,
        val endExclusive: Int,
        val argument: String
    )

    private sealed interface ParseResult {
        data object NotExtended : ParseResult
        data object Invalid : ParseResult
        data class Rule(val value: ProceduralCosmeticRule) : ParseResult
    }

    private val HAS_TEXT_NAMES = listOf(":has-text(", ":-abp-contains(")
    private val ABP_PROPERTIES_NAMES = listOf(":-abp-properties(", ":properties(")
    private val NATIVE_HAS_NAMES = listOf(":has(")
    private val CSS_PROPERTY_NAME = Regex("-?[A-Za-z][A-Za-z0-9-]{0,63}")
}
