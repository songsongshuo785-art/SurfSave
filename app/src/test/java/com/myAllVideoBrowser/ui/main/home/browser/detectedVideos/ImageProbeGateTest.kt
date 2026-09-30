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

        assertTrue(gate.tryAcquire("https://cdn.example/a.jpg"))
        assertFalse(gate.tryAcquire("https://cdn.example/a.jpg"))
    }

    @Test
    fun differentIdentitiesDoNotBlockEachOther() {
        val gate = ImageProbeGate()

        assertTrue(gate.tryAcquire("https://cdn.example/a.jpg"))
        assertTrue(gate.tryAcquire("https://cdn.example/b.jpg"))
    }

    @Test
    fun releaseAllowsAcquiringTheSameIdentityAgain() {
        val gate = ImageProbeGate()

        assertTrue(gate.tryAcquire("https://cdn.example/a.jpg"))
        gate.release("https://cdn.example/a.jpg")
        assertTrue(gate.tryAcquire("https://cdn.example/a.jpg"))
    }

    @Test
    fun blankIdentityAlwaysAcquires() {
        val gate = ImageProbeGate()

        assertTrue(gate.tryAcquire(""))
        assertTrue(gate.tryAcquire(""))
    }

    @Test
    fun releaseOfAnUnknownIdentityIsANoOp() {
        val gate = ImageProbeGate()

        gate.release("https://cdn.example/unknown.jpg")
        assertTrue(gate.tryAcquire("https://cdn.example/a.jpg"))
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
                    if (gate.tryAcquire("https://cdn.example/a.jpg")) {
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
