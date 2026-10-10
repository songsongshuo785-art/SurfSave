package com.myAllVideoBrowser.util.site_adapters

import org.junit.Assert.assertEquals
import org.junit.Test

class SiteAdapterAttachmentPolicyTest {

    @Test
    fun overlappingUrlsAttachEvenWhenAnotherCandidateIsPreferred() {
        val decision = SiteAdapterAttachmentPolicy.decide(
            candidates = listOf(
                SiteAdapterCandidate("ad", setOf("https://ads.example/pre.mp4")),
                SiteAdapterCandidate("main", setOf("https://cdn.example/1080.mp4"))
            ),
            adapterUrls = setOf("https://cdn.example/1080.mp4", "https://cdn.example/720.mp4"),
            preferredVideoId = "ad"
        )

        assertEquals(SiteAdapterAttachment.Attach("main", overlap = true), decision)
    }

    @Test
    fun preferredCandidateWinsAmongSeveralOverlappingCandidates() {
        val decision = SiteAdapterAttachmentPolicy.decide(
            candidates = listOf(
                SiteAdapterCandidate("first", setOf("https://cdn.example/1080.mp4")),
                SiteAdapterCandidate("second", setOf("https://cdn.example/720.mp4"))
            ),
            adapterUrls = setOf("https://cdn.example/1080.mp4", "https://cdn.example/720.mp4"),
            preferredVideoId = "second"
        )

        assertEquals(SiteAdapterAttachment.Attach("second", overlap = true), decision)
    }

    @Test
    fun singleCandidateWithoutOverlapIsStillAttached() {
        val decision = SiteAdapterAttachmentPolicy.decide(
            candidates = listOf(
                SiteAdapterCandidate("only", setOf("https://cdn.example/play?token=1"))
            ),
            adapterUrls = setOf("https://cdn.example/1080.mp4"),
            preferredVideoId = "only"
        )

        assertEquals(SiteAdapterAttachment.Attach("only", overlap = false), decision)
    }

    @Test
    fun severalCandidatesWithoutOverlapAreNeverGuessed() {
        val decision = SiteAdapterAttachmentPolicy.decide(
            candidates = listOf(
                SiteAdapterCandidate("ad", setOf("https://ads.example/pre.mp4")),
                SiteAdapterCandidate("main", setOf("https://cdn.example/play?token=1"))
            ),
            adapterUrls = setOf("https://cdn.example/1080.mp4"),
            preferredVideoId = "main"
        )

        assertEquals(SiteAdapterAttachment.Ambiguous, decision)
    }

    @Test
    fun noCandidateYetMeansNoAttachment() {
        val decision = SiteAdapterAttachmentPolicy.decide(
            candidates = emptyList(),
            adapterUrls = setOf("https://cdn.example/1080.mp4"),
            preferredVideoId = null
        )

        assertEquals(SiteAdapterAttachment.NoCandidate, decision)
    }

    @Test
    fun emptyAdapterUrlsCannotMatchAnything() {
        val decision = SiteAdapterAttachmentPolicy.decide(
            candidates = listOf(
                SiteAdapterCandidate("a", setOf("https://cdn.example/a.mp4")),
                SiteAdapterCandidate("b", setOf("https://cdn.example/b.mp4"))
            ),
            adapterUrls = emptySet(),
            preferredVideoId = "a"
        )

        assertEquals(SiteAdapterAttachment.Ambiguous, decision)
    }
}
