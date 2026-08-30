package com.myAllVideoBrowser.contentblock

import android.app.Application
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class FilterSubscriptionRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun bundledRules_areHashSizeAndFormatVerified() = runBlocking {
        val fixture = Fixture()
        val repository = fixture.repository(temporaryFolder.newFolder())

        val loaded = repository.loadBestAvailable()

        assertEquals(ContentBlockRulesOrigin.BUNDLED, loaded.ruleSet.origin)
        assertEquals(listOf("easylist", "easyprivacy", "surfsave"),
            loaded.ruleSet.sources.map { it.manifest.id })
        assertEquals(64, loaded.ruleSet.cacheKey.length)
        assertNull(loaded.warning)
    }

    @Test
    fun bundledHashMismatch_isRejectedInsteadOfSilentlyLoading() {
        runBlocking {
            val fixture = Fixture(corruptEasyListHash = true)
            try {
                fixture.repository(temporaryFolder.newFolder()).loadBestAvailable()
                fail("Expected invalid bundled hash to be rejected")
            } catch (_: RuntimeException) {
                Unit
            }
        }
    }

    @Test
    fun invalidStoredPointer_fallsBackToVerifiedBundledWithVisibleWarning() = runBlocking {
        val directory = temporaryFolder.newFolder()
        directory.resolve("current.json").writeText("{\"directory\":\"../escape\"}", Charsets.UTF_8)
        val repository = Fixture().repository(directory)

        val loaded = repository.loadBestAvailable()

        assertEquals(ContentBlockRulesOrigin.BUNDLED, loaded.ruleSet.origin)
        assertEquals("stored-rules-invalid", loaded.warning)
    }

    @Test
    fun updatedEngineCache_roundTripsAndIsSeparatedByContext() = runBlocking {
        val repository = Fixture().repository(temporaryFolder.newFolder())
        val bundled = repository.loadBestAvailable().ruleSet
        val updated = bundled.copy(origin = ContentBlockRulesOrigin.UPDATED)

        assertTrue(repository.writeSerializedEngine(updated, false, byteArrayOf(1, 2, 3)))
        assertTrue(repository.writeSerializedEngine(updated, true, byteArrayOf(4, 5)))

        assertArrayEquals(byteArrayOf(1, 2, 3), repository.readSerializedEngine(updated, false))
        assertArrayEquals(byteArrayOf(4, 5), repository.readSerializedEngine(updated, true))
        assertNull(repository.readSerializedEngine(bundled, false))
    }

    @Test
    fun cacheKey_isStableButDependsOnOrderedHashes() {
        val fixture = Fixture()
        val manifest = fixture.manifest
        val sources = fixture.sources

        val first = FilterSubscriptionRepository.cacheKey(manifest, sources)
        val second = FilterSubscriptionRepository.cacheKey(manifest, sources)
        val changed = FilterSubscriptionRepository.cacheKey(
            manifest,
            sources.mapIndexed { index, source ->
                if (index == 0) source.copy(sha256 = "A".repeat(64)) else source
            }
        )

        assertEquals(first, second)
        assertNotNull(first)
        assertTrue(first != changed)
    }

    @Test
    fun successfulUpdate_isNotCurrentUntilExplicitCommit_andConditional304IsRetained() =
        runBlocking {
            val server = MockWebServer().apply { start() }
            try {
                val directory = temporaryFolder.newFolder()
                val fixture = Fixture()
                val repository = fixture.repository(directory, clientFor(server))
                val bundled = repository.loadBestAvailable().ruleSet
                server.enqueue(filterResponse("10", "||new-ads.example^", "ETag" to "\"one\""))
                server.enqueue(
                    filterResponse(
                        "20",
                        "||new-track.example^",
                        "Last-Modified" to "Wed, 21 Oct 2015 07:28:00 GMT"
                    )
                )

                val updated = (repository.update(bundled) as FilterUpdateResult.Updated).ruleSet
                server.takeRequest()
                server.takeRequest()

                assertEquals(ContentBlockRulesOrigin.BUNDLED,
                    repository.loadBestAvailable().ruleSet.origin)
                assertTrue(repository.commitUpdatedRuleSet(updated))
                val committed = repository.loadBestAvailable().ruleSet
                assertEquals(ContentBlockRulesOrigin.UPDATED, committed.origin)

                server.enqueue(MockResponse().setResponseCode(304))
                server.enqueue(MockResponse().setResponseCode(304))
                val notModified = repository.update(committed) as FilterUpdateResult.NotModified
                assertEquals(ContentBlockRulesOrigin.UPDATED, notModified.ruleSet.origin)
                assertEquals("\"one\"", server.takeRequest().getHeader("If-None-Match"))
                assertEquals(
                    "Wed, 21 Oct 2015 07:28:00 GMT",
                    server.takeRequest().getHeader("If-Modified-Since")
                )
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun failedUpdate_doesNotReplaceCommittedLastKnownGood() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            val directory = temporaryFolder.newFolder()
            val fixture = Fixture()
            val repository = fixture.repository(directory, clientFor(server))
            val bundled = repository.loadBestAvailable().ruleSet
            server.enqueue(filterResponse("10", "||new-ads.example^"))
            server.enqueue(filterResponse("20", "||new-track.example^"))
            val updated = (repository.update(bundled) as FilterUpdateResult.Updated).ruleSet
            assertTrue(repository.commitUpdatedRuleSet(updated))

            server.enqueue(MockResponse().setResponseCode(503))
            val failed = repository.update(updated)

            assertEquals(FilterUpdateFailure.HTTP, (failed as FilterUpdateResult.Failed).reason)
            assertEquals(updated.cacheKey, repository.loadBestAvailable().ruleSet.cacheKey)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun invalidDownloadedRules_areRejectedBeforeCommit() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            val repository = Fixture().repository(
                temporaryFolder.newFolder(),
                clientFor(server)
            )
            val bundled = repository.loadBestAvailable().ruleSet
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("[Adblock Plus 2.0]\n\u0000invalid\n")
            )

            val failed = repository.update(bundled)

            assertEquals(
                FilterUpdateFailure.INVALID_CONTENT,
                (failed as FilterUpdateResult.Failed).reason
            )
            assertEquals(ContentBlockRulesOrigin.BUNDLED,
                repository.loadBestAvailable().ruleSet.origin)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun tamperedCommittedSource_fallsBackToBundledLastKnownGood() = runBlocking {
        val directory = temporaryFolder.newFolder()
        val fixture = Fixture()
        val repository = fixture.repository(directory)
        val bundled = repository.loadBestAvailable().ruleSet
        val updated = fixture.updatedRuleSet(bundled, "tamper-target", 3_000L)
        assertTrue(repository.commitUpdatedRuleSet(updated))
        val generation = directory.resolve("generations")
            .listFiles()
            .orEmpty()
            .single { it.isDirectory && !it.name.startsWith(".tmp-") }
        val source = generation.resolve("source-easylist.txt")
        source.writeText("x".repeat(source.length().toInt()), Charsets.UTF_8)

        val loaded = repository.loadBestAvailable()

        assertEquals(ContentBlockRulesOrigin.BUNDLED, loaded.ruleSet.origin)
        assertEquals("stored-rules-invalid", loaded.warning)
    }

    @Test
    fun committingThirdGeneration_keepsCurrentAndPreviousAndPrunesOlderCaches() = runBlocking {
        val directory = temporaryFolder.newFolder()
        val fixture = Fixture()
        val repository = fixture.repository(directory)
        val bundled = repository.loadBestAvailable().ruleSet
        val first = fixture.updatedRuleSet(bundled, "first", 1_000L)
        val second = fixture.updatedRuleSet(bundled, "second", 2_000L)
        // Commit order, not wall-clock order, defines the rollback generation.
        val third = fixture.updatedRuleSet(bundled, "third", 500L)

        assertTrue(repository.writeSerializedEngine(first, false, byteArrayOf(1)))
        assertTrue(repository.commitUpdatedRuleSet(first))
        val firstDirectory = requireNotNull(committedGenerationNames(directory).single())
        assertTrue(repository.writeSerializedEngine(second, false, byteArrayOf(2)))
        assertTrue(repository.commitUpdatedRuleSet(second))
        val secondDirectory = committedGenerationNames(directory).single { it != firstDirectory }
        assertTrue(repository.writeSerializedEngine(third, false, byteArrayOf(3)))
        assertTrue(repository.commitUpdatedRuleSet(third))

        val retained = committedGenerationNames(directory)
        assertEquals(2, retained.size)
        assertFalse(firstDirectory in retained)
        assertTrue(secondDirectory in retained)
        assertTrue(retained.any { it.startsWith("${third.cacheKey}-") })
        assertNull(repository.readSerializedEngine(first, false))
        assertArrayEquals(byteArrayOf(2), repository.readSerializedEngine(second, false))
        assertArrayEquals(byteArrayOf(3), repository.readSerializedEngine(third, false))
        assertEquals(third.cacheKey, repository.loadBestAvailable().ruleSet.cacheKey)
    }

    @Test
    fun managedDeletion_rejectsCanonicalPathEscapeWithoutTouchingOutsideDirectory() {
        val base = temporaryFolder.newFolder()
        val managedRoot = base.resolve("generations").apply { mkdirs() }
        val outside = temporaryFolder.newFolder().apply {
            resolve("keep.txt").writeText("keep", Charsets.UTF_8)
        }

        assertFalse(
            FilterSubscriptionRepository.deleteDirectoryIfManagedDirectChild(
                managedRoot,
                managedRoot.resolve(
                    "..${java.io.File.separator}..${java.io.File.separator}${outside.name}"
                )
            )
        )
        assertTrue(outside.resolve("keep.txt").isFile)
    }

    @Test
    fun oversizedSubscription_isRejectedBeforeAllocation() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            val fixture = Fixture()
            val repository = fixture.repository(temporaryFolder.newFolder(), clientFor(server))
            val bundled = repository.loadBestAvailable().ruleSet
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("x".repeat(9 * 1024 * 1024))
            )

            val failed = repository.update(bundled)

            assertEquals(FilterUpdateFailure.TOO_LARGE,
                (failed as FilterUpdateResult.Failed).reason)
        } finally {
            server.shutdown()
        }
    }

    private fun filterResponse(
        version: String,
        rule: String,
        header: Pair<String, String>? = null
    ): MockResponse {
        return MockResponse()
            .setResponseCode(200)
            .setBody("[Adblock Plus 2.0]\n! Version: $version\n$rule\n")
            .apply { header?.let { setHeader(it.first, it.second) } }
    }

    private fun clientFor(server: MockWebServer): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                val original = chain.request()
                val local = server.url(original.url.encodedPath)
                chain.proceed(original.newBuilder().url(local).build())
            }
            .build()
    }

    private fun committedGenerationNames(directory: java.io.File): Set<String> {
        return directory.resolve("generations")
            .listFiles()
            .orEmpty()
            .filter { it.isDirectory && !it.name.startsWith(".tmp-") }
            .mapTo(mutableSetOf()) { it.name }
    }

    private class Fixture(corruptEasyListHash: Boolean = false) {
        private val contents = linkedMapOf(
            "easylist" to "[Adblock Plus 2.0]\n! Version: 1\n||ads.example^\n",
            "easyprivacy" to "[Adblock Plus 2.0]\n! Version: 2\n||track.example^\n",
            "surfsave" to "[Adblock Plus 2.0]\n! Version: 3\n||media-ads.example^\$media\n"
        )
        val manifest: FilterManifest
        val sources: List<VerifiedFilterSource>
        private val assets: Map<String, ByteArray>

        init {
            val sourceManifests = contents.map { (id, text) ->
                val bytes = text.toByteArray(Charsets.UTF_8)
                FilterSourceManifest(
                    id = id,
                    asset = "contentblock/$id.txt",
                    url = "https://filters.example/$id.txt",
                    license = "test",
                    version = id,
                    sha256 = if (corruptEasyListHash && id == "easylist") {
                        "0".repeat(64)
                    } else {
                        FilterSubscriptionRepository.sha256(bytes)
                    },
                    bytes = bytes.size.toLong()
                )
            }
            manifest = FilterManifest(
                schemaVersion = 1,
                engineVersion = FilterSubscriptionRepository.ENGINE_VERSION,
                snapshotDate = "2026-08-29",
                sources = sourceManifests
            )
            sources = sourceManifests.map { source ->
                val text = requireNotNull(contents[source.id])
                VerifiedFilterSource(
                    manifest = source,
                    content = text,
                    sha256 = FilterSubscriptionRepository.sha256(text.toByteArray()),
                    bytes = text.toByteArray().size.toLong()
                )
            }
            val manifestJson = kotlinx.serialization.json.Json.encodeToString(
                FilterManifest.serializer(),
                manifest
            )
            assets = buildMap {
                put(FilterSubscriptionRepository.SOURCE_MANIFEST_ASSET,
                    manifestJson.toByteArray(Charsets.UTF_8))
                sourceManifests.forEach { source ->
                    put(source.asset, requireNotNull(contents[source.id]).toByteArray(Charsets.UTF_8))
                }
            }
        }

        fun repository(
            directory: java.io.File,
            client: OkHttpClient = OkHttpClient()
        ) = FilterSubscriptionRepository(
            baseDirectory = directory,
            bundledLoader = { asset -> requireNotNull(assets[asset]) },
            client = client,
            clock = { 1_000L }
        )

        fun updatedRuleSet(
            bundled: ContentBlockRuleSet,
            marker: String,
            updatedAtEpochMillis: Long
        ): ContentBlockRuleSet {
            val sources = bundled.sources.mapIndexed { index, source ->
                if (index != 0) return@mapIndexed source
                val content = "[Adblock Plus 2.0]\n! Version: $marker\n||$marker.example^\n"
                val bytes = content.toByteArray(Charsets.UTF_8)
                val hash = FilterSubscriptionRepository.sha256(bytes)
                source.copy(
                    manifest = source.manifest.copy(
                        version = marker,
                        sha256 = hash,
                        bytes = bytes.size.toLong()
                    ),
                    content = content,
                    sha256 = hash,
                    bytes = bytes.size.toLong()
                )
            }
            return bundled.copy(
                sources = sources,
                cacheKey = FilterSubscriptionRepository.cacheKey(manifest, sources),
                rulesVersion = sources.joinToString(" / ") {
                    "${it.manifest.id}:${it.manifest.version}"
                },
                updatedAtEpochMillis = updatedAtEpochMillis,
                origin = ContentBlockRulesOrigin.UPDATED
            )
        }
    }
}
