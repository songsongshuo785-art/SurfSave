package com.myAllVideoBrowser.contentblock

import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/**
 * 为被内容规则阻止的请求生成类型匹配的中性响应。
 *
 * WebView 没有直接返回 ERR_BLOCKED_BY_CLIENT 的公开接口。统一返回 text/plain 会让脚本、
 * 样式和图片走错误的 MIME/加载生命周期，因此这里集中维护最小替代响应；普通 block 与
 * 未来可能支持的 ABP redirect resource 仍是两种不同语义。
 */
object BlockedResourceResponseFactory {
    fun create(resourceType: BrowserResourceType): WebResourceResponse {
        val payload = payloadFor(resourceType)
        return WebResourceResponse(
            payload.mimeType,
            payload.encoding,
            payload.statusCode,
            payload.reasonPhrase,
            NO_STORE_HEADERS,
            ByteArrayInputStream(payload.body)
        )
    }

    internal fun payloadFor(resourceType: BrowserResourceType): BlockedResourcePayload {
        return when (resourceType) {
            BrowserResourceType.DOCUMENT,
            BrowserResourceType.SUBDOCUMENT -> textPayload(
                mimeType = "text/html",
                body = "<!doctype html><meta charset=\"utf-8\">"
            )

            BrowserResourceType.SCRIPT -> textPayload(
                mimeType = "application/javascript",
                body = "/* blocked by SurfSave */"
            )

            BrowserResourceType.STYLESHEET -> textPayload(
                mimeType = "text/css",
                body = "/* blocked by SurfSave */"
            )

            BrowserResourceType.IMAGE -> textPayload(
                mimeType = "image/svg+xml",
                body = TRANSPARENT_IMAGE
            )

            BrowserResourceType.FONT,
            BrowserResourceType.MEDIA,
            BrowserResourceType.XML_HTTP_REQUEST,
            BrowserResourceType.WEBSOCKET,
            BrowserResourceType.PING,
            BrowserResourceType.OTHER,
            BrowserResourceType.UNKNOWN -> BlockedResourcePayload(
                mimeType = "text/plain",
                encoding = "utf-8",
                statusCode = 204,
                reasonPhrase = "No Content",
                body = EMPTY_BODY
            )
        }
    }

    private fun textPayload(mimeType: String, body: String) = BlockedResourcePayload(
        mimeType = mimeType,
        encoding = "utf-8",
        statusCode = 200,
        reasonPhrase = "OK",
        body = body.toByteArray(Charsets.UTF_8)
    )

    private val NO_STORE_HEADERS = mapOf("Cache-Control" to "no-store")
    private val EMPTY_BODY = ByteArray(0)
    private const val TRANSPARENT_IMAGE =
        "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"1\" height=\"1\"/>"
}

internal data class BlockedResourcePayload(
    val mimeType: String,
    val encoding: String?,
    val statusCode: Int,
    val reasonPhrase: String,
    val body: ByteArray
)
