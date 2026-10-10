package com.myAllVideoBrowser.util.media

import com.myAllVideoBrowser.data.local.room.entity.VideoFormatEntity
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.ui.main.home.browser.detectedVideos.MediaUrlIdentity

/**
 * 格式身份（选择键 / 去重键）的唯一来源，strategy-aware。
 *
 * 为什么不能"归一化 URL 优先"：
 * - HLS master 的多个清晰度共享同一个 `url = manifest.baseUri`（见 VideoServiceSuperX 的 HLS/DASH 分支），
 *   真正区分它们的是 `formatId`（`hls-{height}p-{bandwidth}` / `mpd-{height}p-{bandwidth}`）、`videoOnlyUrl`、height、bitrate。
 * - 直链的 `formatId` 反而是恒定的（`"0"` / `"direct"`），所以 DIRECT 身份**只能**用媒体 URL，绝不能带 formatId。
 *
 * 不变量：宁可多留一条，也不能把不同清晰度合并。
 */
internal object FormatIdentity {

    /**
     * @param urlNormalizer URL 归一化器。UI / 检测去重使用 [MediaUrlIdentity]
     *        （保守：只去签名类参数与 utm_ 前缀，gclid / session / ref 等一律保留）；
     *        `DownloadFingerprint` 传入自己更宽的归一化器（还会剥 gclid、session、ref、source 等跟踪参数），
     *        以保持既有的重复下载拦截强度。
     */
    fun of(
        videoInfo: VideoInfo,
        format: VideoFormatEntity,
        urlNormalizer: (String?) -> String = MediaUrlIdentity::of
    ): String {
        val resolution = DownloadStrategyResolver.resolve(videoInfo, format)
        val parts = when (resolution.strategy) {
            DownloadStrategy.DIRECT_HTTP -> listOf(
                DIRECT_PREFIX,
                urlNormalizer(format.url)
            )

            DownloadStrategy.HLS_MANIFEST -> listOf(
                HLS_PREFIX,
                urlNormalizer(format.manifestUrl ?: format.url),
                format.formatId.orEmpty(),
                format.videoOnlyUrl.orEmpty(),
                positive(format.height),
                positive(format.bitrate)
            )

            DownloadStrategy.DASH_MANIFEST -> listOf(
                DASH_PREFIX,
                urlNormalizer(format.manifestUrl ?: format.url),
                format.formatId.orEmpty(),
                positive(format.height),
                positive(format.bitrate)
            )

            DownloadStrategy.PAGE_EXTRACTOR,
            DownloadStrategy.YTDLP_FORMAT -> listOf(
                // 显式策略但 extractorInputUrl 缺失时这里会是空串：这是 resolver 已记录的不变量违规，
                // 刻意不回退 videoInfo.originalUrl，否则会重新制造「直链 format + 页面 URL」的混合对象。
                YTDLP_PREFIX,
                urlNormalizer(resolution.extractorInputUrl),
                format.formatId.orEmpty()
            )
        }

        if (parts.drop(1).all { it.isBlank() }) {
            // 无任何可用信息时退化为 format 主键，避免把所有未知 format 合并成一条。
            return parts.first() + "|" + format.id
        }

        return parts.joinToString(SEPARATOR)
    }

    /**
     * 旧算法（2026-10 之前的 selectionKey），仅用于解析升级前的旧选择键。
     * 保留它是为了 `findFormat` 能在 merge / 升级后仍解析到用户原来选中的那条 format。
     */
    fun legacySelectionKey(format: VideoFormatEntity): String {
        return format.formatId?.takeIf { it.isNotBlank() }
            ?: format.format?.takeIf { it.isNotBlank() }
            ?: format.url?.takeIf { it.isNotBlank() }
            ?: format.videoOnlyUrl?.takeIf { it.isNotBlank() }
            ?: format.audioOnlyUrl?.takeIf { it.isNotBlank() }
            ?: format.id
    }

    private fun positive(value: Int): String = if (value > 0) value.toString() else ""

    private fun positive(value: Long?): String =
        if (value != null && value > 0) value.toString() else ""

    private const val SEPARATOR = "|"
    private const val DIRECT_PREFIX = "direct"
    private const val HLS_PREFIX = "hls"
    private const val DASH_PREFIX = "dash"
    private const val YTDLP_PREFIX = "ytdlp"
}
