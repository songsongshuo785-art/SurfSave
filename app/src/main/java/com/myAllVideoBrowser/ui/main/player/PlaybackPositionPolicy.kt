package com.myAllVideoBrowser.ui.main.player

data class SavedPlaybackPosition(
    val positionMs: Long,
    val durationMs: Long,
    val updatedAtMs: Long
)

internal data class PlaybackRestoreDecision(
    val positionMs: Long?,
    val shouldClear: Boolean
)

internal object PlaybackPositionPolicy {
    const val MIN_PERSISTED_POSITION_MS = 1_000L
    const val MIN_END_MARGIN_MS = 1_000L
    const val MAX_END_MARGIN_MS = 15_000L
    const val END_MARGIN_RATIO = 0.05

    /**
     * 片尾区间：`min(15s, max(1s, duration × 5%))`。
     * 固定 15 秒会把短视频的中间位置也当成片尾，导致“看过却不记位置”。
     */
    fun endMarginMs(durationMs: Long): Long {
        if (durationMs <= 0L) return 0L
        return minOf(
            MAX_END_MARGIN_MS,
            maxOf(MIN_END_MARGIN_MS, (durationMs * END_MARGIN_RATIO).toLong())
        )
    }

    /**
     * 是否恢复历史进度。
     *
     * [declaredLive] 来自业务层（`VideoInfo.isLive`）：直播既不恢复也不清理记录
     * （避免把将来可能变成点播的记录误删）。是否 live 不由 media3 的
     * `Player.isCurrentMediaItemLive` 决定，原因见 [PlaybackPositionGate]。
     */
    fun restore(
        saved: SavedPlaybackPosition?,
        currentDurationMs: Long,
        declaredLive: Boolean = false
    ): PlaybackRestoreDecision {
        if (declaredLive) {
            return PlaybackRestoreDecision(null, false)
        }
        if (saved == null || saved.positionMs < MIN_PERSISTED_POSITION_MS) {
            return PlaybackRestoreDecision(null, false)
        }
        val duration = maxOf(saved.durationMs, currentDurationMs)
        if (shouldClearAtEnd(saved.positionMs, duration)) {
            return PlaybackRestoreDecision(null, true)
        }
        return PlaybackRestoreDecision(saved.positionMs, false)
    }

    fun shouldClearAtEnd(positionMs: Long, durationMs: Long): Boolean {
        if (durationMs <= 0L) return false
        return positionMs >= (durationMs - endMarginMs(durationMs)).coerceAtLeast(0L)
    }
}
