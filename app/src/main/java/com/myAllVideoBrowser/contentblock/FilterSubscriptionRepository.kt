package com.myAllVideoBrowser.contentblock

import android.app.Application
import android.util.AtomicFile
import com.myAllVideoBrowser.util.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

interface FilterRuleStore {
    suspend fun loadBestAvailable(): RuleSetLoadResult

    suspend fun update(current: ContentBlockRuleSet): FilterUpdateResult

    fun commitUpdatedRuleSet(ruleSet: ContentBlockRuleSet): Boolean

    fun readSerializedEngine(ruleSet: ContentBlockRuleSet, contextFree: Boolean): ByteArray?

    fun writeSerializedEngine(
        ruleSet: ContentBlockRuleSet,
        contextFree: Boolean,
        bytes: ByteArray
    ): Boolean
}

@Singleton
class FilterSubscriptionRepository internal constructor(
    private val baseDirectory: File,
    private val bundledLoader: (String) -> ByteArray,
    client: OkHttpClient,
    private val clock: () -> Long
) : FilterRuleStore {
    private val lastCommittedGeneration = AtomicReference<String?>(null)

    private val client = client.newBuilder()
        .cookieJar(CookieJar.NO_COOKIES)
        .followSslRedirects(false)
        .build()

    @Inject
    constructor(
        application: Application,
        client: OkHttpClient
    ) : this(
        baseDirectory = File(application.filesDir, "contentblock"),
        bundledLoader = { asset ->
            application.assets.open(asset).use { input ->
                readLimited(input, MAX_BUNDLED_ASSET_BYTES)
            }
        },
        client = client,
        clock = System::currentTimeMillis
    )

    override suspend fun loadBestAvailable(): RuleSetLoadResult = withContext(Dispatchers.IO) {
        val bundled = loadBundledRuleSet()
        val updated = runCatching { loadStoredGeneration(bundled.sourceManifestSha256) }
            .getOrNull()
        if (updated != null) {
            RuleSetLoadResult(updated)
        } else {
            RuleSetLoadResult(
                ruleSet = bundled,
                warning = if (currentPointerFile().isFile) "stored-rules-invalid" else null
            )
        }
    }

    override suspend fun update(current: ContentBlockRuleSet): FilterUpdateResult =
        withContext(Dispatchers.IO) {
            try {
                val bundledManifest = readBundledManifest()
                val previousById = current.sources.associateBy { it.manifest.id }
                val updatedSources = bundledManifest.sources.map { configured ->
                    if (configured.id !in REMOTE_SOURCE_IDS) {
                        loadBundledSource(configured)
                    } else {
                        downloadSource(configured, previousById[configured.id])
                    }
                }
                val updatedAt = clock()
                val ruleSet = buildRuleSet(
                    manifest = bundledManifest,
                    manifestBytes = bundledLoader(SOURCE_MANIFEST_ASSET),
                    sources = updatedSources,
                    updatedAtEpochMillis = updatedAt,
                    origin = ContentBlockRulesOrigin.UPDATED
                )
                if (updatedSources.map { it.sha256 } == current.sources.map { it.sha256 }) {
                    FilterUpdateResult.NotModified(ruleSet, updatedAt)
                } else {
                    FilterUpdateResult.Updated(ruleSet)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: TooLargeException) {
                FilterUpdateResult.Failed(FilterUpdateFailure.TOO_LARGE)
            } catch (_: InvalidContentException) {
                FilterUpdateResult.Failed(FilterUpdateFailure.INVALID_CONTENT)
            } catch (_: ManifestException) {
                FilterUpdateResult.Failed(FilterUpdateFailure.MANIFEST)
            } catch (_: HttpStatusException) {
                FilterUpdateResult.Failed(FilterUpdateFailure.HTTP)
            } catch (_: IOException) {
                FilterUpdateResult.Failed(FilterUpdateFailure.NETWORK)
            } catch (_: StorageException) {
                FilterUpdateResult.Failed(FilterUpdateFailure.STORAGE)
            } catch (_: RuntimeException) {
                FilterUpdateResult.Failed(FilterUpdateFailure.STORAGE)
            }
        }

    override fun commitUpdatedRuleSet(ruleSet: ContentBlockRuleSet): Boolean {
        if (ruleSet.origin != ContentBlockRulesOrigin.UPDATED) return false
        return runCatching {
            persistGeneration(ruleSet)
            true
        }.getOrDefault(false)
    }

    override fun readSerializedEngine(
        ruleSet: ContentBlockRuleSet,
        contextFree: Boolean
    ): ByteArray? = runCatching {
        if (ruleSet.origin == ContentBlockRulesOrigin.BUNDLED) {
            readBundledEngine(ruleSet, contextFree)
        } else {
            val file = engineCacheFile(ruleSet.cacheKey, contextFree)
            file.takeIf { it.isFile && it.length() in 1L..MAX_ENGINE_BYTES.toLong() }
                ?.readBytes()
        }
    }.getOrNull()

    override fun writeSerializedEngine(
        ruleSet: ContentBlockRuleSet,
        contextFree: Boolean,
        bytes: ByteArray
    ): Boolean {
        if (bytes.isEmpty() || bytes.size > MAX_ENGINE_BYTES) return false
        return runCatching {
            writeAtomic(engineCacheFile(ruleSet.cacheKey, contextFree), bytes)
            true
        }.getOrDefault(false)
    }

    private fun loadBundledRuleSet(): ContentBlockRuleSet {
        val manifestBytes = bundledLoader(SOURCE_MANIFEST_ASSET)
        if (manifestBytes.size > MAX_MANIFEST_BYTES) throw ManifestException()
        val manifest = parseSourceManifest(manifestBytes)
        val sources = manifest.sources.map(::loadBundledSource)
        return buildRuleSet(
            manifest = manifest,
            manifestBytes = manifestBytes,
            sources = sources,
            updatedAtEpochMillis = 0L,
            origin = ContentBlockRulesOrigin.BUNDLED
        )
    }

    private fun readBundledManifest(): FilterManifest {
        return parseSourceManifest(bundledLoader(SOURCE_MANIFEST_ASSET))
    }

    private fun parseSourceManifest(bytes: ByteArray): FilterManifest {
        val manifest = runCatching {
            JSON.decodeFromString<FilterManifest>(bytes.toString(Charsets.UTF_8))
        }.getOrElse { throw ManifestException() }
        if (
            manifest.schemaVersion != SOURCE_SCHEMA_VERSION ||
            manifest.engineVersion != ENGINE_VERSION ||
            manifest.sources.map { it.id }.toSet().size != manifest.sources.size ||
            manifest.sources.map { it.id }.toSet() != REQUIRED_SOURCE_IDS
        ) {
            throw ManifestException()
        }
        manifest.sources.forEach { source ->
            if (
                !SAFE_ID.matches(source.id) ||
                !source.asset.startsWith("contentblock/") ||
                source.bytes <= 0L ||
                source.bytes > MAX_LIST_BYTES ||
                !SHA_256.matches(source.sha256)
            ) {
                throw ManifestException()
            }
            val scheme = runCatching { URI(source.url).scheme }.getOrNull()
            if (!scheme.equals("https", ignoreCase = true)) throw ManifestException()
        }
        return manifest
    }

    private fun loadBundledSource(manifest: FilterSourceManifest): VerifiedFilterSource {
        val bytes = bundledLoader(manifest.asset)
        validateBytes(bytes, manifest.bytes, manifest.sha256)
        validateFilterContent(bytes)
        return VerifiedFilterSource(
            manifest = manifest,
            content = bytes.toString(Charsets.UTF_8),
            sha256 = sha256(bytes),
            bytes = bytes.size.toLong()
        )
    }

    private fun downloadSource(
        configured: FilterSourceManifest,
        previous: VerifiedFilterSource?
    ): VerifiedFilterSource {
        val request = Request.Builder()
            .url(configured.url)
            .get()
            .apply {
                previous?.etag?.takeIf(::isSafeValidator)?.let { header("If-None-Match", it) }
                previous?.lastModified?.takeIf(::isSafeValidator)?.let {
                    header("If-Modified-Since", it)
                }
            }
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 304) return previous ?: throw InvalidContentException()
            if (!response.isSuccessful) throw HttpStatusException()
            val contentLength = response.body.contentLength()
            if (contentLength > MAX_LIST_BYTES) throw TooLargeException()
            val bytes = response.body.byteStream().use { readLimited(it, MAX_LIST_BYTES) }
            validateFilterContent(bytes)
            val version = extractVersion(bytes).ifBlank { sha256(bytes).take(12) }
            return VerifiedFilterSource(
                manifest = configured.copy(
                    version = version,
                    sha256 = sha256(bytes),
                    bytes = bytes.size.toLong()
                ),
                content = bytes.toString(Charsets.UTF_8),
                sha256 = sha256(bytes),
                bytes = bytes.size.toLong(),
                etag = response.header("ETag")?.takeIf(::isSafeValidator),
                lastModified = response.header("Last-Modified")?.takeIf(::isSafeValidator)
            )
        }
    }

    private fun buildRuleSet(
        manifest: FilterManifest,
        manifestBytes: ByteArray,
        sources: List<VerifiedFilterSource>,
        updatedAtEpochMillis: Long,
        origin: ContentBlockRulesOrigin
    ): ContentBlockRuleSet {
        if (sources.map { it.manifest.id } != manifest.sources.map { it.id }) {
            throw ManifestException()
        }
        val cacheKey = cacheKey(manifest, sources)
        val rulesVersion = sources.joinToString(" / ") {
            "${it.manifest.id}:${it.manifest.version}"
        }
        return ContentBlockRuleSet(
            sources = sources,
            sourceManifestSha256 = sha256(manifestBytes),
            cacheKey = cacheKey,
            rulesVersion = rulesVersion,
            updatedAtEpochMillis = updatedAtEpochMillis,
            origin = origin
        )
    }

    private fun persistGeneration(ruleSet: ContentBlockRuleSet) {
        val generations = File(baseDirectory, GENERATIONS_DIRECTORY)
        if (!generations.mkdirs() && !generations.isDirectory) throw StorageException()
        val previousDirectory = lastCommittedGeneration.get() ?: readCurrentGenerationDirectory()
        val directoryName = "${ruleSet.cacheKey}-${ruleSet.updatedAtEpochMillis}-${UUID.randomUUID()}"
        val temporary = File(generations, ".tmp-$directoryName")
        if (!temporary.mkdir()) throw StorageException()
        try {
            val storedSources = ruleSet.sources.map { source ->
                val fileName = "source-${source.manifest.id}.txt"
                writeSynced(File(temporary, fileName), source.content.toByteArray(Charsets.UTF_8))
                StoredSource(
                    id = source.manifest.id,
                    file = fileName,
                    url = source.manifest.url,
                    license = source.manifest.license,
                    version = source.manifest.version,
                    sha256 = source.sha256,
                    bytes = source.bytes,
                    etag = source.etag,
                    lastModified = source.lastModified
                )
            }
            val stored = StoredGeneration(
                schemaVersion = SOURCE_SCHEMA_VERSION,
                cacheKey = ruleSet.cacheKey,
                sourceManifestSha256 = ruleSet.sourceManifestSha256,
                updatedAtEpochMillis = ruleSet.updatedAtEpochMillis,
                sources = storedSources
            )
            writeSynced(
                File(temporary, GENERATION_MANIFEST_FILE),
                JSON.encodeToString(stored).toByteArray(Charsets.UTF_8)
            )
            val committed = File(generations, directoryName)
            if (!temporary.renameTo(committed)) throw StorageException()
            writeAtomic(
                currentPointerFile(),
                JSON.encodeToString(CurrentPointer(directoryName)).toByteArray(Charsets.UTF_8)
            )
            lastCommittedGeneration.set(directoryName)
            runCatching {
                pruneCommittedStorage(
                    currentDirectory = directoryName,
                    previousDirectory = previousDirectory
                )
            }.onFailure {
                AppLogger.w("Content-block storage cleanup failed after a successful commit")
            }
        } catch (error: RuntimeException) {
            deleteTemporaryDirectory(temporary)
            throw error
        } catch (error: IOException) {
            deleteTemporaryDirectory(temporary)
            throw StorageException(error)
        }
    }

    private fun readCurrentGenerationDirectory(): String? {
        lastCommittedGeneration.get()?.let { return it }
        return runCatching {
            val pointerFile = currentPointerFile()
            if (
                !pointerFile.isFile ||
                pointerFile.length() !in 1L..MAX_MANIFEST_BYTES.toLong()
            ) {
                return null
            }
            JSON.decodeFromString<CurrentPointer>(pointerFile.readText(Charsets.UTF_8))
                .directory
                .takeIf(SAFE_GENERATION_NAME::matches)
        }.getOrNull()
    }

    private fun pruneCommittedStorage(
        currentDirectory: String,
        previousDirectory: String?
    ) {
        val generationRoot = managedDirectory(GENERATIONS_DIRECTORY) ?: return
        val generationCandidates = generationRoot.listFiles().orEmpty()
        val retainedGenerations = buildSet {
            add(currentDirectory)
            previousDirectory
                ?.takeIf(SAFE_GENERATION_NAME::matches)
                ?.takeIf { previous ->
                    isManagedDirectChildDirectory(generationRoot, File(generationRoot, previous))
                }
                ?.let { add(it) }
        }
        var cleanupIncomplete = false
        generationCandidates.forEach { candidate ->
            val isCommittedGeneration = SAFE_GENERATION_NAME.matches(candidate.name)
            val isTemporaryGeneration = SAFE_TEMP_GENERATION_NAME.matches(candidate.name)
            if (
                (isCommittedGeneration && candidate.name !in retainedGenerations) ||
                isTemporaryGeneration
            ) {
                cleanupIncomplete =
                    !deleteDirectoryIfManagedDirectChild(
                        File(baseDirectory, GENERATIONS_DIRECTORY),
                        candidate
                    ) || cleanupIncomplete
            }
        }

        val retainedCacheKeys = retainedGenerations
            .filter(SAFE_GENERATION_NAME::matches)
            .mapTo(mutableSetOf()) { it.substringBefore('-') }
        managedDirectory(ENGINE_CACHE_DIRECTORY)?.listFiles()?.forEach { candidate ->
            val match = SAFE_ENGINE_CACHE_FILE.matchEntire(candidate.name) ?: return@forEach
            if (match.groupValues[1] !in retainedCacheKeys) {
                cleanupIncomplete =
                    !deleteFileIfManagedDirectChild(
                        File(baseDirectory, ENGINE_CACHE_DIRECTORY),
                        candidate
                    ) || cleanupIncomplete
            }
        }
        if (cleanupIncomplete) {
            AppLogger.w("Content-block storage cleanup skipped unsafe or undeletable entries")
        }
    }

    private fun managedDirectory(name: String): File? {
        return runCatching {
            val base = baseDirectory.canonicalFile
            val directory = File(baseDirectory, name).canonicalFile
            directory.takeIf { it.parentFile == base && it.isDirectory }
        }.getOrNull()
    }

    private fun loadStoredGeneration(sourceManifestSha256: String): ContentBlockRuleSet? {
        val directoryName = lastCommittedGeneration.get() ?: currentPointerFile()
            .takeIf { it.isFile && it.length() in 1L..MAX_MANIFEST_BYTES.toLong() }
            ?.readText(Charsets.UTF_8)
            ?.let { JSON.decodeFromString<CurrentPointer>(it).directory }
            ?: return null
        if (!SAFE_GENERATION_NAME.matches(directoryName)) return null
        val directory = File(File(baseDirectory, GENERATIONS_DIRECTORY), directoryName)
        val metadataFile = File(directory, GENERATION_MANIFEST_FILE)
        if (!metadataFile.isFile || metadataFile.length() !in 1L..MAX_MANIFEST_BYTES.toLong()) {
            return null
        }
        val metadata = JSON.decodeFromString<StoredGeneration>(
            metadataFile.readText(Charsets.UTF_8)
        )
        if (
            metadata.schemaVersion != SOURCE_SCHEMA_VERSION ||
            metadata.sourceManifestSha256 != sourceManifestSha256 ||
            metadata.cacheKey != directoryName.substringBefore('-') ||
            metadata.sources.map { it.id }.toSet() != REQUIRED_SOURCE_IDS
        ) {
            return null
        }
        val sources = metadata.sources.map { stored ->
            if (!SAFE_ID.matches(stored.id) || !SAFE_SOURCE_FILE.matches(stored.file)) return null
            val file = File(directory, stored.file)
            if (
                !file.isFile ||
                stored.bytes !in 1L..MAX_LIST_BYTES.toLong() ||
                file.length() != stored.bytes ||
                !SHA_256.matches(stored.sha256)
            ) {
                return null
            }
            val bytes = file.readBytes()
            if (sha256(bytes) != stored.sha256) return null
            validateFilterContent(bytes)
            VerifiedFilterSource(
                manifest = FilterSourceManifest(
                    id = stored.id,
                    asset = "contentblock/${stored.file}",
                    url = stored.url,
                    license = stored.license,
                    version = stored.version,
                    sha256 = stored.sha256,
                    bytes = stored.bytes
                ),
                content = bytes.toString(Charsets.UTF_8),
                sha256 = stored.sha256,
                bytes = stored.bytes,
                etag = stored.etag,
                lastModified = stored.lastModified
            )
        }
        val sourceManifest = readBundledManifest()
        val rebuiltKey = cacheKey(sourceManifest, sources)
        if (rebuiltKey != metadata.cacheKey) return null
        lastCommittedGeneration.compareAndSet(null, directoryName)
        return ContentBlockRuleSet(
            sources = sources,
            sourceManifestSha256 = metadata.sourceManifestSha256,
            cacheKey = metadata.cacheKey,
            rulesVersion = sources.joinToString(" / ") {
                "${it.manifest.id}:${it.manifest.version}"
            },
            updatedAtEpochMillis = metadata.updatedAtEpochMillis,
            origin = ContentBlockRulesOrigin.UPDATED
        )
    }

    private fun readBundledEngine(
        ruleSet: ContentBlockRuleSet,
        contextFree: Boolean
    ): ByteArray? {
        val manifestBytes = bundledLoader(ENGINE_MANIFEST_ASSET)
        if (manifestBytes.size > MAX_MANIFEST_BYTES) return null
        val manifest = JSON.decodeFromString<BundledEngineManifest>(
            manifestBytes.toString(Charsets.UTF_8)
        )
        if (
            manifest.schemaVersion != SOURCE_SCHEMA_VERSION ||
            manifest.engineVersion != ENGINE_VERSION ||
            manifest.sourceManifestSha256 != ruleSet.sourceManifestSha256 ||
            manifest.cacheKey != ruleSet.cacheKey
        ) {
            return null
        }
        val payload = if (contextFree) manifest.contextFree else manifest.full
        if (
            !payload.asset.startsWith("contentblock/") ||
            payload.bytes <= 0 ||
            payload.bytes > MAX_ENGINE_BYTES ||
            !SHA_256.matches(payload.sha256)
        ) {
            return null
        }
        val bytes = bundledLoader(payload.asset)
        return bytes.takeIf {
            it.size.toLong() == payload.bytes && sha256(it) == payload.sha256
        }
    }

    private fun currentPointerFile() = File(baseDirectory, CURRENT_POINTER_FILE)

    private fun engineCacheFile(cacheKey: String, contextFree: Boolean): File {
        val suffix = if (contextFree) "context-free" else "full"
        return File(File(baseDirectory, ENGINE_CACHE_DIRECTORY), "$cacheKey-$suffix.dat")
    }

    private fun validateBytes(bytes: ByteArray, expectedBytes: Long, expectedSha: String) {
        if (bytes.size.toLong() != expectedBytes || sha256(bytes) != expectedSha) {
            throw ManifestException()
        }
    }

    private fun validateFilterContent(bytes: ByteArray) {
        if (bytes.isEmpty() || bytes.size > MAX_LIST_BYTES || bytes.any { it == 0.toByte() }) {
            throw InvalidContentException()
        }
        val prefix = bytes.copyOfRange(0, minOf(bytes.size, 4_096)).toString(Charsets.UTF_8)
        if (!prefix.contains("[Adblock Plus", ignoreCase = true)) {
            throw InvalidContentException()
        }
        var lines = 1
        var currentLine = 0
        bytes.forEach { byte ->
            if (byte == '\n'.code.toByte()) {
                lines++
                currentLine = 0
                if (lines > MAX_RULE_LINES) throw TooLargeException()
            } else {
                currentLine++
                if (currentLine > MAX_RULE_LINE_BYTES) throw TooLargeException()
            }
        }
    }

    private fun extractVersion(bytes: ByteArray): String {
        return bytes.toString(Charsets.UTF_8)
            .lineSequence()
            .take(100)
            .firstOrNull { it.startsWith("! Version:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            ?.take(MAX_VERSION_LENGTH)
            .orEmpty()
    }

    private fun writeAtomic(file: File, bytes: ByteArray) {
        file.parentFile?.let { parent ->
            if (!parent.mkdirs() && !parent.isDirectory) throw StorageException()
        }
        val atomicFile = AtomicFile(file)
        val output = atomicFile.startWrite()
        try {
            output.write(bytes)
            output.fd.sync()
            atomicFile.finishWrite(output)
        } catch (error: IOException) {
            atomicFile.failWrite(output)
            throw StorageException(error)
        }
    }

    private fun writeSynced(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
    }

    private fun deleteTemporaryDirectory(directory: File) {
        if (!SAFE_TEMP_GENERATION_NAME.matches(directory.name)) return
        deleteDirectoryIfManagedDirectChild(
            File(baseDirectory, GENERATIONS_DIRECTORY),
            directory
        )
    }

    @Serializable
    private data class CurrentPointer(val directory: String)

    @Serializable
    private data class StoredGeneration(
        val schemaVersion: Int,
        val cacheKey: String,
        val sourceManifestSha256: String,
        val updatedAtEpochMillis: Long,
        val sources: List<StoredSource>
    )

    @Serializable
    private data class StoredSource(
        val id: String,
        val file: String,
        val url: String,
        val license: String,
        val version: String,
        val sha256: String,
        val bytes: Long,
        val etag: String? = null,
        val lastModified: String? = null
    )

    private class TooLargeException : RuntimeException()
    private class InvalidContentException : RuntimeException()
    private class ManifestException : RuntimeException()
    private class HttpStatusException : RuntimeException()
    private class StorageException(cause: Throwable? = null) : RuntimeException(cause)

    companion object {
        const val SOURCE_MANIFEST_ASSET = "contentblock/manifest.json"
        const val ENGINE_MANIFEST_ASSET = "contentblock/engine-manifest.json"
        const val ENGINE_VERSION = "adblock-rust-0.13.3"
        const val SOURCE_SCHEMA_VERSION = 1
        const val STALE_AFTER_MILLIS = 7L * 24L * 60L * 60L * 1_000L

        private const val CURRENT_POINTER_FILE = "current.json"
        private const val GENERATIONS_DIRECTORY = "generations"
        private const val GENERATION_MANIFEST_FILE = "generation.json"
        private const val ENGINE_CACHE_DIRECTORY = "engine-cache"
        private const val MAX_LIST_BYTES = 8 * 1024 * 1024
        private const val MAX_BUNDLED_ASSET_BYTES = 16 * 1024 * 1024
        private const val MAX_ENGINE_BYTES = 32 * 1024 * 1024
        private const val MAX_MANIFEST_BYTES = 256 * 1024
        private const val MAX_RULE_LINES = 1_000_000
        private const val MAX_RULE_LINE_BYTES = 64 * 1024
        private const val MAX_VERSION_LENGTH = 64
        private val SAFE_ID = Regex("[a-z][a-z0-9_-]{1,31}")
        private val SAFE_SOURCE_FILE = Regex("source-[a-z][a-z0-9_-]{1,31}\\.txt")
        private val SAFE_GENERATION_NAME = Regex("[A-F0-9]{64}-[0-9]+-[a-f0-9-]{36}")
        private val SAFE_TEMP_GENERATION_NAME = Regex("\\.tmp-${SAFE_GENERATION_NAME.pattern}")
        private val SAFE_ENGINE_CACHE_FILE = Regex(
            "([A-F0-9]{64})-(?:full|context-free)\\.dat"
        )
        private val SHA_256 = Regex("[A-F0-9]{64}")
        private val REQUIRED_SOURCE_IDS = setOf("easylist", "easyprivacy", "surfsave")
        private val REMOTE_SOURCE_IDS = setOf("easylist", "easyprivacy")
        private val JSON = Json {
            ignoreUnknownKeys = false
            encodeDefaults = true
        }

        internal fun cacheKey(
            manifest: FilterManifest,
            sources: List<VerifiedFilterSource>
        ): String {
            val material = buildString {
                append("schema=").append(manifest.schemaVersion).append('\n')
                append("engine=").append(manifest.engineVersion).append('\n')
                append("sources=")
                sources.forEachIndexed { index, source ->
                    if (index > 0) append(',')
                    append(source.manifest.id).append(':').append(source.sha256)
                }
            }
            return sha256(material.toByteArray(Charsets.UTF_8))
        }

        internal fun sha256(bytes: ByteArray): String {
            return MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02X".format(Locale.US, it.toInt() and 0xff) }
        }

        internal fun readLimited(input: InputStream, maximumBytes: Int): ByteArray {
            val output = ByteArrayOutputStream(minOf(maximumBytes, 64 * 1024))
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > maximumBytes) throw TooLargeException()
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }

        internal fun deleteDirectoryIfManagedDirectChild(
            managedRoot: File,
            candidate: File
        ): Boolean {
            return runCatching {
                if (!isManagedDirectChildDirectory(managedRoot, candidate)) return false
                val target = candidate.canonicalFile
                deleteTreeWithin(target, candidate)
            }.getOrDefault(false)
        }

        private fun isManagedDirectChildDirectory(
            managedRoot: File,
            candidate: File
        ): Boolean {
            return runCatching {
                val root = managedRoot.canonicalFile
                val target = candidate.canonicalFile
                root.isDirectory && target.parentFile == root && target.isDirectory
            }.getOrDefault(false)
        }

        private fun deleteTreeWithin(root: File, candidate: File): Boolean {
            val target = candidate.canonicalFile
            if (!isWithinCanonicalRoot(root, target)) return false
            if (candidate.isDirectory) {
                val children = candidate.listFiles() ?: return false
                if (children.any { !deleteTreeWithin(root, it) }) return false
            }
            return !candidate.exists() || candidate.delete()
        }

        private fun isWithinCanonicalRoot(root: File, candidate: File): Boolean {
            var current: File? = candidate
            while (current != null) {
                if (current == root) return true
                current = current.parentFile
            }
            return false
        }

        private fun deleteFileIfManagedDirectChild(
            managedRoot: File,
            candidate: File
        ): Boolean {
            return runCatching {
                val root = managedRoot.canonicalFile
                val target = candidate.canonicalFile
                if (
                    !root.isDirectory ||
                    target.parentFile != root ||
                    !target.isFile ||
                    candidate.isDirectory
                ) {
                    return false
                }
                !candidate.exists() || candidate.delete()
            }.getOrDefault(false)
        }

        private fun isSafeValidator(value: String): Boolean {
            return value.length in 1..512 && value.none { it == '\r' || it == '\n' }
        }
    }
}
