package com.myAllVideoBrowser.contentblock

import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.SharedPrefHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

internal interface ContentBlockRuntime {
    fun evaluate(request: ContentBlockRequest): ContentBlockDecision

    fun isEnabled(): Boolean

    fun isSiteDisabled(pageUrl: String?): Boolean

    fun isPopupAllowed(pageUrl: String?): Boolean

    fun recordPolicyBlock(reason: String): ContentBlockDecision.Block
}

@Singleton
class ContentBlockManager internal constructor(
    enabledProvider: () -> Boolean,
    private val sitePolicy: SiteBlockPolicy,
    private val ruleStore: FilterRuleStore,
    private val engineFactory: ContentBlockEngineFactory,
    private val fallbackEngine: ContentBlockEngine,
    private val clock: () -> Long,
    private val scope: CoroutineScope
) : ContentBlockRuntime, AutoCloseable {
    @Inject
    constructor(
        sharedPrefHelper: SharedPrefHelper,
        sitePolicyStore: SiteBlockPolicyStore,
        ruleStore: FilterSubscriptionRepository,
        engineFactory: NativeContentBlockEngineFactory,
        fallbackEngine: SurfSaveRuleEngine
    ) : this(
        enabledProvider = sharedPrefHelper::isAdBlockingEnabled,
        sitePolicy = sitePolicyStore,
        ruleStore = ruleStore,
        engineFactory = engineFactory,
        fallbackEngine = fallbackEngine,
        clock = System::currentTimeMillis,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    )

    private val snapshot = AtomicReference<ContentBlockEngineSnapshot?>()
    private val currentRuleSet = AtomicReference<ContentBlockRuleSet?>()
    private val blockedRequests = AtomicLong(0L)
    private val runtimeEnabled = AtomicBoolean(enabledProvider())
    private val initializationScheduled = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val lifecycleMutex = Mutex()
    private val mutableState = MutableStateFlow(
        ContentBlockState(
            status = if (runtimeEnabled.get()) {
                ContentBlockEngineStatus.INITIALIZING
            } else {
                ContentBlockEngineStatus.DISABLED
            }
        )
    )

    val state: StateFlow<ContentBlockState> = mutableState.asStateFlow()

    fun initializeAsync(refreshIfStale: Boolean = true) {
        if (closed.get() || snapshot.get() != null ||
            !initializationScheduled.compareAndSet(false, true)
        ) {
            return
        }
        scope.launch {
            try {
                initialize()
                if (refreshIfStale && shouldRefresh(currentRuleSet.get())) updateRules()
            } finally {
                initializationScheduled.set(false)
            }
        }
    }

    fun onEnabledChanged(enabled: Boolean) {
        if (closed.get()) return
        runtimeEnabled.set(enabled)
        if (!enabled) {
            mutableState.value = mutableState.value.copy(
                status = ContentBlockEngineStatus.DISABLED,
                isUpdating = false
            )
            return
        }
        val rules = currentRuleSet.get()
        if (snapshot.get() != null && rules != null) {
            publishReadyState(rules, statusFor(rules, clock()), mutableState.value.lastError)
        } else {
            mutableState.value = mutableState.value.copy(
                status = ContentBlockEngineStatus.INITIALIZING,
                isUpdating = false
            )
            initializeAsync()
        }
    }

    suspend fun initialize() {
        if (closed.get()) return
        lifecycleMutex.withLock {
            if (snapshot.get() != null) return@withLock
            initializeLocked()
        }
    }

    suspend fun updateRules(): FilterUpdateResult {
        if (closed.get()) return FilterUpdateResult.Failed(FilterUpdateFailure.STORAGE)
        return lifecycleMutex.withLock {
            var current = currentRuleSet.get()
            if (current == null || snapshot.get() == null) {
                initializeLocked()
                current = currentRuleSet.get()
            }
            val available = current
                ?: return@withLock FilterUpdateResult.Failed(FilterUpdateFailure.MANIFEST)
            mutableState.value = mutableState.value.copy(
                status = ContentBlockEngineStatus.UPDATING,
                isUpdating = true,
                lastError = null
            )
            when (val result = ruleStore.update(available)) {
                is FilterUpdateResult.Updated -> {
                    val built = runCatching {
                        buildSnapshot(result.ruleSet, persistSerializedCache = false)
                    }.getOrNull()
                    if (built == null) {
                        publishUpdateFailure("engine-build-failed")
                        FilterUpdateResult.Failed(FilterUpdateFailure.ENGINE)
                    } else if (!ruleStore.commitUpdatedRuleSet(result.ruleSet)) {
                        built.retire()
                        publishUpdateFailure("rules-commit-failed")
                        FilterUpdateResult.Failed(FilterUpdateFailure.STORAGE)
                    } else {
                        persistSnapshotCaches(result.ruleSet, built)
                        installSnapshot(built, result.ruleSet)
                        publishReadyState(result.ruleSet, ContentBlockEngineStatus.UP_TO_DATE)
                        result
                    }
                }
                is FilterUpdateResult.NotModified -> {
                    if (!ruleStore.commitUpdatedRuleSet(result.ruleSet)) {
                        publishUpdateFailure("rules-commit-failed")
                        return@withLock FilterUpdateResult.Failed(FilterUpdateFailure.STORAGE)
                    }
                    currentRuleSet.set(result.ruleSet)
                    val readyStatus = statusFor(result.ruleSet, result.checkedAtEpochMillis)
                    mutableState.value = mutableState.value.copy(
                        status = readyStatus,
                        isUpdating = false,
                        lastError = null
                    )
                    result
                }
                is FilterUpdateResult.Failed -> {
                    publishUpdateFailure("rules-update-${result.reason.name.lowercase()}")
                    result
                }
            }
        }
    }

    override fun evaluate(request: ContentBlockRequest): ContentBlockDecision {
        if (!isEnabled()) return ContentBlockDecision.Allow
        if (
            request.source == ContentBlockRequestSource.WEB_VIEW &&
            sitePolicy.isContentBlockingDisabled(request.documentUrl)
        ) {
            return ContentBlockDecision.Allow
        }
        val decision = evaluateInstalled(request) ?: evaluateFallback(request)
        if (decision is ContentBlockDecision.Block) recordBlockedRequest()
        return decision
    }

    fun cosmeticResources(pageUrl: String): CosmeticResources {
        if (!isEnabled() || sitePolicy.isContentBlockingDisabled(pageUrl)) {
            return CosmeticResources()
        }
        return withInstalledEngine(contextFree = false, default = CosmeticResources()) { engine ->
            runCatching { engine.cosmeticResources(pageUrl) }
                .getOrElse { error ->
                    AppLogger.w(
                        "Content-block cosmetic lookup failed: ${error.javaClass.simpleName}"
                    )
                    CosmeticResources()
                }
        }
    }

    fun hiddenSelectors(
        pageUrl: String,
        classes: Collection<String>,
        ids: Collection<String>,
        exceptions: Collection<String>
    ): List<String> {
        if (!isEnabled() || sitePolicy.isContentBlockingDisabled(pageUrl)) return emptyList()
        return withInstalledEngine(contextFree = false, default = emptyList()) { engine ->
            runCatching { engine.hiddenSelectors(classes, ids, exceptions) }
                .getOrElse { error ->
                    AppLogger.w(
                        "Content-block dynamic selector lookup failed: " +
                            error.javaClass.simpleName
                    )
                    emptyList()
                }
        }
    }

    fun setSiteDisabled(pageUrl: String, disabled: Boolean): Boolean {
        return sitePolicy.setContentBlockingDisabled(pageUrl, disabled)
    }

    fun setPopupAllowed(pageUrl: String, allowed: Boolean): Boolean {
        return sitePolicy.setPopupBlockingAllowed(pageUrl, allowed)
    }

    override fun isEnabled(): Boolean = runtimeEnabled.get()

    override fun isSiteDisabled(pageUrl: String?): Boolean {
        return sitePolicy.isContentBlockingDisabled(pageUrl)
    }

    override fun isPopupAllowed(pageUrl: String?): Boolean {
        return sitePolicy.isPopupBlockingAllowed(pageUrl)
    }

    override fun recordPolicyBlock(reason: String): ContentBlockDecision.Block {
        recordBlockedRequest()
        return ContentBlockDecision.Block(engine = "surfsave-policy", reason = reason)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        scope.cancel()
        snapshot.getAndSet(null)?.retire()
        currentRuleSet.set(null)
        runCatching { fallbackEngine.close() }
    }

    private suspend fun initializeLocked() {
        mutableState.value = ContentBlockState(
            status = ContentBlockEngineStatus.INITIALIZING,
            blockedRequests = blockedRequests.get(),
            isUpdating = false
        )
        val loadResult = runCatching { ruleStore.loadBestAvailable() }.getOrElse { error ->
            publishFallback("rules-load-${error.javaClass.simpleName}")
            return
        }
        runCatching { buildAndInstall(loadResult.ruleSet) }
            .onSuccess {
                publishReadyState(
                    loadResult.ruleSet,
                    statusFor(loadResult.ruleSet, clock()),
                    loadResult.warning
                )
            }
            .onFailure { error ->
                publishFallback("engine-init-${error.javaClass.simpleName}")
            }
    }

    private fun buildAndInstall(ruleSet: ContentBlockRuleSet) {
        val built = buildSnapshot(ruleSet)
        installSnapshot(built, ruleSet)
    }

    private fun installSnapshot(
        built: ContentBlockEngineSnapshot,
        ruleSet: ContentBlockRuleSet
    ) {
        val previous = snapshot.getAndSet(built)
        currentRuleSet.set(ruleSet)
        previous?.retire()
    }

    private fun buildSnapshot(
        ruleSet: ContentBlockRuleSet,
        persistSerializedCache: Boolean = true
    ): ContentBlockEngineSnapshot {
        val cachedFull = ruleStore.readSerializedEngine(ruleSet, contextFree = false)
        val cachedContextFree = ruleStore.readSerializedEngine(ruleSet, contextFree = true)
        val pair = if (cachedFull != null && cachedContextFree != null) {
            runCatching {
                createPair(
                    full = { engineFactory.fromSerialized(cachedFull) },
                    contextFree = { engineFactory.fromSerialized(cachedContextFree) }
                )
            }.getOrNull()
        } else {
            null
        } ?: createPair(
            full = { engineFactory.fromLists(ruleSet.filterLists, contextFree = false) },
            contextFree = { engineFactory.fromLists(ruleSet.filterLists, contextFree = true) }
        ).also { engines ->
            if (persistSerializedCache) persistEnginePair(ruleSet, engines.first, engines.second)
        }
        return ContentBlockEngineSnapshot(
            full = pair.first,
            contextFree = pair.second,
            cacheKey = ruleSet.cacheKey,
            rulesVersion = ruleSet.rulesVersion,
            updatedAtEpochMillis = ruleSet.updatedAtEpochMillis,
            origin = ruleSet.origin
        )
    }

    private fun persistSnapshotCaches(
        ruleSet: ContentBlockRuleSet,
        built: ContentBlockEngineSnapshot
    ) {
        persistEnginePair(ruleSet, built.full, built.contextFree)
    }

    private fun persistEnginePair(
        ruleSet: ContentBlockRuleSet,
        full: ContentBlockEngine,
        contextFree: ContentBlockEngine
    ) {
        runCatching {
            check(ruleStore.writeSerializedEngine(
                ruleSet,
                contextFree = false,
                bytes = full.serialize()
            )) { "cache write rejected" }
        }.onFailure { error ->
            AppLogger.w(
                "Content-block full cache write skipped: ${error.javaClass.simpleName}"
            )
        }
        runCatching {
            check(ruleStore.writeSerializedEngine(
                ruleSet,
                contextFree = true,
                bytes = contextFree.serialize()
            )) { "cache write rejected" }
        }.onFailure { error ->
            AppLogger.w(
                "Content-block context-free cache write skipped: " +
                    error.javaClass.simpleName
            )
        }
    }

    private fun createPair(
        full: () -> ContentBlockEngine,
        contextFree: () -> ContentBlockEngine
    ): Pair<ContentBlockEngine, ContentBlockEngine> {
        val fullEngine = full()
        return try {
            fullEngine to contextFree()
        } catch (error: Throwable) {
            runCatching { fullEngine.close() }
            throw error
        }
    }

    private fun evaluateInstalled(request: ContentBlockRequest): ContentBlockDecision? {
        while (true) {
            val current = snapshot.get() ?: return null
            val lease = current.acquire() ?: continue
            try {
                val engine = if (request.source == ContentBlockRequestSource.SERVICE_WORKER) {
                    lease.contextFree
                } else {
                    lease.full
                }
                try {
                    return engine.evaluate(request)
                } catch (error: Throwable) {
                    AppLogger.w(
                        "Content-block engine request failed: ${error.javaClass.simpleName}"
                    )
                    if (snapshot.compareAndSet(current, null)) {
                        current.retire()
                        publishFallback("engine-request-failed")
                        return null
                    }
                    // A validated replacement was installed while this request used the old
                    // generation. Retry against that replacement instead of retiring it.
                }
            } finally {
                lease.close()
            }
        }
    }

    private fun evaluateFallback(request: ContentBlockRequest): ContentBlockDecision {
        if (mutableState.value.status != ContentBlockEngineStatus.FALLBACK) {
            publishFallback(mutableState.value.lastError ?: "native-engine-unavailable")
        }
        return runCatching { fallbackEngine.evaluate(request) }
            .getOrElse { error ->
                AppLogger.w(
                    "Content-block fallback request failed: ${error.javaClass.simpleName}"
                )
                publishEngineFailure("fallback-engine-request-failed")
                ContentBlockDecision.Allow
            }
    }

    private inline fun <T> withInstalledEngine(
        contextFree: Boolean,
        default: T,
        block: (ContentBlockEngine) -> T
    ): T {
        while (true) {
            val current = snapshot.get() ?: return default
            val lease = current.acquire() ?: continue
            lease.use {
                return block(if (contextFree) lease.contextFree else lease.full)
            }
        }
    }

    private fun recordBlockedRequest() {
        val count = blockedRequests.incrementAndGet()
        if (count == 1L || count % STATE_COUNTER_BATCH == 0L) {
            mutableState.value = mutableState.value.copy(blockedRequests = count)
        }
    }

    private fun publishReadyState(
        ruleSet: ContentBlockRuleSet,
        status: ContentBlockEngineStatus,
        warning: String? = null
    ) {
        mutableState.value = ContentBlockState(
            status = if (isEnabled()) status else ContentBlockEngineStatus.DISABLED,
            engineVersion = snapshot.get()?.full?.version.orEmpty(),
            rulesVersion = ruleSet.rulesVersion,
            updatedAtEpochMillis = ruleSet.updatedAtEpochMillis,
            blockedRequests = blockedRequests.get(),
            lastError = warning,
            rulesOrigin = ruleSet.origin,
            isUpdating = false
        )
    }

    private fun publishUpdateFailure(reason: String) {
        mutableState.value = mutableState.value.copy(
            status = if (isEnabled()) {
                ContentBlockEngineStatus.UPDATE_FAILED
            } else {
                ContentBlockEngineStatus.DISABLED
            },
            blockedRequests = blockedRequests.get(),
            lastError = reason,
            isUpdating = false
        )
    }

    private fun publishFallback(reason: String) {
        mutableState.value = ContentBlockState(
            status = if (isEnabled()) {
                ContentBlockEngineStatus.FALLBACK
            } else {
                ContentBlockEngineStatus.DISABLED
            },
            engineVersion = fallbackEngine.version,
            rulesVersion = currentRuleSet.get()?.rulesVersion.orEmpty(),
            updatedAtEpochMillis = currentRuleSet.get()?.updatedAtEpochMillis ?: 0L,
            blockedRequests = blockedRequests.get(),
            lastError = reason,
            rulesOrigin = ContentBlockRulesOrigin.FALLBACK,
            isUpdating = false
        )
    }

    private fun publishEngineFailure(reason: String) {
        mutableState.value = mutableState.value.copy(
            status = if (isEnabled()) {
                ContentBlockEngineStatus.ENGINE_FAILED
            } else {
                ContentBlockEngineStatus.DISABLED
            },
            blockedRequests = blockedRequests.get(),
            lastError = reason,
            isUpdating = false
        )
    }

    private fun statusFor(
        ruleSet: ContentBlockRuleSet,
        nowEpochMillis: Long
    ): ContentBlockEngineStatus {
        if (ruleSet.origin == ContentBlockRulesOrigin.BUNDLED) {
            return ContentBlockEngineStatus.BUNDLED
        }
        return if (nowEpochMillis - ruleSet.updatedAtEpochMillis >
            FilterSubscriptionRepository.STALE_AFTER_MILLIS
        ) {
            ContentBlockEngineStatus.STALE
        } else {
            ContentBlockEngineStatus.UP_TO_DATE
        }
    }

    private fun shouldRefresh(ruleSet: ContentBlockRuleSet?): Boolean {
        if (ruleSet == null || ruleSet.origin == ContentBlockRulesOrigin.BUNDLED) return true
        return clock() - ruleSet.updatedAtEpochMillis >
            FilterSubscriptionRepository.STALE_AFTER_MILLIS
    }

    companion object {
        private const val STATE_COUNTER_BATCH = 16L
    }
}
