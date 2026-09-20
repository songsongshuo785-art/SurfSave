package com.myAllVideoBrowser.data.local.room.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    indices = [Index(value = ["downloadManagerId"], unique = true)]
)
data class BrowserFileDownload(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val downloadManagerId: Long,
    val url: String,
    val sourcePageUrl: String = "",
    val userAgent: String = "",
    val fileName: String,
    val mimeType: String = "",
    val expectedSize: Long = 0,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val status: Int = BrowserFileDownloadStatus.PENDING,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long = 0,
    val localUri: String = "",
    val relativePath: String,
    val failureReason: Int = 0,
    val systemBindingTrusted: Boolean = true
) {
    val isActive: Boolean
        get() = status == BrowserFileDownloadStatus.PENDING ||
            status == BrowserFileDownloadStatus.RUNNING ||
            status == BrowserFileDownloadStatus.PAUSED

    val canOpen: Boolean
        get() = status == BrowserFileDownloadStatus.SUCCESSFUL && localUri.isNotBlank()

    val canRetry: Boolean
        get() = status == BrowserFileDownloadStatus.FAILED ||
            status == BrowserFileDownloadStatus.MISSING ||
            status == BrowserFileDownloadStatus.CANCELED

    val progressPercent: Int
        get() = if (totalBytes > 0) {
            ((downloadedBytes.coerceIn(0, totalBytes) * 100L) / totalBytes).toInt()
        } else {
            0
        }
}

object BrowserFileDownloadStatus {
    const val PENDING = 1
    const val RUNNING = 2
    const val PAUSED = 3
    const val SUCCESSFUL = 4
    const val FAILED = 5
    const val MISSING = 6
    const val CANCELED = 7
}
