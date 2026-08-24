package com.myAllVideoBrowser.util

import android.content.Context
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.data.local.room.entity.ProgressInfo
import com.myAllVideoBrowser.util.FileUtil.Companion.getFileSizeReadable
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskState

/**
 * Turns raw downloader state (yt-dlp logs, English status words) into short
 * localized lines for the progress list.
 */
object ProgressTextHumanizer {

    fun statusText(context: Context, status: Int): String {
        val res = when (status) {
            VideoTaskState.DOWNLOADING,
            VideoTaskState.START,
            VideoTaskState.PROXYREADY -> R.string.download_status_downloading

            VideoTaskState.PAUSE,
            VideoTaskState.PAUSING -> R.string.download_status_paused

            VideoTaskState.PENDING -> R.string.download_status_pending
            VideoTaskState.PREPARE -> R.string.download_status_preparing
            VideoTaskState.FINALIZING -> R.string.download_status_merging
            VideoTaskState.CANCELING -> R.string.download_status_canceling
            VideoTaskState.ERROR,
            VideoTaskState.ENOSPC -> R.string.download_status_failed

            else -> return ""
        }
        return context.getString(res)
    }

    /** "4.5 MB / 120 MB · 下载中" */
    fun progressLine(context: Context, info: ProgressInfo): String {
        val downloaded = getFileSizeReadable(info.progressDownloaded.toDouble())
        val size = if (info.progressTotal > 0) {
            "$downloaded / ${getFileSizeReadable(info.progressTotal.toDouble())}"
        } else {
            "$downloaded · ${context.getString(R.string.candidate_unknown_size)}"
        }
        val status = statusText(context, info.downloadStatus)
        return if (status.isBlank()) size else "$size · $status"
    }
}
