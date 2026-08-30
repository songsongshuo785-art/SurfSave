package com.myAllVideoBrowser.contentblock

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ContentBlockManagerTest {
    @Test
    fun disabledManager_staysUninitializedUntilEnabledAndRestoresReadyStatus() = runBlocking {
        val factory = FakeEngineFactory()
        val manager = manager(enabledProvider = { false }, factory = factory)
        try {
            assertEquals(ContentBlockEngineStatus.DISABLED, manager.state.value.status)
            assertTrue(factory.fullEngines.isEmpty())

            manager.onEnabledChanged(true)
            manager.initialize()
            assertTrue(factory.fullEngines.isNotEmpty())
            assertEquals(ContentBlockEngineStatus.BUNDLED, manager.state.value.status)

            manager.onEnabledChanged(false)
            assertEquals(ContentBlockEngineStatus.DISABLED, manager.state.value.status)
            manager.onEnabledChanged(true)
            assertEquals(ContentBlockEngineStatus.BUNDLED, manager.state.value.status)
        } finally {
            manager.close()
        }
    }

    @Test
    fun initializedManager_routesWebViewAndServiceWorkerToSeparateSnapshots() = runBlocking {
        val store = FakeRuleStore()
        val factory = FakeEngineFactory()
        val manager = manager(store = store, factory = factory)
        try {
            manager.initialize()
            val coordinator = ContentBlockCoordinator(manager, Unit)

            val webDecision = coordinator.evaluate(request("https://ads.example/banner.js"))
            val workerDecision = coordinator.evaluate(
                request(
                    "https://ads.example/worker.js",
                    source = ContentBlockRequestSource.SERVICE_WORKER
                )
            )

            assertTrue(webDecision is ContentBlockDecision.Block)
            assertTrue(workerDecision is ContentBlockDecision.Block)
            assertEquals("full-1", (webDecision as ContentBlockDecision.Block).engine)
            assertEquals("context-1", (workerDecision as ContentBlockDecision.Block).engine)
            assertEquals("https://page.example/watch", factory.fullEngines.single().requests.single().documentUrl)
            assertNull(factory.contextEngines.single().requests.single().documentUrl)
            assertEquals(ContentBlockEngineStatus.BUNDLED, manager.state.value.status)
        } finally {
            manager.close()
        }
    }

    @Test
    fun updateFailure_keepsLastKnownGoodSnapshotAndExposesFailure() = runBlocking {
        val store = FakeRuleStore().apply {
            updateResult = FilterUpdateResult.Failed(FilterUpdateFailure.NETWORK)
        }
        val factory = FakeEngineFactory()
        val manager = manager(store = store, factory = factory)
        try {
            manager.initialize()

            val result = manager.updateRules()
            val decision = manager.evaluate(request("https://ads.example/banner.js"))

            assertTrue(result is FilterUpdateResult.Failed)
            assertEquals(ContentBlockEngineStatus.UPDATE_FAILED, manager.state.value.status)
            assertEquals("rules-update-network", manager.state.value.lastError)
            assertEquals("full-1", (decision as ContentBlockDecision.Block).engine)
            assertFalse(factory.fullEngines.single().closed)
        } finally {
            manager.close()
        }
    }

    @Test
    fun successfulUpdate_swapsGenerationAndClosesRetiredEngines() = runBlocking {
        val store = FakeRuleStore()
        val factory = FakeEngineFactory()
        val manager = manager(store = store, factory = factory)
        try {
            manager.initialize()
            val firstFull = factory.fullEngines.single()
            val firstContext = factory.contextEngines.single()
            store.updateResult = FilterUpdateResult.Updated(
                store.ruleSet.copy(
                    cacheKey = "B".repeat(64),
                    rulesVersion = "updated",
                    updatedAtEpochMillis = 2_000L,
                    origin = ContentBlockRulesOrigin.UPDATED
                )
            )

            manager.updateRules()

            assertEquals(1, store.commitCalls)
            assertTrue(firstFull.closed)
            assertTrue(firstContext.closed)
            assertEquals(ContentBlockEngineStatus.UP_TO_DATE, manager.state.value.status)
            val decision = manager.evaluate(request("https://ads.example/banner.js"))
            assertEquals("full-2", (decision as ContentBlockDecision.Block).engine)
        } finally {
            manager.close()
        }
    }

    @Test
    fun failureFromRetiredGeneration_doesNotDiscardConcurrentReplacement() = runBlocking {
        val store = FakeRuleStore()
        val factory = FakeEngineFactory()
        val manager = manager(store = store, factory = factory)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val observed = AtomicReference<ContentBlockDecision>()
        try {
            manager.initialize()
            val firstEngine = factory.fullEngines.single().apply {
                evaluateStarted = entered
                evaluateRelease = release
                failEvaluate = true
            }
            val requestThread = Thread {
                observed.set(manager.evaluate(request("https://ads.example/banner.js")))
            }
            requestThread.start()
            assertTrue(entered.await(5, TimeUnit.SECONDS))

            store.updateResult = FilterUpdateResult.Updated(
                store.ruleSet.copy(
                    cacheKey = "E".repeat(64),
                    rulesVersion = "replacement",
                    updatedAtEpochMillis = 2_000L,
                    origin = ContentBlockRulesOrigin.UPDATED
                )
            )
            manager.updateRules()
            release.countDown()
            requestThread.join(5_000L)

            assertFalse(requestThread.isAlive)
            assertTrue(firstEngine.closed)
            assertEquals(
                "full-2",
                (observed.get() as ContentBlockDecision.Block).engine
            )
            assertFalse(factory.fullEngines.last().closed)
            assertEquals(ContentBlockEngineStatus.UP_TO_DATE, manager.state.value.status)
        } finally {
            release.countDown()
            manager.close()
        }
    }

    @Test
    fun engineBuildFailure_doesNotCommitNewRulesOrReplaceLastKnownGood() = runBlocking {
        val store = FakeRuleStore()
        val factory = FakeEngineFactory()
        val manager = manager(store = store, factory = factory)
        try {
            manager.initialize()
            val oldEngine = factory.fullEngines.single()
            store.updateResult = FilterUpdateResult.Updated(
                store.ruleSet.copy(
                    cacheKey = "D".repeat(64),
                    origin = ContentBlockRulesOrigin.UPDATED
                )
            )
            factory.failNextFull = true

            val result = manager.updateRules()

            assertEquals(FilterUpdateFailure.ENGINE, (result as FilterUpdateResult.Failed).reason)
            assertEquals(0, store.commitCalls)
            assertFalse(oldEngine.closed)
            val decision = manager.evaluate(request("https://ads.example/banner.js"))
            assertEquals("full-1", (decision as ContentBlockDecision.Block).engine)
        } finally {
            manager.close()
        }
    }

    @Test
    fun commitFailure_doesNotPublishOrCacheUncommittedGeneration() = runBlocking {
        val store = FakeRuleStore().apply { commitSucceeds = false }
        val factory = FakeEngineFactory()
        val manager = manager(store = store, factory = factory)
        try {
            manager.initialize()
            val baselineCacheWrites = store.cacheWriteCalls
            store.updateResult = FilterUpdateResult.Updated(
                store.ruleSet.copy(
                    cacheKey = "F".repeat(64),
                    rulesVersion = "not-committed",
                    origin = ContentBlockRulesOrigin.UPDATED
                )
            )

            val result = manager.updateRules()

            assertEquals(FilterUpdateFailure.STORAGE, (result as FilterUpdateResult.Failed).reason)
            assertEquals(baselineCacheWrites, store.cacheWriteCalls)
            assertTrue(factory.fullEngines.last().closed)
            val decision = manager.evaluate(request("https://ads.example/banner.js"))
            assertEquals("full-1", (decision as ContentBlockDecision.Block).engine)
        } finally {
            manager.close()
        }
    }

    @Test
    fun initializationFailure_usesExplicitFallbackInsteadOfPretendingRustIsReady() = runBlocking {
        val store = FakeRuleStore().apply { loadFailure = IllegalStateException("secret-url") }
        val fallback = FakeEngine("fallback")
        val manager = manager(store = store, fallback = fallback)
        try {
            manager.initialize()

            val decision = manager.evaluate(request("https://ads.example/banner.js"))

            assertEquals(ContentBlockEngineStatus.FALLBACK, manager.state.value.status)
            assertEquals("fallback", manager.state.value.engineVersion)
            assertTrue(manager.state.value.lastError.orEmpty().startsWith("rules-load-"))
            assertFalse(manager.state.value.lastError.orEmpty().contains("secret-url"))
            assertEquals("fallback", (decision as ContentBlockDecision.Block).engine)
        } finally {
            manager.close()
        }
    }

    @Test
    fun fallbackFailure_isVisibleInsteadOfBeingSilentlySwallowed() = runBlocking {
        val store = FakeRuleStore().apply { loadFailure = IllegalStateException("private") }
        val fallback = FakeEngine("fallback").apply { failEvaluate = true }
        val manager = manager(store = store, fallback = fallback)
        try {
            manager.initialize()

            assertSame(
                ContentBlockDecision.Allow,
                manager.evaluate(request("https://ads.example/banner.js"))
            )
            assertEquals(ContentBlockEngineStatus.ENGINE_FAILED, manager.state.value.status)
            assertEquals("fallback-engine-request-failed", manager.state.value.lastError)
        } finally {
            manager.close()
        }
    }

    @Test
    fun disabledSite_bypassesEngineWithoutAffectingOtherSites() = runBlocking {
        val policy = FakeSitePolicy().apply { disabled = true }
        val factory = FakeEngineFactory()
        val manager = manager(sitePolicy = policy, factory = factory)
        try {
            manager.initialize()

            assertSame(
                ContentBlockDecision.Allow,
                manager.evaluate(request("https://ads.example/banner.js"))
            )
            assertTrue(factory.fullEngines.single().requests.isEmpty())
        } finally {
            manager.close()
        }
    }

    private fun manager(
        store: FakeRuleStore = FakeRuleStore(),
        sitePolicy: FakeSitePolicy = FakeSitePolicy(),
        factory: FakeEngineFactory = FakeEngineFactory(),
        fallback: FakeEngine = FakeEngine("fallback"),
        enabledProvider: () -> Boolean = { true }
    ) = ContentBlockManager(
        enabledProvider = enabledProvider,
        sitePolicy = sitePolicy,
        ruleStore = store,
        engineFactory = factory,
        fallbackEngine = fallback,
        clock = { 2_000L },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    )

    private fun request(
        url: String,
        source: ContentBlockRequestSource = ContentBlockRequestSource.WEB_VIEW
    ) = ContentBlockRequest(
        url = url,
        documentUrl = "https://page.example/watch",
        method = "GET",
        resourceType = BrowserResourceType.SCRIPT,
        isMainFrame = false,
        source = source
    )

    private class FakeRuleStore : FilterRuleStore {
        val ruleSet = ContentBlockRuleSet(
            sources = listOf(
                VerifiedFilterSource(
                    manifest = FilterSourceManifest(
                        id = "easylist",
                        asset = "contentblock/easylist.txt",
                        url = "https://filters.example/easylist.txt",
                        license = "test",
                        version = "1",
                        sha256 = "A".repeat(64),
                        bytes = 1
                    ),
                    content = "[Adblock Plus 2.0]",
                    sha256 = "A".repeat(64),
                    bytes = 1
                )
            ),
            sourceManifestSha256 = "C".repeat(64),
            cacheKey = "A".repeat(64),
            rulesVersion = "bundled",
            updatedAtEpochMillis = 0L,
            origin = ContentBlockRulesOrigin.BUNDLED
        )
        var loadFailure: RuntimeException? = null
        var updateResult: FilterUpdateResult = FilterUpdateResult.NotModified(ruleSet, 2_000L)
        var commitCalls = 0
        var commitSucceeds = true
        var cacheWriteCalls = 0

        override suspend fun loadBestAvailable(): RuleSetLoadResult {
            loadFailure?.let { throw it }
            return RuleSetLoadResult(ruleSet)
        }

        override suspend fun update(current: ContentBlockRuleSet): FilterUpdateResult = updateResult

        override fun commitUpdatedRuleSet(ruleSet: ContentBlockRuleSet): Boolean {
            commitCalls++
            return commitSucceeds
        }

        override fun readSerializedEngine(
            ruleSet: ContentBlockRuleSet,
            contextFree: Boolean
        ): ByteArray? = null

        override fun writeSerializedEngine(
            ruleSet: ContentBlockRuleSet,
            contextFree: Boolean,
            bytes: ByteArray
        ): Boolean {
            cacheWriteCalls++
            return true
        }
    }

    private class FakeEngineFactory : ContentBlockEngineFactory {
        val fullEngines = mutableListOf<FakeEngine>()
        val contextEngines = mutableListOf<FakeEngine>()
        var failNextFull = false

        override fun fromLists(
            filterLists: List<String>,
            contextFree: Boolean
        ): ContentBlockEngine {
            if (!contextFree && failNextFull) {
                failNextFull = false
                error("engine build failure")
            }
            val list = if (contextFree) contextEngines else fullEngines
            return FakeEngine(
                version = "${if (contextFree) "context" else "full"}-${list.size + 1}"
            ).also(list::add)
        }

        override fun fromSerialized(serialized: ByteArray): ContentBlockEngine {
            error("No serialized cache in this fixture")
        }
    }

    private class FakeEngine(override val version: String) : ContentBlockEngine {
        val requests = mutableListOf<ContentBlockRequest>()
        var closed = false
        var failEvaluate = false
        var evaluateStarted: CountDownLatch? = null
        var evaluateRelease: CountDownLatch? = null

        override fun evaluate(request: ContentBlockRequest): ContentBlockDecision {
            check(!closed)
            requests += request
            evaluateStarted?.countDown()
            evaluateRelease?.await(5, TimeUnit.SECONDS)
            if (failEvaluate) error("engine request failure")
            return if (request.url.contains("ads.example")) {
                ContentBlockDecision.Block(version)
            } else {
                ContentBlockDecision.Allow
            }
        }

        override fun cosmeticResources(url: String) = CosmeticResources()
        override fun hiddenSelectors(
            classes: Collection<String>,
            ids: Collection<String>,
            exceptions: Collection<String>
        ) = emptyList<String>()
        override fun serialize() = version.toByteArray()
        override fun statsJson() = "{}"
        override fun close() {
            closed = true
        }
    }

    private class FakeSitePolicy : SiteBlockPolicy {
        var disabled = false
        var popupAllowed = false

        override fun isContentBlockingDisabled(pageUrl: String?): Boolean = disabled
        override fun setContentBlockingDisabled(pageUrl: String, disabled: Boolean): Boolean {
            this.disabled = disabled
            return true
        }
        override fun isPopupBlockingAllowed(pageUrl: String?): Boolean = popupAllowed
        override fun setPopupBlockingAllowed(pageUrl: String, allowed: Boolean): Boolean {
            popupAllowed = allowed
            return true
        }
    }
}
