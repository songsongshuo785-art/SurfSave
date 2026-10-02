package com.myAllVideoBrowser.ui.main.home.browser.detectedVideos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ImageProbeGateTest {
    @Test
    fun firstAcquireWinsAndTheSecondIsSkipped() {
        val gate = ImageProbeGate()

        assertTrue(gate.tryAcquire(5L, "https://cdn.example/a.jpg"))
        assertFalse(gate.tryAcquire(5L, "https://cdn.example/a.jpg"))
    }

    @Test
    fun differentIdentitiesDoNotBlockEachOther() {
        val gate = ImageProbeGate()

        assertTrue(gate.tryAcquire(5L, "https://cdn.example/a.jpg"))
        assertTrue(gate.tryAcquire(5L, "https://cdn.example/b.jpg"))
    }

    @Test
    fun sameIdentityOnDifferentPageGenerationsDoesNotBlockEachOther() {
        val gate = ImageProbeGate()

        // A probe still in flight for a stale page must never suppress the same
        // image on the current page: the old probe discards its result.
        assertTrue(gate.tryAcquire(5L, "https://cdn.example/a.jpg"))
        assertTrue(gate.tryAcquire(6L, "https://cdn.example/a.jpg"))
        assertFalse(gate.tryAcquire(5L, "https://cdn.example/a.jpg"))
        assertFalse(gate.tryAcquire(6L, "https://cdn.example/a.jpg"))
    }

    @Test
    fun nullGenerationIsItsOwnKey() {
        val gate = ImageProbeGate()

        assertTrue(gate.tryAcquire(null, "https://cdn.example/a.jpg"))
        assertTrue(gate.tryAcquire(5L, "https://cdn.example/a.jpg"))
        assertFalse(gate.tryAcquire(null, "https://cdn.example/a.jpg"))
    }

    @Test
    fun releaseAllowsAcquiringTheSameKeyAgain() {
        val gate = ImageProbeGate()

        assertTrue(gate.tryAcquire(5L, "https://cdn.example/a.jpg"))
        gate.release(5L, "https://cdn.example/a.jpg")
        assertTrue(gate.tryAcquire(5L, "https://cdn.example/a.jpg"))
    }

    @Test
    fun releaseForADifferentGenerationReleasesNothing() {
        val gate = ImageProbeGate()

        assertTrue(gate.tryAcquire(5L, "https://cdn.example/a.jpg"))
        gate.release(6L, "https://cdn.example/a.jpg")
        assertFalse(gate.tryAcquire(5L, "https://cdn.example/a.jpg"))
    }

    @Test
    fun blankIdentityAlwaysAcquires() {
        val gate = ImageProbeGate()

        assertTrue(gate.tryAcquire(5L, ""))
        assertTrue(gate.tryAcquire(5L, ""))
        assertTrue(gate.tryAcquire(null, ""))
    }

    @Test
    fun releaseOfAnUnknownKeyIsANoOp() {
        val gate = ImageProbeGate()

        gate.release(5L, "https://cdn.example/unknown.jpg")
        assertTrue(gate.tryAcquire(5L, "https://cdn.example/a.jpg"))
    }

    @Test
    fun concurrentAcquiresLetExactlyOneCallerProbe() {
        val gate = ImageProbeGate()
        val callers = 16
        val ready = CountDownLatch(callers)
        val start = CountDownLatch(1)
        val winners = AtomicInteger()
        val pool = Executors.newFixedThreadPool(callers)

        try {
            repeat(callers) {
                pool.execute {
                    ready.countDown()
                    start.await()
                    if (gate.tryAcquire(5L, "https://cdn.example/a.jpg")) {
                        winners.incrementAndGet()
                    }
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            pool.shutdown()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }

        assertEquals(1, winners.get())
    }
}
