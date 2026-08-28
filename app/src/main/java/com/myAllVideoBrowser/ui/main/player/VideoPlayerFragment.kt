package com.myAllVideoBrowser.ui.main.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.GestureDetector
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
import androidx.media3.ui.PlayerView.SHOW_BUFFERING_ALWAYS
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.databinding.FragmentPlayerBinding
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
        const val VIDEO_DETECTED_BY_SUPER_X = "video_detected_by_super_x"
        const val VIDEO_EXTRACTED_AT = "video_extracted_at"
        const val SOURCE_BROWSER = "browser"
        const val SOURCE_VIDEO_LIBRARY = "video_library"
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

    @Inject
    lateinit var videoRepository: VideoRepository

    private lateinit var player: ExoPlayer
    private lateinit var trackSelector: DefaultTrackSelector

    // 水平滑动 seek（快进/快退）状态：滑动期间暂停播放以渲染目标帧预览，松手恢复
    private var seeking = false
    private var seekStartPos = 0L
    private var wasPlayingBeforeSeek = false
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
    private var playbackSource = ""
    private var playbackDetectedBySuperX = false
    private var playbackExtractedAt = 0L
    private var currentPlaybackHeaders: Map<String, String> = emptyMap()
    private var refreshAttempted = false
    private var refreshInProgress = false
    private var playbackErrorDialogShown = false

    private val surfaceRecoveryListener = object : Player.Listener {
        override fun onRenderedFirstFrame() {
            awaitingForegroundFrame = false
        }
    }

    private val gestureDetector by lazy {
        GestureDetector(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                // 双击左半屏快退、右半屏快进（幅度同 seekBack/Forward 的 10s）
                val width = dataBinding.videoView.width
                if (width > 0 && e.x < width / 2f) player.seekBack() else player.seekForward()
                return true
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
                val controllerH = (120 * resources.displayMetrics.density).toInt()
                if (view.isControllerFullyVisible && e2.y > view.height - controllerH) return false
                // 右滑(totalDx>0)快进、左滑快退；用总位移判断主方向（避免增量 distanceX 抖动）
                val totalDx = e2.x - e1.x
                val totalDy = e2.y - e1.y
                if (Math.abs(totalDx) < Math.abs(totalDy) * 1.5f) return false
                val width = view.width
                val duration = player.duration.coerceAtLeast(0L)
                if (width <= 0 || duration <= 0L) return false
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
        })
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
        dataBinding.videoView.useController = !inPip
        if (inPip) {
            registerPipStateListener()
        } else {
            unregisterPipStateListener()
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
        playbackSource = arguments?.getString(VIDEO_SOURCE).orEmpty()
        playbackDetectedBySuperX = arguments?.getBoolean(VIDEO_DETECTED_BY_SUPER_X) == true
        playbackExtractedAt = arguments?.getLong(VIDEO_EXTRACTED_AT) ?: 0L

        val iUrl = arguments?.getString(VIDEO_URL)?.toUri()

        if (iUrl != null) {
            videoPlayerViewModel.videoUrl.set(iUrl)
        }

        val url = videoPlayerViewModel.videoUrl.get() ?: Uri.EMPTY
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

            // 双击/滑动 seek 由 gestureDetector 处理；返回 false 不消费触摸，让 PlayerView controller 正常显示/隐藏。
            // 松手（UP/CANCEL）时若处于滑动 seek，恢复播放状态。
            currentBinding.videoView.setOnTouchListener { _, e ->
                gestureDetector.onTouchEvent(e)
                if (e.action == MotionEvent.ACTION_UP || e.action == MotionEvent.ACTION_CANCEL) {
                    if (seeking) {
                        seeking = false
                        player.playWhenReady = wasPlayingBeforeSeek
                        hideSeekPreview()
                    }
                }
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

            player.setMediaSource(createMediaSource(url, headers))
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

    private fun handleClose() {
        videoPlayerViewModel.stop()
        (activity as? VideoPlayerActivity)?.finishPlayer()
    }

    private val surfaceRecoveryRunnable = Runnable {
        if (!hostBackgrounded && awaitingForegroundFrame && ::player.isInitialized &&
            ::dataBinding.isInitialized
        ) {
            awaitingForegroundFrame = false
            val position = resumePositionMs
            val shouldPlay = resumePlayWhenReady
            dataBinding.videoView.player = null
            player.stop()
            player.clearMediaItems()
            val recoveryUrl = videoPlayerViewModel.videoUrl.get() ?: Uri.EMPTY
            player.setMediaSource(createMediaSource(recoveryUrl, currentPlaybackHeaders))
            player.prepare()
            player.seekTo(position.coerceAtLeast(0L))
            player.playWhenReady = shouldPlay
            dataBinding.videoView.player = player
            AppLogger.d("PLAYER_SURFACE_RECOVERY: reprepared player after foreground frame timeout")
        }
    }

    fun onHostStopped() {
        if (!::player.isInitialized || !::dataBinding.isInitialized || hostBackgrounded) return
        hostBackgrounded = true
        surfaceRecoveryGeneration++
        dataBinding.root.removeCallbacks(surfaceRecoveryRunnable)
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
