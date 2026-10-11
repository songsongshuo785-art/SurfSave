package com.myAllVideoBrowser.util.site_adapters

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 脚本契约测试：DOM 行为本身只能在真机确认（见 work.md 的 #25 清单），这里只守住几条不允许
 * 被改掉的边界——站点门控、只读、边沿触发、重新上报入口。
 */
class Rule34FilterPanelProbeTest {

    private val script = Rule34FilterPanelProbe.SCRIPT

    @Test
    fun `stays scoped to rule34video`() {
        assertTrue(script.contains("rule34video.com"))
        assertTrue(script.contains("location.hostname"))
        // 后缀匹配而不是 indexOf，避免 evil-rule34video.com 也命中。
        assertFalse(script.contains("indexOf('rule34video.com')"))
    }

    @Test
    fun `is read only`() {
        assertFalse(script.contains(".click("))
        assertFalse(script.contains("appendChild"))
        assertFalse(script.contains("innerHTML"))
        assertFalse(script.contains("setAttribute("))
        assertFalse(script.contains(".style."))
        assertFalse(script.contains("remove()"))
    }

    @Test
    fun `observes the dom and reports only when the state flips`() {
        assertTrue(script.contains("MutationObserver"))
        assertTrue(script.contains("subtree: true"))
        assertTrue(script.contains("attributes: true"))
        // 边沿触发：状态未变直接 return，不重复过桥。
        assertTrue(script.contains("if (!force && next === active)"))
    }

    @Test
    fun `treats several panels as or and respects aria state`() {
        assertTrue(script.contains("[aria-expanded=\"true\"][aria-controls]"))
        assertTrue(script.contains("aria-hidden"))
        assertTrue(script.contains("hidden"))
        assertTrue(script.contains("selectorPanelOpen() || overlayPanelOpen()"))
    }

    @Test
    fun `exposes a re-evaluate entry point for tab switches`() {
        assertTrue(script.contains("window.__surfsaveFilterPanelProbe"))
        assertTrue(script.contains("evaluate: function(force)"))
        assertTrue(Rule34FilterPanelProbe.REEVALUATE_SCRIPT.contains("__surfsaveFilterPanelProbe"))
        assertTrue(Rule34FilterPanelProbe.REEVALUATE_SCRIPT.contains("evaluate(true)"))
    }
}
