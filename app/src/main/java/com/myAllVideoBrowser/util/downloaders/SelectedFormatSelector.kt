package com.myAllVideoBrowser.util.downloaders

import com.myAllVideoBrowser.data.local.room.entity.VideoFormatEntity
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.media.DownloadStrategyResolver

/**
 * 从下载任务里取出「用户选中的那条 format」。
 *
 * 新任务的不变量：入队时已经把用户选择收敛成单独一条 format。
 * 旧任务（升级前入队）可能仍带多条 format，且没有任何策略盖章，此时保持旧版本的
 * `first()` 行为并打日志，绝不能因为多格式而让升级用户的老任务直接失败。
 *
 * 唯一会 fail fast 的情况：**已盖章**任务出现多条 format（新代码违反不变量），
 * 或显式 YTDLP/PAGE_EXTRACTOR 策略缺少 `extractorInputUrl`。
 */
internal object SelectedFormatSelector {

    fun select(videoInfo: VideoInfo, context: String): VideoFormatEntity {
        val formats = videoInfo.formats.formats
        if (formats.isEmpty()) {
            throw IllegalArgumentException("$context: download task has no selected format.")
        }

        if (formats.size > 1) {
            // 只认「可成功解析的显式 strategy」：未知持久化值不算盖章，走下面的 legacy 兼容路径，
            // 与 DownloadStrategyResolver 的 UNKNOWN_STRATEGY → legacy 回落保持一致。
            if (formats.any(DownloadStrategyResolver::hasValidExplicitStrategy)) {
                throw IllegalStateException(
                    "$context: stamped download task carries ${formats.size} formats."
                )
            }
            AppLogger.w(
                "DOWNLOAD_STRATEGY: LEGACY_MULTI_FORMAT count=${formats.size} " +
                    "task=${videoInfo.id} context=$context"
            )
        }

        val selected = formats.first()
        val resolution = DownloadStrategyResolver.resolve(videoInfo, selected)
        if (!resolution.isValid) {
            throw IllegalStateException(
                "$context: formatId=${selected.formatId} strategy=${resolution.strategy.name} " +
                    "is missing extractorInputUrl."
            )
        }
        return selected
    }
}
