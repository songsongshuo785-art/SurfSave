package com.myAllVideoBrowser.contentblock.nativebridge

import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.myAllVideoBrowser.contentblock.BrowserResourceType
import com.myAllVideoBrowser.contentblock.ContentBlockDecision
import com.myAllVideoBrowser.contentblock.ContentBlockRequest
import com.myAllVideoBrowser.contentblock.ContentBlockRequestSource
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdblockRustNativeInstrumentedTest {
    @Test
    fun bundledSnapshotLoadsMatchesSerializesAndReleases() {
        val engine = loadBundledEngine(BUNDLED_FULL_ENGINE_ASSET)
        try {
            assertTrue(engine.version.contains("adblock-rust"))
            assertTrue(engine.evaluate(adRequest()) is ContentBlockDecision.Block)
            assertTrue(engine.evaluate(normalMediaRequest()) is ContentBlockDecision.Allow)
            assertTrue(engine.serialize().isNotEmpty())
        } finally {
            engine.close()
            engine.close()
        }
    }

    @Test
    fun fullAndContextFreeNativeHeapIncreaseStaysWithinFiftyMegabytes() {
        val beforeBytes = Debug.getNativeHeapAllocatedSize()
        val fullEngine = loadBundledEngine(BUNDLED_FULL_ENGINE_ASSET)
        val contextFreeEngine = loadBundledEngine(BUNDLED_CONTEXT_FREE_ENGINE_ASSET)
        try {
            val increaseBytes = (Debug.getNativeHeapAllocatedSize() - beforeBytes).coerceAtLeast(0)
            assertTrue(
                "Native heap increased by ${increaseBytes / (1024.0 * 1024.0)} MiB",
                increaseBytes <= NATIVE_HEAP_LIMIT_BYTES
            )
        } finally {
            fullEngine.close()
            contextFreeEngine.close()
        }
    }

    @Test
    fun nativeCompilationDropsDocumentRulesFromContextFreeSnapshot() {
        val rules = listOf(
            "[Adblock Plus 2.0]\n" +
                "||global-ads.example^\n" +
                "||page-scoped.example^\$domain=video.example\n"
        )
        val fullEngine = AdblockRustEngine.fromLists(rules, contextFree = false)
        val contextFreeEngine = AdblockRustEngine.fromLists(rules, contextFree = true)
        try {
            val pageScoped = ContentBlockRequest(
                url = "https://page-scoped.example/ad.js",
                documentUrl = "https://video.example/watch",
                method = "GET",
                resourceType = BrowserResourceType.SCRIPT,
                isMainFrame = false,
                source = ContentBlockRequestSource.WEB_VIEW
            )
            assertTrue(fullEngine.evaluate(pageScoped) is ContentBlockDecision.Block)
            assertTrue(
                contextFreeEngine.evaluate(
                    pageScoped.copy(
                        documentUrl = null,
                        source = ContentBlockRequestSource.SERVICE_WORKER
                    )
                ) is ContentBlockDecision.Allow
            )
            assertTrue(contextFreeEngine.evaluate(adRequest()) is ContentBlockDecision.Block)
        } finally {
            fullEngine.close()
            contextFreeEngine.close()
        }
    }

    @Test
    fun jniNetworkHotPathP95StaysWithinOneMillisecond() {
        val engine = loadBundledEngine(BUNDLED_FULL_ENGINE_ASSET)
        try {
            repeat(WARMUP_CALLS) { engine.evaluate(adRequest()) }
            val samples = LongArray(MEASURED_CALLS)
            repeat(MEASURED_CALLS) { index ->
                val startedAt = System.nanoTime()
                engine.evaluate(if (index % 2 == 0) adRequest() else normalMediaRequest())
                samples[index] = System.nanoTime() - startedAt
            }
            samples.sort()
            val p95Nanos = samples[(samples.size * 95 / 100).coerceAtMost(samples.lastIndex)]

            assertTrue(
                "JNI content-block P95 was ${p95Nanos / 1_000_000.0} ms",
                p95Nanos <= P95_LIMIT_NANOS
            )
        } finally {
            engine.close()
        }
    }

    private fun loadBundledEngine(asset: String): AdblockRustEngine {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val serialized = context.assets.open(asset).use { it.readBytes() }
        return AdblockRustEngine.fromSerialized(serialized)
    }

    private fun adRequest() = ContentBlockRequest(
        url = "https://trafficjunky.com/preroll/video.m3u8",
        documentUrl = "https://video.example/watch",
        method = "GET",
        resourceType = BrowserResourceType.MEDIA,
        isMainFrame = false,
        source = ContentBlockRequestSource.WEB_VIEW
    )

    private fun normalMediaRequest() = ContentBlockRequest(
        url = "https://cdn.example/video/master.m3u8",
        documentUrl = "https://video.example/watch",
        method = "GET",
        resourceType = BrowserResourceType.MEDIA,
        isMainFrame = false,
        source = ContentBlockRequestSource.WEB_VIEW
    )

    companion object {
        private const val BUNDLED_FULL_ENGINE_ASSET = "contentblock/engine-full.dat"
        private const val BUNDLED_CONTEXT_FREE_ENGINE_ASSET =
            "contentblock/engine-context-free.dat"
        private const val WARMUP_CALLS = 500
        private const val MEASURED_CALLS = 5_000
        private const val P95_LIMIT_NANOS = 1_000_000L
        private const val NATIVE_HEAP_LIMIT_BYTES = 50L * 1024L * 1024L
    }
}
