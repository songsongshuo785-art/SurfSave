package com.myAllVideoBrowser.util.downloaders

import com.myAllVideoBrowser.data.local.room.entity.ProgressInfo
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.util.media.FormatIdentity
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

object DownloadFingerprint {
    private val volatileQueryKeys = setOf(
        "auth",
        "authorization",
        "e",
        "expire",
        "expires",
        "fbclid",
        "gclid",
        "igshid",
        "key",
        "msclkid",
        "pass",
        "password",
        "policy",
        "ref",
        "session",
        "sig",
        "signature",
        "source",
        "spm",
        "token",
        "utm_campaign",
        "utm_content",
        "utm_medium",
        "utm_source",
        "utm_term"
    )

    fun fromProgressInfo(progressInfo: ProgressInfo): String {
        return progressInfo.downloadFingerprint.ifBlank {
            fromVideoInfo(progressInfo.videoInfo)
        }
    }

    fun fromVideoInfo(videoInfo: VideoInfo): String {
        // 指纹的"主身份"与选择键同源：引擎种类 + strategy-aware FormatIdentity。
        // 不再把 downloadUrls / originalUrl / 所有候选 URL 混进来：那些是"这个页面见过什么"，
        // 而不是"这一次要下载哪个文件"（否则 720p 与 1080p 会得到相同指纹）。
        // 旧数据（未盖章）仍取 first format，与改动前一致。
        val selected = videoInfo.formats.formats.firstOrNull()
        val raw = listOf(
            DownloadEngineKindResolver.kindOf(videoInfo, selected).name,
            selected?.let {
                FormatIdentity.ofWithNormalizer(videoInfo, it, ::normalizeUrl)
            }.orEmpty(),
            videoInfo.ext
        ).joinToString("#")

        return sha256(raw)
    }

    /**
     * 比 UI 侧 [com.myAllVideoBrowser.ui.main.home.browser.detectedVideos.MediaUrlIdentity] 更宽的
     * 指纹专用归一化：还剔 gclid/session/auth 等 volatile 键、排序剩余参数、归一化默认端口。
     * 函数类型与 `FormatIdentity.ofWithNormalizer` 的入参一致（`(String?) -> String`）。
     */
    internal fun normalizeUrl(input: String?): String {
        val trimmed = input?.trim().orEmpty()
        if (trimmed.isBlank()) {
            return ""
        }

        return runCatching {
            val uri = URI(trimmed)
            val scheme = uri.scheme?.lowercase(Locale.US) ?: return@runCatching trimmed
            val host = uri.host?.lowercase(Locale.US)
            val port = when {
                uri.port == -1 -> -1
                scheme == "http" && uri.port == 80 -> -1
                scheme == "https" && uri.port == 443 -> -1
                else -> uri.port
            }
            val path = uri.rawPath.orEmpty().ifBlank { "/" }
            val query = normalizeQuery(uri.rawQuery)
            URI(
                scheme,
                uri.rawUserInfo,
                host,
                port,
                path,
                query,
                null
            ).toASCIIString()
        }.getOrElse {
            trimmed.substringBefore("#")
        }
    }

    private fun normalizeQuery(rawQuery: String?): String? {
        if (rawQuery.isNullOrBlank()) {
            return null
        }

        val kept = rawQuery.split("&")
            .mapNotNull { part ->
                val key = part.substringBefore("=", "").lowercase(Locale.US)
                if (key.isBlank() || key in volatileQueryKeys || key.startsWith("x-amz-")) {
                    null
                } else {
                    part
                }
            }
            .sorted()

        return kept.takeIf { it.isNotEmpty() }?.joinToString("&")
    }

    private fun sha256(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
