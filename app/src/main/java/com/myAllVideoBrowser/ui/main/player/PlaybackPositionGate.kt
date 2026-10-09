package com.myAllVideoBrowser.ui.main.player

import androidx.media3.common.Player

/**
 * 播放进度保存门禁：只回答"此刻从播放器读到的位置，能否写进持久化存储"。
 *
 * 关键约束（历史 bug 的根因）：**Media3 对 live 的判断不参与本门禁。**
 *
 * media3 1.10 的 `Timeline.Window.isLive()` 等价于 `liveConfiguration != null`，而
 * `MediaItem.Builder()` 构造出的 `MediaItem` 必然带非空 live 配置，于是
 * `Player.isCurrentMediaItemLive` 对下面这类**点播**媒体同样为 true：
 *  - 缺少 `#EXT-X-ENDLIST` 的 HLS（media3 按直播处理）；
 *  - 长度与时长相未知的流式 Progressive/MP4（直连或经代理丢失 Content-Length）。
 * 旧实现把它当保存前置门禁直接 return，导致这些视频的进度**只在第一次被写入，之后永不再更新**。
 *
 * 现在：
 *  - 是否直播由业务层 `declaredLive`（`VideoInfo.isLive`）决定；
 *  - media3 的 `isLive / isDynamic / isSeekable` 只用于"初始恢复复核"与诊断日志。
 */
internal object PlaybackPositionGate {

    enum class Action { SAVE, SKIP, CLEAR }

    enum class Cause {
        NONE,
        DECLARED_LIVE,
        PLAYER_IDLE,
        EMPTY_TIMELINE,
        PLACEHOLDER_WINDOW,
        INVALID_POSITION,
        PLAYBACK_ENDED,
        NEAR_END,
        BELOW_MIN_POSITION
    }

    data class Result(val action: Action, val cause: Cause)

    /** 初始恢复复核结论。 */
    enum class RestoreVeto {
        /** 恢复位置有效，继续沿用。 */
        KEEP,

        /** 动态窗口（直播 / 无 ENDLIST 的 HLS）或业务层直播：放弃恢复位置，回到默认位置。 */
        RESET_TO_DEFAULT,

        /** 已接近片尾：清理记录并回到默认位置。 */
        CLEAR_AND_RESET,

        /** 静态窗口但真实时长尚未拿到：等下一次回调（READY）再复核，不消耗一次性标记。 */
        DEFER
    }

    /**
     * 采集到的播放器状态必须**先经过本门禁**才允许写库。
     *
     * 顺序即优先级：业务直播 → 已结束 → 播放器未就绪/已停止 → Timeline 未建立 →
     * 占位窗口 → 位置非法 → 接近片尾 → 最小位置门槛 → 允许保存。
     *
     * `STATE_ENDED` 刻意放在 Timeline/位置检查之前：它是终止事实，且 CLEAR 不需要
     * position，普通 VOD 不会因为恰好读到空 Timeline 或占位窗口而漏掉清理；
     * 业务直播（declaredLive）优先级更高，仍然只 SKIP 不清理。
     */
    fun evaluate(
        declaredLive: Boolean,
        playbackState: Int,
        timelineEmpty: Boolean,
        windowIsPlaceholder: Boolean,
        positionMs: Long,
        durationMs: Long,
        force: Boolean
    ): Result {
        if (declaredLive) return Result(Action.SKIP, Cause.DECLARED_LIVE)
        if (playbackState == Player.STATE_ENDED) return Result(Action.CLEAR, Cause.PLAYBACK_ENDED)
        // IDLE：尚未 prepare 或已被 stop()。此时 currentPosition 可能仍是
        // setMediaSource(source, startMs) 留下的 masking 值，写下去会把真实进度覆盖成旧值。
        if (playbackState == Player.STATE_IDLE) return Result(Action.SKIP, Cause.PLAYER_IDLE)
        // Timeline 为空时 media3 的 currentPosition 返回 masking position（即请求的起始位置），
        // 不是真实播放位置；clearMediaItems() 之后还会变成 0。
        if (timelineEmpty) return Result(Action.SKIP, Cause.EMPTY_TIMELINE)
        if (windowIsPlaceholder) return Result(Action.SKIP, Cause.PLACEHOLDER_WINDOW)
        if (positionMs < 0L) return Result(Action.SKIP, Cause.INVALID_POSITION)
        if (PlaybackPositionPolicy.shouldClearAtEnd(positionMs, durationMs)) {
            return Result(Action.CLEAR, Cause.NEAR_END)
        }
        if (!force && positionMs < PlaybackPositionPolicy.MIN_PERSISTED_POSITION_MS) {
            return Result(Action.SKIP, Cause.BELOW_MIN_POSITION)
        }
        return Result(Action.SAVE, Cause.NONE)
    }

    /**
     * 初始恢复复核（一次性）。
     *
     * `setMediaSource(source, startMs)` 在 Timeline 建立之前就把恢复位置交给了 media3，
     * 而"这个窗口是不是动态窗口"只有拿到真实 Timeline 才知道。因此在第一个非 empty、
     * 非 placeholder 的 Window 到达时复核一次：
     *  - 业务直播 / 动态窗口 → 放弃恢复位置（避免 seek 进已滚动的 live window）；
     *  - 静态源 → 用真实 duration 复核片尾；
     *  - 静态源但时长未知 → [RestoreVeto.DEFER]，等下一个回调（READY）再复核。
     */
    fun evaluateInitialRestore(
        declaredLive: Boolean,
        windowIsDynamic: Boolean,
        restoredPositionMs: Long?,
        durationMs: Long
    ): RestoreVeto {
        val restored = restoredPositionMs ?: return RestoreVeto.KEEP
        if (declaredLive || windowIsDynamic) return RestoreVeto.RESET_TO_DEFAULT
        if (durationMs <= 0L) return RestoreVeto.DEFER
        if (PlaybackPositionPolicy.shouldClearAtEnd(restored, durationMs)) {
            return RestoreVeto.CLEAR_AND_RESET
        }
        return RestoreVeto.KEEP
    }

    /**
     * 重新 prepare（403 刷新 / surface recovery）时的待复核目标转移。
     *
     * 这两条路径都会显式给出本次要播放的位置，但“初始恢复复核是否已完成”是另一回事：
     * Timeline 还没建立就 403（或还没拿到首帧就回前台）时，请求位置其实仍是 masking 出来的
     * 恢复目标，这种情况下必须让新 Timeline 再走一次 veto，否则 dynamic 窗口会恢复旧历史位置。
     *
     * - 已验证过：保持原目标不变（后续不再 veto）。
     * - 本来就没有待恢复目标：保持 null，不做多余动作。
     * - 其余情况：目标改为本次真正请求的位置。
     */
    fun pendingRestoreAfterReprepare(
        validated: Boolean,
        pendingRestoreMs: Long?,
        requestedStartMs: Long
    ): Long? {
        if (validated) return pendingRestoreMs
        if (pendingRestoreMs == null) return null
        return requestedStartMs
    }
}
