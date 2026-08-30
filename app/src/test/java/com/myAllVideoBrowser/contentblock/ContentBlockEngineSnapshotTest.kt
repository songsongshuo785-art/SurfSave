package com.myAllVideoBrowser.contentblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentBlockEngineSnapshotTest {
    @Test
    fun retire_waitsForActiveLeaseBeforeClosingNativeEngines() {
        val full = FakeEngine("full")
        val serviceWorker = FakeEngine("sw")
        val snapshot = ContentBlockEngineSnapshot(
            full = full,
            contextFree = serviceWorker,
            cacheKey = "key",
            rulesVersion = "rules",
            updatedAtEpochMillis = 1L,
            origin = ContentBlockRulesOrigin.UPDATED
        )
        val lease = requireNotNull(snapshot.acquire())

        snapshot.retire()

        assertFalse(full.closed)
        assertFalse(serviceWorker.closed)
        assertNull(snapshot.acquire())

        lease.close()

        assertTrue(full.closed)
        assertTrue(serviceWorker.closed)
        assertEquals(1, full.closeCalls)
        assertEquals(1, serviceWorker.closeCalls)
    }

    private class FakeEngine(override val version: String) : ContentBlockEngine {
        var closed = false
        var closeCalls = 0

        override fun evaluate(request: ContentBlockRequest) = ContentBlockDecision.Allow
        override fun cosmeticResources(url: String) = CosmeticResources()
        override fun hiddenSelectors(
            classes: Collection<String>,
            ids: Collection<String>,
            exceptions: Collection<String>
        ) = emptyList<String>()
        override fun serialize() = byteArrayOf(1)
        override fun statsJson() = "{}"
        override fun close() {
            closeCalls++
            closed = true
        }
    }
}
