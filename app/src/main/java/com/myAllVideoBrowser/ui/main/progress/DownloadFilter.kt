package com.myAllVideoBrowser.ui.main.progress

import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.data.local.room.entity.ProgressInfo

/**
 * 下载页三个筛选的数据源。
 *
 * - 全部 = 进行中 + 已完成（进行中在前，已完成按 completedAt 倒序）
 * - 进行中 = progressInfos
 * - 已完成 = historyInfos
 */
object DownloadFilter {
    fun mediaFor(
        filter: Int,
        active: List<ProgressInfo>,
        history: List<ProgressInfo>,
    ): List<ProgressInfo> = when (filter) {
        R.id.downloads_active -> active
        R.id.downloads_completed -> history
        else -> active + history
    }
}
