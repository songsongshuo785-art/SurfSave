package com.myAllVideoBrowser.util.site_adapters

import android.webkit.JavascriptInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterPanelStateBridgeTest {

    @Test
    fun `forwards the panel state`() {
        val received = mutableListOf<Boolean>()
        val bridge = FilterPanelStateBridge { received += it }

        bridge.setFilterPanelOpen(true)
        bridge.setFilterPanelOpen(false)

        assertEquals(listOf(true, false), received)
    }

    @Test
    fun `a failing callback never propagates into the bridge thread`() {
        val bridge = FilterPanelStateBridge { throw IllegalStateException("boom") }

        bridge.setFilterPanelOpen(true)
    }

    @Test
    fun `the bridge method is exposed to javascript`() {
        // 没有 @JavascriptInterface 的公开方法在 API 17+ 不会被注入到页面,探针会静默失效。
        val method = FilterPanelStateBridge::class.java.getDeclaredMethod(
            "setFilterPanelOpen",
            Boolean::class.javaPrimitiveType
        )
        assertNotNull(method.getAnnotation(JavascriptInterface::class.java))
    }

    @Test
    fun `the probe talks to the registered bridge name`() {
        assertTrue(
            Rule34FilterPanelProbe.SCRIPT.contains("window.${Rule34FilterPanelProbe.BRIDGE_NAME}")
        )
        assertTrue(
            Rule34FilterPanelProbe.SCRIPT.contains("setFilterPanelOpen")
        )
    }
}
