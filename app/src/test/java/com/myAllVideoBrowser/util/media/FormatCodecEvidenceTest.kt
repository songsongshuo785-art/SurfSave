package com.myAllVideoBrowser.util.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D2 回归网：`-f` 的取值只允许在「明确 codec 证据」下追加 `+bestaudio`。
 *
 * 真实失败样本来自 Hanime1 `22554`（任务日志 2026-10-11 01:52）：
 * `vcodec=null`、`acodec=none`（html5 解析不提供 codec 字段）却被判成 video-only，
 * 于是给一条完整 MP4 传了 `-f '1+bestaudio'`，yt-dlp 直接报
 * `[html5] watch?v=22554-1: Requested format is not available`。
 */
class FormatCodecEvidenceTest {

    @Test
    fun missingVideoCodecIsNotEvidenceOfVideoOnly() {
        // 修复前：`vcodec != "none" && acodec == "none"` → true → 追加 +bestaudio。
        assertFalse(FormatCodecEvidence.isVideoOnly(vcodec = null, acodec = "none"))
        assertFalse(FormatCodecEvidence.isVideoOnly(vcodec = "none", acodec = "none"))
        assertFalse(FormatCodecEvidence.isVideoOnly(vcodec = "unknown", acodec = "none"))
        // Android 的 JSONObject.optString 会把 JSON null 变成字符串 "null"。
        assertFalse(FormatCodecEvidence.isVideoOnly(vcodec = "null", acodec = "none"))
        assertFalse(FormatCodecEvidence.isVideoOnly(vcodec = "", acodec = "none"))

        assertEquals("1", FormatCodecEvidence.formatSelector("1", vcodec = null, acodec = "none"))
        assertEquals("1", FormatCodecEvidence.formatSelector("1", vcodec = "null", acodec = "none"))
    }

    @Test
    fun explicitVideoCodecWithNoAudioStillMergesBestAudio() {
        assertTrue(FormatCodecEvidence.isVideoOnly(vcodec = "avc1.640028", acodec = "none"))
        assertEquals(
            "137+bestaudio",
            FormatCodecEvidence.formatSelector("137", vcodec = "avc1.640028", acodec = "none")
        )
    }

    @Test
    fun muxedFormatKeepsItsPlainSelector() {
        assertFalse(FormatCodecEvidence.isVideoOnly(vcodec = "h264", acodec = "aac"))
        assertEquals(
            "18",
            FormatCodecEvidence.formatSelector("18", vcodec = "h264", acodec = "aac")
        )
    }

    @Test
    fun audioOnlyFormatsNeverGetAFormatSelector() {
        // 既有语义逐字保留：vcodec == "none" 且 acodec != "none" → 走 --extract-audio，不传 -f。
        assertNull(FormatCodecEvidence.formatSelector("140", vcodec = "none", acodec = "mp4a.40.2"))
        assertTrue(FormatCodecEvidence.isAudioOnly(vcodec = "none", acodec = "mp4a.40.2"))
        assertFalse(FormatCodecEvidence.isAudioOnly(vcodec = null, acodec = "none"))
    }
}
