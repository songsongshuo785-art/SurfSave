package com.myAllVideoBrowser.ui.main.player

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 播放进度门禁回归测试。
 *
 * 核心回归点（历史 bug）：media3 把"缺少 #EXT-X-ENDLIST 的 HLS"和"长度未知的流式
 * Progressive/MP4"判定为 live，旧实现用 `player.isCurrentMediaItemLive` 直接跳过保存，
 * 导致这类点播视频的进度**只有第一次被写入**。门禁的入参里没有 media3 的 live/dynamic，
 * 因此这两个场景必须仍然 SAVE（恢复侧另见 [PlaybackPositionGate.evaluateInitialRestore]）。
 */
class PlaybackPositionGateTest {

    private fun evaluate(
        declaredLive: Boolean = false,
        playbackState: Int = Player.STATE_READY,
        timelineEmpty: Boolean = false,
        windowIsPlaceholder: Boolean = false,
        positionMs: Long = 180_000L,
        durationMs: Long = 600_000L,
        force: Boolean = false
    ): PlaybackPositionGate.Result = PlaybackPositionGate.evaluate(
        declaredLive = declaredLive,
        playbackState = playbackState,
        timelineEmpty = timelineEmpty,
        windowIsPlaceholder = windowIsPlaceholder,
        positionMs = positionMs,
        durationMs = durationMs,
        force = force
    )

    @Test
    fun declaredLive_neverSaves() {
        val result = evaluate(declaredLive = true)

        assertEquals(PlaybackPositionGate.Action.SKIP, result.action)
        assertEquals(PlaybackPositionGate.Cause.DECLARED_LIVE, result.cause)
    }

    @Test
    fun idlePlayer_skipsEvenWhenForced() {
        // stop() 之后 currentPosition 仍可能是 setMediaSource(source, startMs) 留下的 masking 值，
        // 强制保存会把真实进度覆盖成旧值。
        val result = evaluate(playbackState = Player.STATE_IDLE, force = true)

        assertEquals(PlaybackPositionGate.Action.SKIP, result.action)
        assertEquals(PlaybackPositionGate.Cause.PLAYER_IDLE, result.cause)
    }

    @Test
    fun emptyTimeline_skips() {
        val result = evaluate(timelineEmpty = true, force = true)

        assertEquals(PlaybackPositionGate.Action.SKIP, result.action)
        assertEquals(PlaybackPositionGate.Cause.EMPTY_TIMELINE, result.cause)
    }

    @Test
    fun placeholderWindow_skips() {
        // stop() 只把 Timeline 换成 placeholder 副本，位置仍在但不可信。
        val result = evaluate(windowIsPlaceholder = true, force = true)

        assertEquals(PlaybackPositionGate.Action.SKIP, result.action)
        assertEquals(PlaybackPositionGate.Cause.PLACEHOLDER_WINDOW, result.cause)
    }

    @Test
    fun negativePosition_skips() {
        val result = evaluate(positionMs = -1L, force = true)

        assertEquals(PlaybackPositionGate.Action.SKIP, result.action)
        assertEquals(PlaybackPositionGate.Cause.INVALID_POSITION, result.cause)
    }

    @Test
    fun readyStaticPlayer_saves() {
        val result = evaluate()

        assertEquals(PlaybackPositionGate.Action.SAVE, result.action)
        assertEquals(PlaybackPositionGate.Cause.NONE, result.cause)
    }

    /**
     * P1 → P2 覆盖回归：流式 MP4（media3 判为 live、无时长）连续两次周期保存都要落库。
     */
    @Test
    fun streamedProgressiveMp4_savesRepeatedlyAndKeepsRestore() {
        val firstTick = evaluate(positionMs = 60_000L, durationMs = 0L)
        val secondTick = evaluate(positionMs = 180_000L, durationMs = 0L)
        val restore = PlaybackPositionGate.evaluateInitialRestore(
            declaredLive = false,
            windowIsDynamic = false,
            restoredPositionMs = 60_000L,
            durationMs = 0L
        )

        assertEquals(PlaybackPositionGate.Action.SAVE, firstTick.action)
        assertEquals(PlaybackPositionGate.Action.SAVE, secondTick.action)
        assertEquals(PlaybackPositionGate.RestoreVeto.DEFER, restore)
    }

    /**
     * B 决策：`isDynamic` / 缺 ENDLIST 的 HLS 允许保存，但禁止恢复。
     */
    @Test
    fun hlsWithoutEndList_savesButDoesNotRestore() {
        val save = evaluate(positionMs = 300_000L, durationMs = 0L)
        val restore = PlaybackPositionGate.evaluateInitialRestore(
            declaredLive = false,
            windowIsDynamic = true,
            restoredPositionMs = 120_000L,
            durationMs = 0L
        )

        assertEquals(PlaybackPositionGate.Action.SAVE, save.action)
        assertEquals(PlaybackPositionGate.RestoreVeto.RESET_TO_DEFAULT, restore)
    }

    @Test
    fun ended_clears() {
        val result = evaluate(playbackState = Player.STATE_ENDED, positionMs = 600_000L)

        assertEquals(PlaybackPositionGate.Action.CLEAR, result.action)
        assertEquals(PlaybackPositionGate.Cause.PLAYBACK_ENDED, result.cause)
    }

    @Test
    fun nearEnd_clears() {
        val result = evaluate(positionMs = 590_000L, durationMs = 600_000L)

        assertEquals(PlaybackPositionGate.Action.CLEAR, result.action)
        assertEquals(PlaybackPositionGate.Cause.NEAR_END, result.cause)
    }

    @Test
    fun belowMinPosition_skipsUnlessForced() {
        val periodic = evaluate(positionMs = 500L, force = false)
        val forced = evaluate(positionMs = 500L, force = true)

        assertEquals(PlaybackPositionGate.Action.SKIP, periodic.action)
        assertEquals(PlaybackPositionGate.Cause.BELOW_MIN_POSITION, periodic.cause)
        assertEquals(PlaybackPositionGate.Action.SAVE, forced.action)
    }

    @Test
    fun initialRestore_keepsStaticPosition() {
        val veto = PlaybackPositionGate.evaluateInitialRestore(
            declaredLive = false,
            windowIsDynamic = false,
            restoredPositionMs = 120_000L,
            durationMs = 600_000L
        )

        assertEquals(PlaybackPositionGate.RestoreVeto.KEEP, veto)
    }

    @Test
    fun initialRestore_noSavedPosition_keepsNothing() {
        val veto = PlaybackPositionGate.evaluateInitialRestore(
            declaredLive = false,
            windowIsDynamic = false,
            restoredPositionMs = null,
            durationMs = 0L
        )

        assertEquals(PlaybackPositionGate.RestoreVeto.KEEP, veto)
    }

    @Test
    fun initialRestore_declaredLive_resetsToDefault() {
        val veto = PlaybackPositionGate.evaluateInitialRestore(
            declaredLive = true,
            windowIsDynamic = false,
            restoredPositionMs = 120_000L,
            durationMs = 600_000L
        )

        assertEquals(PlaybackPositionGate.RestoreVeto.RESET_TO_DEFAULT, veto)
    }

    @Test
    fun initialRestore_clearsNearEndWithRealDuration() {
        val veto = PlaybackPositionGate.evaluateInitialRestore(
            declaredLive = false,
            windowIsDynamic = false,
            restoredPositionMs = 595_000L,
            durationMs = 600_000L
        )

        assertEquals(PlaybackPositionGate.RestoreVeto.CLEAR_AND_RESET, veto)
    }
}
