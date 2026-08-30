package com.myAllVideoBrowser.contentblock.web

import com.myAllVideoBrowser.contentblock.ProceduralCosmeticAction
import com.myAllVideoBrowser.contentblock.ProceduralCosmeticOperator
import com.myAllVideoBrowser.contentblock.ProceduralCosmeticRule
import kotlinx.serialization.Serializable

internal object ProceduralCosmeticSanitizer {
    fun sanitize(rules: Collection<ProceduralCosmeticRule>): List<SafeProceduralRule> {
        var totalCharacters = 0
        return rules.asSequence()
            .mapNotNull(::sanitizeRule)
            .distinct()
            .take(MAX_RULES)
            .takeWhile { rule ->
                totalCharacters += rule.characterCount
                totalCharacters <= MAX_TOTAL_CHARACTERS
            }
            .toList()
    }

    private fun sanitizeRule(rule: ProceduralCosmeticRule): SafeProceduralRule? {
        if (rule.selector.isEmpty() || rule.selector.size > MAX_OPERATORS_PER_RULE) return null
        val operators = rule.selector.mapNotNull(::sanitizeOperator)
        if (operators.size != rule.selector.size || operators.first().type != TYPE_CSS_SELECTOR) {
            return null
        }
        val action = sanitizeAction(rule.action) ?: return null
        return SafeProceduralRule(operators = operators, action = action)
    }

    private fun sanitizeOperator(operator: ProceduralCosmeticOperator): SafeProceduralOperator? {
        val type = operator.type.trim().lowercase()
        val argument = operator.argument.trim()
        if (type !in SUPPORTED_OPERATOR_TYPES ||
            argument.length !in 1..MAX_OPERATOR_ARGUMENT_LENGTH ||
            argument.any(Char::isISOControl)
        ) {
            return null
        }
        return when (type) {
            TYPE_CSS_SELECTOR -> argument.takeIf(::isSafeQuerySelector)
            TYPE_MIN_TEXT_LENGTH -> argument.toIntOrNull()
                ?.takeIf { it in 0..MAX_TEXT_LENGTH }
                ?.toString()
            TYPE_UPWARD -> argument.toIntOrNull()?.let { levels ->
                levels.takeIf { it in 1..MAX_UPWARD_LEVELS }?.toString()
            } ?: argument.takeIf(::isSafeQuerySelector)
            else -> argument
        }?.let { SafeProceduralOperator(type = type, argument = it) }
    }

    private fun sanitizeAction(action: ProceduralCosmeticAction?): SafeProceduralAction? {
        if (action == null) return SafeProceduralAction(type = ACTION_HIDE)
        val type = action.type.trim().lowercase()
        return when (type) {
            ACTION_REMOVE -> SafeProceduralAction(type = ACTION_HIDE)
            ACTION_STYLE -> sanitizeStyle(action.argument.orEmpty())?.let { declarations ->
                SafeProceduralAction(type = ACTION_STYLE, styles = declarations)
            }
            ACTION_REMOVE_ATTR -> sanitizeName(action.argument, HTML_NAME_PATTERN)?.let {
                SafeProceduralAction(type = ACTION_REMOVE_ATTR, argument = it)
            }
            ACTION_REMOVE_CLASS -> sanitizeName(action.argument, CLASS_NAME_PATTERN)?.let {
                SafeProceduralAction(type = ACTION_REMOVE_CLASS, argument = it)
            }
            else -> null
        }
    }

    private fun sanitizeStyle(value: String): List<SafeStyleDeclaration>? {
        if (value.length !in 1..MAX_STYLE_LENGTH || value.any(Char::isISOControl)) return null
        val rawDeclarations = value.split(';').filter(String::isNotBlank)
        if (rawDeclarations.isEmpty() || rawDeclarations.size > MAX_STYLE_DECLARATIONS) return null
        val declarations = rawDeclarations.map { rawDeclaration ->
            val separator = rawDeclaration.indexOf(':')
            if (separator <= 0) return null
            val property = rawDeclaration.substring(0, separator).trim().lowercase()
            var propertyValue = rawDeclaration.substring(separator + 1).trim()
            var important = false
            if (propertyValue.endsWith("!important", ignoreCase = true)) {
                propertyValue = propertyValue.dropLast("!important".length).trimEnd()
                important = true
            }
            if (property !in SAFE_STYLE_PROPERTIES ||
                propertyValue.length !in 1..MAX_STYLE_VALUE_LENGTH ||
                !isSafeStyleValue(propertyValue)
            ) {
                return null
            }
            SafeStyleDeclaration(property, propertyValue, important)
        }
        return declarations
    }

    private fun isSafeQuerySelector(value: String): Boolean {
        if (value.length > MAX_QUERY_SELECTOR_LENGTH) return false
        val lower = value.lowercase()
        return "javascript:" !in lower && "url(" !in lower &&
            "/*" !in value && "*/" !in value &&
            value.none { it == '{' || it == '}' || it == ';' || it == '@' }
    }

    private fun isSafeStyleValue(value: String): Boolean {
        val lower = value.lowercase()
        return value.none { it == '{' || it == '}' || it == ';' } &&
            "url(" !in lower && "expression(" !in lower && "javascript:" !in lower &&
            "@import" !in lower && "behavior:" !in lower && "-moz-binding" !in lower
    }

    private fun sanitizeName(value: String?, pattern: Regex): String? {
        val trimmed = value?.trim().orEmpty()
        return trimmed.takeIf(pattern::matches)
    }

    private val SafeProceduralRule.characterCount: Int
        get() = operators.sumOf { it.type.length + it.argument.length } +
            action.type.length + action.argument.length +
            action.styles.sumOf { it.property.length + it.value.length }

    private const val MAX_RULES = 256
    private const val MAX_OPERATORS_PER_RULE = 12
    private const val MAX_OPERATOR_ARGUMENT_LENGTH = 1_024
    private const val MAX_QUERY_SELECTOR_LENGTH = 1_024
    private const val MAX_TOTAL_CHARACTERS = 256 * 1_024
    private const val MAX_STYLE_LENGTH = 1_024
    private const val MAX_STYLE_VALUE_LENGTH = 256
    private const val MAX_STYLE_DECLARATIONS = 8
    private const val MAX_TEXT_LENGTH = 1_000_000
    private const val MAX_UPWARD_LEVELS = 32

    private const val TYPE_CSS_SELECTOR = "css-selector"
    private const val TYPE_MIN_TEXT_LENGTH = "min-text-length"
    private const val TYPE_UPWARD = "upward"
    private const val ACTION_HIDE = "hide"
    private const val ACTION_REMOVE = "remove"
    private const val ACTION_STYLE = "style"
    private const val ACTION_REMOVE_ATTR = "remove-attr"
    private const val ACTION_REMOVE_CLASS = "remove-class"

    private val SUPPORTED_OPERATOR_TYPES = setOf(
        TYPE_CSS_SELECTOR,
        "has-text",
        "matches-attr",
        "matches-css",
        "matches-css-before",
        "matches-css-after",
        "matches-path",
        TYPE_MIN_TEXT_LENGTH,
        TYPE_UPWARD,
        "xpath"
    )
    private val HTML_NAME_PATTERN = Regex("[A-Za-z_][A-Za-z0-9_:.-]{0,63}")
    private val CLASS_NAME_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
    private val SAFE_STYLE_PROPERTIES = setOf(
        "display", "visibility", "opacity", "pointer-events", "overflow", "overflow-x",
        "overflow-y", "position", "top", "right", "bottom", "left", "z-index",
        "width", "height", "min-width", "min-height", "max-width", "max-height",
        "margin", "margin-top", "margin-right", "margin-bottom", "margin-left",
        "padding", "padding-top", "padding-right", "padding-bottom", "padding-left"
    )
}

@Serializable
internal data class SafeProceduralRule(
    val operators: List<SafeProceduralOperator>,
    val action: SafeProceduralAction
)

@Serializable
internal data class SafeProceduralOperator(val type: String, val argument: String)

@Serializable
internal data class SafeProceduralAction(
    val type: String,
    val argument: String = "",
    val styles: List<SafeStyleDeclaration> = emptyList()
)

@Serializable
internal data class SafeStyleDeclaration(
    val property: String,
    val value: String,
    val important: Boolean
)
