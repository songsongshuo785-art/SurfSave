package com.myAllVideoBrowser.util.downloaders

import com.myAllVideoBrowser.data.local.room.entity.ProgressInfo
import com.myAllVideoBrowser.data.local.room.entity.VideoFormatEntity
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.util.media.DownloadStrategy
import com.myAllVideoBrowser.util.media.DownloadStrategyResolution
import com.myAllVideoBrowser.util.media.DownloadStrategyResolver

/** 执行下载的引擎种类。 */
enum class DownloadEngineKind {
    /** `CustomRegularDownloader`：直接 HTTP 下载。 */
    REGULAR,

    /** `SuperXDownloader`：HLS/DASH 清单下载。 */
    SUPERX,

    /** `YoutubeDlDownloader`：yt-dlp 页面/extractor 解析。 */
    YTDLP
}

/**
 * 引擎判定的**唯一权威**。
 *
 * 这个判据必须贯穿：路由器、下载队列（execution token 事务、pause/cancel/resume）、
 * 启动对账、指纹。任何一处仍用旧布尔标记（`isRegularDownload` / `isDetectedBySuperX`）都会
 * 造成「Router 认为是 Custom，而队列认为是 yt-dlp」这类更危险的状态。
 *
 * 兼容策略：**完全未盖章的旧数据一律走 [legacyKindOf]**，与改动前的行为逐字一致；
 * 只要该 VideoInfo 上有任一 format 带显式策略，就按策略判定。
 */
internal object DownloadEngineKindResolver {

    fun kindOf(task: ProgressInfo): DownloadEngineKind = kindOf(task.videoInfo)

    fun kindOf(videoInfo: VideoInfo, format: VideoFormatEntity? = null): DownloadEngineKind {
        val selected = format ?: videoInfo.formats.formats.firstOrNull()
            ?: return legacyKindOf(videoInfo)

        val hasExplicitStrategy = selected.downloadStrategy != null ||
            videoInfo.formats.formats.any { it.downloadStrategy != null }
        if (!hasExplicitStrategy) {
            return legacyKindOf(videoInfo)
        }

        return engineKindOf(DownloadStrategyResolver.resolve(videoInfo, selected))
    }

    fun engineKindOf(resolution: DownloadStrategyResolution): DownloadEngineKind =
        when (resolution.strategy) {
            DownloadStrategy.DIRECT_HTTP -> DownloadEngineKind.REGULAR
            DownloadStrategy.HLS_MANIFEST,
            DownloadStrategy.DASH_MANIFEST -> DownloadEngineKind.SUPERX

            DownloadStrategy.PAGE_EXTRACTOR,
            DownloadStrategy.YTDLP_FORMAT -> DownloadEngineKind.YTDLP
        }

    /** 改动前的判据，仅用于未盖章的旧数据。 */
    private fun legacyKindOf(videoInfo: VideoInfo): DownloadEngineKind = when {
        videoInfo.isRegularDownload -> DownloadEngineKind.REGULAR
        videoInfo.isDetectedBySuperX -> DownloadEngineKind.SUPERX
        else -> DownloadEngineKind.YTDLP
    }
}
