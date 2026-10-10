package com.myAllVideoBrowser.util.downloaders

import android.content.Context
import com.myAllVideoBrowser.data.local.room.entity.ProgressInfo
import com.myAllVideoBrowser.util.downloaders.custom_downloader.CustomRegularDownloader
import com.myAllVideoBrowser.util.downloaders.super_x_downloader.SuperXDownloader
import com.myAllVideoBrowser.util.downloaders.youtubedl_downloader.YoutubeDlDownloader
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadEngineRouter @Inject constructor() {
    fun start(context: Context, task: ProgressInfo) {
        when (DownloadEngineKindResolver.kindOf(task)) {
            DownloadEngineKind.REGULAR -> CustomRegularDownloader.startDownload(context, task.videoInfo)
            DownloadEngineKind.SUPERX -> SuperXDownloader.startDownload(context, task.videoInfo)
            DownloadEngineKind.YTDLP -> YoutubeDlDownloader.startDownload(context, task)
        }
    }

    fun pause(context: Context, task: ProgressInfo) {
        when (DownloadEngineKindResolver.kindOf(task)) {
            DownloadEngineKind.REGULAR -> CustomRegularDownloader.pauseDownload(context, task)
            DownloadEngineKind.SUPERX -> SuperXDownloader.pauseDownload(context, task)
            DownloadEngineKind.YTDLP -> YoutubeDlDownloader.pauseDownload(context, task)
        }
    }

    fun resume(context: Context, task: ProgressInfo) {
        when (DownloadEngineKindResolver.kindOf(task)) {
            DownloadEngineKind.REGULAR -> CustomRegularDownloader.resumeDownload(context, task)
            DownloadEngineKind.SUPERX -> SuperXDownloader.resumeDownload(context, task)
            DownloadEngineKind.YTDLP -> YoutubeDlDownloader.resumeDownload(context, task)
        }
    }

    fun cancel(context: Context, task: ProgressInfo, removeFile: Boolean) {
        when (DownloadEngineKindResolver.kindOf(task)) {
            DownloadEngineKind.REGULAR ->
                CustomRegularDownloader.cancelDownload(context, task, removeFile)

            DownloadEngineKind.SUPERX ->
                SuperXDownloader.cancelDownload(context, task, removeFile)

            DownloadEngineKind.YTDLP ->
                YoutubeDlDownloader.cancelDownload(context, task, removeFile)
        }
    }

    fun stopAndSave(context: Context, task: ProgressInfo) {
        when (DownloadEngineKindResolver.kindOf(task)) {
            DownloadEngineKind.REGULAR ->
                CustomRegularDownloader.stopAndSaveDownload(context, task)

            DownloadEngineKind.SUPERX ->
                SuperXDownloader.stopAndSaveDownload(context, task)

            DownloadEngineKind.YTDLP ->
                YoutubeDlDownloader.stopAndSaveDownload(context, task)
        }
    }

    fun recoverFinalization(context: Context, task: ProgressInfo) {
        require(DownloadEngineKindResolver.kindOf(task) == DownloadEngineKind.YTDLP) {
            "Only yt-dlp tasks support finalization recovery."
        }
        YoutubeDlDownloader.recoverFinalization(context, task)
    }

    /** 供启动对账使用的引擎种类查询，避免对账逻辑自行复制判据。 */
    fun engineKindOf(task: ProgressInfo): DownloadEngineKind =
        DownloadEngineKindResolver.kindOf(task)
}
