package com.myAllVideoBrowser.ui.main.player

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.myAllVideoBrowser.R
import kotlin.math.roundToInt

/**
 * Media3 版 PiP 帮助类：按 player.videoSize 构造 PiP 参数，提供 快退 / 暂停-播放 / 快进 三个 RemoteAction。
 * 参考 mpvEx 的 MPVPipHelper（libmpv 版），把 MPVLib 调用换成 Media3 Player API
 * （seekBack/seekForward/pause/play）。
 *
 * 生命周期：buildParams 在进 PiP 前构造；registerReceiver 在 onPictureInPictureModeChanged(true)
 *   进入 PiP 成功后才调（避免进入失败泄漏）；退出 PiP 必须 unregisterReceiver。
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PipHelper(private val player: Player, private val activity: android.app.Activity, private val autoEnterEnabled: Boolean) {

    companion object {
        private const val ACTION_REWIND = "com.surfsave.browser.player.pip.REWIND"
        private const val ACTION_PLAY_PAUSE = "com.surfsave.browser.player.pip.PLAY_PAUSE"
        private const val ACTION_FORWARD = "com.surfsave.browser.player.pip.FORWARD"

        private const val REQUEST_REWIND = 1
        private const val REQUEST_PLAY_PAUSE = 2
        private const val REQUEST_FORWARD = 3
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_REWIND -> player.seekBack()
                ACTION_PLAY_PAUSE -> togglePlayPause()
                ACTION_FORWARD -> player.seekForward()
            }
            // 处理后刷新 PiP params，让播放/暂停按钮图标跟随状态更新
            refreshActions()
        }
    }

    /** 播放/暂停切换：按状态决策执行 seek+play / pause / play。 */
    private fun togglePlayPause() {
        when (PipActionState.resolveToggleAction(
            playbackState = player.playbackState,
            playWhenReady = player.playWhenReady
        )) {
            PipActionState.ToggleAction.SEEK_AND_PLAY -> {
                player.seekToDefaultPosition()
                player.play()
            }

            PipActionState.ToggleAction.PAUSE -> player.pause()
            PipActionState.ToggleAction.PLAY -> player.play()
        }
    }

    /** 刷新 PiP RemoteAction（播放/暂停图标）。入口供 PiP 播放状态变化时调用；非 PiP 时为空操作。 */
    fun refreshActions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && activity.isInPictureInPictureMode) {
            activity.setPictureInPictureParams(buildParams(activity))
        }
    }

    /** 构造 PiP 参数：按视频宽高比 setAspectRatio + 3 个 RemoteAction + setAutoEnterEnabled(API31+)。 */
    @RequiresApi(Build.VERSION_CODES.O)
    fun buildParams(context: Context): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()

        // 复用 VideoGeometry：纳入旋转元数据 + 像素宽高比，避免裸宽高在带旋转视频上算出错误比例
        val (displayWidth, displayHeight) = VideoGeometry.displaySizeOf(player.videoSize)
        if (displayWidth > 0f && displayHeight > 0f) {
            builder.setAspectRatio(Rational(displayWidth.roundToInt(), displayHeight.roundToInt()))
        }

        // 图标状态：播放意图（playWhenReady）为基础，但结束态（STATE_ENDED）下
        // playWhenReady 通常仍为 true，必须单独判为"可重播"（显示播放按钮）。
        // isPlaying 是异步真实状态（缓冲时 false），用它判断会在"已点播放、实际在缓冲"
        // 时把图标错画成播放按钮；真实状态变化由 onIsPlayingChanged 回调驱动
        // refreshActions() 兜底刷新。
        val isPlaying = PipActionState.shouldShowPauseIcon(
            playWhenReady = player.playWhenReady,
            playbackState = player.playbackState
        )
        builder.setActions(
            listOf(
                buildAction(context, ACTION_REWIND, R.drawable.ic_pip_rewind, context.getString(R.string.pip_rewind), REQUEST_REWIND),
                buildAction(
                    context,
                    ACTION_PLAY_PAUSE,
                    if (isPlaying) R.drawable.ic_pip_pause else R.drawable.ic_pip_play,
                    if (isPlaying) context.getString(R.string.pip_pause) else context.getString(R.string.pip_play),
                    REQUEST_PLAY_PAUSE
                ),
                buildAction(context, ACTION_FORWARD, R.drawable.ic_pip_forward, context.getString(R.string.pip_forward), REQUEST_FORWARD)
            )
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // 仅在用户开启"自动小窗"时才允许系统自动带入 PiP，否则完全走 onUserLeaveHint 判断
            builder.setAutoEnterEnabled(autoEnterEnabled)
        }

        return builder.build()
    }

    /** 进 PiP 时注册按钮接收器（NOT_EXPORTED：仅本应用的 PendingIntent broadcast）。 */
    fun registerReceiver(context: Context) {
        val filter = IntentFilter().apply {
            addAction(ACTION_REWIND)
            addAction(ACTION_PLAY_PAUSE)
            addAction(ACTION_FORWARD)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    /** 退出 PiP 时注销。 */
    fun unregisterReceiver(context: Context) {
        runCatching { context.unregisterReceiver(receiver) }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildAction(
        context: Context, action: String, iconRes: Int, title: String, requestCode: Int
    ): RemoteAction {
        val intent = Intent(action).setPackage(context.packageName)
        val pendingIntent = PendingIntent.getBroadcast(
            context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE
        )
        return RemoteAction(
            Icon.createWithResource(context, iconRes),
            title,
            title,
            pendingIntent
        )
    }
}

/**
 * PiP 播放/暂停按钮的纯状态决策：与 Android/Media3 解耦，便于单元测试。
 */
object PipActionState {
    /** 点击播放/暂停按钮应执行的动作。 */
    enum class ToggleAction {
        /** 结束态：回到开头并播放。 */
        SEEK_AND_PLAY,

        /** 播放中：暂停。 */
        PAUSE,

        /** 暂停/停止：开始播放。 */
        PLAY
    }

    /** 是否显示"暂停"图标：仅在播放意图且未结束时为 true；结束态（可重播）显示"播放"图标。 */
    fun shouldShowPauseIcon(playWhenReady: Boolean, playbackState: Int): Boolean {
        return playWhenReady && playbackState != Player.STATE_ENDED
    }

    /**
     * 决定点击播放/暂停按钮后的动作：
     * - 结束态（STATE_ENDED）→ 回到开头再播放（playWhenReady 此时通常仍为 true，不能走 PAUSE）；
     * - 播放意图（playWhenReady=true）→ 暂停；
     * - 暂停/停止（playWhenReady=false）→ 播放。
     */
    fun resolveToggleAction(playbackState: Int, playWhenReady: Boolean): ToggleAction {
        return when {
            playbackState == Player.STATE_ENDED -> ToggleAction.SEEK_AND_PLAY
            playWhenReady -> ToggleAction.PAUSE
            else -> ToggleAction.PLAY
        }
    }
}
