package com.myAllVideoBrowser.contentblock.web

import com.myAllVideoBrowser.contentblock.ProceduralCosmeticAction
import com.myAllVideoBrowser.contentblock.ProceduralCosmeticOperator
import com.myAllVideoBrowser.contentblock.ProceduralCosmeticRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProceduralCosmeticSanitizerTest {
    @Test
    fun commonHasAndHasTextRules_areKeptAsStructuredOperations() {
        val rules = listOf(
            rule(
                operator("css-selector", ".card"),
                operator("css-selector", "> [data-kind=advert]")
            ),
            rule(
                operator("css-selector", ".notice"),
                operator("has-text", "Sponsored")
            )
        )

        val sanitized = ProceduralCosmeticSanitizer.sanitize(rules)

        assertEquals(2, sanitized.size)
        assertEquals("css-selector", sanitized[0].operators[1].type)
        assertEquals("has-text", sanitized[1].operators[1].type)
        assertEquals("hide", sanitized[1].action.type)
    }

    @Test
    fun unknownOperatorsAndUnsafeStyleValues_areRejected() {
        val rules = listOf(
            rule(operator("css-selector", ".safe"), operator("run-script", "alert(1)")),
            rule(
                operator("css-selector", ".unsafe-style"),
                action = ProceduralCosmeticAction("style", "background:url(https://bad)")
            ),
            rule(
                operator("css-selector", ".partially-unsafe-style"),
                action = ProceduralCosmeticAction(
                    "style",
                    "height:0; background:url(https://bad)"
                )
            )
        )

        assertTrue(ProceduralCosmeticSanitizer.sanitize(rules).isEmpty())
    }

    @Test
    fun removeBecomesReversibleHide_andSafeStyleIsSplitIntoDeclarations() {
        val rules = listOf(
            rule(
                operator("css-selector", ".remove-me"),
                action = ProceduralCosmeticAction("remove")
            ),
            rule(
                operator("css-selector", ".collapse"),
                action = ProceduralCosmeticAction(
                    "style",
                    "height:0!important; overflow:hidden"
                )
            )
        )

        val sanitized = ProceduralCosmeticSanitizer.sanitize(rules)

        assertEquals("hide", sanitized[0].action.type)
        assertEquals(2, sanitized[1].action.styles.size)
        assertTrue(sanitized[1].action.styles.first().important)
    }

    private fun rule(
        vararg operators: ProceduralCosmeticOperator,
        action: ProceduralCosmeticAction? = null
    ) = ProceduralCosmeticRule(operators.toList(), action)

    private fun operator(type: String, argument: String) =
        ProceduralCosmeticOperator(type, argument)
}
