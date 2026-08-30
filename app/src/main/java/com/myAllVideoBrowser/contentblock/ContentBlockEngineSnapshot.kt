package com.myAllVideoBrowser.contentblock

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Immutable pair of engines installed as one generation.
 *
 * The owner reference prevents native handles from being closed while a request is in flight.
 * [retire] drops that owner only after the manager has atomically published a replacement.
 */
internal class ContentBlockEngineSnapshot(
    val full: ContentBlockEngine,
    val contextFree: ContentBlockEngine,
    val cacheKey: String,
    val rulesVersion: String,
    val updatedAtEpochMillis: Long,
    val origin: ContentBlockRulesOrigin
) {
    private val references = AtomicInteger(1)
    private val retired = AtomicBoolean(false)

    fun acquire(): Lease? {
        while (true) {
            if (retired.get()) return null
            val current = references.get()
            if (current <= 0) return null
            if (!references.compareAndSet(current, current + 1)) continue
            if (retired.get()) {
                releaseReference()
                return null
            }
            return Lease(this)
        }
    }

    fun retire() {
        if (retired.compareAndSet(false, true)) releaseReference()
    }

    private fun releaseReference() {
        val remaining = references.decrementAndGet()
        check(remaining >= 0) { "Content-block snapshot reference underflow" }
        if (remaining == 0) {
            runCatching { full.close() }
            if (contextFree !== full) runCatching { contextFree.close() }
        }
    }

    class Lease internal constructor(
        private val snapshot: ContentBlockEngineSnapshot
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        val full: ContentBlockEngine get() = snapshot.full
        val contextFree: ContentBlockEngine get() = snapshot.contextFree

        override fun close() {
            if (closed.compareAndSet(false, true)) snapshot.releaseReference()
        }
    }
}
