package com.myAllVideoBrowser.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class SynchronizedLruCacheTest {

    @Test
    fun evictsOldestEntryWhenFull() {
        val cache = SynchronizedLruCache<String, String>(3)

        cache.put("a", "1")
        cache.put("b", "2")
        cache.put("c", "3")
        cache.put("d", "4")

        assertEquals(3, cache.size())
        assertNull(cache.get("a"))
        assertEquals("2", cache.get("b"))
        assertEquals("4", cache.get("d"))
    }

    @Test
    fun accessPromotesEntry() {
        val cache = SynchronizedLruCache<String, String>(3)

        cache.put("a", "1")
        cache.put("b", "2")
        cache.put("c", "3")

        cache.get("a")

        cache.put("d", "4")

        assertEquals(3, cache.size())
        assertNotNull(cache.get("a"))
        assertNull(cache.get("b"))
    }

    @Test
    fun concurrentReadWriteDoesNotCorrupt() {
        val cache = SynchronizedLruCache<Int, Int>(100)
        val threads = 8
        val opsPerThread = 500
        val executor = Executors.newFixedThreadPool(threads)
        val latch = CountDownLatch(threads)
        val errors = AtomicInteger(0)

        repeat(threads) { threadId ->
            executor.submit {
                try {
                    repeat(opsPerThread) { i ->
                        val key = threadId * opsPerThread + i
                        cache.put(key, key)
                        cache.get(key)
                    }
                } catch (_: Exception) {
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await()
        executor.shutdown()

        assertEquals(0, errors.get())
        assert(cache.size() <= 100) { "Cache size ${cache.size()} exceeds max 100" }
    }
}
