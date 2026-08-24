package com.myAllVideoBrowser.util

import kotlin.math.roundToInt

/** Maps the persisted byte threshold to the integer-step Material Slider. */
internal object VideoDetectionThresholdSlider {
    const val MAX_BYTES = 50 * 1024 * 1024
    const val STEP_COUNT = 100

    fun toSliderValue(bytes: Int): Float {
        val step = (bytes.toDouble() * STEP_COUNT / MAX_BYTES)
            .roundToInt()
            .coerceIn(0, STEP_COUNT)
        return step.toFloat()
    }

    fun toBytes(sliderValue: Float): Int {
        val step = sliderValue.roundToInt().coerceIn(0, STEP_COUNT)
        return (step.toLong() * MAX_BYTES / STEP_COUNT).toInt()
    }

    fun normalizeBytes(bytes: Int): Int = toBytes(toSliderValue(bytes))
}
