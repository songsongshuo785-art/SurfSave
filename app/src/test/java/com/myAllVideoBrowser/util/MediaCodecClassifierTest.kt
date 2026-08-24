package com.myAllVideoBrowser.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCodecClassifierTest {
    @Test
    fun codecList_findsVideoAndAudioRegardlessOfOrder() {
        assertEquals(
            "hev1.1.6.l120",
            MediaCodecClassifier.firstVideoCodec("mp4a.40.2, HEV1.1.6.L120")
        )
        assertEquals(
            "mp4a.40.2",
            MediaCodecClassifier.firstAudioCodec("mp4a.40.2, HEV1.1.6.L120")
        )
    }

    @Test
    fun allHevcAndDolbyVisionSpellings_requireH264Transcode() {
        listOf("hvc1.1.6.L93", "hev1.1.6.L120", "HEVC", "dvh1.05", "dvhe.08")
            .forEach { assertTrue(it, MediaCodecClassifier.requiresH264Transcode(it)) }
        assertFalse(MediaCodecClassifier.requiresH264Transcode("avc1.640028,mp4a.40.2"))
        assertFalse(MediaCodecClassifier.requiresH264Transcode("av01.0.08M.08"))
    }
}
