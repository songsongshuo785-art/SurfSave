package com.myAllVideoBrowser.ui.main.player

import androidx.media3.common.VideoSize

/**
 * 视频尺寸 → 实际显示尺寸/方向的统一计算。
 *
 * Media3 的 [VideoSize.width]/[VideoSize.height] 是解码后的原始尺寸；旋转元数据
 * [VideoSize.unappliedRotationDegrees] 在渲染器未应用时需由上层补上（90/270 时宽高互换）；
 * 非方形像素时 [VideoSize.pixelWidthHeightRatio] 影响宽度方向的显示宽度。
 *
 * fragment 的视频尺寸回调、Activity 的自动旋转、PipHelper 的 PiP 宽高比三处复用本工具，
 * 避免裸宽高在不同代码路径上算出不一致的比例（例如带旋转信息的竖屏视频被误判为横屏）。
 */
object VideoGeometry {

    /**
     * 实际显示宽高（含 90/270 旋转互换 + pixelWidthHeightRatio）。无效尺寸返回 (0,0)。
     *
     * [VideoSize.unappliedRotationDegrees] 在 Media3 中已标记 deprecated（现代 renderer 会自动应用旋转，
     * 该值通常为 0），但保留读取作为兜底：少数软解/容器在渲染器未应用旋转时该值非 0，
     * 此时需互换宽高，否则会把竖屏视频误判为横屏。
     */
    @Suppress("DEPRECATION")
    fun displaySizeOf(videoSize: VideoSize): Pair<Float, Float> {
        if (videoSize.width <= 0 || videoSize.height <= 0) return 0f to 0f
        val rotated = videoSize.unappliedRotationDegrees == 90 ||
            videoSize.unappliedRotationDegrees == 270
        val displayWidth = if (rotated) videoSize.height.toFloat() else videoSize.width.toFloat()
        val displayHeight = if (rotated) videoSize.width.toFloat() else videoSize.height.toFloat()
        val pixelRatio =
            if (videoSize.pixelWidthHeightRatio > 0f) videoSize.pixelWidthHeightRatio else 1f
        return (displayWidth * pixelRatio) to displayHeight
    }

    /** 实际显示宽高比；无效尺寸返回 1f（按方形处理，避免误触发方向切换）。 */
    fun displayRatio(videoSize: VideoSize): Float {
        val (w, h) = displaySizeOf(videoSize)
        return if (h == 0f) 1f else w / h
    }

    /** 接近 1:1（0.9..1.1）——不锁方向，交给系统/用户自由旋转。 */
    fun isNearSquare(videoSize: VideoSize): Boolean = displayRatio(videoSize) in 0.9f..1.1f

    /** 横屏（实际显示宽高比 > 1，已排除接近方形）。 */
    fun isLandscape(videoSize: VideoSize): Boolean = displayRatio(videoSize) > 1f
}
