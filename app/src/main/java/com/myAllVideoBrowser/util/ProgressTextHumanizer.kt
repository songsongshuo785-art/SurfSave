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

    private val audioExtensions = setOf(
        "aac", "aiff", "amr", "flac", "m4a", "mp3", "ogg", "opus", "wav", "wma"
    )

    fun statusText(context: Context, status: Int): String {
        val res = when (status) {
            VideoTaskState.DOWNLOADING,
            VideoTaskState.PROXYREADY -> R.string.download_status_downloading

            VideoTaskState.PAUSE,
            VideoTaskState.PAUSING -> R.string.download_status_paused

            VideoTaskState.PENDING -> R.string.download_status_waiting
            VideoTaskState.PREPARE -> R.string.download_status_preparing
            VideoTaskState.START -> R.string.download_status_fetching
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
        if (info.downloadStatus == VideoTaskState.FINALIZING) {
            return context.getString(
                R.string.download_progress_finalizing,
                100,
                context.getString(R.string.download_status_merging)
            )
        }

        val downloaded = getFileSizeReadable(info.progressDownloaded.toDouble())
        val size = if (info.progressTotal > 0) {
            "$downloaded / ${getFileSizeReadable(info.progressTotal.toDouble())}"
        } else {
            "$downloaded · ${context.getString(R.string.candidate_unknown_size)}"
        }
        val status = when (info.downloadStatus) {
            VideoTaskState.DOWNLOADING,
            VideoTaskState.PROXYREADY -> context.getString(
                if (isAudio(info)) R.string.download_status_downloading_audio
                else R.string.download_status_downloading_video
            )
            else -> statusText(context, info.downloadStatus)
        }
        return if (status.isBlank()) size else "$size · $status"
    }

    private fun isAudio(info: ProgressInfo): Boolean {
        val extension = info.videoInfo.ext.trim().lowercase().substringBefore('?')
        if (extension in audioExtensions) return true

        val format = info.videoInfo.formats.formats.firstOrNull() ?: return false
        return format.vcodec.isNullOrBlank() && !format.acodec.isNullOrBlank()
    }
}
