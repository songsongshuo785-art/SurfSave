package com.myAllVideoBrowser.ui.main.player

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.Window
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.ui.main.base.BaseActivity
import com.myAllVideoBrowser.util.SharedPrefHelper
import com.myAllVideoBrowser.util.ext.addFragment
import javax.inject.Inject

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class VideoPlayerActivity : BaseActivity() {

    @Inject
    lateinit var sharedPrefHelper: SharedPrefHelper

    private var pipHelper: PipHelper? = null

    /** 进入时记录系统方向，退出时原样恢复（manifest 未写死方向，通常为 UNSPECIFIED）。 */
    private var initialRequestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    /** 上一次按视频尺寸请求的方向，防抖：目标未变时不重复 set，避免系统反复应用抖动。 */
    private var lastRequestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initialRequestedOrientation = requestedOrientation
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)

        intent.extras?.let { addFragment(R.id.player_content_frame, it, ::VideoPlayerFragment) }
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
        super.onDestroy()
        // 退出恢复进入时的系统方向
        requestedOrientation = initialRequestedOrientation
        // 兜底注销，防止 receiver 泄漏
        pipHelper?.unregisterReceiver(this)
        pipHelper = null
    }

    override fun onStop() {
        super.onStop()
        // 无条件暂停：onStop 表示"Activity 不可见了"，此时停播是对的。PiP 小窗可见时不会进入
        // onStop（系统只让 Activity 处于 PAUSED），故无需 isInPictureInPictureMode 判断。去掉判断
        // 修复：点 PiP 右上角 X 关闭时，onStop 早于 PiP 状态清零（isInPictureInPictureMode 仍 true），
        // 原判断会跳过 pause，导致 Activity 进 stopped 但不 finish、player 既不 pause 也不 release，
        // 声音继续到进程结束。release 仍由 onDestroyView 在真正 finish 时负责，这里只 pause。
        playerFragment()?.getPlayerOrNull()?.pause()
    }
}
