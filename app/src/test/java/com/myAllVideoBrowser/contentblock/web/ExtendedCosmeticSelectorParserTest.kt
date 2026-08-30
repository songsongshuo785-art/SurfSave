package com.myAllVideoBrowser.contentblock.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtendedCosmeticSelectorParserTest {
    @Test
    fun nativeHas_usesGuardedProceduralPath() {
        val result = ExtendedCosmeticSelectorParser.partition(
            listOf(".advert", ".card:has([data-kind=advert])")
        )

        assertEquals(listOf(".advert"), result.staticSelectors)
        assertEquals(1, result.proceduralRules.size)
        assertEquals(
            ".card:has([data-kind=advert])",
            result.proceduralRules.single().selector.single().argument
        )
    }

    @Test
    fun hasTextAndNestedHasText_becomeStructuredRules() {
        val result = ExtendedCosmeticSelectorParser.partition(
            listOf(
                ".notice:has-text(Sponsored)",
                ".card:has(span:has-text(Advertisement))"
            )
        )

        assertTrue(result.staticSelectors.isEmpty())
        assertEquals(".notice", result.proceduralRules[0].selector[0].argument)
        assertEquals("Sponsored", result.proceduralRules[0].selector[1].argument)
        assertEquals(".card", result.proceduralRules[1].selector[0].argument)
        assertEquals("Advertisement", result.proceduralRules[1].selector[1].argument)
    }

    @Test
    fun onlyScopedAbpPropertiesBecomeBoundedComputedStyleMatchers() {
        val result = ExtendedCosmeticSelectorParser.partition(
            listOf(
                ":-abp-properties(height: 300px; width: 315px;)",
                ".banner:-abp-properties(*data:image*)"
            )
        )

        assertTrue(result.staticSelectors.isEmpty())
        assertEquals(1, result.proceduralRules.size)
        assertEquals(".banner", result.proceduralRules[0].selector[0].argument)
        assertEquals("data:image", result.proceduralRules[0].selector[1].argument)
    }

    @Test
    fun malformedExtendedSelector_isDroppedInsteadOfInjectedAsInvalidCss() {
        val result = ExtendedCosmeticSelectorParser.partition(
            listOf(".advert:has-text(")
        )

        assertTrue(result.staticSelectors.isEmpty())
        assertTrue(result.proceduralRules.isEmpty())
    }
}
