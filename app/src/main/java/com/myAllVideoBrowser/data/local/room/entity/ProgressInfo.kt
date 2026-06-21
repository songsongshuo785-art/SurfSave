package com.myAllVideoBrowser.data.local.room.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.myAllVideoBrowser.util.FileUtil.Companion.getFileSizeReadable
import com.myAllVideoBrowser.util.RoomConverter
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskState
import java.util.*

@Entity(tableName = "ProgressInfo")
@TypeConverters(RoomConverter::class)
data class ProgressInfo(
    @PrimaryKey
    var id: String = UUID.randomUUID().toString(),

    var downloadId: Long = 0,

    @param:TypeConverters(RoomConverter::class)
    var videoInfo: VideoInfo,

    @Deprecated("bytesDownloaded deprecated use progressDownloaded instead")
    var bytesDownloaded: Int = 0,

    @Deprecated("bytesTotal deprecated use progressTotal instead")
    var bytesTotal: Int = 0,

    @ColumnInfo(defaultValue = "0")
    var progressDownloaded: Long = 0,

    @ColumnInfo(defaultValue = "0")
    var progressTotal: Long = 0,

    var downloadStatus: Int = -1,

    var isLive: Boolean = false,

    var isM3u8: Boolean = false,

    var fragmentsDownloaded: Int = 0,

    var fragmentsTotal: Int = 1,

    @ColumnInfo(name = "infoLine")
    var infoLine: String = "",

    @ColumnInfo(defaultValue = "0")
    var queuePosition: Long = 0,

    @ColumnInfo(defaultValue = "0")
    var queuedAt: Long = 0,

    @ColumnInfo(defaultValue = "0")
    var startedAt: Long = 0,

    @ColumnInfo(defaultValue = "0")
    var completedAt: Long = 0,

    @ColumnInfo(defaultValue = "")
    var downloadFingerprint: String = "",

    @ColumnInfo(defaultValue = "")
    var lastError: String = "",

    @ColumnInfo(defaultValue = "")
    var logPath: String = "",

    @ColumnInfo(defaultValue = "0")
    var queuedForLater: Boolean = false
) {
    // НЕ ТРОГАТЬ VAR!!!! иначе пиздец с миграцией
    var progress: Int = 0
        get() {
            return if (progressTotal > 0) {
                (progressDownloaded * 100f / progressTotal).toInt()
            } else {
                0
            }
        }

    // НЕ ТРОГАТЬ VAR!!!! иначе пиздец с миграцией
    var progressSize: String = ""
        get() {
            return getFileSizeReadable(progressDownloaded.toDouble()) + "/" + getFileSizeReadable(progressTotal.toDouble()) + " - $downloadStatusFormatted"
        }

    // НЕ ТРОГАТЬ VAR!!!! иначе пиздец с миграцией
    var downloadStatusFormatted: String = ""
        get() = when (downloadStatus) {
            VideoTaskState.DOWNLOADING -> "downloading"
            VideoTaskState.SUCCESS -> "success"
            VideoTaskState.PAUSE -> "pause"
            VideoTaskState.PENDING -> "pending"
            VideoTaskState.PREPARE -> "prepare"
            VideoTaskState.ENOSPC -> "failed"
            VideoTaskState.ERROR -> "failed"
            else -> "undefined"
        }

    val isActive: Boolean
        get() = downloadStatus == VideoTaskState.PREPARE ||
            downloadStatus == VideoTaskState.START ||
            downloadStatus == VideoTaskState.DOWNLOADING ||
            downloadStatus == VideoTaskState.PROXYREADY

    val isPendingQueue: Boolean
        get() = downloadStatus == VideoTaskState.PENDING && !queuedForLater

    val isLaterQueue: Boolean
        get() = queuedForLater || downloadStatus == VideoTaskState.PAUSE

    val canMoveInQueue: Boolean
        get() = downloadStatus == VideoTaskState.PENDING || queuedForLater

    val canOpenDetails: Boolean
        get() = lastError.isNotBlank() || logPath.isNotBlank() || downloadStatus == VideoTaskState.ERROR ||
            downloadStatus == VideoTaskState.ENOSPC
}
