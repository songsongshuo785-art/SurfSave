package com.myAllVideoBrowser.util.site_adapters

import java.util.Locale

/**
 * 哪些站点允许用"面板打开"状态临时抑制 SwipeRefreshLayout 的下拉刷新。
 *
 * 背景：`SwipeRefreshLayout` 的 `canChildScrollUp` 只反映 WebView 的根滚动器，页面里独立滚动的
 * 浮层（Rule34Video 的过滤器面板）对它不可见，于是面板内的下滑被当成"页面到顶下拉"而触发整页
 * 刷新。修复方式是让站点探针（[Rule34FilterPanelProbe]）异步上报"面板是否打开"，这里只做站点
 * 门控与最终判定，纯函数便于单测。
 *
 * 语义（与 #25 验收一致）：**面板打开期间一律抑制**，不区分面板自身 `scrollTop` 是否为 0。
 */
object SiteScrollGesturePolicy {

    /** 已实现面板状态探测的站点；后缀匹配，避免 `evil-rule34video.com` 这类伪装域名。 */
    private val PANEL_GUARDED_HOSTS = setOf("rule34video.com")

    fun isPanelGuardedHost(host: String?): Boolean {
        if (host.isNullOrBlank()) {
            return false
        }
        val normalized = host.lowercase(Locale.US).trim().trimEnd('.')
        if (normalized.isEmpty()) {
            return false
        }
        return PANEL_GUARDED_HOSTS.any { guarded ->
            normalized == guarded || normalized.endsWith(".$guarded")
        }
    }

    /** 只有"受管站点 + 面板确实打开"才抑制下拉刷新，其余情况保持原有行为。 */
    fun shouldSuppressPullToRefresh(host: String?, panelOpen: Boolean): Boolean =
        panelOpen && isPanelGuardedHost(host)
}
