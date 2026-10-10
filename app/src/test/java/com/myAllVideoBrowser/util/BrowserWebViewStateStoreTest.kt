package com.myAllVideoBrowser.util

import android.app.Application
import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BrowserWebViewStateStoreTest {

    private class ManualExecutor : Executor {
        private val tasks = mutableListOf<Runnable>()

        override fun execute(command: Runnable) {
            tasks.add(command)
        }

        fun runAll() {
            while (tasks.isNotEmpty()) {
                tasks.removeAt(0).run()
            }
        }

        /** Runs queued work newest first, which models a delete arriving from another thread. */
        fun runNewestFirst() {
            while (tasks.isNotEmpty()) {
                tasks.removeAt(tasks.lastIndex).run()
            }
        }

        fun pendingCount(): Int = tasks.size
    }

    private lateinit var directory: File
    private lateinit var executor: ManualExecutor
    private lateinit var store: BrowserWebViewStateStore
    private var packageVersion: String? = "120.0.6099.230"

    @Before
    fun setUp() {
        directory = File(RuntimeEnvironment.getApplication().cacheDir, "webview_state_test")
        directory.deleteRecursively()
        executor = ManualExecutor()
        store = BrowserWebViewStateStore(
            directoryProvider = { directory },
            codec = ParcelWebViewStateCodec(),
            ioExecutor = executor,
            webViewPackageVersionProvider = { packageVersion },
            clock = { 1_700_000_000_000L },
            logger = {},
            errorLogger = {}
        )
    }

    private fun state(value: String) = Bundle().apply {
        putString("marker", value)
        putInt("entries", value.length)
    }

    private fun save(
        tabId: String,
        url: String = "https://example.com/c",
        entries: Int = 3,
        index: Int = 2,
        marker: String = "state"
    ): Boolean = store.saveAsync(tabId, state(marker), url, entries, index)

    private fun fileOf(tabId: String): File =
        File(directory, BrowserWebViewStateStore.fileNameForTab(tabId))

    @Test
    fun `save and load round trip keeps metadata and payload`() {
        assertTrue(save(TAB_A, url = "https://example.com/b", entries = 3, index = 1))
        executor.runAll()

        val loaded = store.load(TAB_A)

        assertNotNull(loaded)
        assertEquals("https://example.com/b", loaded!!.meta.savedUrl)
        assertEquals(3, loaded.meta.entryCount)
        assertEquals(1, loaded.meta.currentIndex)
        assertEquals(TAB_A, loaded.meta.tabId)
        assertEquals("state", loaded.state.getString("marker"))
    }

    @Test
    fun `load returns null when nothing was stored`() {
        assertNull(store.load(TAB_A))
    }

    @Test
    fun `non http url and invalid counts are refused without writing`() {
        assertFalse(save(TAB_A, url = "about:blank"))
        assertFalse(save(TAB_A, entries = 0, index = 0))
        assertFalse(save(TAB_A, entries = 2, index = 5))
        executor.runAll()

        assertFalse(fileOf(TAB_A).exists())
        assertEquals(0, executor.pendingCount())
    }

    @Test
    fun `oversized payload is refused and drops the previous snapshot`() {
        assertTrue(save(TAB_A, marker = "small"))
        executor.runAll()
        assertTrue(fileOf(TAB_A).exists())

        val bigStore = BrowserWebViewStateStore(
            directoryProvider = { directory },
            codec = object : WebViewStateCodec {
                override fun encode(state: Bundle): ByteArray =
                    ByteArray(BrowserWebViewStateStore.MAX_SNAPSHOT_BYTES + 1)

                override fun decode(bytes: ByteArray): Bundle? = Bundle()
            },
            ioExecutor = executor,
            logger = {},
            errorLogger = {}
        )

        assertFalse(bigStore.saveAsync(TAB_A, Bundle(), "https://example.com/c", 3, 2))
        executor.runAll()

        assertFalse("stale snapshot must not survive an oversized save", fileOf(TAB_A).exists())
        assertNull(store.load(TAB_A))
    }

    @Test
    fun `codec failure is refused without writing`() {
        val brokenStore = BrowserWebViewStateStore(
            directoryProvider = { directory },
            codec = object : WebViewStateCodec {
                override fun encode(state: Bundle): ByteArray? = null

                override fun decode(bytes: ByteArray): Bundle? = null
            },
            ioExecutor = executor,
            logger = {},
            errorLogger = {}
        )

        assertFalse(brokenStore.saveAsync(TAB_A, Bundle(), "https://example.com/c", 3, 2))
        executor.runAll()

        assertFalse(fileOf(TAB_A).exists())
    }

    @Test
    fun `corrupted file is discarded on load`() {
        assertTrue(save(TAB_A))
        executor.runAll()
        fileOf(TAB_A).writeBytes(byteArrayOf(1, 2, 3, 4, 5))

        assertNull(store.load(TAB_A))

        executor.runAll()
        assertFalse("corrupted snapshot must be deleted", fileOf(TAB_A).exists())
    }

    @Test
    fun `snapshot of another tab is discarded on load`() {
        assertTrue(save(TAB_B))
        executor.runAll()
        // Move B's snapshot into A's file name: the embedded tab id no longer matches.
        assertTrue(fileOf(TAB_B).renameTo(fileOf(TAB_A)))

        assertNull(store.load(TAB_A))

        executor.runAll()
        assertFalse(fileOf(TAB_A).exists())
    }

    @Test
    fun `unknown envelope version is discarded on load`() {
        assertTrue(save(TAB_A))
        executor.runAll()
        val bytes = fileOf(TAB_A).readBytes()
        bytes[7] = 42
        fileOf(TAB_A).writeBytes(bytes)

        assertNull(store.load(TAB_A))
    }

    @Test
    fun `oversized file on disk is discarded on load`() {
        assertTrue(save(TAB_A))
        executor.runAll()
        fileOf(TAB_A).writeBytes(ByteArray(BrowserWebViewStateStore.MAX_SNAPSHOT_BYTES + 200_000))

        assertNull(store.load(TAB_A))
    }

    @Test
    fun `a queued write cannot resurrect a deleted tab state`() {
        assertTrue(save(TAB_A, marker = "first"))
        // The write is still queued when the tab is closed.
        store.delete(TAB_A)
        executor.runAll()

        assertFalse(fileOf(TAB_A).exists())
        assertNull(store.load(TAB_A))
    }

    @Test
    fun `a stale write never lands even when it runs after the delete`() {
        assertTrue(save(TAB_A, marker = "stale"))
        store.delete(TAB_A)
        assertFalse(fileOf(TAB_A).exists())

        // Newest first: the delete executes before the write it invalidated.
        executor.runNewestFirst()

        assertFalse("a write captured before the delete must never recreate the file", fileOf(TAB_A).exists())
        assertNull(store.load(TAB_A))
    }

    @Test
    fun `a write queued before a newer save cannot overwrite it`() {
        assertTrue(save(TAB_A, marker = "older"))
        // A newer capture replaces the tab state before the older task runs.
        val queuedOlder = executor.pendingCount() == 1
        assertTrue(queuedOlder)
        assertTrue(save(TAB_A, marker = "newer"))
        executor.runAll()

        val loaded = store.load(TAB_A)
        assertNotNull(loaded)
        assertEquals("newer", loaded!!.state.getString("marker"))
    }

    @Test
    fun `state can be saved again after a delete with a new generation`() {
        assertTrue(save(TAB_A, marker = "before"))
        executor.runAll()
        store.delete(TAB_A)
        executor.runAll()
        assertFalse(fileOf(TAB_A).exists())

        assertTrue(save(TAB_A, marker = "after"))
        executor.runAll()

        val loaded = store.load(TAB_A)
        assertNotNull(loaded)
        assertEquals("after", loaded!!.state.getString("marker"))
    }

    @Test
    fun `generation advances on every delete`() {
        val before = store.generationOf(TAB_A)
        store.delete(TAB_A)
        store.delete(TAB_A)

        assertEquals(before + 2, store.generationOf(TAB_A))
    }

    @Test
    fun `trim removes snapshots of tabs that are gone`() {
        assertTrue(save(TAB_A))
        assertTrue(save(TAB_B))
        assertTrue(save(TAB_C))
        executor.runAll()

        store.trimToTabs(setOf(TAB_A, TAB_C))
        executor.runAll()

        assertTrue(fileOf(TAB_A).exists())
        assertTrue(fileOf(TAB_C).exists())
        assertFalse(fileOf(TAB_B).exists())
    }

    @Test
    fun `trim keeps atomic file leftovers of live tabs`() {
        assertTrue(save(TAB_A))
        executor.runAll()
        val backup = File(directory, BrowserWebViewStateStore.fileNameForTab(TAB_A) + ".bak")
        backup.writeBytes(byteArrayOf(1))

        store.trimToTabs(setOf(TAB_A))
        executor.runAll()

        assertTrue(backup.exists())
        assertTrue(fileOf(TAB_A).exists())
    }

    @Test
    fun `clearAll removes every snapshot`() {
        assertTrue(save(TAB_A))
        assertTrue(save(TAB_B))
        executor.runAll()

        store.clearAll()
        executor.runAll()

        assertFalse(fileOf(TAB_A).exists())
        assertFalse(fileOf(TAB_B).exists())
    }

    @Test
    fun `file names are filesystem safe`() {
        val name = BrowserWebViewStateStore.fileNameForTab("../../etc/passwd\u0000x")

        assertFalse(name.contains("/"))
        assertFalse(name.contains("..") && name.contains("/"))
        assertTrue(name.startsWith(".."))
        assertTrue(name.endsWith(".sws"))
        assertEquals(96 + 4, BrowserWebViewStateStore.fileNameForTab("a".repeat(400)).length)
    }

    companion object {
        private const val TAB_A = "aaaaaaaa-1111-2222-3333-444444444444"
        private const val TAB_B = "bbbbbbbb-1111-2222-3333-444444444444"
        private const val TAB_C = "cccccccc-1111-2222-3333-444444444444"
    }
}
