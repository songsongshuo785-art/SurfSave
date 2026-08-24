package com.myAllVideoBrowser.ui.main.player

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerForegroundPolicyTest {
    @Test
    fun playingVideoResumesFromCapturedPosition() {
        val snapshot = PlayerForegroundPolicy.capture(12_345L, true, Player.STATE_READY)

        assertEquals(12_345L, snapshot.positionMs)
        assertTrue(snapshot.shouldResumePlayback)
    }

    @Test
    fun pausedVideoRemainsPaused() {
        val snapshot = PlayerForegroundPolicy.capture(8_000L, false, Player.STATE_READY)

        assertFalse(snapshot.shouldResumePlayback)
    }

    @Test
    fun endedVideoDoesNotRestartAndNegativePositionIsClamped() {
        val snapshot = PlayerForegroundPolicy.capture(-1L, true, Player.STATE_ENDED)

        assertEquals(0L, snapshot.positionMs)
        assertFalse(snapshot.shouldResumePlayback)
    }
}
