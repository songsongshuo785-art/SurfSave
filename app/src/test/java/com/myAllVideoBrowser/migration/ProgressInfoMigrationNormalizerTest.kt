package com.myAllVideoBrowser.migration

import com.myAllVideoBrowser.data.local.room.entity.DownloadRequestData
import com.myAllVideoBrowser.data.local.room.entity.ProgressInfo
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.util.downloaders.DownloadFingerprint
import org.junit.Assert.assertEquals
import org.junit.Test

class ProgressInfoMigrationNormalizerTest {

    @Test
    fun normalize_backfillsBlankFingerprintWithCurrentDownloadSemantics() {
        val videoInfo = VideoInfo(
            id = "legacy-video",
            title = "Legacy",
            ext = "mp4",
            downloadUrls = listOf(
                DownloadRequestData(url = "https://cdn.example/legacy.mp4")
            )
        )
        val normalized = ProgressInfoMigrationNormalizer.normalize(
            ProgressInfo(
                id = "legacy-progress",
                videoInfo = videoInfo,
                downloadFingerprint = ""
            )
        )

        assertEquals(DownloadFingerprint.fromVideoInfo(videoInfo), normalized.downloadFingerprint)
    }

    @Test
    fun normalize_preservesExistingFingerprint() {
        val normalized = ProgressInfoMigrationNormalizer.normalize(
            ProgressInfo(
                id = "current-progress",
                videoInfo = VideoInfo(id = "current-video"),
                downloadFingerprint = "existing-fingerprint"
            )
        )

        assertEquals("existing-fingerprint", normalized.downloadFingerprint)
    }
}
