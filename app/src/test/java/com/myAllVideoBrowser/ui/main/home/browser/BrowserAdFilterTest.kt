package com.myAllVideoBrowser.ui.main.home.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserAdFilterTest {
    @Test
    fun thirdPartyDomainRule_blocksHostAndSubdomains() {
        val filter = filterOf("block|domain|ads.example.net||third-party")

        assertTrue(
            filter.shouldBlock(
                url = "https://static.ads.example.net/banner.js",
                pageUrl = "https://news.example.org/article",
                isMainFrame = false,
                contentType = ContentType.OTHER
            )
        )
    }

    @Test
    fun thirdPartyRule_doesNotBlockSameSiteRequest() {
        val filter = filterOf("block|domain|ads.example.com||third-party")

        assertFalse(
            filter.shouldBlock(
                url = "https://ads.example.com/bootstrap.js",
                pageUrl = "https://www.example.com/watch",
                isMainFrame = false,
                contentType = ContentType.OTHER
            )
        )
    }

    @Test
    fun mainFrameAndNonHttpRequests_areAlwaysAllowed() {
        val filter = filterOf("block|domain|ads.example.net||")

        assertFalse(
            filter.shouldBlock(
                url = "https://ads.example.net/landing",
                pageUrl = "https://news.example.org/",
                isMainFrame = true,
                contentType = ContentType.OTHER
            )
        )
        assertFalse(
            filter.shouldBlock(
                url = "data:text/javascript,alert(1)",
                pageUrl = "https://news.example.org/",
                isMainFrame = false,
                contentType = ContentType.OTHER
            )
        )
    }

    @Test
    fun hardRule_blocksManifestSegmentDirectAndExtensionlessMedia() {
        val filter = filterOf("block|domain|ads.example.net||")
        val pageUrl = "https://news.example.org/watch"

        listOf(
            "https://ads.example.net/master.m3u8",
            "https://ads.example.net/segment-10.ts",
            "https://ads.example.net/chunk.m4s",
            "https://ads.example.net/video.mp4"
        ).forEach { url ->
            assertTrue(
                url,
                filter.shouldBlock(
                    url = url,
                    pageUrl = pageUrl,
                    isMainFrame = false,
                    contentType = BrowserMediaClassifier.classify(url)
                )
            )
        }
        assertTrue(
            filter.shouldBlock(
                url = "https://ads.example.net/playback?id=5",
                pageUrl = pageUrl,
                isMainFrame = false,
                contentType = ContentType.OTHER,
                requestHeaders = mapOf("accept" to "video/mp4,*/*")
            )
        )
    }

    @Test
    fun unmatchedPlaybackResources_remainAllowed() {
        val filter = filterOf("block|domain|ads.example.net||")
        val pageUrl = "https://news.example.org/watch"

        listOf(
            "https://media.example.org/master.m3u8",
            "https://media.example.org/segment.ts",
            "https://media.example.org/video.mp4"
        ).forEach { url ->
            assertFalse(
                url,
                filter.shouldBlock(
                    url = url,
                    pageUrl = pageUrl,
                    isMainFrame = false,
                    contentType = BrowserMediaClassifier.classify(url)
                )
            )
        }
    }

    @Test
    fun softPathRule_blocksOrdinaryRequestButProtectsPlayback() {
        val filter = filterOf("block|path|/adserver/||third-party,soft")
        val pageUrl = "https://news.example.org/watch"

        assertTrue(
            filter.shouldBlock(
                "https://cdn.other.net/adserver/bootstrap.js",
                pageUrl,
                false,
                ContentType.OTHER
            )
        )
        assertFalse(
            filter.shouldBlock(
                "https://cdn.other.net/adserver/preroll.mp4",
                pageUrl,
                false,
                ContentType.VIDEO
            )
        )
    }

    @Test
    fun allowRuleOverridesHardMediaBlockRule() {
        val filter = filterOf(
            "block|domain|ads.example.net||",
            "allow|domain|safe.ads.example.net|news.example.org|"
        )

        assertFalse(
            filter.shouldBlock(
                url = "https://safe.ads.example.net/player.mp4",
                pageUrl = "https://news.example.org/watch",
                isMainFrame = false,
                contentType = ContentType.VIDEO
            )
        )
        assertTrue(
            filter.shouldBlock(
                url = "https://other.ads.example.net/preroll.mp4",
                pageUrl = "https://news.example.org/watch",
                isMainFrame = false,
                contentType = ContentType.VIDEO
            )
        )
    }

    @Test
    fun scopedRule_onlyAppliesToConfiguredPageDomain() {
        val filter = filterOf("block|path|/sponsor-slot/|video.example.org|third-party")
        val requestUrl = "https://cdn.other.net/sponsor-slot/card.js"

        assertTrue(
            filter.shouldBlock(
                requestUrl,
                "https://video.example.org/watch",
                false,
                ContentType.OTHER
            )
        )
        assertFalse(
            filter.shouldBlock(
                requestUrl,
                "https://news.example.net/story",
                false,
                ContentType.OTHER
            )
        )
    }

    @Test
    fun pageAllowRule_disablesWebViewFilteringForThatSite() {
        val filter = filterOf(
            "block|domain|ads.example.net||",
            "allow|page|video.example.org||"
        )

        assertFalse(
            filter.shouldBlock(
                "https://ads.example.net/banner.js",
                "https://www.video.example.org/watch",
                false,
                ContentType.OTHER
            )
        )
    }

    @Test
    fun pathRule_doesNotInspectQueryString() {
        val filter = filterOf("block|path|/pagead/||third-party")

        assertFalse(
            filter.shouldBlock(
                "https://cdn.other.net/app.js?return=/pagead/script.js",
                "https://example.org/",
                false,
                ContentType.OTHER
            )
        )
    }

    @Test
    fun serviceWorker_onlyUsesContextFreeHardRules() {
        val filter = filterOf(
            "block|domain|global-ads.example.net||",
            "block|domain|third-party.example.net||third-party",
            "block|domain|scoped.example.net|video.example.org|",
            "block|domain|soft.example.net||soft",
            "allow|page|video.example.org||"
        )

        assertTrue(
            filter.shouldBlock(
                "https://global-ads.example.net/banner.js",
                "https://video.example.org/watch",
                false,
                ContentType.OTHER,
                requestSource = BrowserRequestSource.SERVICE_WORKER
            )
        )
        listOf(
            "https://third-party.example.net/banner.js",
            "https://scoped.example.net/banner.js",
            "https://soft.example.net/banner.js"
        ).forEach { url ->
            assertFalse(
                url,
                filter.shouldBlock(
                    url,
                    "https://video.example.org/watch",
                    false,
                    ContentType.OTHER,
                    requestSource = BrowserRequestSource.SERVICE_WORKER
                )
            )
        }
    }

    @Test
    fun serviceWorker_requestDomainBoundPathRuleIsContextFree() {
        val filter = filterOf(
            "block|path|/vast/||request-domain=ads.example.net"
        )

        assertTrue(
            filter.shouldBlock(
                "https://cdn.ads.example.net/vast/request",
                "",
                false,
                ContentType.OTHER,
                requestSource = BrowserRequestSource.SERVICE_WORKER
            )
        )
        assertFalse(
            filter.shouldBlock(
                "https://normal.example.org/vast/request",
                "",
                false,
                ContentType.OTHER,
                requestSource = BrowserRequestSource.SERVICE_WORKER
            )
        )
    }

    @Test
    fun unboundGlobalPathRule_isRejected() {
        val filter = filterOf("block|path|/vast/||")

        assertFalse(
            filter.shouldBlock(
                "https://example.net/vast/request",
                "https://news.example.org/",
                false,
                ContentType.OTHER
            )
        )
    }

    @Test
    fun malformedAndCommentLines_areIgnored() {
        val filter = filterOf(
            "# comment",
            "block|domain|missing-fields",
            "unknown|domain|ads.example.net||third-party",
            "block|domain|ads.example.net|!!!|third-party",
            "block|domain|ads.example.net||thirdparty",
            "block|path|/vast/||request-domain=!!!"
        )

        assertFalse(
            filter.shouldBlock(
                "https://ads.example.net/banner.js",
                "https://example.org/",
                false,
                ContentType.OTHER
            )
        )
    }

    private fun filterOf(vararg rules: String): BrowserAdFilter {
        return BrowserAdFilter.fromLines(rules.asSequence())
    }
}
