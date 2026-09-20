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
    const val END_MARGIN_MS = 15_000L

    fun restore(saved: SavedPlaybackPosition?, currentDurationMs: Long): PlaybackRestoreDecision {
        if (saved == null || saved.positionMs < MIN_PERSISTED_POSITION_MS) {
            return PlaybackRestoreDecision(null, false)
        }
        val duration = maxOf(saved.durationMs, currentDurationMs)
        if (duration > 0L && saved.positionMs >= (duration - END_MARGIN_MS).coerceAtLeast(0L)) {
            return PlaybackRestoreDecision(null, true)
        }
        return PlaybackRestoreDecision(saved.positionMs, false)
    }

    fun shouldClearAtEnd(positionMs: Long, durationMs: Long): Boolean {
        return durationMs > 0L && positionMs >= (durationMs - END_MARGIN_MS).coerceAtLeast(0L)
    }
}
