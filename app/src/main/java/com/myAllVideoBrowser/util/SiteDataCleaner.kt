package com.myAllVideoBrowser.util

import android.webkit.WebStorage
import java.net.URI
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 网页 origin 的规范化工具。
 *
 * 与 Chromium 的 origin 序列化保持一致:scheme 与 host 小写、省略默认端口(443/80)、不带路径与查询。
 * 非 http/https 的 URL(about:blank、file、data、blob、intent 等)没有可清理的站点存储,返回 null。
 */
object SiteOrigin {

    private const val HTTP_PORT = 80
    private const val HTTPS_PORT = 443

    fun of(rawUrl: String?): String? {
        if (rawUrl.isNullOrBlank()) return null

        val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.US) ?: return null
        if (scheme != "http" && scheme != "https") return null

        // 注意:非 ASCII(国际化域名)的 host 会解析为 null,此时视为没有可清理的站点存储。
        val host = uri.host?.lowercase(Locale.US) ?: return null

        val port = uri.port
        val isDefaultPort = (scheme == "https" && port == HTTPS_PORT) ||
            (scheme == "http" && port == HTTP_PORT)

        return if (port == -1 || isDefaultPort) "$scheme://$host" else "$scheme://$host:$port"
    }
}

/**
 * 清理当前站点的网页存储。
 *
 * 只处理 WebView Origin Storage(Web Storage / AppCache / WebSQL 等);
 * 不处理 Cookie,也不处理使用独立 IndexedDB / Cache API 保存状态的网站。
 */
@Singleton
class SiteDataCleaner @Inject constructor() {

    /**
     * 请求清空该 origin 的网页存储。
     *
     * [WebStorage.deleteOrigin] 是 void 且无回调,无法得知何时真正完成,
     * 因此不返回"是否已清除";失败只记录日志。
     *
     * 另外:该调用对已经加载的文档不生效(页面内存里仍有一份),
     * 调用方必须在之后刷新页面。
     */
    fun requestClearOriginWebStorage(origin: String) {
        runCatching { WebStorage.getInstance().deleteOrigin(origin) }
            .onFailure {
                AppLogger.d("SITE_DATA: deleteOrigin failed origin=$origin error=${it.javaClass.simpleName}")
            }
    }
}
