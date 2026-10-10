package com.myAllVideoBrowser.ui.component

import android.app.Application
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.databinding.ObservableField
import androidx.databinding.ViewDataBinding
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.data.local.model.LocalVideo
import com.myAllVideoBrowser.data.local.room.entity.*
import com.myAllVideoBrowser.databinding.*
import com.myAllVideoBrowser.ui.component.adapter.*
import com.myAllVideoBrowser.ui.main.home.browser.detectedVideos.VideoDetectionTabViewModel
import com.myAllVideoBrowser.ui.main.home.browser.webTab.WebTab
import com.myAllVideoBrowser.util.AppUtil
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import android.os.Looper
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Executes the production XML and adapters with local fixtures. PNGs are Android native
 * rendering evidence, not emulator screenshots or an end-to-end network/playback test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class SurfLayoutRenderTest {
    @Test fun lightChinese() = renderSuite("zh-rCN-w393dp-h852dp-port-xhdpi", "light-zh", 393, 852, 1f)
    @Test fun darkChinese() = renderSuite("zh-rCN-w393dp-h852dp-port-night-xhdpi", "dark-zh", 393, 852, 1f)
    @Test fun smallEnglish() = renderSuite("en-rUS-w360dp-h640dp-port-xhdpi", "small-en", 360, 640, 1f)
    @Test fun largeTextChinese() = renderSuite("zh-rCN-w360dp-h640dp-port-xhdpi", "large-zh", 360, 640, 1.5f)
    @Test fun largeTextEnglish() = renderSuite("en-rUS-w360dp-h640dp-port-xhdpi", "large-en", 360, 640, 1.5f)
    @Test fun landscapeEnglish() = renderSuite("en-rUS-w800dp-h360dp-land-xhdpi", "land-en", 800, 360, 1f)

    private fun renderSuite(qualifiers: String, prefix: String, width: Int, height: Int, fontScale: Float) {
        RuntimeEnvironment.setQualifiers(qualifiers)
        RuntimeEnvironment.setFontScale(fontScale)
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme)
        com.myAllVideoBrowser.util.ContextUtils.initApplicationContext(context)
        val activityController = Robolectric.buildActivity(Activity::class.java)
        activityController.get().setTheme(R.style.AppTheme)
        activityController.setup()
        val activity = activityController.get()
        val inflater = LayoutInflater.from(context)
        val host = LinearLayout(context)
        fun capture(name: String, binding: ViewDataBinding, navigationIndex: Int? = null) {
            binding.executePendingBindings()
            (binding.root.parent as? ViewGroup)?.removeView(binding.root)
            val root = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(context.getColor(R.color.colorSurface))
                addView(binding.root, LinearLayout.LayoutParams(-1, 0, 1f))
            }
            if (navigationIndex != null) {
                val shell = inflater.inflate(R.layout.activity_main, host, false)
                val nav = shell.findViewById<BottomNavigationView>(R.id.bottom_bar)
                (nav.parent as ViewGroup).removeView(nav)
                nav.selectedItemId = nav.menu.getItem(navigationIndex).itemId
                root.addView(nav, LinearLayout.LayoutParams(-1, (72 * context.resources.displayMetrics.density).toInt()))
            }
            val density = context.resources.displayMetrics.density
            val w = (width * density).toInt()
            val h = (height * density).toInt()
            activity.setContentView(root)
            repeat(2) {
                shadowOf(Looper.getMainLooper()).idle()
                root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, w, h)
            }
            if (binding is FragmentBrowserBinding) {
                binding.drawerLayout.openDrawer(androidx.core.view.GravityCompat.START, false)
                root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, w, h)
                assertTrue(binding.drawerLayout.isDrawerOpen(androidx.core.view.GravityCompat.START))
            }
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            val out = File("build/reports/surf-ui/$prefix-$name.png")
            requireNotNull(out.parentFile).mkdirs()
            out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            assertTrue("$name must have visible content", binding.root.height > 0)
        }

        val home = FragmentBrowserHomeBinding.inflate(inflater, host, false)
        home.executePendingBindings()
        home.homeTabsCountText.text = context.getString(R.string.tabs)
        home.browserHomeTabsCountBadge.text = "3"
        home.homeCommonSitesGrid.layoutManager = GridLayoutManager(context, if (fontScale > 1.3f) 3 else 4)
        home.homeCommonSitesGrid.adapter = TopPageAdapter(listOf("YouTube", "Vimeo", "Bilibili", "TED").map {
            PageInfo(name = it, link = "https://${it.lowercase()}.com")
        }, mock(TopPageAdapter.TopPagesListener::class.java))
        capture("home", home, 0)
        assertTrue(home.homeSearchButton.width >= 48 * context.resources.displayMetrics.density)

        val tasks = listOf(
            ProgressInfo(downloadId = 1, videoInfo = VideoInfo(title = "Ocean walk — a quiet afternoon", ext = "mp4"), downloadStatus = VideoTaskState.DOWNLOADING, progressTotal = 120_000_000, progressDownloaded = 48_000_000),
            ProgressInfo(downloadId = 2, videoInfo = VideoInfo(title = "Morning in the mountains", ext = "mp4"), downloadStatus = VideoTaskState.PAUSE, progressTotal = 80_000_000, progressDownloaded = 20_000_000),
            ProgressInfo(downloadId = 3, videoInfo = VideoInfo(title = "An evening by the sea", ext = "mp4"), downloadStatus = VideoTaskState.ERROR, lastError = "HTTP 403")
        )
        val progress = FragmentProgressBinding.inflate(inflater, host, false)
        progress.executePendingBindings()
        progress.downloadsSubtitle.text = context.getString(R.string.surf_download_count, 1, 3)
        progress.rvProgress.layoutManager = LinearLayoutManager(context)
        val taskListener = mock(ProgressListener::class.java)
        progress.rvProgress.adapter = ProgressAdapter(tasks, taskListener)
        capture("downloads", progress, 1)
        val firstTask = progress.rvProgress.findViewHolderForAdapterPosition(0) as ProgressAdapter.ProgressViewHolder
        firstTask.binding.primaryAction.performClick()
        verify(taskListener).onPrimaryAction(1)

        val library = FragmentVideoBinding.inflate(inflater, host, false)
        library.executePendingBindings()
        library.layoutEmpty.visibility = View.GONE
        library.librarySubtitle.text = context.getString(R.string.surf_library_count, 4)
        library.rvVideo.layoutManager = GridLayoutManager(context, if (fontScale > 1.3f) 1 else 2)
        library.rvVideo.adapter = VideoAdapter(listOf("Ocean walk", "Mountain light", "Summer memories", "Evening breeze").mapIndexed { i, name ->
            LocalVideo(i.toLong(), Uri.parse("file:///fixture-$i.mp4"), "$name.mp4").apply { size = "120 MB"; quality = "1080P"; sourceUrl = "https://example.org/watch" }
        }, mock(VideoListener::class.java))
        capture("library", library, 2)
        library.layoutEmpty.visibility = View.VISIBLE
        library.rvVideo.visibility = View.GONE
        capture("library-empty", library, 2)

        val media = VideoInfo(title = "Ocean walk — a quiet afternoon", ext = "mp4", originalUrl = "https://example.org/watch", formats = VideFormatEntityList(listOf(1080, 720, 480, 360).map { quality ->
            VideoFormatEntity(formatId = "$quality", height = quality, ext = "mp4", url = "https://example.org/$quality.mp4", fileSize = 120_000_000)
        }))
        val model = mock(VideoDetectionTabViewModel::class.java)
        `when`(model.formatsTitles).thenReturn(ObservableField(emptyMap()))
        `when`(model.selectedFormats).thenReturn(ObservableField(emptyMap()))
        `when`(model.selectedFormatUrl).thenReturn(ObservableField(""))
        val panel = FragmentDetectedVideosTabBinding.inflate(inflater, host, false)
        panel.executePendingBindings()
        panel.title.setText(R.string.detected_videos_title)
        panel.detectedSubtitle.text = "example.org"
        panel.detectedSubtitle.visibility = View.VISIBLE
        panel.detectedSecondaryActions.visibility = View.GONE
        panel.videoInfoList.layoutManager = LinearLayoutManager(context)
        val mediaListener = mock(DownloadTabListener::class.java)
        panel.videoInfoList.adapter = VideoInfoAdapter(listOf(media), model, mediaListener, mock(AppUtil::class.java))
        BottomSheetBehavior.from(panel.detectedSheet).state = BottomSheetBehavior.STATE_EXPANDED
        capture("media", panel)
        panel.videoInfoList.scrollBy(0, 1500)
        capture("media-actions", panel)
        val mediaHolder = panel.videoInfoList.findViewHolderForAdapterPosition(0) as VideoInfoAdapter.VideoInfoViewHolder
        assertTrue("Download text must fit at the current system font size",
            mediaHolder.binding.tvDownload.layout.height <= mediaHolder.binding.tvDownload.height -
                mediaHolder.binding.tvDownload.compoundPaddingTop - mediaHolder.binding.tvDownload.compoundPaddingBottom)
        mediaHolder.binding.tvDownload.performClick()
        // 选择键已按 strategy-aware 身份生成（旧实现是 formatId 优先，会把不同清晰度合并）。
        verify(mediaListener).onDownloadVideo(
            media,
            com.myAllVideoBrowser.util.VideoFormatUi.selectionKey(media, media.formats.formats.first()),
            media.title
        )
        mediaHolder.binding.qualityToggle.performClick()
        assertEquals(4, mediaHolder.binding.candidatesList.adapter!!.itemCount)
        mediaHolder.binding.qualityToggle.performClick()
        assertEquals(3, mediaHolder.binding.candidatesList.adapter!!.itemCount)

        val browser = FragmentWebTabBinding.inflate(inflater, host, false)
        browser.executePendingBindings()
        browser.floatingContainer.visibility = View.VISIBLE
        browser.fabIcon.visibility = View.VISIBLE
        browser.fabIcon.setImageResource(R.drawable.ic_download_24dp)
        browser.loadingWavy.visibility = View.GONE
        browser.videoDetectionBadge.visibility = View.VISIBLE
        browser.videoDetectionBadge.text = "99+"
        browser.floatingContainer.setTopBoundaryView(browser.appBar)
        capture("browser-controls", browser, 0)
        val density = context.resources.displayMetrics.density
        assertEquals(48 * density, browser.floatingContainer.width.toFloat(), 1f)
        assertEquals(48 * density, browser.floatingContainer.height.toFloat(), 1f)
        assertEquals(24 * density, browser.fabIcon.width.toFloat(), 1f)
        assertTrue(browser.floatingContainer.y >= browser.appBar.bottom + 8 * density)
        assertEquals(8 * density, browser.containerBrowser.width -
            browser.floatingContainer.x - browser.floatingContainer.width, 1f)
        assertTrue("99+ must fit without ellipsis at the current font scale",
            browser.videoDetectionBadge.layout.getEllipsisCount(0) == 0 &&
                browser.videoDetectionBadge.paint.measureText("99+") <=
                browser.videoDetectionBadge.width - browser.videoDetectionBadge.compoundPaddingLeft -
                browser.videoDetectionBadge.compoundPaddingRight)
        browser.floatingContainer.restorePosition(0f, 0.4f)
        capture("browser-controls-left", browser, 0)

        val player = FragmentPlayerBinding.inflate(inflater, host, false)
        player.executePendingBindings()
        player.tvTitle.text = media.title
        val playback = PreviewPlayer()
        player.videoView.player = playback
        com.myAllVideoBrowser.ui.main.player.PlayerChrome.bind(player, playback)
        player.videoView.showController()
        capture("player", player)
        assertTrue(player.btnBack.height >= 48 * density)
        assertEquals(3, player.topBar.childCount)
        val playButton = player.root.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause)
        val timeBar = player.root.findViewById<View>(androidx.media3.ui.R.id.exo_progress)
        assertTrue(playButton.isShown)
        val beforeSeek = playback.currentPosition
        player.root.findViewById<View>(androidx.media3.ui.R.id.exo_ffwd).performClick()
        assertEquals(beforeSeek + playback.seekForwardIncrement, playback.currentPosition)
        player.root.findViewById<View>(androidx.media3.ui.R.id.exo_rew).performClick()
        assertEquals(beforeSeek, playback.currentPosition)
        assertTrue(timeBar.height >= 48 * density)
        playButton.performClick()
        assertTrue(playback.playWhenReady)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(3500))
        assertFalse("Playing controls auto hide", player.videoView.isControllerFullyVisible)
        assertEquals(View.GONE, player.topBar.visibility)
        player.videoView.showController()
        playButton.performClick()
        assertFalse(playback.playWhenReady)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(3500))
        assertTrue("Paused controls remain visible", player.videoView.isControllerFullyVisible)
        assertEquals(View.GONE, player.loadingBar.visibility)
        playback.buffering(true)
        assertEquals(View.VISIBLE, player.loadingBar.visibility)
        capture("player-buffering", player)
        playback.buffering(false)
        assertEquals(View.GONE, player.loadingBar.visibility)
        player.videoView.player = null

        fun pagePreview(index: Int): Bitmap {
            val bitmap = Bitmap.createBitmap(360, if (index % 3 == 2) 300 else 560, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            canvas.drawColor(android.graphics.Color.rgb(248, 249, 251))
            paint.color = android.graphics.Color.rgb(25, 43, 59)
            canvas.drawRect(0f, 0f, 360f, 54f, paint)
            paint.color = android.graphics.Color.WHITE; paint.textSize = 20f
            canvas.drawText("SURFSAVE / PREVIEW", 20f, 34f, paint)
            paint.color = android.graphics.Color.rgb(40 + index % 4 * 20, 115, 130)
            canvas.drawRect(20f, 75f, 340f, 240f, paint)
            paint.color = android.graphics.Color.rgb(40, 55, 70); paint.textSize = 22f
            canvas.drawText("A moment to explore", 20f, 280f, paint)
            paint.color = android.graphics.Color.rgb(170, 185, 194)
            repeat(8) { row -> canvas.drawRect(20f, 310f + row * 24, if (row % 3 == 2) 250f else 340f, 317f + row * 24, paint) }
            return bitmap
        }
        val tabs = FragmentBrowserBinding.inflate(inflater, host, false)
        tabs.executePendingBindings()
        tabs.tabsToolbar.title = context.getString(R.string.tabs_with_count_title, 100)
        tabs.tabsList.layoutManager = androidx.recyclerview.widget.StaggeredGridLayoutManager(
            if (width >= 600) 3 else 2, androidx.recyclerview.widget.StaggeredGridLayoutManager.VERTICAL
        ).apply { gapStrategy = androidx.recyclerview.widget.StaggeredGridLayoutManager.GAP_HANDLING_NONE }
        tabs.tabsList.itemAnimator = null
        tabs.tabsList.addItemDecoration(TabGridSpacingDecoration())
        val previews = List(3) { pagePreview(it) }
        val tabListener = mock(WebTabsListener::class.java)
        val tabItems = List(100) { i -> WebTab("https://example.org/article/$i", "Page ${i + 1} · A quiet afternoon", pageThumbnail = previews[i % 3], id = "tab-$i") }
        val tabAdapter = WebTabsAdapter(tabItems, tabListener)
        tabs.tabsList.adapter = tabAdapter
        tabAdapter.setSelectedTabId(tabItems[0].id)
        capture("tabs-overview", tabs)
        assertEquals("Overview fills the browser width", tabs.drawerLayout.width, tabs.drawerLayoutContent.width)
        val tabHolder = tabs.tabsList.findViewHolderForAdapterPosition(0) as WebTabsAdapter.WebTabsViewHolder
        assertTrue(tabHolder.binding.closeTab.width >= 48 * density)
        assertTrue(tabHolder.binding.tabTitle.top >= tabHolder.binding.faviconTab.bottom)
        val ratio = tabHolder.binding.faviconTab.width.toFloat() / tabHolder.binding.faviconTab.height
        assertEquals("Preview uses its complete frame without blank bands", 360f / 560f, ratio, 0.01f)
        val second = tabs.tabsList.findViewHolderForAdapterPosition(1)!!
        assertEquals(12 * density, (second.itemView.left - tabHolder.itemView.right).toFloat(), 1f)
        tabHolder.binding.closeTab.performClick()
        verify(tabListener).onCloseTabClicked(tabItems[0])
        repeat(15) { tabs.tabsList.scrollBy(0, (300 * density).toInt()) }
        assertTrue("100-tab list actually scrolls and recycles", tabs.tabsList.findViewHolderForAdapterPosition(0) == null)
        capture("tabs-scrolled", tabs)
        val pagerBeforeResize = tabs.viewPager
        for (windowWidth in intArrayOf(360, 800, 360)) {
            val w = (windowWidth * density).toInt()
            val h = (height * density).toInt()
            tabs.drawerLayout.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            tabs.drawerLayout.layout(0, 0, w, h)
            assertEquals(w, tabs.drawerLayoutContent.width)
            assertEquals(if (windowWidth >= 600) 3 else 2,
                (tabs.tabsList.layoutManager as androidx.recyclerview.widget.StaggeredGridLayoutManager).spanCount)
            assertSame("Window resize retains the browser pager", pagerBeforeResize, tabs.viewPager)
        }
        tabs.tabsList.adapter = null
        activityController.pause().stop().destroy()
    }
}
