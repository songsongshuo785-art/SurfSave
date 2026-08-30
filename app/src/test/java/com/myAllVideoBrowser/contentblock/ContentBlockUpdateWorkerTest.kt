package com.myAllVideoBrowser.contentblock

import org.junit.Assert.assertEquals
import org.junit.Test

class ContentBlockUpdateWorkerTest {
    @Test
    fun updatedAndNotModifiedAreSuccessful() {
        val ruleSet = testRuleSet()

        assertEquals(
            ContentBlockUpdateOutcome.SUCCESS,
            contentBlockUpdateOutcome(FilterUpdateResult.Updated(ruleSet))
        )
        assertEquals(
            ContentBlockUpdateOutcome.SUCCESS,
            contentBlockUpdateOutcome(FilterUpdateResult.NotModified(ruleSet, 1_000L))
        )
    }

    @Test
    fun transientFailuresRetry() {
        assertEquals(
            ContentBlockUpdateOutcome.RETRY,
            contentBlockUpdateOutcome(FilterUpdateResult.Failed(FilterUpdateFailure.NETWORK))
        )
        assertEquals(
            ContentBlockUpdateOutcome.RETRY,
            contentBlockUpdateOutcome(FilterUpdateResult.Failed(FilterUpdateFailure.HTTP))
        )
    }

    @Test
    fun invalidOrLocalFailuresDoNotLoopForever() {
        val permanentFailures = FilterUpdateFailure.entries - setOf(
            FilterUpdateFailure.NETWORK,
            FilterUpdateFailure.HTTP
        )

        permanentFailures.forEach { failure ->
            assertEquals(
                failure.name,
                ContentBlockUpdateOutcome.FAILURE,
                contentBlockUpdateOutcome(FilterUpdateResult.Failed(failure))
            )
        }
    }

    private fun testRuleSet(): ContentBlockRuleSet {
        return ContentBlockRuleSet(
            sources = emptyList(),
            sourceManifestSha256 = "B".repeat(64),
            cacheKey = "A".repeat(64),
            rulesVersion = "test",
            updatedAtEpochMillis = 1_000L,
            origin = ContentBlockRulesOrigin.UPDATED
        )
    }
}
