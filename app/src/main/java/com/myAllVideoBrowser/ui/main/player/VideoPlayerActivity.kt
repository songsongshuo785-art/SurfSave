package com.myAllVideoBrowser.ui.main.player

import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.Window
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.media3.common.Player
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            // 进 PiP 成功：注册按钮接收器 + 隐藏自定义控件层（PiP 只显示视频画面）
            pipHelper?.registerReceiver(this)
            fragment?.setPipMode(true)
        } else {
            pipHelper?.unregisterReceiver(this)
            pipHelper = null
            fragment?.setPipMode(false)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 兜底注销，防止 receiver 泄漏
        pipHelper?.unregisterReceiver(this)
        pipHelper = null
    }

    override fun onStop() {
        super.onStop()
        // 不在 PiP 且 Activity 不可见时暂停，避免后台偷播
        val inPip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode
        if (!inPip) {
            playerFragment()?.getPlayerOrNull()?.pause()
        }
    }
}
