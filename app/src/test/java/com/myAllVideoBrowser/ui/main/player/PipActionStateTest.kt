package com.myAllVideoBrowser.ui.main.player

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PiP 播放/暂停按钮图标决策的回归测试。
 *
 * 背景：早期实现只用 playWhenReady 判断图标，视频进入 STATE_ENDED 后 playWhenReady 通常仍为
 * true，导致小窗一直显示"暂停"图标、首次点击也不会重播。此测试钉住结束态与暂停态的图标语义。
 */
class PipActionStateTest {

    // 播放中（意图 + 非结束态）→ 显示"暂停"图标
    @Test
    fun playingState_showsPauseIcon() {
        assertTrue(
            PipActionState.shouldShowPauseIcon(
                playWhenReady = true,
                playbackState = Player.STATE_READY
            )
        )
    }

    // 缓冲中（意图 true 但尚未实际播放）→ 仍按播放意图显示"暂停"图标（不误判为暂停按钮）
    @Test
    fun bufferingWithPlayIntent_showsPauseIcon() {
        assertTrue(
            PipActionState.shouldShowPauseIcon(
                playWhenReady = true,
                playbackState = Player.STATE_BUFFERING
            )
        )
    }

    // 暂停（意图 false）→ 显示"播放"图标
    @Test
    fun pausedState_showsPlayIcon() {
        assertFalse(
            PipActionState.shouldShowPauseIcon(
                playWhenReady = false,
                playbackState = Player.STATE_READY
            )
        )
    }

    // 播放结束（STATE_ENDED 且 playWhenReady 仍为 true）→ 必须显示"播放"图标，提示可重播
    @Test
    fun endedStateWithPlayIntent_showsPlayIcon() {
        assertFalse(
            PipActionState.shouldShowPauseIcon(
                playWhenReady = true,
                playbackState = Player.STATE_ENDED
            )
        )
    }

    // 空闲/准备中（意图 true）→ 按播放意图显示"暂停"图标
    @Test
    fun idleWithPlayIntent_showsPauseIcon() {
        assertTrue(
            PipActionState.shouldShowPauseIcon(
                playWhenReady = true,
                playbackState = Player.STATE_IDLE
            )
        )
        assertTrue(
            PipActionState.shouldShowPauseIcon(
                playWhenReady = true,
                playbackState = Player.STATE_BUFFERING
            )
        )
    }

    // ---- 点击动作决策（resolveToggleAction）----

    // 结束态点击：即使 playWhenReady 仍为 true，也必须回到开头并播放（不能暂停）
    @Test
    fun endedState_toggleResolvesToSeekAndPlay() {
        assertEquals(
            PipActionState.ToggleAction.SEEK_AND_PLAY,
            PipActionState.resolveToggleAction(
                playbackState = Player.STATE_ENDED,
                playWhenReady = true
            )
        )
    }

    // 播放中点击：暂停
    @Test
    fun playing_toggleResolvesToPause() {
        assertEquals(
            PipActionState.ToggleAction.PAUSE,
            PipActionState.resolveToggleAction(
                playbackState = Player.STATE_READY,
                playWhenReady = true
            )
        )
    }

    // 暂停态点击：开始播放
    @Test
    fun paused_toggleResolvesToPlay() {
        assertEquals(
            PipActionState.ToggleAction.PLAY,
            PipActionState.resolveToggleAction(
                playbackState = Player.STATE_READY,
                playWhenReady = false
            )
        )
    }

    // 缓冲中但意图播放：点击应暂停（与图标语义一致）
    @Test
    fun bufferingWithPlayIntent_toggleResolvesToPause() {
        assertEquals(
            PipActionState.ToggleAction.PAUSE,
            PipActionState.resolveToggleAction(
                playbackState = Player.STATE_BUFFERING,
                playWhenReady = true
            )
        )
    }
}
