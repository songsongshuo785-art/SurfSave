package com.myAllVideoBrowser.util

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

/**
 * 媒体地址可能与页面地址分属不同域名。这里只继承安全的页面请求头；Cookie 与鉴权头
 * 仅在同源时继承，或使用 yt-dlp 为具体格式返回的专用值，避免把页面凭据发给 CDN 之外的域名。
 */
object MediaRequestHeaderPolicy {
    private val blockedHeaderNames = setOf(
        "connection",
        "content-length",
        "host",
        "proxy-authorization",
        "transfer-encoding"
    )
    private val originBoundHeaderNames = setOf("authorization", "cookie")

    fun fromJsonObject(json: JSONObject?): Map<String, String> {
        if (json == null) return emptyMap()
        val result = linkedMapOf<String, String>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            val value = json.opt(name)?.toString().orEmpty()
            putSanitized(result, name, value)
        }
        return result
    }

    fun mergeForFormat(
        sourceHeaders: Map<String, String>,
        formatHeaders: Map<String, String>,
        sourceUrl: String,
        mediaUrl: String
    ): Map<String, String> {
        val sameOrigin = isSameOrigin(sourceUrl, mediaUrl)
        val result = linkedMapOf<String, String>()
        sourceHeaders.forEach { (name, value) ->
            if (sameOrigin || name.lowercase() !in originBoundHeaderNames) {
                putSanitized(result, name, value)
            }
        }
        formatHeaders.forEach { (name, value) -> putSanitized(result, name, value) }
        return result
    }

    fun forPlayback(
        storedHeaders: Map<String, String>,
        freshCookie: String?
    ): Map<String, String> {
        val result = linkedMapOf<String, String>()
        storedHeaders.forEach { (name, value) -> putSanitized(result, name, value) }
        if (!freshCookie.isNullOrBlank()) {
            putSanitized(result, "Cookie", freshCookie)
        }
        return result
    }

    private fun putSanitized(target: MutableMap<String, String>, rawName: String, rawValue: String) {
        val name = rawName.trim()
        val value = rawValue.trim()
        if (name.isEmpty() || value.isEmpty() || '\r' in name || '\n' in name ||
            '\r' in value || '\n' in value || name.lowercase() in blockedHeaderNames
        ) {
            return
        }
        target.keys.firstOrNull { it.equals(name, ignoreCase = true) }?.let(target::remove)
        target[name] = value
    }

    private fun isSameOrigin(first: String, second: String): Boolean {
        val firstUrl = first.toHttpUrlOrNull() ?: return false
        val secondUrl = second.toHttpUrlOrNull() ?: return false
        return firstUrl.scheme == secondUrl.scheme &&
            firstUrl.host == secondUrl.host &&
            firstUrl.port == secondUrl.port
    }
}
