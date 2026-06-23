package com.myAllVideoBrowser.ui.main.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Bundle
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView.SHOW_BUFFERING_ALWAYS
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.databinding.FragmentPlayerBinding
import com.myAllVideoBrowser.ui.main.base.BaseFragment
import com.myAllVideoBrowser.util.AppUtil
import com.myAllVideoBrowser.util.proxy_utils.OkHttpProxyClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import javax.inject.Inject


@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class VideoPlayerFragment : BaseFragment() {

    companion object {
        const val VIDEO_URL = "video_url"
        const val VIDEO_HEADERS = "video_headers"
        const val VIDEO_NAME = "video_name"
        private const val SEEK_INCREMENT_MS = 10_000L
        private const val TOP_BAR_PADDING_DP = 4
        private const val MENU_TRACKS = 1
    }

    @Inject
    lateinit var viewModelFactory: ViewModelProvider.Factory

    @Inject
    lateinit var appUtil: AppUtil

    @Inject
    lateinit var okHttpClient: OkHttpProxyClient

    private lateinit var player: ExoPlayer
    private lateinit var trackSelector: DefaultTrackSelector

    private val gestureDetector by lazy {
        GestureDetector(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                // 双击左半屏快退、右半屏快进（幅度同 seekBack/Forward 的 10s）
                val width = dataBinding.videoView.width
                if (width > 0 && e.x < width / 2f) player.seekBack() else player.seekForward()
                return true
            }
        })
    }

    private lateinit var videoPlayerViewModel: VideoPlayerViewModel

    private lateinit var dataBinding: FragmentPlayerBinding

    /** 供 VideoPlayerActivity 构造 PiP 参数 / 控制播放用；view 销毁后返回 null 避免操作已 release 的 player。 */
    fun getPlayerOrNull(): ExoPlayer? {
        return if (::player.isInitialized && view != null) player else null
    }

    /** PiP 模式切换：进入时隐藏顶部控制栏（PiP 只显示视频画面），退出时恢复。 */
    fun setPipMode(inPip: Boolean) {
        if (!::dataBinding.isInitialized) return
        dataBinding.topBar.visibility = if (inPip) View.GONE else View.VISIBLE
        dataBinding.videoView.useController = !inPip
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        videoPlayerViewModel =
            ViewModelProvider(this, viewModelFactory)[VideoPlayerViewModel::class.java]
        arguments?.getString(VIDEO_HEADERS)?.let { rawHeaders ->
            try {
                val headers =
                    Json.parseToJsonElement(rawHeaders).jsonObject.mapValues { (_, value) ->
                        value.toString().removeSurrounding("\"")
                    }
                videoPlayerViewModel.videoHeaders.set(headers)
            } catch (e: Exception) {
                videoPlayerViewModel.videoHeaders.set(emptyMap())
            }
        }
        arguments?.getString(VIDEO_NAME)?.let { videoPlayerViewModel.videoName.set(it) }

        val iUrl = arguments?.getString(VIDEO_URL)?.toUri()

        if (iUrl != null) {
            videoPlayerViewModel.videoUrl.set(iUrl)
        }

        val url = videoPlayerViewModel.videoUrl.get() ?: Uri.EMPTY
        // The "Cookie" header will be passed here, but OkHttp using CookieJar
        val headers = videoPlayerViewModel.videoHeaders.get() ?: emptyMap()

        val mediaFactory = createMediaFactory(headers, url.toString().startsWith("http"))

        trackSelector = DefaultTrackSelector(requireContext())
        player = ExoPlayer.Builder(requireContext())
            .setRenderersFactory(createRenderFactory())
            .setMediaSourceFactory(mediaFactory)
            .setTrackSelector(trackSelector)
            .setSeekBackIncrementMs(SEEK_INCREMENT_MS)
            .setSeekForwardIncrementMs(SEEK_INCREMENT_MS)
            .build()

        dataBinding = FragmentPlayerBinding.inflate(inflater, container, false).apply {
            val currentBinding = this

            currentBinding.viewModel = videoPlayerViewModel
            currentBinding.btnBack.setOnClickListener(navigationIconClickListener)
            currentBinding.videoView.player = player
            // 共享元素过渡目标端 transitionName，与 VideoFragment.startVideo 源端 "surf_video_thumb" 一致
            currentBinding.videoView.transitionName = "surf_video_thumb"
            currentBinding.videoView.setShowBuffering(SHOW_BUFFERING_ALWAYS)
            // 默认画面比例：FIT（完整显示，不裁切不变形）。全屏按钮不再绑定 ZOOM，
            // 用户如需裁切/填满，通过「更多」右侧的画面比例入口显式选择。
            currentBinding.videoView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT

            currentBinding.btnSpeed.setOnClickListener { showSpeedPicker() }
            currentBinding.btnAspect.setOnClickListener { showAspectPicker() }
            currentBinding.btnPip.setOnClickListener {
                (activity as? VideoPlayerActivity)?.enterPipIfPossible()
            }
            // 「更多」按钮弹出真菜单（当前仅轨道选择，为后续扩展留口），避免叫"更多"却直接跳单一功能
            currentBinding.btnMore.setOnClickListener { showOverflowMenu() }

            // 双击左/右半屏快退/快进；返回 false 不消费触摸，让 PlayerView controller 正常显示/隐藏
            currentBinding.videoView.setOnTouchListener { _, e ->
                gestureDetector.onTouchEvent(e)
                false
            }

            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY && player.playWhenReady) {
                        currentBinding.loadingBar.visibility = View.GONE
                        // 首帧就绪：启动缩略图→播放器共享元素过渡（仅一次，防黑帧）
                        maybeStartPostponedTransition()
                    } else if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
                        currentBinding.loadingBar.visibility = View.GONE
                        // 兜底：确保过渡不因 player 状态无限推迟
                        maybeStartPostponedTransition()
                    } else {
                        currentBinding.loadingBar.visibility = View.VISIBLE
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    maybeStartPostponedTransition()  // 兜底：出错也必须启动过渡，否则界面卡死
                    if (videoPlayerViewModel.videoUrl.get().toString().startsWith("http")) {
                        AlertDialog.Builder(requireContext())
                            .setTitle(getString(R.string.player_download_only_title))
                            .setMessage(getString(R.string.player_download_only_message))
                            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                                dialog.dismiss()
                                handleClose()
                            }
                            .show()
                        return
                    }
                    Toast.makeText(context, getString(R.string.player_playback_error), Toast.LENGTH_LONG).show()
                }

                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    // 实际显示尺寸（含旋转/像素比）由 VideoGeometry 统一计算，驱动 Activity 自动旋转
                    (activity as? VideoPlayerActivity)?.onVideoSizeChanged(videoSize)
                }
            })

            val mediaItem: MediaItem = MediaItem.fromUri(url)
            player.setMediaItem(mediaItem)
            player.prepare()
            player.playWhenReady = true
        }

        return dataBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        handleBackPressed()
        handlePlayerEvents()
        applyTopBarInsets()
        videoPlayerViewModel.start()
        getActivity(context)?.let { appUtil.hideSystemUI(it.window, dataBinding.root) }
        // 超时兜底：极端情况下 player 不进 READY/ERROR（如初始化异常），1.5s 后强制启动过渡，避免界面卡死
        dataBinding.root.postDelayed({ maybeStartPostponedTransition() }, 1500)
    }

    /** 共享元素过渡（缩略图→播放器）启动控制：保证 startPostponedEnterTransition 只调一次。
     *  由 player STATE_READY/ENDED/IDLE、onPlayerError、onViewCreated 1.5s 超时三路触发。 */
    private var sharedElementTransitionStarted = false
    private fun maybeStartPostponedTransition() {
        if (sharedElementTransitionStarted) return
        sharedElementTransitionStarted = true
        activity?.startPostponedEnterTransition()
    }

    /**
     * 顶部控制栏补 status bar / 刘海安全区 inset（沉浸式下系统栏可见时也不被遮挡）。
     * - base 为常量（不取 v.padding），避免反复 dispatch 时 padding 累加；
     * - 四向都补 inset，横屏刘海/挖孔在左/右时左右按钮不贴危险区；
     * - 显式 requestApplyInsets，触发沉浸式下首次 inset 派发。
     */
    private fun applyTopBarInsets() {
        val base = (TOP_BAR_PADDING_DP * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(dataBinding.topBar) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(
                left = base + bars.left,
                top = base + bars.top,
                right = base + bars.right,
                bottom = base
            )
            insets
        }
        ViewCompat.requestApplyInsets(dataBinding.topBar)
    }

    private fun getActivity(context: Context?): Activity? {
        if (context == null) {
            return null
        } else if (context is ContextWrapper) {
            return if (context is Activity) {
                context
            } else {
                getActivity(context.baseContext)
            }
        }
        return null
    }

    override fun onDestroyView() {
        getActivity(context)?.let { appUtil.showSystemUI(it.window, dataBinding.root) }
        videoPlayerViewModel.stop()
        player.release()
        super.onDestroyView()
    }

    private val navigationIconClickListener = View.OnClickListener {
        handleClose()
    }

    private fun handlePlayerEvents() {
        videoPlayerViewModel.stopPlayerEvent.observe(viewLifecycleOwner) {
            player.stop()
        }
    }

    private fun createRenderFactory(): RenderersFactory {
        return DefaultRenderersFactory(requireContext().applicationContext)
            .setExtensionRendererMode(EXTENSION_RENDERER_MODE_PREFER)
            .setMediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                var decoderInfos =
                    MediaCodecSelector.DEFAULT
                        .getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
                if (MimeTypes.VIDEO_H264 == mimeType) {
                    decoderInfos = ArrayList(decoderInfos)
                    decoderInfos.reverse()
                }
                decoderInfos
            }
    }

    private fun createMediaFactory(
        headers: Map<String, String>,
        isHttp: Boolean
    ): DefaultMediaSourceFactory {
        val dataSourceFactory: DataSource.Factory = if (isHttp) {
            OkHttpDataSource.Factory(okHttpClient.getProxyOkHttpClient())
                .setDefaultRequestProperties(headers)
        } else {
            DefaultDataSource.Factory(requireContext())
        }

        return DefaultMediaSourceFactory(requireContext()).setDataSourceFactory(dataSourceFactory)
    }

    private fun handleBackPressed() {
        this.view?.isFocusableInTouchMode = true
        this.view?.requestFocus()
        this.view?.setOnKeyListener { _, keyCode, _ ->
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                handleClose()
                true
            } else false
        }
    }

    private fun handleClose() {
        videoPlayerViewModel.stop()
        activity?.finish()
    }

    /** 「更多」按钮：弹出真菜单（当前仅"音轨/字幕"，后续可扩展）。 */
    private fun showOverflowMenu() {
        val popup = PopupMenu(requireContext(), dataBinding.btnMore)
        popup.menu.add(0, MENU_TRACKS, 0, getString(R.string.player_tracks))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_TRACKS -> {
                    showTracksPicker()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun showAspectPicker() {
        // 菜单用解释文案（裁切保留"会裁掉边缘"提示），按钮只显示短文案，避免长文案挤掉标题
        val labels = arrayOf(
            getString(R.string.player_aspect_fit),
            getString(R.string.player_aspect_fill),
            getString(R.string.player_aspect_crop)
        )
        val shortLabels = arrayOf(
            getString(R.string.player_aspect_fit_short),
            getString(R.string.player_aspect_fill_short),
            getString(R.string.player_aspect_crop_short)
        )
        val modes = intArrayOf(
            AspectRatioFrameLayout.RESIZE_MODE_FIT,
            AspectRatioFrameLayout.RESIZE_MODE_FILL,
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        )
        val current = when (dataBinding.videoView.resizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_FILL -> 1
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> 2
            else -> 0
        }
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.player_aspect_title))
            .setSingleChoiceItems(labels, current) { dialog, which ->
                dataBinding.videoView.resizeMode = modes[which]
                dataBinding.btnAspect.text = shortLabels[which]
                dialog.dismiss()
            }
            .show()
    }

    private fun showSpeedPicker() {
        val labels = arrayOf("0.5x", "1.0x", "1.25x", "1.5x", "2.0x")
        val values = floatArrayOf(0.5f, 1.0f, 1.25f, 1.5f, 2.0f)
        val current = player.playbackParameters.speed
        val checked = values.indexOfFirst { it == current }.coerceAtLeast(0)
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.player_speed_title))
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                val speed = values[which]
                player.playbackParameters = PlaybackParameters(speed)
                dataBinding.btnSpeed.text = "${formatSpeed(speed)}x"
                dialog.dismiss()
            }
            .show()
    }

    private fun formatSpeed(speed: Float): String {
        return if (speed == speed.toInt().toFloat()) speed.toInt().toString() else speed.toString()
    }

    private fun showTracksPicker() {
        val tracks = player.currentTracks
        data class TrackOption(val group: TrackGroup, val type: Int, val index: Int, val label: String)
        val options = mutableListOf<TrackOption>()

        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO && group.type != C.TRACK_TYPE_TEXT) continue
            val typePrefix = if (group.type == C.TRACK_TYPE_AUDIO) {
                getString(R.string.player_audio_prefix)
            } else {
                getString(R.string.player_sub_prefix)
            }
            for (i in 0 until group.length) {
                val fmt = group.getTrackFormat(i)
                val label = listOfNotNull(fmt.label, fmt.language)
                    .joinToString(" ").ifBlank { getString(R.string.player_track_fallback, options.size + 1) }
                options += TrackOption(group.mediaTrackGroup, group.type, i, "$typePrefix: $label")
            }
        }

        if (options.isEmpty()) {
            Toast.makeText(context, getString(R.string.player_no_tracks), Toast.LENGTH_SHORT).show()
            return
        }

        val items = options.map { it.label }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.player_tracks_title))
            .setItems(items) { _, which ->
                val opt = options[which]
                player.trackSelectionParameters = player.trackSelectionParameters
                    .buildUpon()
                    // 先恢复该类型（曾用"关闭字幕"禁用过 text，重选字幕时需重新 enable）
                    .setTrackTypeDisabled(opt.type, false)
                    .setOverrideForType(TrackSelectionOverride(opt.group, opt.index))
                    .build()
            }
            .setNegativeButton(getString(R.string.player_disable_subtitles)) { _, _ ->
                player.trackSelectionParameters = player.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
            }
            .show()
    }
}
