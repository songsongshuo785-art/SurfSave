package com.myAllVideoBrowser.util

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import com.myAllVideoBrowser.ui.main.home.browser.webTab.WebTab
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BrowserThumbnailPersistenceTest {
    private class QueuedIo : CoroutineDispatcher() {
        val tasks = java.util.ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.addLast(block) }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }
    private val io = QueuedIo()
    private val scope = CoroutineScope(Dispatchers.Unconfined + Job())
    @Before fun setup() { ContextUtils.initApplicationContext(RuntimeEnvironment.getApplication()); BrowserThumbnailStore.clearAll() }
    @After fun cleanup() { BrowserThumbnailStore.clearAll() }
    private fun tab() = WebTab("https://example.org/page", "Page", pageThumbnail = Bitmap.createBitmap(240, 320, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }, id = "tab")

    @Test fun closedTabIsNotResurrectedAndOrphanFileIsRemoved() {
        var latest: WebTab? = tab()
        var writes = 0
        BrowserThumbnailPersistence.persist(scope, latest!!, { latest }, { writes++; latest = it }, io)
        latest = null
        io.drain()
        assertNull(latest)
        assertEquals(0, writes)
        assertEquals(0, BrowserThumbnailStore.directory().listFiles()!!.size)
    }
    @Test fun olderCaptureCannotReplaceNewImageEvenWhenItFinishesLast() {
        val first = tab()
        val second = tab()
        var latest: WebTab? = first
        BrowserThumbnailPersistence.persist(scope, first, { latest }, { latest = it }, io)
        latest = second
        BrowserThumbnailPersistence.persist(scope, second, { latest }, { latest = it }, io)
        io.tasks.removeLast().run()
        val newPath = latest!!.getPageThumbnailPath()
        assertNotNull(newPath)
        io.drain()
        assertEquals(newPath, latest!!.getPageThumbnailPath())
        assertNull("Stored tabs release their large in-memory captures", latest!!.getPageThumbnail())
        assertEquals(1, BrowserThumbnailStore.directory().listFiles()!!.size)
    }
    @Test fun navigationInvalidatesCaptureEvenIfTabIdMatches() {
        val before = tab()
        var latest: WebTab? = before
        BrowserThumbnailPersistence.persist(scope, before, { latest }, { latest = it }, io)
        latest = before.copyWith(url = "https://example.org/next")
        io.drain()
        assertEquals("https://example.org/next", latest!!.getUrl())
        assertNull(latest!!.getPageThumbnailPath())
        assertEquals(0, BrowserThumbnailStore.directory().listFiles()!!.size)
    }
}
