package com.myAllVideoBrowser.migration

import com.google.gson.Gson
import com.myAllVideoBrowser.data.local.room.entity.DownloadRequestData
import com.myAllVideoBrowser.data.local.room.entity.ProgressInfo
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.util.downloaders.DownloadFingerprint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun normalizeImported_realLegacyJsonWithoutNewFieldsBackfillsNonNullValues() {
        val legacyJson = """
            {
              "id":"legacy-progress",
              "videoInfo":{"id":"legacy-video"},
              "downloadFingerprint":"legacy-fingerprint"
            }
        """.trimIndent()
        val deserialized = Gson().fromJson(legacyJson, ProgressInfo::class.java)

        val normalized = ProgressInfoMigrationNormalizer.normalizeImported(
            listOf(deserialized)
        ).single()

        assertEquals("legacy-fingerprint", normalized.downloadFingerprint)
        assertEquals("", normalized.infoLine)
        assertEquals("", normalized.lastError)
        assertEquals("", normalized.logPath)
        assertEquals("", normalized.executionToken)
        assertEquals("", normalized.finalizationSource)
        assertEquals("", normalized.finalizationTarget)
        assertEquals("", normalized.finalMediaUri)
        assertFalse(normalized.mediaBindingTrusted)
    }
}
