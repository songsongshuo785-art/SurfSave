package com.myAllVideoBrowser.contentblock.web

import com.myAllVideoBrowser.contentblock.CosmeticResources
import com.myAllVideoBrowser.contentblock.ProceduralCosmeticOperator
import com.myAllVideoBrowser.contentblock.ProceduralCosmeticRule
import com.myAllVideoBrowser.contentblock.TrustedScriptletInvocation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentBlockJavaScriptBridgeTest {
    @Test
    fun bootstrapScriptRetriesStyleInsertionAndObservesClassAndIdChanges() {
        val script = ContentBlockWebController.BOOTSTRAP_SCRIPT

        assertTrue(script.contains("DOMContentLoaded', () => applySelectors([])"))
        assertTrue(script.contains("mutation.type === 'attributes'"))
        assertTrue(script.contains("attributes: true"))
        assertTrue(script.contains("attributeFilter: ['class', 'id']"))
        assertEquals(1, Regex("new MutationObserver").findAll(script).count())
        assertTrue(script.contains("work.operations < 4096"))
        assertTrue(script.contains("performance.now() + 12"))
        assertTrue(script.contains("state.proceduralRuns >= 24"))
    }

    @Test
    fun bootstrapKeepsSafeSelectorsAndStructuredTrustedActionsOnly() {
        val bridge = bridge(
            resources = CosmeticResources(
                hideSelectors = listOf(".advert", "x{background:url(https://bad)}", ".advert"),
                exceptions = listOf(".allowed")
            ),
            scriptlets = listOf(
                TrustedScriptletInvocation("remove-cookie", "ad_session", "ignored-source"),
                TrustedScriptletInvocation("unknown", "value", "alert(1)")
            )
        )

        val payload = Json.parseToJsonElement(
            bridge.bootstrap("https://example.org/watch#comments", DOCUMENT_TOKEN)
        ).jsonObject

        assertTrue(payload.getValue("active").jsonPrimitive.content.toBoolean())
        assertEquals(
            listOf(".advert"),
            payload.getValue("selectors").jsonArray.map { it.jsonPrimitive.content }
        )
        val actions = payload.getValue("actions").jsonArray
        assertEquals(1, actions.size)
        assertEquals("remove-cookie", actions[0].jsonObject.getValue("name").jsonPrimitive.content)
        assertFalse(payload.toString().contains("ignored-source"))
        assertFalse(payload.toString().contains("alert(1)"))
    }

    @Test
    fun inactivePageReturnsNoSelectorsOrScriptlets() {
        val bridge = ContentBlockJavaScriptBridge(
            isActive = { false },
            cosmeticProvider = { error("must not query") },
            dynamicProvider = { _, _, _, _ -> error("must not query") },
            scriptletProvider = { error("must not query") },
            testMarker = Unit
        )

        val payload = Json.parseToJsonElement(
            bridge.bootstrap("https://example.org/", DOCUMENT_TOKEN)
        ).jsonObject

        assertFalse(payload.getValue("active").jsonPrimitive.content.toBoolean())
        assertTrue(payload.getValue("selectors").jsonArray.isEmpty())
    }

    @Test
    fun bootstrapIncludesOnlySanitizedStructuredProceduralRules() {
        val bridge = bridge(
            resources = CosmeticResources(
                proceduralRules = listOf(
                    ProceduralCosmeticRule(
                        selector = listOf(
                            ProceduralCosmeticOperator("css-selector", ".card"),
                            ProceduralCosmeticOperator("has-text", "Sponsored")
                        )
                    ),
                    ProceduralCosmeticRule(
                        selector = listOf(
                            ProceduralCosmeticOperator("css-selector", ".unsafe"),
                            ProceduralCosmeticOperator("run-script", "alert(1)")
                        )
                    )
                )
            ),
            scriptlets = emptyList()
        )

        val payload = Json.parseToJsonElement(
            bridge.bootstrap("https://example.org/watch", DOCUMENT_TOKEN)
        ).jsonObject
        val rules = payload.getValue("proceduralRules").jsonArray

        assertEquals(1, rules.size)
        assertEquals(
            "has-text",
            rules.single().jsonObject.getValue("operators").jsonArray[1]
                .jsonObject.getValue("type").jsonPrimitive.content
        )
        assertFalse(payload.toString().contains("alert(1)"))
    }

    @Test
    fun refreshScriptDisposesOldGenerationBeforeReapplyingRules() {
        val script = ContentBlockWebController.FORCE_REFRESH_SCRIPT

        assertTrue(script.indexOf("state.dispose") < script.indexOf("bridge.bootstrap"))
        assertTrue(ContentBlockWebController.CLEAR_SCRIPT.contains("state.dispose"))
        assertTrue(ContentBlockWebController.CLEAR_SCRIPT.contains("delete window"))
    }

    @Test
    fun generichidePreventsDynamicSelectorLookup() {
        var dynamicCalls = 0
        val bridge = ContentBlockJavaScriptBridge(
            isActive = { true },
            cosmeticProvider = { CosmeticResources(generichide = true) },
            dynamicProvider = { _, _, _, _ ->
                dynamicCalls++
                listOf(".dynamic-ad")
            },
            scriptletProvider = { emptyList() },
            testMarker = Unit
        )
        bridge.bootstrap("https://example.org/", DOCUMENT_TOKEN)

        assertEquals(
            "[]",
            bridge.dynamic(
                "https://example.org/",
                DOCUMENT_TOKEN,
                "[\"advert\"]",
                "[]"
            )
        )
        assertEquals(0, dynamicCalls)
    }

    @Test
    fun dynamicLookupUsesCurrentPageAndSanitizesOutput() {
        var observedExceptions: Collection<String> = emptyList()
        val bridge = ContentBlockJavaScriptBridge(
            isActive = { true },
            cosmeticProvider = { CosmeticResources(exceptions = listOf(".allowed")) },
            dynamicProvider = { _, classes, ids, exceptions ->
                assertEquals(listOf("advert"), classes)
                assertEquals(listOf("banner"), ids)
                observedExceptions = exceptions
                listOf(".advert", "x;body{display:none}")
            },
            scriptletProvider = { emptyList() },
            testMarker = Unit
        )
        bridge.bootstrap("https://example.org/", DOCUMENT_TOKEN)

        val result = Json.parseToJsonElement(
            bridge.dynamic(
                "https://example.org/",
                DOCUMENT_TOKEN,
                "[\"advert\",\"advert\"]",
                "[\"banner\"]"
            )
        ).jsonArray.map { it.jsonPrimitive.content }

        assertEquals(listOf(".allowed"), observedExceptions)
        assertEquals(listOf(".advert"), result)
        assertEquals(
            "[]",
            bridge.dynamic(
                "https://other.example/",
                DOCUMENT_TOKEN,
                "[\"ad\"]",
                "[]"
            )
        )
    }

    @Test
    fun documentStatesRemainIndependentAcrossTopPageAndIframe() {
        val observedPages = mutableListOf<String>()
        val bridge = ContentBlockJavaScriptBridge(
            isActive = { true },
            cosmeticProvider = { pageUrl ->
                val host = pageUrl.substringAfter("//").substringBefore('/')
                CosmeticResources(exceptions = listOf(".allow-$host"))
            },
            dynamicProvider = { pageUrl, _, _, exceptions ->
                observedPages += "$pageUrl:${exceptions.single()}"
                listOf(".dynamic-ad")
            },
            scriptletProvider = { emptyList() },
            testMarker = Unit
        )

        bridge.bootstrap("https://top.example/", "top-frame-token")
        bridge.bootstrap("https://frame.example/", "child-frame-token")

        assertEquals(
            "[\".dynamic-ad\"]",
            bridge.dynamic(
                "https://top.example/",
                "top-frame-token",
                "[\"advert\"]",
                "[]"
            )
        )
        assertEquals(
            "[\".dynamic-ad\"]",
            bridge.dynamic(
                "https://frame.example/",
                "child-frame-token",
                "[\"advert\"]",
                "[]"
            )
        )
        assertEquals(
            listOf(
                "https://top.example/:.allow-top.example",
                "https://frame.example/:.allow-frame.example"
            ),
            observedPages
        )
    }

    @Test
    fun bootstrapScriptGuardsSemanticLayoutAndPassesPerDocumentToken() {
        val script = ContentBlockWebController.BOOTSTRAP_SCRIPT

        assertTrue(script.contains("bridge.bootstrap(pageUrl, documentToken)"))
        assertTrue(script.contains("state.documentToken"))
        assertTrue(script.contains("if (isProtectedStructure(element)) return"))
        assertTrue(script.contains("CSS.supports('selector(:is(*))')"))
        assertTrue(script.contains("[role=\"navigation\"]"))
        assertTrue(script.contains("element.querySelectorAll('a[href],button,[role=\"tab\"]')"))
    }

    private fun bridge(
        resources: CosmeticResources,
        scriptlets: List<TrustedScriptletInvocation>
    ): ContentBlockJavaScriptBridge {
        return ContentBlockJavaScriptBridge(
            isActive = { true },
            cosmeticProvider = { resources },
            dynamicProvider = { _, _, _, _ -> emptyList() },
            scriptletProvider = { scriptlets },
            testMarker = Unit
        )
    }

    companion object {
        private const val DOCUMENT_TOKEN = "unit-test-document-token"
    }
}
