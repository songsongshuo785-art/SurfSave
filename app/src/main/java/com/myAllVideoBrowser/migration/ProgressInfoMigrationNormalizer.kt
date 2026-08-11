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
            executionToken = item.executionToken.orEmpty(),
            finalizationSource = item.finalizationSource.orEmpty(),
            finalizationTarget = item.finalizationTarget.orEmpty()
        )
    }

    fun normalize(items: List<ProgressInfo>): List<ProgressInfo> = items.map(::normalize)
}
