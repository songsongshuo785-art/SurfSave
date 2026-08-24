package com.myAllVideoBrowser.ui.main.home.browser.webTab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageTranslationLanguagePolicyTest {
    @Test
    fun dominantForeignLanguageIgnoresMisleadingDeclaredTargetLanguage() {
        val source = PageTranslationLanguagePolicy.selectDominantForeignLanguage(
            candidates = listOf(
                TranslationLanguageCandidate("en", 0.91f),
                TranslationLanguageCandidate("zh", 0.08f)
            ),
            targetLanguage = "zh",
            declaredLanguage = "zh"
        )

        assertEquals("en", source)
    }

    @Test
    fun englishNodeUsesForeignFallbackEvenWhenPageIsDeclaredChinese() {
        val source = PageTranslationLanguagePolicy.selectNodeSourceLanguage(
            text = "The battery consistently gets about ninety-five percent charge.",
            candidates = listOf(
                TranslationLanguageCandidate("zh", 0.60f),
                TranslationLanguageCandidate("en", 0.20f)
            ),
            targetLanguage = "zh",
            dominantForeignLanguage = "en",
            declaredLanguage = "zh"
        )

        assertEquals("en", source)
    }

    @Test
    fun chineseNavigationNodeIsSkippedWithoutBlockingEnglishNodes() {
        val chineseSource = PageTranslationLanguagePolicy.selectNodeSourceLanguage(
            text = "首页 热门话题 登录账户",
            candidates = listOf(TranslationLanguageCandidate("zh", 0.95f)),
            targetLanguage = "zh",
            dominantForeignLanguage = "en",
            declaredLanguage = "zh"
        )
        val englishSource = PageTranslationLanguagePolicy.selectNodeSourceLanguage(
            text = "Open the comments and read the full discussion",
            candidates = listOf(TranslationLanguageCandidate("en", 0.93f)),
            targetLanguage = "zh",
            dominantForeignLanguage = "en",
            declaredLanguage = "en"
        )

        assertNull(chineseSource)
        assertEquals("en", englishSource)
    }

    @Test
    fun declaredForeignLanguageIsFallbackForShortAmbiguousText() {
        val source = PageTranslationLanguagePolicy.selectNodeSourceLanguage(
            text = "Read more",
            candidates = emptyList(),
            targetLanguage = "zh",
            dominantForeignLanguage = null,
            declaredLanguage = "en"
        )

        assertEquals("en", source)
    }

    @Test
    fun scriptRatioDistinguishesChineseShellFromEnglishFeedText() {
        assertTrue(
            PageTranslationLanguagePolicy.isClearlyTargetText(
                "主页 热门 最新消息",
                "zh"
            )
        )
        assertTrue(
            PageTranslationLanguagePolicy.isClearlyForeignText(
                "This post and its comments are still entirely in English",
                "zh"
            )
        )
        assertFalse(
            PageTranslationLanguagePolicy.isClearlyTargetText(
                "This post and its comments are still entirely in English",
                "zh"
            )
        )
    }

    @Test
    fun obviousEnglishNodeUsesDominantLanguageWithoutIndividualIdentification() {
        val work = PageTranslationLanguagePolicy.resolveNodeLanguageWork(
            text = "This Reddit comment is clearly written in English.",
            targetLanguage = "zh",
            dominantForeignLanguage = "en"
        )

        assertEquals(NodeLanguageResolution.USE_DOMINANT, work)
    }

    @Test
    fun englishFeedUsesDominantFastPathForEveryNode() {
        val work = (1..200).map { index ->
            PageTranslationLanguagePolicy.resolveNodeLanguageWork(
                text = "English Reddit comment number $index remains entirely in English.",
                targetLanguage = "zh",
                dominantForeignLanguage = "en"
            )
        }

        assertTrue(work.all { it == NodeLanguageResolution.USE_DOMINANT })
    }

    @Test
    fun ambiguousLanguageIdentificationStopsAtBatchLimit() {
        assertTrue(PageTranslationLanguagePolicy.canIdentifyAnotherNode(23))
        assertFalse(PageTranslationLanguagePolicy.canIdentifyAnotherNode(24))
    }

    @Test
    fun chineseNodeIsSkippedBeforeLanguageIdentification() {
        val work = PageTranslationLanguagePolicy.resolveNodeLanguageWork(
            text = "这是一条已经是中文的评论",
            targetLanguage = "zh",
            dominantForeignLanguage = "en"
        )

        assertEquals(NodeLanguageResolution.SKIP_TARGET, work)
    }

    @Test
    fun mixedScriptNodeUsesLimitedIdentificationPath() {
        val work = PageTranslationLanguagePolicy.resolveNodeLanguageWork(
            text = "English 中文混合测试内容",
            targetLanguage = "zh",
            dominantForeignLanguage = "en"
        )

        assertEquals(NodeLanguageResolution.IDENTIFY, work)
    }

    @Test
    fun tinyAmbiguousNodeIsSkippedInsteadOfStartingModelWork() {
        val work = PageTranslationLanguagePolicy.resolveNodeLanguageWork(
            text = "字 A",
            targetLanguage = "zh",
            dominantForeignLanguage = "en"
        )

        assertEquals(NodeLanguageResolution.SKIP, work)
    }

    @Test
    fun longTextIsSplitWithoutDroppingContent() {
        val text = (1..80).joinToString(" ") { "sentence-$it." }

        val chunks = PageTranslationLanguagePolicy.chunkText(text, maxLength = 120)

        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 120 })
        assertEquals(text, chunks.joinToString(" "))
    }
}
