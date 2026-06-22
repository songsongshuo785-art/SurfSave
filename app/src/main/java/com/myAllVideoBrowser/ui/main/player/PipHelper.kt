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
                ACTION_PLAY_PAUSE -> if (player.isPlaying) player.pause() else player.play()
                ACTION_FORWARD -> player.seekForward()
            }
            // 处理后刷新 PiP params，让播放/暂停按钮图标跟随状态更新
            refreshPipParams()
        }
    }

    private fun refreshPipParams() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && activity.isInPictureInPictureMode) {
            activity.setPictureInPictureParams(buildParams(activity))
        }
    }

    /** 构造 PiP 参数：按视频宽高比 setAspectRatio + 3 个 RemoteAction + setAutoEnterEnabled(API31+)。 */
    @RequiresApi(Build.VERSION_CODES.O)
    fun buildParams(context: Context): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()

        val videoSize = player.videoSize
        if (videoSize.width > 0 && videoSize.height > 0) {
            builder.setAspectRatio(Rational(videoSize.width, videoSize.height))
        }

        val isPlaying = player.isPlaying
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
