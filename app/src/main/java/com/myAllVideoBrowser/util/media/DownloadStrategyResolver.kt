package com.myAllVideoBrowser.util.media

import com.myAllVideoBrowser.data.local.room.entity.VideoFormatEntity
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.util.AppLogger

/**
 * 下载策略的唯一解析点。
 *
 * 判定顺序（first match wins），对应评审确认的表格：
 * 1. format 上已有显式 `downloadStrategy` → 直接采用（新数据）；显式 YTDLP/PAGE_EXTRACTOR 缺
 *    `extractorInputUrl` 视为**违反新数据不变量**（`isValid = false`），不再回退 `videoInfo.originalUrl`。
 * 2. `videoInfo.isRegularDownload` → [DownloadStrategy.DIRECT_HTTP]（legacy 直接下载证据）。
 * 3. **format 局部** SuperX 强证据（`manifestRequestUrl` 或 SurfSave 自己合成的
 *    `hls-*` / `mpd-*` formatId）→ HLS/DASH。这里刻意不使用 `videoInfo.isDetectedBySuperX`：
 *    合并后的 VideoInfo 是 VideoInfo 级标记，会污染同一对象内的 yt-dlp format。
 * 4. 其余 → [DownloadStrategy.YTDLP_FORMAT]（legacy 推断，此处才允许 `videoInfo.originalUrl` 兜底）。
 *
 * 本函数**不抛异常**：展示路径（列表渲染 / DiffUtil）会频繁调用它，未知或损坏的持久化字符串
 * 只应降级 + 打日志，不能把界面或下载队列炸掉。执行路径由 `SelectedFormatSelector` 检查 [DownloadStrategyResolution.isValid]。
 */
internal object DownloadStrategyResolver {

    fun resolve(videoInfo: VideoInfo, format: VideoFormatEntity): DownloadStrategyResolution {
        parseStrategy(format.downloadStrategy)?.let { explicit ->
            return explicitResolution(explicit, format)
        }

        if (videoInfo.isRegularDownload) {
            return DownloadStrategyResolution(
                strategy = DownloadStrategy.DIRECT_HTTP,
                provenance = DownloadStrategyProvenance.LEGACY,
                extractorInputUrl = null,
                isValid = true
            )
        }

        if (hasSuperXProvenance(format)) {
            val strategy = if (format.isMpd) {
                DownloadStrategy.DASH_MANIFEST
            } else {
                DownloadStrategy.HLS_MANIFEST
            }
            return DownloadStrategyResolution(
                strategy = strategy,
                provenance = DownloadStrategyProvenance.LEGACY,
                extractorInputUrl = null,
                isValid = true
            )
        }

        return DownloadStrategyResolution(
            strategy = DownloadStrategy.YTDLP_FORMAT,
            provenance = DownloadStrategyProvenance.LEGACY,
            extractorInputUrl = format.extractorInputUrl?.takeIf { it.isNotBlank() }
                ?: videoInfo.originalUrl.takeIf { it.isNotBlank() },
            isValid = true
        )
    }

    private fun explicitResolution(
        strategy: DownloadStrategy,
        format: VideoFormatEntity
    ): DownloadStrategyResolution {
        val requiresExtractorInput =
            strategy == DownloadStrategy.YTDLP_FORMAT || strategy == DownloadStrategy.PAGE_EXTRACTOR
        val extractorInputUrl = format.extractorInputUrl?.takeIf { it.isNotBlank() }

        if (requiresExtractorInput && extractorInputUrl == null) {
            AppLogger.e(
                "DOWNLOAD_STRATEGY: INVALID_STRATEGY strategy=${strategy.name} " +
                    "formatId=${format.formatId} reason=missing_extractor_input"
            )
            return DownloadStrategyResolution(
                strategy = strategy,
                provenance = DownloadStrategyProvenance.EXPLICIT,
                extractorInputUrl = null,
                isValid = false
            )
        }

        if (strategy == DownloadStrategy.DIRECT_HTTP && format.url.isNullOrBlank()) {
            // 不视为不变量违规（避免引入未经确认的 fail-fast），但必须可见。
            AppLogger.w(
                "DOWNLOAD_STRATEGY: SUSPECT_STRATEGY strategy=DIRECT_HTTP " +
                    "formatId=${format.formatId} reason=missing_direct_url"
            )
        }

        return DownloadStrategyResolution(
            strategy = strategy,
            provenance = DownloadStrategyProvenance.EXPLICIT,
            extractorInputUrl = extractorInputUrl,
            isValid = true
        )
    }

    /**
     * 只认 SurfSave 自己产生的 format 级证据，不使用 VideoInfo 级标记。
     * `hls-media` 已被 `hls-` 前缀覆盖，保留显式判断以便阅读。
     */
    private fun hasSuperXProvenance(format: VideoFormatEntity): Boolean {
        if (!format.manifestRequestUrl.isNullOrBlank()) {
            return true
        }
        val formatId = format.formatId.orEmpty()
        return formatId.startsWith("hls-", ignoreCase = true) ||
            formatId.equals("hls-media", ignoreCase = true) ||
            formatId.startsWith("mpd-", ignoreCase = true)
    }

    /** 禁止裸 `Enum.valueOf()`：持久化字符串可能来自更早/更高版本或已损坏数据。 */
    private fun parseStrategy(raw: String?): DownloadStrategy? {
        val value = raw?.trim().orEmpty()
        if (value.isBlank()) {
            return null
        }
        val parsed = DownloadStrategy.entries.firstOrNull { it.name == value }
        if (parsed == null) {
            AppLogger.w("DOWNLOAD_STRATEGY: UNKNOWN_STRATEGY value=$value")
        }
        return parsed
    }
}
