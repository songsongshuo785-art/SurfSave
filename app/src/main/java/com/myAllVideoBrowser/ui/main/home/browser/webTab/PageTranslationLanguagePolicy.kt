package com.myAllVideoBrowser.ui.main.home.browser.webTab

internal data class TranslationLanguageCandidate(
    val language: String,
    val confidence: Float
)

internal enum class NodeLanguageResolution {
    SKIP_TARGET,
    USE_DOMINANT,
    IDENTIFY,
    SKIP
}

internal object PageTranslationLanguagePolicy {
    private const val MIN_FOREIGN_CONFIDENCE = 0.30f
    private const val MIN_TARGET_CONFIDENCE = 0.55f
    private const val MIN_SCRIPT_LETTERS = 2
    private const val MIN_NODE_LANGUAGE_SAMPLE_LENGTH = 12
    private const val MAX_NODE_LANGUAGE_IDENTIFICATIONS = 24
    private const val DEFAULT_TRANSLATION_CHUNK_LENGTH = 450

    fun selectDominantForeignLanguage(
        candidates: List<TranslationLanguageCandidate>,
        targetLanguage: String,
        declaredLanguage: String?
    ): String? {
        return strongestForeign(candidates, targetLanguage)
            ?.takeIf { it.confidence >= MIN_FOREIGN_CONFIDENCE }
            ?.language
            ?: declaredLanguage?.takeIf { it != targetLanguage }
    }

    fun selectNodeSourceLanguage(
        text: String,
        candidates: List<TranslationLanguageCandidate>,
        targetLanguage: String,
        dominantForeignLanguage: String?,
        declaredLanguage: String?
    ): String? {
        if (isClearlyTargetText(text, targetLanguage)) return null

        val strongestByLanguage = strongestByLanguage(candidates)
        val strongestForeign = strongestByLanguage
            .filterKeys { it != targetLanguage }
            .maxByOrNull { it.value }
        val targetConfidence = strongestByLanguage[targetLanguage]

        if (strongestForeign != null && strongestForeign.value >= MIN_FOREIGN_CONFIDENCE) {
            return strongestForeign.key
        }
        if (targetConfidence != null && targetConfidence >= MIN_TARGET_CONFIDENCE &&
            !isClearlyForeignText(text, targetLanguage)
        ) {
            return null
        }

        return dominantForeignLanguage?.takeIf { it != targetLanguage }
            ?: declaredLanguage?.takeIf { it != targetLanguage }
            ?: strongestForeign?.key
    }

    fun isClearlyTargetText(text: String, targetLanguage: String): Boolean {
        if (!targetLanguage.startsWith("zh")) return false
        val profile = scriptProfile(text)
        return profile.hanLetters >= MIN_SCRIPT_LETTERS &&
            profile.hanLetters >= profile.latinLetters * 2
    }

    fun isClearlyForeignText(text: String, targetLanguage: String): Boolean {
        if (!targetLanguage.startsWith("zh")) return false
        val profile = scriptProfile(text)
        return profile.latinLetters >= MIN_SCRIPT_LETTERS &&
            profile.latinLetters > profile.hanLetters * 2
    }

    fun resolveNodeLanguageWork(
        text: String,
        targetLanguage: String,
        dominantForeignLanguage: String?
    ): NodeLanguageResolution {
        return when {
            isClearlyTargetText(text, targetLanguage) -> NodeLanguageResolution.SKIP_TARGET
            dominantForeignLanguage != null &&
                isClearlyForeignText(text, targetLanguage) -> NodeLanguageResolution.USE_DOMINANT
            text.length >= MIN_NODE_LANGUAGE_SAMPLE_LENGTH -> NodeLanguageResolution.IDENTIFY
            else -> NodeLanguageResolution.SKIP
        }
    }

    fun canIdentifyAnotherNode(identificationsUsed: Int): Boolean {
        return identificationsUsed in 0 until MAX_NODE_LANGUAGE_IDENTIFICATIONS
    }

    fun chunkText(
        text: String,
        maxLength: Int = DEFAULT_TRANSLATION_CHUNK_LENGTH
    ): List<String> {
        require(maxLength > 0)
        val normalized = text.trim()
        if (normalized.isEmpty()) return emptyList()
        if (normalized.length <= maxLength) return listOf(normalized)

        val chunks = mutableListOf<String>()
        var start = 0
        while (start < normalized.length) {
            var end = (start + maxLength).coerceAtMost(normalized.length)
            if (end < normalized.length) {
                val minimumSplit = start + maxLength / 2
                val preferredSplit = (end downTo minimumSplit).firstOrNull { index ->
                    normalized[index - 1] in TRANSLATION_SPLIT_CHARACTERS
                }
                if (preferredSplit != null) end = preferredSplit
            }

            normalized.substring(start, end).trim().takeIf { it.isNotEmpty() }?.let(chunks::add)
            start = end
            while (start < normalized.length && normalized[start].isWhitespace()) start++
        }
        return chunks
    }

    private fun strongestForeign(
        candidates: List<TranslationLanguageCandidate>,
        targetLanguage: String
    ): TranslationLanguageCandidate? {
        return strongestByLanguage(candidates)
            .filterKeys { it != targetLanguage }
            .maxByOrNull { it.value }
            ?.let { TranslationLanguageCandidate(it.key, it.value) }
    }

    private fun strongestByLanguage(
        candidates: List<TranslationLanguageCandidate>
    ): Map<String, Float> {
        return candidates
            .groupBy { it.language }
            .mapValues { (_, values) -> values.maxOf { it.confidence } }
    }

    private fun scriptProfile(text: String): ScriptProfile {
        var hanLetters = 0
        var latinLetters = 0
        var index = 0
        while (index < text.length) {
            val codePoint = Character.codePointAt(text, index)
            when (Character.UnicodeScript.of(codePoint)) {
                Character.UnicodeScript.HAN -> hanLetters++
                Character.UnicodeScript.LATIN -> latinLetters++
                else -> Unit
            }
            index += Character.charCount(codePoint)
        }
        return ScriptProfile(hanLetters, latinLetters)
    }

    private data class ScriptProfile(
        val hanLetters: Int,
        val latinLetters: Int
    )

    private val TRANSLATION_SPLIT_CHARACTERS = setOf(
        '\n', ' ', '.', ',', ';', ':', '!', '?',
        '\u3002', '\uff0c', '\uff1b', '\uff1a', '\uff01', '\uff1f'
    )
}
