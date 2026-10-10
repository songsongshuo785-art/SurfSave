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
            // 不能「非 MPD 默认 HLS」：老 format 可能只有 manifestRequestUrl 而没有清单类型证据，
            // 这种数据必须继续往下走到 legacy 回退，由本函数统一决定最终策略。
            val strategy = when {
                format.isMpd -> DownloadStrategy.DASH_MANIFEST
                format.isM3u8 -> DownloadStrategy.HLS_MANIFEST
                else -> null
            }
            if (strategy != null) {
                return DownloadStrategyResolution(
                    strategy = strategy,
                    provenance = DownloadStrategyProvenance.LEGACY,
                    extractorInputUrl = null,
                    isValid = true
                )
            }
            AppLogger.w(
                "DOWNLOAD_STRATEGY: SUSPECT_PROVENANCE formatId=${format.formatId} " +
                    "reason=superx_evidence_without_manifest_type"
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

    /**
     * 「已盖章」的权威判据：持久化字符串能解析成**当前版本认识的**枚举值。
     * 未知值（例如更高版本写入的 `DIRECT_FILE_V2`）不算盖章 —— 它由 legacy 路径安全接管，
     * 与 [resolve] 里 `parseStrategy` 返回 null 后的回落语义保持一致。
     * `SelectedFormatSelector` / `DownloadEngineKindResolver` 必须共用这一个判据。
     */
    fun hasValidExplicitStrategy(format: VideoFormatEntity): Boolean =
        parseStrategy(format.downloadStrategy) != null

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
