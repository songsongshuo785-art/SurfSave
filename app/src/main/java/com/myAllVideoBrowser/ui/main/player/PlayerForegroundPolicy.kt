package com.myAllVideoBrowser.ui.main.player

import androidx.media3.common.Player

internal data class ForegroundPlaybackSnapshot(
    val positionMs: Long,
    val shouldResumePlayback: Boolean
)

internal object PlayerForegroundPolicy {
    fun capture(
        positionMs: Long,
        playWhenReady: Boolean,
        playbackState: Int
    ): ForegroundPlaybackSnapshot {
        return ForegroundPlaybackSnapshot(
            positionMs = positionMs.coerceAtLeast(0L),
            shouldResumePlayback = playWhenReady && playbackState != Player.STATE_ENDED
        )
    }
}
