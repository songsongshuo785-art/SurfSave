package com.myAllVideoBrowser.migration

import com.myAllVideoBrowser.data.local.room.entity.ProgressInfo
import com.myAllVideoBrowser.util.downloaders.DownloadFingerprint

internal object ProgressInfoMigrationNormalizer {
    fun normalize(item: ProgressInfo): ProgressInfo {
        val fingerprint = item.downloadFingerprint.orEmpty().ifBlank {
            DownloadFingerprint.fromVideoInfo(item.videoInfo)
        }
        return item.copy(
            downloadFingerprint = fingerprint,
            infoLine = item.infoLine.orEmpty(),
            lastError = item.lastError.orEmpty(),
            logPath = item.logPath.orEmpty(),
            executionToken = item.executionToken.orEmpty(),
            finalizationSource = item.finalizationSource.orEmpty(),
            finalizationTarget = item.finalizationTarget.orEmpty(),
            finalMediaUri = item.finalMediaUri.orEmpty()
        )
    }

    fun normalize(items: List<ProgressInfo>): List<ProgressInfo> = items.map(::normalize)

    fun normalizeImported(items: List<ProgressInfo>): List<ProgressInfo> =
        normalize(items).map { item ->
            item.copy(
                finalMediaUri = "",
                mediaBindingTrusted = false
            )
        }
}
