package com.myAllVideoBrowser.ui.main.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.databinding.FragmentPlayerBinding
import com.myAllVideoBrowser.data.repository.PlaybackPositionStore
import com.myAllVideoBrowser.data.repository.VideoRepository
import com.myAllVideoBrowser.ui.main.base.BaseFragment
import com.myAllVideoBrowser.util.AppUtil
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.DisplayNameFormatter
import com.myAllVideoBrowser.util.MediaRequestHeaderPolicy
import com.myAllVideoBrowser.util.proxy_utils.OkHttpProxyClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import kotlin.math.abs
import kotlin.math.roundToInt
import javax.inject.Inject


@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class VideoPlayerFragment : BaseFragment() {

    companion object {
        const val VIDEO_URL = "video_url"
        const val VIDEO_HEADERS = "video_headers"
        const val VIDEO_NAME = "video_name"
        const val VIDEO_SOURCE = "video_source"
        const val VIDEO_MEDIA_KIND = "video_media_kind"
        const val VIDEO_FORMAT_ID = "video_format_id"
        const val VIDEO_FORMAT_HEIGHT = "video_format_height"
        const val VIDEO_PAGE_URL = "video_page_url"
        const val VIDEO_MEDIA_IDENTITY = "video_media_identity"
        const val VIDEO_DETECTED_BY_SUPER_X = "video_detected_by_super_x"
        const val VIDEO_EXTRACTED_AT = "video_extracted_at"

        /**
         * 业务层直播判定（`VideoInfo.isLive`）。
         * 是否记录/恢复进度只看它，不看 media3 的 `isCurrentMediaItemLive`。
         */
        const val VIDEO_IS_LIVE = "video_is_live"
        const val SOURCE_BROWSER = "browser"
        const val SOURCE_VIDEO_LIBRARY = "video_library"
        private const val SEEK_INCREMENT_MS = 10_000L
        private const val TOP_BAR_PADDING_DP = 4
        private const val MENU_TRACKS = 1
        private const val MENU_SPEED = 2
        private const val MENU_ASPECT = 3
        private const val MENU_PIP = 4
        private const val POSITION_SAVE_INTERVAL_MS = 10_000L
        private const val LONG_PRESS_SPEED = 2f
    }

    @Inject
    lateinit var viewModelFactory: ViewModelProvider.Factory

    @Inject
    lateinit var appUtil: AppUtil

    @Inject
    lateinit var okHttpClient: OkHttpProxyClient

    @Inject
    lateinit var videoRepository: VideoRepository

    @Inject
    lateinit var playbackPositionStore: PlaybackPositionStore

    private lateinit var player: ExoPlayer
    private lateinit var trackSelector: DefaultTrackSelector

    // 水平滑动 seek（快进/快退）状态：滑动期间暂停播放以渲染目标帧预览，松手恢复
    private var seeking = false
    private var seekStartPos = 0L
    private var wasPlayingBeforeSeek = false
    private enum class GestureMode {
        NONE,
        SEEK,
        BRIGHTNESS,
        VOLUME,
        LONG_PRESS_SPEED
    }
    private var gestureMode = GestureMode.NONE
    private var verticalStartValue = 0f
    private var longPressOriginalSpeed: Float? = null
    private var gestureFeedbackHideRunnable: Runnable? = null
    // PiP 模式标志：小窗内不响应水平滑动 seek
    private var isPipMode = false
    // 滑动 seek 预览气泡节流：避免每个 move 都 Glide 取帧造成请求堆积卡顿
    private var lastPreviewTargetMs = 0L
    private var lastPreviewWallMs = 0L
    private var hostBackgrounded = false
    private var resumePositionMs = 0L
    private var resumePlayWhenReady = false
    private var surfaceRecoveryGeneration = 0L
    private var awaitingForegroundFrame = false
    private var playbackMediaKind = PlaybackMediaKind.AUTO
    private var playbackFormatId = ""
    private var playbackFormatHeight = 0
    private var playbackPageUrl = ""
    private var playbackMediaIdentity = ""
    private var playbackSource = ""
    private var playbackDetectedBySuperX = false
    private var playbackExtractedAt = 0L
    private var playbackPositionKey = ""
    /**
     * 本次会话交给 `setMediaSource(source, startMs)` 的恢复位置。
     * 同时是一次性"初始恢复复核"的输入：Timeline 真实建立后再判断它还算不算数。
     */
    private var initialPlaybackPositionMs: Long? = null
    /** 初始恢复复核是否已得出结论（[PlaybackPositionGate.RestoreVeto.DEFER] 不消耗该标记）。 */
    private var initialRestoreValidated = false
    /** 业务层直播判定（[VIDEO_IS_LIVE]）：直播既不恢复也不记录进度。 */
    private var playbackDeclaredLive = false
    /** 复用的 Timeline.Window 持有者，避免每次采集/保存都分配。 */
    private val playbackWindow = Timeline.Window()
    private var playbackPositionSaveRunnable: Runnable? = null
    private var currentPlaybackHeaders: Map<String, String> = emptyMap()
    private var refreshAttempted = false
    private var refreshInProgress = false
    private var playbackErrorDialogShown = false
    private var touchGestureConsumed = false
    private var touchGestureTracking = false

    private val surfaceRecoveryListener = object : Player.Listener {
        override fun onRenderedFirstFrame() {
            awaitingForegroundFrame = false
        }
    }

    private val gestureDetector by lazy {
        GestureDetector(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                return !isPipMode && !isBottomControlsTouch(e.y)
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                // 双击左半屏快退、右半屏快进（幅度同 seekBack/Forward 的 10s）
                if (isPipMode || isBottomControlsTouch(e.y)) return false
                val width = dataBinding.videoView.width
                if (width > 0 && e.x < width / 2f) {
                    player.seekBack()
                    showGestureFeedback(
                        R.drawable.surf_player_rewind,
                        getString(R.string.player_gesture_rewind, SEEK_INCREMENT_MS / 1000L)
                    )
                } else {
                    player.seekForward()
                    showGestureFeedback(
                        R.drawable.surf_player_forward,
                        getString(R.string.player_gesture_forward, SEEK_INCREMENT_MS / 1000L)
                    )
                }
                dataBinding.videoView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                touchGestureConsumed = true
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (isPipMode) return false
                dataBinding.videoView.performClick()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (isPipMode || isBottomControlsTouch(e.y) || longPressOriginalSpeed != null) return
                gestureMode = GestureMode.LONG_PRESS_SPEED
                longPressOriginalSpeed = player.playbackParameters.speed
                player.playbackParameters = player.playbackParameters.withSpeed(LONG_PRESS_SPEED)
                showGestureFeedback(
                    R.drawable.ic_speed_24px,
                    getString(R.string.player_gesture_speed, formatSpeed(LONG_PRESS_SPEED))
                )
                dataBinding.videoView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (e1 == null || isPipMode) return false
                val view = dataBinding.videoView
                // 触摸落在底部进度条区域 → 不抢事件，交给 PlayerView 自带 TimeBar（避免双 seek 打架）
                if (gestureMode == GestureMode.NONE && isBottomControlsTouch(e1.y)) return false
                // 右滑(totalDx>0)快进、左滑快退；用总位移判断主方向（避免增量 distanceX 抖动）
                val totalDx = e2.x - e1.x
                val totalDy = e2.y - e1.y
                if (longPressOriginalSpeed != null || gestureMode == GestureMode.LONG_PRESS_SPEED) {
                    return true
                }

                // Once a gesture crosses the slop threshold, its mode is fixed
                // until ACTION_UP/ACTION_CANCEL. This prevents a diagonal or
                // shaky finger path from switching between seek and volume/brightness.
                when (gestureMode) {
                    GestureMode.BRIGHTNESS,
                    GestureMode.VOLUME -> {
                        updateVerticalAdjustment(totalDy)
                        return true
                    }

                    GestureMode.SEEK -> {
                        return updateSeek(totalDx)
                    }

                    GestureMode.NONE -> Unit
                    GestureMode.LONG_PRESS_SPEED -> return true
                }

                if (abs(totalDy) > abs(totalDx) * 1.25f) {
                    gestureMode = if (e1.x < view.width / 2f) {
                        GestureMode.BRIGHTNESS
                    } else {
                        GestureMode.VOLUME
                    }
                    verticalStartValue = when (gestureMode) {
                        GestureMode.BRIGHTNESS -> currentBrightness()
                        GestureMode.VOLUME -> currentVolumeFraction()
                        else -> 0f
                    }
                    updateVerticalAdjustment(totalDy)
                    return true
                }
                if (abs(totalDx) < abs(totalDy) * 1.5f) return false
                gestureMode = GestureMode.SEEK
                return updateSeek(totalDx)
            }
        })
    }

    private fun updateSeek(totalDx: Float): Boolean {
        val view = dataBinding.videoView
        val duration = player.duration.coerceAtLeast(0L)
        if (view.width <= 0 || duration <= 0L) return false
        val width = view.width
        // 首次进入滑动 seek：暂停播放 + 记录起点（之后基于起点算总位移，避免累加抖动）
        if (!seeking) {
            seeking = true
            seekStartPos = player.currentPosition
            wasPlayingBeforeSeek = player.playWhenReady
            player.playWhenReady = false
            if (!view.isControllerFullyVisible) view.showController()
        }
        // 滑满整屏最多 ±30 秒（不按视频总时长百分比，避免长视频一拉跳很远）
        val maxSeekMs = 30_000L
        val target = (seekStartPos + (totalDx / width.toFloat() * maxSeekMs.toFloat()).toLong())
            .coerceIn(0L, duration)
        player.seekTo(target)
        showSeekPreview(target)
        return true
    }

    private fun isBottomControlsTouch(y: Float): Boolean {
        if (!::dataBinding.isInitialized) return false
        val view = dataBinding.videoView
        val controllerH = view.findViewById<View>(R.id.player_bottom_controls).height
        return view.isControllerFullyVisible && controllerH > 0 && y > view.height - controllerH
    }

    private fun updateVerticalAdjustment(totalDy: Float) {
        val view = dataBinding.videoView
        if (view.width <= 0 || view.height <= 0) return

        val value = (verticalStartValue - totalDy / view.height.toFloat()).coerceIn(0f, 1f)
        when (gestureMode) {
            GestureMode.BRIGHTNESS -> setBrightness(value)
            GestureMode.VOLUME -> setVolume(value)
            else -> return
        }
        val label = when (gestureMode) {
            GestureMode.BRIGHTNESS -> getString(
                R.string.player_gesture_brightness,
                (value * 100).roundToInt()
            )
            GestureMode.VOLUME -> getString(
                R.string.player_gesture_volume,
                (value * 100).roundToInt()
            )
            else -> return
        }
        showGestureFeedback(
            if (gestureMode == GestureMode.BRIGHTNESS) {
                R.drawable.ic_light_mode_24px
            } else {
                R.drawable.ic_volume_up_24px
            },
            label
        )
    }

    private fun currentBrightness(): Float {
        val windowBrightness = activity?.window?.attributes?.screenBrightness
            ?.takeIf { it >= 0f }
        if (windowBrightness != null) return windowBrightness

        val resolver = context?.contentResolver ?: return 0.5f
        val systemBrightness = runCatching {
            Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrNull()
        return ((systemBrightness ?: 128) / 255f).coerceIn(0f, 1f)
    }

    private fun setBrightness(value: Float) {
        val window = activity?.window ?: return
        val attributes = window.attributes
        attributes.screenBrightness = value
        window.attributes = attributes
    }

    private fun audioManager(): AudioManager? =
        requireContext().getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private fun currentVolumeFraction(): Float {
        val manager = audioManager() ?: return 0f
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return if (max > 0) manager.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat() else 0f
    }

    private fun setVolume(value: Float) {
        val manager = audioManager() ?: return
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max > 0) {
            manager.setStreamVolume(AudioManager.STREAM_MUSIC, (value * max).roundToInt(), 0)
        }
    }

    private fun showGestureFeedback(iconRes: Int, text: String) {
        if (!::dataBinding.isInitialized) return
        gestureFeedbackHideRunnable?.let(dataBinding.root::removeCallbacks)
        dataBinding.gestureFeedbackIcon.setImageResource(iconRes)
        dataBinding.gestureFeedbackText.text = text
        dataBinding.gestureFeedback.contentDescription = text
        dataBinding.gestureFeedback.visibility = View.VISIBLE
        dataBinding.gestureFeedback.animate().cancel()
        dataBinding.gestureFeedback.alpha = 1f
        dataBinding.gestureFeedback.scaleX = 0.92f
        dataBinding.gestureFeedback.scaleY = 0.92f
        dataBinding.gestureFeedback.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(resources.getInteger(R.integer.motion_micro_ms).toLong())
            .start()
        val hide = Runnable {
            dataBinding.gestureFeedback.animate()
                .alpha(0f)
                .setDuration(resources.getInteger(R.integer.motion_micro_ms).toLong())
                .withEndAction {
                    dataBinding.gestureFeedback.visibility = View.GONE
                    dataBinding.gestureFeedback.alpha = 1f
                }
                .start()
        }
        gestureFeedbackHideRunnable = hide
        dataBinding.root.postDelayed(hide, 850L)
    }

    private fun finishTouchGestures(showFeedback: Boolean = true) {
        if (seeking) {
            seeking = false
            player.playWhenReady = wasPlayingBeforeSeek
            hideSeekPreview()
        }
        val originalSpeed = longPressOriginalSpeed
        longPressOriginalSpeed = null
        if (originalSpeed != null) {
            player.playbackParameters = player.playbackParameters.withSpeed(originalSpeed)
            if (showFeedback) {
                showGestureFeedback(
                    R.drawable.ic_speed_24px,
                    getString(R.string.player_gesture_speed_restored, formatSpeed(originalSpeed))
                )
            }
        }
        gestureMode = GestureMode.NONE
    }

    private lateinit var videoPlayerViewModel: VideoPlayerViewModel

    private lateinit var dataBinding: FragmentPlayerBinding

    /** 供 VideoPlayerActivity 构造 PiP 参数 / 控制播放用；view 销毁后返回 null 避免操作已 release 的 player。 */
    fun getPlayerOrNull(): ExoPlayer? {
        return if (::player.isInitialized && view != null) player else null
    }

    // PiP 播放状态监听：onIsPlayingChanged 时刷新小窗 RemoteAction 图标。
    // 仅在 PiP 期间注册，退出小窗即移除，避免长驻监听和重复回调。
    private var pipStateListener: Player.Listener? = null

    /** PiP 模式切换：进入时隐藏顶部控制栏（PiP 只显示视频画面），退出时恢复。 */
    fun setPipMode(inPip: Boolean) {
        isPipMode = inPip
        if (!::dataBinding.isInitialized) return
        dataBinding.topBar.visibility = if (inPip) View.GONE else View.VISIBLE
        dataBinding.loadingBar.visibility = if (!inPip && player.playbackState == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
        dataBinding.videoView.useController = !inPip
        if (inPip) {
            registerPipStateListener()
        } else {
            unregisterPipStateListener()
            dataBinding.videoView.showController()
        }
    }

    private fun registerPipStateListener() {
        if (pipStateListener != null || !::player.isInitialized) return
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // 播放状态真正变化（含缓冲结束自动续播）后，刷新 PiP 按钮图标
                (activity as? VideoPlayerActivity)?.refreshPipActions()
            }
        }
        pipStateListener = listener
        player.addListener(listener)
    }

    private fun unregisterPipStateListener() {
        val listener = pipStateListener ?: return
        pipStateListener = null
        if (::player.isInitialized) {
            player.removeListener(listener)
        }
    }

    /** 滑动 seek 时显示预览气泡：目标时间始终刷新，缩略图按节流加载（仅本地视频，远程只显示时间）。 */
    private fun showSeekPreview(targetMs: Long) {
        if (!::dataBinding.isInitialized) return
        dataBinding.seekPreviewContainer.visibility = View.VISIBLE
        dataBinding.tvSeekTime.text = formatSeekTime(targetMs)
        // 远程视频（http/m3u8/mpd）不抽帧，只显示目标时间：隐藏缩略图框避免空白/旧图
        if (videoPlayerViewModel.videoUrl.get().toString().startsWith("http")) {
            dataBinding.ivSeekPreview.visibility = View.GONE
            Glide.with(this).clear(dataBinding.ivSeekPreview)
            return
        }
        dataBinding.ivSeekPreview.visibility = View.VISIBLE
        val now = SystemClock.uptimeMillis()
        if (Math.abs(targetMs - lastPreviewTargetMs) < 500 && now - lastPreviewWallMs < 100) return
        lastPreviewTargetMs = targetMs
        lastPreviewWallMs = now
        Glide.with(this)
            .load(videoPlayerViewModel.videoUrl.get())
            .apply(RequestOptions().frame(targetMs * 1000L))
            .into(dataBinding.ivSeekPreview)
    }

    /** 松手后隐藏预览气泡并取消待执行的 Glide 请求，避免滑动中请求堆积卡顿。 */
    private fun hideSeekPreview() {
        if (!::dataBinding.isInitialized) return
        dataBinding.seekPreviewContainer.visibility = View.GONE
        Glide.with(this).clear(dataBinding.ivSeekPreview)
    }

    private fun formatSeekTime(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(0L)
        return "%02d:%02d".format(totalSec / 60, totalSec % 60)
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
        arguments?.getString(VIDEO_NAME)?.let {
            // Humanized display title (extension/separator cleanup, fallback to raw)
            videoPlayerViewModel.videoName.set(DisplayNameFormatter.clean(it).ifBlank { it })
        }

        playbackMediaKind = PlaybackMediaKind.fromSerialized(arguments?.getString(VIDEO_MEDIA_KIND))
        playbackFormatId = arguments?.getString(VIDEO_FORMAT_ID).orEmpty()
        playbackFormatHeight = arguments?.getInt(VIDEO_FORMAT_HEIGHT) ?: 0
        playbackPageUrl = arguments?.getString(VIDEO_PAGE_URL).orEmpty()
        playbackMediaIdentity = arguments?.getString(VIDEO_MEDIA_IDENTITY).orEmpty()
        playbackSource = arguments?.getString(VIDEO_SOURCE).orEmpty()
        playbackDetectedBySuperX = arguments?.getBoolean(VIDEO_DETECTED_BY_SUPER_X) == true
        playbackExtractedAt = arguments?.getLong(VIDEO_EXTRACTED_AT) ?: 0L
        playbackDeclaredLive = arguments?.getBoolean(VIDEO_IS_LIVE, false) == true

        val iUrl = arguments?.getString(VIDEO_URL)?.toUri()

        if (iUrl != null) {
            videoPlayerViewModel.videoUrl.set(iUrl)
        }

        val url = videoPlayerViewModel.videoUrl.get() ?: Uri.EMPTY
        playbackPositionKey = PlaybackPositionKey.forMedia(
            source = playbackSource,
            mediaUrl = url.toString(),
            mediaIdentity = playbackMediaIdentity,
            mediaKind = playbackMediaKind
        )
        val savedPosition = playbackPositionStore.get(playbackPositionKey)
        val restoreDecision = PlaybackPositionPolicy.restore(
            saved = savedPosition,
            currentDurationMs = 0L,
            declaredLive = playbackDeclaredLive
        )
        if (restoreDecision.shouldClear) playbackPositionStore.remove(playbackPositionKey)
        initialPlaybackPositionMs = restoreDecision.positionMs
        initialRestoreValidated = false
        AppLogger.d(
            "PLAYER_POSITION: action=RESTORE_READ key=${playbackPositionStore.shortId(playbackPositionKey)} " +
                "restored=${restoreDecision.positionMs ?: -1L} clear=${restoreDecision.shouldClear} " +
                "declaredLive=$playbackDeclaredLive kind=$playbackMediaKind"
        )
        // The "Cookie" header will be passed here, but OkHttp using CookieJar
        val headers = videoPlayerViewModel.videoHeaders.get() ?: emptyMap()
        currentPlaybackHeaders = headers

        val mediaFactory = createMediaFactory(headers, url.toString().startsWith("http"))

        trackSelector = DefaultTrackSelector(requireContext())
        player = ExoPlayer.Builder(requireContext())
            .setRenderersFactory(createRenderFactory())
            .setMediaSourceFactory(mediaFactory)
            .setTrackSelector(trackSelector)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setSeekBackIncrementMs(SEEK_INCREMENT_MS)
            .setSeekForwardIncrementMs(SEEK_INCREMENT_MS)
            .build()
        player.addListener(surfaceRecoveryListener)

        dataBinding = FragmentPlayerBinding.inflate(inflater, container, false).apply {
            val currentBinding = this

            currentBinding.viewModel = videoPlayerViewModel
            currentBinding.btnBack.setOnClickListener(navigationIconClickListener)
            currentBinding.videoView.player = player
            PlayerChrome.bind(currentBinding, player) { isPipMode }
            // 共享元素过渡目标端 transitionName，与 VideoFragment.startVideo 源端 "surf_video_thumb" 一致
            currentBinding.videoView.transitionName = "surf_video_thumb"
            // 默认画面比例：FIT（完整显示，不裁切不变形）。全屏按钮不再绑定 ZOOM，
            // 用户如需裁切/填满，通过「更多」右侧的画面比例入口显式选择。
            currentBinding.videoView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT

            currentBinding.btnMore.setOnClickListener { showOverflowMenu() }

            // 双击/滑动 seek 由 gestureDetector 处理；已识别手势由这里消费，普通单击由 onSingleTapConfirmed 触发。
            // 松手（UP/CANCEL）时若处于滑动 seek，恢复播放状态。
            currentBinding.videoView.setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                    touchGestureConsumed = false
                }
                val handled = gestureDetector.onTouchEvent(e)
                if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                    touchGestureTracking = handled
                }
                if (handled && e.actionMasked != MotionEvent.ACTION_DOWN) {
                    touchGestureConsumed = true
                }
                if (e.action == MotionEvent.ACTION_UP || e.action == MotionEvent.ACTION_CANCEL) {
                    val consumed = touchGestureTracking || touchGestureConsumed || gestureMode != GestureMode.NONE
                    finishTouchGestures()
                    touchGestureConsumed = false
                    touchGestureTracking = false
                    return@setOnTouchListener consumed
                }
                touchGestureTracking || touchGestureConsumed || gestureMode != GestureMode.NONE
            }

            player.addListener(object : Player.Listener {
                override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                    // 真实 Timeline（非 empty、非 placeholder）一到就复核初始恢复：
                    // 动态窗口/直播放弃恢复位置，静态源用真实 duration 复核片尾。
                    validateInitialRestoreAgainstResolvedTimeline()
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {

                        // 首帧就绪：启动缩略图→播放器共享元素过渡（仅一次，防黑帧）
                        maybeStartPostponedTransition()
                        // 兜底复核：静态源到 READY 一定能拿到真实 duration（收敛 DEFER 分支）
                        validateInitialRestoreAgainstResolvedTimeline()
                    } else if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {

                        if (playbackState == Player.STATE_ENDED) {
                            // 统一走 Gate：declaredLive（直播/事件流播完）→ SKIP 不清理，
                            // 普通 VOD → CLEAR。Fragment 不再直接改 Store。
                            persistPlaybackPosition(
                                force = true,
                                reason = PlaybackSaveReason.PLAYBACK_ENDED
                            )
                        }
                        // 兜底：确保过渡不因 player 状态无限推迟
                        maybeStartPostponedTransition()
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    maybeStartPostponedTransition()  // 兜底：出错也必须启动过渡，否则界面卡死
                    if (tryRefreshExpiredRemoteUrl(error)) {
                        return
                    }
                    showPlaybackError(error)
                }

                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    // 实际显示尺寸（含旋转/像素比）由 VideoGeometry 统一计算，驱动 Activity 自动旋转
                    (activity as? VideoPlayerActivity)?.onVideoSizeChanged(videoSize)
                }
            })

            val mediaSource = createMediaSource(url, headers)
            // 恢复位置继续通过 setMediaSource(source, startMs) 交给 media3：
            // 这样首帧与共享元素过渡不会先渲染 0 处再跳。该位置是否仍然有效，
            // 由 validateInitialRestoreAgainstResolvedTimeline() 在真实 Timeline 到达后一次性复核。
            if (initialPlaybackPositionMs != null) {
                player.setMediaSource(mediaSource, initialPlaybackPositionMs!!)
            } else {
                player.setMediaSource(mediaSource)
            }
            player.prepare()
            player.playWhenReady = true
        }

        return dataBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        handlePlayerEvents()
        applyTopBarInsets()
        videoPlayerViewModel.start()
        startPlaybackPositionPersistence()
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
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.updatePadding(
                left = base + bars.left,
                top = base + bars.top,
                right = base + bars.right,
                bottom = base
            )
            dataBinding.videoView.findViewById<View>(R.id.player_bottom_controls).updatePadding(
                left = (16 * resources.displayMetrics.density).toInt() + bars.left,
                right = (16 * resources.displayMetrics.density).toInt() + bars.right,
                bottom = (8 * resources.displayMetrics.density).toInt() + bars.bottom
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
        gestureFeedbackHideRunnable?.let { dataBinding.root.removeCallbacks(it) }
        finishTouchGestures(showFeedback = false)
        persistPlaybackPosition(force = true, reason = PlaybackSaveReason.DESTROY_VIEW)
        stopPlaybackPositionPersistence()
        surfaceRecoveryGeneration++
        dataBinding.root.removeCallbacks(surfaceRecoveryRunnable)
        unregisterPipStateListener()
        getActivity(context)?.let { appUtil.showSystemUI(it.window, dataBinding.root) }
        videoPlayerViewModel.stop()
        player.removeListener(surfaceRecoveryListener)
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
            .setEnableDecoderFallback(true)
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

    private fun buildMediaItem(url: Uri): MediaItem {
        return MediaItem.Builder()
            .setUri(url)
            .apply {
                when (playbackMediaKind) {
                    PlaybackMediaKind.HLS -> setMimeType(MimeTypes.APPLICATION_M3U8)
                    PlaybackMediaKind.DASH -> setMimeType(MimeTypes.APPLICATION_MPD)
                    PlaybackMediaKind.AUTO -> Unit
                }
            }
            .build()
    }

    private fun createMediaSource(url: Uri, headers: Map<String, String>) =
        createMediaFactory(headers, url.toString().startsWith("http"))
            .createMediaSource(buildMediaItem(url))

    private fun tryRefreshExpiredRemoteUrl(error: PlaybackException): Boolean {
        val responseCode = findHttpResponseCode(error)
        if (responseCode !in setOf(401, 403) ||
            playbackSource != SOURCE_BROWSER ||
            playbackPageUrl.isBlank() ||
            refreshAttempted || refreshInProgress
        ) {
            return false
        }

        refreshAttempted = true
        refreshInProgress = true
        val sourceUrl = playbackPageUrl
        val sourceCookie = CookieManager.getInstance().getCookie(sourceUrl)
        val userAgent = currentPlaybackHeaders.entries
            .firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }
            ?.value
        val request = runCatching {
            Request.Builder().url(sourceUrl).get().apply {
                if (!userAgent.isNullOrBlank()) header("User-Agent", userAgent)
                if (!sourceCookie.isNullOrBlank()) header("Cookie", sourceCookie)
            }.build()
        }.getOrNull()

        if (request == null) {
            refreshInProgress = false
            return false
        }

        val resumePosition = player.currentPosition.coerceAtLeast(0L)
        viewLifecycleOwner.lifecycleScope.launch {
            val refreshedFormat = withContext(Dispatchers.IO) {
                runCatching {
                    val refreshPlan = PlaybackRefreshPlanResolver.resolve(
                        playbackDetectedBySuperX,
                        playbackMediaKind
                    )
                    val refreshedInfo = if (refreshPlan.useSuperXDetector) {
                        videoRepository.getVideoInfoBySuperXDetector(
                            request,
                            refreshPlan.isHls,
                            refreshPlan.isDash,
                            false
                        )
                    } else {
                        videoRepository.getVideoInfo(
                            request,
                            refreshPlan.isHls || refreshPlan.isDash,
                            false
                        )
                    }
                    PlaybackFormatRefreshMatcher.find(
                        refreshedInfo?.formats?.formats.orEmpty(),
                        playbackFormatId,
                        playbackFormatHeight,
                        playbackMediaKind
                    )
                }.getOrNull()
            }
            refreshInProgress = false
            val refreshedUrl = refreshedFormat?.url?.takeIf { it.isNotBlank() }
                ?: refreshedFormat?.manifestUrl?.takeIf { it.isNotBlank() }
            if (refreshedFormat == null || refreshedUrl == null || !isAdded) {
                showPlaybackError(error)
                return@launch
            }

            playbackMediaKind = PlaybackMediaKindResolver.resolve(refreshedFormat)
            playbackFormatId = refreshedFormat.formatId.orEmpty()
            playbackFormatHeight = refreshedFormat.height
            playbackExtractedAt = System.currentTimeMillis()
            val freshCookie = CookieManager.getInstance().getCookie(refreshedUrl)
            currentPlaybackHeaders = MediaRequestHeaderPolicy.forPlayback(
                refreshedFormat.httpHeaders.orEmpty(),
                freshCookie
            )
            val refreshedUri = Uri.parse(refreshedUrl)
            videoPlayerViewModel.videoUrl.set(refreshedUri)
            videoPlayerViewModel.videoHeaders.set(currentPlaybackHeaders)
            // 刷新位置是本会话续播点，但“初始恢复复核是否已完成”是另一回事：
            // Timeline 还没建立就 403 时，resumePosition 仍是 masking 出来的恢复目标，
            // 这种情况下必须让新 Timeline 再走一次 veto（dynamic 不许恢复旧历史位置）。
            initialPlaybackPositionMs = PlaybackPositionGate.pendingRestoreAfterReprepare(
                validated = initialRestoreValidated,
                pendingRestoreMs = initialPlaybackPositionMs,
                requestedStartMs = resumePosition
            )
            AppLogger.d(
                "PLAYER_POSITION: action=REPREPARE reason=URL_REFRESH start=$resumePosition " +
                    "restored=${initialPlaybackPositionMs ?: -1L} validated=$initialRestoreValidated " +
                    "key=${playbackPositionStore.shortId(playbackPositionKey)}"
            )
            player.stop()
            player.setMediaSource(createMediaSource(refreshedUri, currentPlaybackHeaders), resumePosition)
            player.prepare()
            player.playWhenReady = true
            AppLogger.d(
                "PLAYER_REFRESH: refreshed expired browser media format=$playbackFormatId " +
                    "kind=$playbackMediaKind superX=$playbackDetectedBySuperX"
            )
        }
        return true
    }

    private fun showPlaybackError(error: PlaybackException) {
        if (!isAdded || playbackErrorDialogShown) return
        playbackErrorDialogShown = true
        val responseCode = findHttpResponseCode(error)
        val failure = PlaybackFailureClassifier.classify(error.errorCodeName, responseCode)
        val message = when (failure) {
            PlaybackFailureKind.HTTP_AUTHORIZATION -> R.string.player_error_http_authorization
            PlaybackFailureKind.MANIFEST -> R.string.player_error_manifest
            PlaybackFailureKind.DECODER -> R.string.player_error_decoder
            PlaybackFailureKind.DRM -> R.string.player_error_drm
            PlaybackFailureKind.NETWORK -> R.string.player_error_network
            PlaybackFailureKind.UNKNOWN -> R.string.player_playback_error
        }
        AppLogger.e(
            "PLAYER_ERROR: kind=$failure code=${error.errorCodeName} http=$responseCode " +
                "media=$playbackMediaKind"
        )
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.player_playback_error_title))
            .setMessage(getString(message))
            .setPositiveButton(android.R.string.ok) { dialog, _ -> dialog.dismiss() }
            .setOnDismissListener { playbackErrorDialogShown = false }
            .show()
    }

    private fun findHttpResponseCode(error: Throwable): Int? {
        var current: Throwable? = error
        repeat(12) {
            if (current is HttpDataSource.InvalidResponseCodeException) {
                return current.responseCode
            }
            current = current?.cause
        }
        return null
    }

    /** 进度写入来源，仅用于结构化诊断日志。 */
    private enum class PlaybackSaveReason {
        PERIODIC,
        PLAYER_BACK,
        HOST_STOPPED,
        PLAYBACK_ENDED,
        DESTROY_VIEW
    }

    /** 采集到的播放器状态；未就绪时各字段如实反映未就绪，不做猜测。 */
    private data class PlaybackSnapshot(
        val playbackState: Int,
        val timelineEmpty: Boolean,
        val windowIsPlaceholder: Boolean,
        val windowIsDynamic: Boolean,
        val media3IsLive: Boolean,
        val media3IsSeekable: Boolean,
        val positionMs: Long,
        val durationMs: Long
    )

    private fun handleClose() {
        persistPlaybackPosition(force = true, reason = PlaybackSaveReason.PLAYER_BACK)
        videoPlayerViewModel.stop()
        (activity as? VideoPlayerActivity)?.finishPlayer()
    }

    private fun startPlaybackPositionPersistence() {
        val root = dataBinding.root
        val runnable = object : Runnable {
            override fun run() {
                persistPlaybackPosition(force = false, reason = PlaybackSaveReason.PERIODIC)
                if (::dataBinding.isInitialized && view != null) {
                    root.postDelayed(this, POSITION_SAVE_INTERVAL_MS)
                }
            }
        }
        playbackPositionSaveRunnable = runnable
        root.postDelayed(runnable, POSITION_SAVE_INTERVAL_MS)
    }

    private fun stopPlaybackPositionPersistence() {
        playbackPositionSaveRunnable?.let { dataBinding.root.removeCallbacks(it) }
        playbackPositionSaveRunnable = null
    }

    /**
     * 采集"可信播放快照"。
     *
     * Timeline 为空时 media3 的 `currentPosition` 返回的是 `setMediaSource(source, startMs)`
     * 请求的起始位置（masking position），`clearMediaItems()` 之后还会变成 0；占位窗口
     * 同样没有真实位置。这里如实标注这些状态，由 [PlaybackPositionGate] 决定是否落库。
     */
    private fun trustedPlaybackSnapshot(): PlaybackSnapshot {
        val playbackState = player.playbackState
        val timeline = player.currentTimeline
        val timelineEmpty = timeline.isEmpty
        var windowIsPlaceholder = !timelineEmpty
        var windowIsDynamic = false
        var media3IsLive = false
        var media3IsSeekable = false
        var position = 0L
        if (!timelineEmpty) {
            val index = player.currentMediaItemIndex
            val window = if (index == C.INDEX_UNSET || index < 0 || index >= timeline.windowCount) {
                null
            } else {
                timeline.getWindow(index, playbackWindow)
            }
            windowIsPlaceholder = window == null || window.isPlaceholder
            if (window != null) {
                windowIsDynamic = window.isDynamic
                media3IsLive = window.isLive
                media3IsSeekable = window.isSeekable
            }
            if (!windowIsPlaceholder) {
                // 不在这里钳位：Gate 必须看到真实的非法值（media3 的 C.TIME_UNSET /
                // 负值）才能 SKIP；钳成 0 会让 force=true 的退出保存把已有进度写成 0。
                position = player.currentPosition
            }
        }
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
        return PlaybackSnapshot(
            playbackState = playbackState,
            timelineEmpty = timelineEmpty,
            windowIsPlaceholder = windowIsPlaceholder,
            windowIsDynamic = windowIsDynamic,
            media3IsLive = media3IsLive,
            media3IsSeekable = media3IsSeekable,
            positionMs = position,
            durationMs = duration
        )
    }

    /**
     * 一次性初始恢复复核，见 [PlaybackPositionGate.evaluateInitialRestore]。
     *
     * 静态源在真实时长未知时返回 DEFER，此时不消耗 [initialRestoreValidated]，
     * 等 READY（或下一次 Timeline 变化）再复核。
     */
    private fun validateInitialRestoreAgainstResolvedTimeline() {
        val restoredPosition = initialPlaybackPositionMs ?: return
        if (initialRestoreValidated || !::player.isInitialized) return
        val snapshot = trustedPlaybackSnapshot()
        if (snapshot.timelineEmpty || snapshot.windowIsPlaceholder) return
        val veto = PlaybackPositionGate.evaluateInitialRestore(
            declaredLive = playbackDeclaredLive,
            windowIsDynamic = snapshot.windowIsDynamic,
            restoredPositionMs = restoredPosition,
            durationMs = snapshot.durationMs
        )
        if (veto == PlaybackPositionGate.RestoreVeto.DEFER) return
        initialRestoreValidated = true
        when (veto) {
            PlaybackPositionGate.RestoreVeto.KEEP -> Unit
            PlaybackPositionGate.RestoreVeto.RESET_TO_DEFAULT -> {
                AppLogger.d(
                    "PLAYER_POSITION: action=RESET_RESTORE " +
                        "cause=${if (playbackDeclaredLive) "DECLARED_LIVE" else "DYNAMIC_WINDOW"} " +
                        "restored=$restoredPosition dynamic=${snapshot.windowIsDynamic} " +
                        "media3Live=${snapshot.media3IsLive} key=${playbackPositionStore.shortId(playbackPositionKey)}"
                )
                player.seekToDefaultPosition()
            }
            PlaybackPositionGate.RestoreVeto.CLEAR_AND_RESET -> {
                playbackPositionStore.remove(playbackPositionKey)
                AppLogger.d(
                    "PLAYER_POSITION: action=RESET_RESTORE cause=NEAR_END " +
                        "restored=$restoredPosition duration=${snapshot.durationMs} " +
                        "key=${playbackPositionStore.shortId(playbackPositionKey)}"
                )
                player.seekToDefaultPosition()
            }
            PlaybackPositionGate.RestoreVeto.DEFER -> Unit
        }
    }

    /**
     * 落库前必经 [PlaybackPositionGate]：
     * Timeline 未建立 / 占位窗口 / 已 stop / 业务直播 / 非法位置一律不写。
     * media3 的 live 判断只进日志，不再阻断保存。
     */
    private fun persistPlaybackPosition(force: Boolean, reason: PlaybackSaveReason) {
        if (playbackPositionKey.isBlank() || !::player.isInitialized) return

        val snapshot = trustedPlaybackSnapshot()
        val result = PlaybackPositionGate.evaluate(
            declaredLive = playbackDeclaredLive,
            playbackState = snapshot.playbackState,
            timelineEmpty = snapshot.timelineEmpty,
            windowIsPlaceholder = snapshot.windowIsPlaceholder,
            positionMs = snapshot.positionMs,
            durationMs = snapshot.durationMs,
            force = force
        )
        val key = playbackPositionStore.shortId(playbackPositionKey)
        when (result.action) {
            PlaybackPositionGate.Action.SAVE -> {
                playbackPositionStore.save(
                    playbackPositionKey,
                    snapshot.positionMs,
                    snapshot.durationMs
                )
                AppLogger.d(
                    "PLAYER_POSITION: action=SAVE reason=$reason key=$key " +
                        "position=${snapshot.positionMs} duration=${snapshot.durationMs} " +
                        "state=${playbackStateName(snapshot.playbackState)} declaredLive=$playbackDeclaredLive " +
                        "media3Live=${snapshot.media3IsLive} dynamic=${snapshot.windowIsDynamic} " +
                        "seekable=${snapshot.media3IsSeekable} timelineEmpty=${snapshot.timelineEmpty} " +
                        "placeholder=${snapshot.windowIsPlaceholder}"
                )
            }
            PlaybackPositionGate.Action.SKIP -> {
                AppLogger.d(
                    "PLAYER_POSITION: action=SKIP reason=$reason cause=${result.cause} key=$key " +
                        "position=${snapshot.positionMs} duration=${snapshot.durationMs} " +
                        "state=${playbackStateName(snapshot.playbackState)} declaredLive=$playbackDeclaredLive " +
                        "media3Live=${snapshot.media3IsLive} dynamic=${snapshot.windowIsDynamic} " +
                        "seekable=${snapshot.media3IsSeekable} timelineEmpty=${snapshot.timelineEmpty} " +
                        "placeholder=${snapshot.windowIsPlaceholder}"
                )
            }
            PlaybackPositionGate.Action.CLEAR -> {
                playbackPositionStore.remove(playbackPositionKey)
                AppLogger.d(
                    "PLAYER_POSITION: action=CLEAR reason=$reason cause=${result.cause} key=$key " +
                        "position=${snapshot.positionMs} duration=${snapshot.durationMs} " +
                        "state=${playbackStateName(snapshot.playbackState)}"
                )
            }
        }
    }

    private fun playbackStateName(playbackState: Int): String = when (playbackState) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> "UNKNOWN($playbackState)"
    }

    private val surfaceRecoveryRunnable = Runnable {
        if (!hostBackgrounded && awaitingForegroundFrame && ::player.isInitialized &&
            ::dataBinding.isInitialized
        ) {
            awaitingForegroundFrame = false
            val recoveryPosition = resumePositionMs.coerceAtLeast(0L)
            val shouldPlay = resumePlayWhenReady
            // 待复核目标必须在任何 stop/clear/setMediaSource/prepare 之前更新：
            // 否则新 Timeline 的 veto（seekToDefaultPosition）会被后面的独立 seekTo 反向覆盖。
            initialPlaybackPositionMs = PlaybackPositionGate.pendingRestoreAfterReprepare(
                validated = initialRestoreValidated,
                pendingRestoreMs = initialPlaybackPositionMs,
                requestedStartMs = recoveryPosition
            )
            AppLogger.d(
                "PLAYER_POSITION: action=REPREPARE reason=SURFACE_RECOVERY start=$recoveryPosition " +
                    "restored=${initialPlaybackPositionMs ?: -1L} validated=$initialRestoreValidated " +
                    "key=${playbackPositionStore.shortId(playbackPositionKey)}"
            )
            dataBinding.videoView.player = null
            player.stop()
            player.clearMediaItems()
            val recoveryUrl = videoPlayerViewModel.videoUrl.get() ?: Uri.EMPTY
            // 恢复位置直接交给 setMediaSource(source, startMs)，不再单独 seekTo：
            // 这样 dynamic veto 之后没有第二个 seek 能把它覆盖掉。
            player.setMediaSource(
                createMediaSource(recoveryUrl, currentPlaybackHeaders),
                recoveryPosition
            )
            player.prepare()
            player.playWhenReady = shouldPlay
            dataBinding.videoView.player = player
            AppLogger.d("PLAYER_SURFACE_RECOVERY: reprepared player after foreground frame timeout")
        }
    }

    fun onHostStopped() {
        if (!::player.isInitialized || !::dataBinding.isInitialized || hostBackgrounded) return
        hostBackgrounded = true
        persistPlaybackPosition(force = true, reason = PlaybackSaveReason.HOST_STOPPED)
        surfaceRecoveryGeneration++
        dataBinding.root.removeCallbacks(surfaceRecoveryRunnable)
        // 注意：这里是"本会话前台恢复用的位置"，不是持久化进度（那个只走 persistPlaybackPosition）。
        // 未 prepare 时 currentPosition 就是 setMediaSource(source, startMs) 请求的起始位置，
        // 恰好就是回前台后应该恢复的位置。
        val snapshot = PlayerForegroundPolicy.capture(
            positionMs = player.currentPosition,
            playWhenReady = player.playWhenReady,
            playbackState = player.playbackState
        )
        resumePositionMs = snapshot.positionMs
        resumePlayWhenReady = snapshot.shouldResumePlayback
        awaitingForegroundFrame = false
        dataBinding.videoView.player = null
        player.pause()
        AppLogger.d("PLAYER_LIFECYCLE: stopped position=$resumePositionMs play=$resumePlayWhenReady")
    }

    fun onHostStarted() {
        if (!hostBackgrounded || !::player.isInitialized || !::dataBinding.isInitialized) return
        hostBackgrounded = false
        val generation = ++surfaceRecoveryGeneration
        dataBinding.videoView.player = player
        player.seekTo(resumePositionMs.coerceAtLeast(0L))
        player.playWhenReady = resumePlayWhenReady
        awaitingForegroundFrame = true
        dataBinding.root.postDelayed({
            if (generation == surfaceRecoveryGeneration) surfaceRecoveryRunnable.run()
        }, 1_500L)
        AppLogger.d("PLAYER_LIFECYCLE: started position=$resumePositionMs play=$resumePlayWhenReady")
    }

    /** Secondary playback settings share one entry in the fullscreen header. */
    private fun showOverflowMenu() {
        val popup = PopupMenu(requireContext(), dataBinding.btnMore)
        popup.menu.apply {
            add(0, MENU_SPEED, 0, "${getString(R.string.player_speed_title)} · ${formatSpeed(player.playbackParameters.speed)}×")
            add(0, MENU_ASPECT, 1, getString(R.string.player_aspect_title))
            add(0, MENU_TRACKS, 2, getString(R.string.player_tracks))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                add(0, MENU_PIP, 3, getString(R.string.player_pip))
            }
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_SPEED -> { showSpeedPicker(); true }
                MENU_ASPECT -> { showAspectPicker(); true }
                MENU_TRACKS -> { showTracksPicker(); true }
                MENU_PIP -> { (activity as? VideoPlayerActivity)?.enterPipIfPossible(); true }
                else -> false
            }
        }
        popup.show()
    }

    private fun showAspectPicker() {
        // Keep the explanation that cropping removes the edges of the video.
        val labels = arrayOf(
            getString(R.string.player_aspect_fit),
            getString(R.string.player_aspect_fill),
            getString(R.string.player_aspect_crop)
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
