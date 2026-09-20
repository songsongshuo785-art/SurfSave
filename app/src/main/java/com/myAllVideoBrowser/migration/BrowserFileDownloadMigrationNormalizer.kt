package com.myAllVideoBrowser.migration

import com.myAllVideoBrowser.data.local.room.entity.BrowserFileDownload
import com.myAllVideoBrowser.data.local.room.entity.BrowserFileDownloadStatus
import com.myAllVideoBrowser.data.repository.BrowserFileDestinationPolicy

internal object BrowserFileDownloadMigrationNormalizer {
    fun normalizeImported(items: List<BrowserFileDownload>): List<BrowserFileDownload> =
        items.mapIndexed { index, item ->
            val fileName = item.fileName.orEmpty()
            val relativePath = BrowserFileDestinationPolicy.requireNormalized(
                item.relativePath.orEmpty(),
                fileName
            )
            item.copy(
                downloadManagerId = -(index.toLong() + 1L),
                url = item.url.orEmpty(),
                sourcePageUrl = item.sourcePageUrl.orEmpty(),
                userAgent = item.userAgent.orEmpty(),
                fileName = fileName,
                mimeType = item.mimeType.orEmpty(),
                expectedSize = item.expectedSize.coerceAtLeast(0L),
                downloadedBytes = item.downloadedBytes.coerceAtLeast(0L),
                totalBytes = item.totalBytes.coerceAtLeast(0L),
                status = BrowserFileDownloadStatus.MISSING,
                createdAt = item.createdAt.coerceAtLeast(0L),
                completedAt = item.completedAt.coerceAtLeast(0L),
                localUri = "",
                relativePath = relativePath,
                failureReason = 0,
                systemBindingTrusted = false
            )
        }
}
