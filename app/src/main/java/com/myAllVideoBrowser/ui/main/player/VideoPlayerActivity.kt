package com.myAllVideoBrowser.ui.main.player

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.content.Intent
import android.view.Window
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.activity.addCallback
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.ui.main.base.BaseActivity
import com.myAllVideoBrowser.ui.main.home.MainActivity
import com.myAllVideoBrowser.util.SharedPrefHelper
import com.myAllVideoBrowser.util.ext.addFragment
import javax.inject.Inject

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class VideoPlayerActivity : BaseActivity() {

    @Inject
    lateinit var sharedPrefHelper: SharedPrefHelper

    private var pipHelper: PipHelper? = null

    /** 上一次按视频尺寸请求的方向，防抖：目标未变时不重复 set，避免系统反复应用抖动。 */
    private var lastRequestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    override fun onCreate(savedInstanceState: Bundle?) {
        postponeEnterTransition()  // 共享元素过渡：等缩略图→播放器首帧就绪再开演（防黑帧）
        super.onCreate(savedInstanceState)
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)

        if (savedInstanceState == null && playerFragment() == null) {
            intent.extras?.let { addFragment(R.id.player_content_frame, it, ::VideoPlayerFragment) }
        }
        onBackPressedDispatcher.addCallback(this) {
            finishPlayer()
        }
    }

    private fun playerFragment(): VideoPlayerFragment? =
        supportFragmentManager.findFragmentById(R.id.player_content_frame) as? VideoPlayerFragment

    /** 播放器界面 PiP 按钮调用：主动进入小窗（默认入口，不依赖 autoPip 设置）。 */
    fun enterPipIfPossible() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val player = playerFragment()?.getPlayerOrNull() ?: return
        enterPip(player)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // 仅在用户开启"播放中按 Home 自动进入小窗"且正在播放时自动进 PiP；
        // 默认关，避免用户只想离开却被塞进小窗
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!sharedPrefHelper.getIsAutoPipEnabled()) return
        val player = playerFragment()?.getPlayerOrNull() ?: return
        if (player.isPlaying) {
            enterPip(player)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun enterPip(player: Player) {
        // 构造 helper 但不在此 register：进 PiP 成功后再 register，避免进入失败导致 receiver 泄漏
        pipHelper = PipHelper(player, this, sharedPrefHelper.getIsAutoPipEnabled())
        enterPictureInPictureMode(pipHelper!!.buildParams(this))
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        val fragment = playerFragment()
        if (isInPictureInPictureMode) {
            // 进 PiP 成功：注册按钮接收器 + 隐藏顶部控制栏（PiP 只显示视频画面）
            pipHelper?.registerReceiver(this)
            fragment?.setPipMode(true)
        } else {
            pipHelper?.unregisterReceiver(this)
            pipHelper = null
            fragment?.setPipMode(false)
            // 退出 PiP 后按当前视频尺寸重新应用方向（PiP 中曾忽略尺寸变化，此处补上）
            playerFragment()?.getPlayerOrNull()?.videoSize?.let { applyOrientationFromVideo(it) }
        }
    }

    /** fragment 在 onVideoSizeChanged 时回调；按实际显示尺寸切换方向。 */
    fun onVideoSizeChanged(videoSize: VideoSize) {
        applyOrientationFromVideo(videoSize)
    }

    /** 播放器播放状态真正变化时（onIsPlayingChanged）刷新 PiP RemoteAction 图标。 */
    fun refreshPipActions() {
        pipHelper?.refreshActions()
    }

    /**
     * 按视频实际显示比例切换 Activity 方向：
     * - 横屏视频 → SENSOR_LANDSCAPE；竖屏视频 → SENSOR_PORTRAIT；接近 1:1 → UNSPECIFIED（不锁）。
     * - PiP 中不改方向（PiP 比例由系统/PipHelper 管理）。
     * - 仅在目标方向变化时 set，避免反复应用抖动。
     */
    private fun applyOrientationFromVideo(videoSize: VideoSize) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode) return
        if (VideoGeometry.displaySizeOf(videoSize).first <= 0f) return
        val target = when {
            VideoGeometry.isNearSquare(videoSize) -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            VideoGeometry.isLandscape(videoSize) -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
        if (target != lastRequestedOrientation) {
            requestedOrientation = target
            lastRequestedOrientation = target
        }
    }

    override fun onDestroy() {
        // 兜底注销，防止 receiver 泄漏
        pipHelper?.unregisterReceiver(this)
        pipHelper = null
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        playerFragment()?.onHostStarted()
    }

    override fun onStop() {
        if (isFinishing) {
            playerFragment()?.getPlayerOrNull()?.pause()
        } else {
            playerFragment()?.onHostStopped()
        }
        super.onStop()
    }

    fun finishPlayer() {
        if (isFinishing) return
        if (isTaskRoot) {
            val source = intent.getStringExtra(VideoPlayerFragment.VIDEO_SOURCE)
            val page = if (source == VideoPlayerFragment.SOURCE_VIDEO_LIBRARY) {
                MainActivity.EXTRA_START_PAGE_VIDEO_LIBRARY
            } else {
                MainActivity.EXTRA_START_PAGE_BROWSER
            }
            startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_START_PAGE, page))
            finish()
        } else {
            finishAfterTransition()
        }
    }
}
