package com.myAllVideoBrowser.util.media

/**
 * 下载计划：由「用户选中的那个 format」自己携带，用来一次决定由哪个执行器下载。
 *
 * 设计约束：
 * - 该枚举以 [name] 字符串持久化在 `VideoFormatEntity.downloadStrategy`（Room 的 JSON 字段）里，
 *   因此**改名属于破坏性变更**；解析端必须容忍未知值并回落到 legacy 推断。
 * - 旧数据（字段为 null）不得依赖这里，必须经 [DownloadStrategyResolver] 推断。
 */
enum class DownloadStrategy {
    /** 已知可直接下载的媒体直链（浏览器/探针捕获的 mp4/webm/m4a 等）。 */
    DIRECT_HTTP,

    /** HLS 清单，由超级下载器（SuperX）执行。 */
    HLS_MANIFEST,

    /** DASH 清单，由超级下载器（SuperX）执行。 */
    DASH_MANIFEST,

    /** 需要 yt-dlp 重新对页面做 extractor 解析，再用 formatId 选择。 */
    PAGE_EXTRACTOR,

    /**
     * 由 yt-dlp 某次解析产生的 format。
     * `formatId` 只在「同一次解析输入」下有意义，因此必须与 [DownloadStrategyResolution.extractorInputUrl] 成对使用。
     */
    YTDLP_FORMAT
}

/** 策略来源：显式盖章（新数据）还是旧数据推断。 */
enum class DownloadStrategyProvenance {
    EXPLICIT,
    LEGACY
}

/**
 * 策略解析结果。
 *
 * @param extractorInputUrl 仅 [DownloadStrategy.YTDLP_FORMAT] / [DownloadStrategy.PAGE_EXTRACTOR] 有意义：
 *        产生该 formatId 的那一次 yt-dlp 实际输入 URL。显式策略下为 null 视为违反不变量（[isValid] = false），
 *        只有 legacy 推断才允许用 `videoInfo.originalUrl` 兜底。
 * @param isValid 新数据不变量是否成立；执行路径必须对 false 直接失败，展示路径可降级但要写日志。
 */
data class DownloadStrategyResolution(
    val strategy: DownloadStrategy,
    val provenance: DownloadStrategyProvenance,
    val extractorInputUrl: String?,
    val isValid: Boolean
)
