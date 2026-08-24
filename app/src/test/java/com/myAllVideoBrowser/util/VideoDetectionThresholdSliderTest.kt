package com.myAllVideoBrowser.util

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoDetectionThresholdSliderTest {

    @Test
    fun legacyByteValuesAreRoundedToValidIntegerSteps() {
        assertEquals(8f, VideoDetectionThresholdSlider.toSliderValue(4 * 1024 * 1024))
        assertEquals(8f, VideoDetectionThresholdSlider.toSliderValue(4 * 1024 * 1024 + 100_000))
        assertEquals(0f, VideoDetectionThresholdSlider.toSliderValue(-1))
        assertEquals(100f, VideoDetectionThresholdSlider.toSliderValue(Int.MAX_VALUE))
    }

    @Test
    fun sliderStepsMapBackToStableByteThresholds() {
        assertEquals(0, VideoDetectionThresholdSlider.toBytes(0f))
        assertEquals(4 * 1024 * 1024, VideoDetectionThresholdSlider.toBytes(8f))
        assertEquals(VideoDetectionThresholdSlider.MAX_BYTES, VideoDetectionThresholdSlider.toBytes(100f))
        assertEquals(4 * 1024 * 1024, VideoDetectionThresholdSlider.toBytes(8.4f))
    }

    @Test
    fun legacyByteValuesNormalizeToTheDisplayedThreshold() {
        assertEquals(
            4 * 1024 * 1024,
            VideoDetectionThresholdSlider.normalizeBytes(4 * 1024 * 1024 + 100_000)
        )
    }
}
