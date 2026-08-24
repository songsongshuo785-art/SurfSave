package com.myAllVideoBrowser.ui.main.player

import com.myAllVideoBrowser.data.local.room.entity.VideoFormatEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PlaybackMediaKindTest {
    @Test
    fun noExtensionStream_usesYtDlpProtocol() {
        assertEquals(
            PlaybackMediaKind.HLS,
            PlaybackMediaKindResolver.resolve(
                VideoFormatEntity(
                    url = "https://cdn.example/api/play?id=1",
                    protocol = "m3u8_native"
                )
            )
        )
        assertEquals(
            PlaybackMediaKind.DASH,
            PlaybackMediaKindResolver.resolve(
                VideoFormatEntity(
                    url = "https://cdn.example/api/play?id=2",
                    protocol = "http_dash_segments"
                )
            )
        )
    }

    @Test
    fun refreshMatcher_prefersStableFormatIdThenResolution() {
        val exact = VideoFormatEntity(formatId = "hls-720", protocol = "m3u8", url = "https://a/exact")
        val sameHeight = VideoFormatEntity(formatId = "changed", height = 720, protocol = "m3u8", url = "https://a/height")

        assertSame(
            exact,
            PlaybackFormatRefreshMatcher.find(
                listOf(sameHeight, exact), "hls-720", 720, PlaybackMediaKind.HLS
            )
        )
        assertSame(
            sameHeight,
            PlaybackFormatRefreshMatcher.find(
                listOf(sameHeight), "missing", 720, PlaybackMediaKind.HLS
            )
        )
    }

    @Test
    fun refreshPlan_keepsOriginalDetectorAndManifestKind() {
        assertEquals(
            PlaybackRefreshPlan(
                useSuperXDetector = true,
                isHls = true,
                isDash = false
            ),
            PlaybackRefreshPlanResolver.resolve(
                detectedBySuperX = true,
                mediaKind = PlaybackMediaKind.HLS
            )
        )
        assertEquals(
            PlaybackRefreshPlan(
                useSuperXDetector = false,
                isHls = false,
                isDash = true
            ),
            PlaybackRefreshPlanResolver.resolve(
                detectedBySuperX = false,
                mediaKind = PlaybackMediaKind.DASH
            )
        )
    }

    @Test
    fun failureClassifier_distinguishesHttpDecoderAndDrm() {
        assertEquals(
            PlaybackFailureKind.HTTP_AUTHORIZATION,
            PlaybackFailureClassifier.classify("ERROR_CODE_IO_BAD_HTTP_STATUS", 403)
        )
        assertEquals(
            PlaybackFailureKind.DECODER,
            PlaybackFailureClassifier.classify("ERROR_CODE_DECODER_INIT_FAILED", null)
        )
        assertEquals(
            PlaybackFailureKind.DRM,
            PlaybackFailureClassifier.classify("ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED", null)
        )
    }
}
