package com.myAllVideoBrowser.util.downloaders

import android.content.Context
import com.myAllVideoBrowser.data.local.room.entity.ProgressInfo
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.util.downloaders.custom_downloader.CustomRegularDownloader
import com.myAllVideoBrowser.util.downloaders.super_x_downloader.SuperXDownloader
import com.myAllVideoBrowser.util.downloaders.youtubedl_downloader.YoutubeDlDownloader
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadEngineRouter @Inject constructor() {
    fun start(context: Context, task: ProgressInfo) {
        start(context, task.videoInfo)
    }

    fun start(context: Context, videoInfo: VideoInfo) {
        when {
            videoInfo.isRegularDownload -> CustomRegularDownloader.startDownload(context, videoInfo)
            videoInfo.isDetectedBySuperX -> SuperXDownloader.startDownload(context, videoInfo)
            else -> YoutubeDlDownloader.startDownload(context, videoInfo)
        }
    }

    fun pause(context: Context, task: ProgressInfo) {
        when {
            task.videoInfo.isRegularDownload -> CustomRegularDownloader.pauseDownload(context, task)
            task.videoInfo.isDetectedBySuperX -> SuperXDownloader.pauseDownload(context, task)
            else -> YoutubeDlDownloader.pauseDownload(context, task)
        }
    }

    fun resume(context: Context, task: ProgressInfo) {
        when {
            task.videoInfo.isRegularDownload -> CustomRegularDownloader.resumeDownload(context, task)
            task.videoInfo.isDetectedBySuperX -> SuperXDownloader.resumeDownload(context, task)
            else -> YoutubeDlDownloader.resumeDownload(context, task)
        }
    }

    fun cancel(context: Context, task: ProgressInfo, removeFile: Boolean) {
        when {
            task.videoInfo.isRegularDownload -> CustomRegularDownloader.cancelDownload(context, task, removeFile)
            task.videoInfo.isDetectedBySuperX -> SuperXDownloader.cancelDownload(context, task, removeFile)
            else -> YoutubeDlDownloader.cancelDownload(context, task, removeFile)
        }
    }

    fun stopAndSave(context: Context, task: ProgressInfo) {
        when {
            task.videoInfo.isRegularDownload -> CustomRegularDownloader.stopAndSaveDownload(context, task)
            task.videoInfo.isDetectedBySuperX -> SuperXDownloader.stopAndSaveDownload(context, task)
            else -> YoutubeDlDownloader.stopAndSaveDownload(context, task)
        }
    }
}
