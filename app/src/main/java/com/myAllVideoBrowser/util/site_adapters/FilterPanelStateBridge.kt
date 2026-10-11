package com.myAllVideoBrowser.util.site_adapters

import android.webkit.JavascriptInterface

/**
 * 站点探针 → 原生的单向状态回传：只接受一个布尔值，不回传任何页面数据、不触发任何行为。
 *
 * `@JavascriptInterface` 方法在 WebView 的 JavaBridge 线程被调用，调用方需自行切回主线程。
 */
class FilterPanelStateBridge(private val onPanelStateChanged: (Boolean) -> Unit) {

    @JavascriptInterface
    fun setFilterPanelOpen(open: Boolean) {
        runCatching { onPanelStateChanged(open) }
    }
}
