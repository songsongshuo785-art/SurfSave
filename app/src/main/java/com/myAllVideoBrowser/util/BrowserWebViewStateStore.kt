package com.myAllVideoBrowser.util

import android.os.Bundle
import androidx.webkit.WebViewCompat
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** A snapshot read back from disk together with its validated metadata. */
data class LoadedWebViewState(
    val state: Bundle,
    val meta: BrowserWebViewStateMeta
)

/**
 * Durable, per-tab storage for the WebView navigation state (back/forward history).
 *
 * Design notes:
 * - Files live in `noBackupFilesDir`, so they are never part of cloud backup or device transfer,
 *   and they are independent from the browser session preferences (no migration impact).
 * - The payload is a Parcel blob, which Android does not guarantee to be readable across
 *   system/WebView versions. Every read is validated (framing, tab id, counts, CRC) and any
 *   failure discards the file and falls back to a plain URL load.
 * - Writes are serialized on a dedicated single-thread executor and tagged with a per-tab
 *   generation counter, so a queued write can never resurrect a closed tab or overwrite newer
 *   state.
 */
class BrowserWebViewStateStore(
    private val directoryProvider: () -> File,
    private val codec: WebViewStateCodec,
    private val ioExecutor: Executor,
    private val webViewPackageVersionProvider: () -> String? = { null },
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val logger: (String) -> Unit = { AppLogger.d(it) },
    private val errorLogger: (String) -> Unit = { AppLogger.w(it) }
) {

    companion object {
        const val DIRECTORY_NAME = "browser_webview_state"

        /** Largest accepted opaque payload for one tab. */
        const val MAX_SNAPSHOT_BYTES = 1_048_576

        /** Safety net so orphan files can never grow without bound. */
        const val MAX_STORED_FILES = 256

        private const val FILE_SUFFIX = ".sws"
        private const val TEMP_SUFFIX = ".tmp"
        private const val MAX_FILE_NAME_LENGTH = 96
        private const val LOAD_SLACK_BYTES = 64 * 1024

        @Volatile
        private var sharedInstance: BrowserWebViewStateStore? = null

        /** Process-wide store backed by `noBackupFilesDir`. */
        fun shared(): BrowserWebViewStateStore {
            sharedInstance?.let { return it }
            return synchronized(this) {
                sharedInstance ?: BrowserWebViewStateStore(
                    directoryProvider = {
                        File(ContextUtils.getApplicationContext().noBackupFilesDir, DIRECTORY_NAME)
                    },
                    codec = ParcelWebViewStateCodec(),
                    ioExecutor = newIoExecutor(),
                    webViewPackageVersionProvider = ::currentWebViewPackageVersion
                ).also { sharedInstance = it }
            }
        }

        fun fileNameForTab(tabId: String): String {
            val safe = tabId
                .map { character ->
                    if (character.isLetterOrDigit() || character == '-' || character == '_' ||
                        character == '.'
                    ) {
                        character
                    } else {
                        '_'
                    }
                }
                .joinToString("")
                .take(MAX_FILE_NAME_LENGTH)
            return safe + FILE_SUFFIX
        }

        private fun newIoExecutor(): Executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "webview-state-io").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY - 1
            }
        }

        private fun currentWebViewPackageVersion(): String? = runCatching {
            WebViewCompat.getCurrentWebViewPackage(ContextUtils.getApplicationContext())?.versionName
        }.getOrNull()
    }

    private val generations = ConcurrentHashMap<String, Long>()

    /** Monotonic per-tab counter used to order writes against deletions. */
    fun generationOf(tabId: String): Long = generations[tabId] ?: 0L

    /**
     * Persists one tab state. The `Bundle` must have been produced by [android.webkit.WebView.saveState]
     * on the UI thread; encoding happens on the calling thread so the caller can drop its reference
     * immediately. Returns `false` when nothing was queued (caller then relies on a URL load).
     */
    fun saveAsync(
        tabId: String,
        state: Bundle,
        savedUrl: String,
        entryCount: Int,
        currentIndex: Int
    ): Boolean {
        if (tabId.isBlank() || !savedUrl.startsWith("http")) {
            return false
        }
        if (entryCount < 1 || currentIndex !in 0 until entryCount) {
            return false
        }

        val payload = codec.encode(state)
        if (payload == null || payload.isEmpty()) {
            errorLogger("WEBVIEW_STATE_SAVE skipped tab=$tabId: payload could not be encoded")
            return false
        }
        if (payload.size > MAX_SNAPSHOT_BYTES) {
            // Too large to keep. Drop any older snapshot as well: keeping it would restore a
            // stale history that no longer matches the tab.
            errorLogger(
                "WEBVIEW_STATE_SAVE skipped tab=$tabId: payload ${payload.size}B exceeds " +
                    "${MAX_SNAPSHOT_BYTES}B, falling back to URL load"
            )
            delete(tabId)
            return false
        }

        val encoded = try {
            BrowserWebViewStateEnvelope.encode(
                BrowserWebViewStateSnapshot(
                    tabId = tabId,
                    savedUrl = savedUrl,
                    savedAtMillis = clock(),
                    webViewPackageVersion = webViewPackageVersionProvider(),
                    entryCount = entryCount,
                    currentIndex = currentIndex,
                    payload = payload
                )
            )
        } catch (error: Throwable) {
            errorLogger(
                "WEBVIEW_STATE_SAVE skipped tab=$tabId: ${error.javaClass.simpleName}"
            )
            return false
        }

        val generation = generationOf(tabId)
        val fileName = fileNameForTab(tabId)
        ioExecutor.execute {
            if (generationOf(tabId) != generation) {
                logger("WEBVIEW_STATE_SAVE dropped stale write tab=$tabId")
                return@execute
            }
            writeAtomically(tabId, File(directoryProvider(), fileName), encoded)
        }
        return true
    }

    /**
     * Reads and validates the stored snapshot for [tabId]. Returns `null` when nothing usable was
     * found; the damaged/unreadable file is removed in that case.
     *
     * This performs a bounded synchronous file read (validated against [MAX_SNAPSHOT_BYTES]); it is
     * called once per tab when a WebView is (re)created.
     */
    fun load(tabId: String): LoadedWebViewState? {
        if (tabId.isBlank()) {
            return null
        }
        val file = File(directoryProvider(), fileNameForTab(tabId))
        if (!file.isFile) {
            return null
        }
        if (file.length() > (MAX_SNAPSHOT_BYTES + LOAD_SLACK_BYTES).toLong()) {
            errorLogger("WEBVIEW_STATE_LOAD rejected tab=$tabId: file too large (${file.length()}B)")
            delete(tabId)
            return null
        }

        val bytes = try {
            file.readBytes()
        } catch (error: Throwable) {
            errorLogger("WEBVIEW_STATE_LOAD failed tab=$tabId: ${error.javaClass.simpleName}")
            delete(tabId)
            return null
        }

        return when (val decoded = BrowserWebViewStateEnvelope.decode(bytes, tabId)) {
            is BrowserWebViewStateDecodeResult.Rejected -> {
                errorLogger("WEBVIEW_STATE_LOAD rejected tab=$tabId: ${decoded.failure}")
                delete(tabId)
                null
            }

            is BrowserWebViewStateDecodeResult.Decoded -> {
                val snapshot = decoded.snapshot
                val state = codec.decode(snapshot.payload)
                if (state == null) {
                    errorLogger("WEBVIEW_STATE_LOAD rejected tab=$tabId: payload undecodable")
                    delete(tabId)
                    return null
                }
                logger(
                    "WEBVIEW_STATE_LOAD tab=$tabId entries=${snapshot.entryCount} " +
                        "index=${snapshot.currentIndex}"
                )
                LoadedWebViewState(
                    state = state,
                    meta = BrowserWebViewStateMeta(
                        tabId = snapshot.tabId,
                        savedUrl = snapshot.savedUrl,
                        savedAtMillis = snapshot.savedAtMillis,
                        webViewPackageVersion = snapshot.webViewPackageVersion,
                        entryCount = snapshot.entryCount,
                        currentIndex = snapshot.currentIndex
                    )
                )
            }
        }
    }

    /**
     * Drops the stored state for [tabId]. Bumping the generation immediately (not on the executor)
     * guarantees that a write queued earlier can no longer recreate the file.
     */
    fun delete(tabId: String) {
        if (tabId.isBlank()) {
            return
        }
        generations.merge(tabId, 1L, Long::plus)
        val fileName = fileNameForTab(tabId)
        ioExecutor.execute { removeFiles(directoryProvider(), fileName) }
    }

    /** Removes snapshots that no longer belong to an open tab. */
    fun trimToTabs(validTabIds: Set<String>) {
        val expected = validTabIds
            .asSequence()
            .filter { it.isNotBlank() }
            .map { fileNameForTab(it) }
            .toSet()
        ioExecutor.execute {
            val directory = directoryProvider()
            val files = directory.listFiles() ?: return@execute
            var removed = 0
            files.forEach { file ->
                if (!file.isFile) {
                    return@forEach
                }
                val baseName = file.name
                    .removeSuffix(TEMP_SUFFIX)
                    .removeSuffix(".new")
                    .removeSuffix(".bak")
                if (baseName !in expected) {
                    if (file.delete()) {
                        removed++
                    }
                }
            }
            if (removed > 0) {
                logger("WEBVIEW_STATE_TRIM removed $removed orphan file(s)")
            }
            enforceFileBudget(directory)
        }
    }

    fun clearAll() {
        generations.clear()
        ioExecutor.execute {
            directoryProvider().listFiles()?.forEach { file ->
                if (file.isFile) {
                    file.delete()
                }
            }
        }
    }

    /**
     * Writes through a sibling temporary file that is fsynced and then renamed onto the target, so a
     * reader either sees the previous snapshot or the new one, never a partial write.
     */
    private fun writeAtomically(tabId: String, file: File, bytes: ByteArray) {
        try {
            val parent = file.parentFile
            if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
                throw IOException("unable to create ${parent.name}")
            }
            val temporary = File(parent, file.name + TEMP_SUFFIX)
            try {
                FileOutputStream(temporary).use { output ->
                    output.write(bytes)
                    output.fd.sync()
                }
                if (!temporary.renameTo(file) && !replaceByDelete(temporary, file)) {
                    throw IOException("unable to replace ${file.name}")
                }
            } finally {
                if (temporary.exists()) {
                    temporary.delete()
                }
            }
        } catch (error: Throwable) {
            errorLogger(
                "WEBVIEW_STATE_SAVE failed tab=$tabId: ${error.javaClass.simpleName}"
            )
        }
    }

    /** Fallback for filesystems where `renameTo` refuses to replace an existing file. */
    private fun replaceByDelete(temporary: File, target: File): Boolean {
        if (target.exists() && !target.delete()) {
            return false
        }
        return temporary.renameTo(target)
    }

    private fun removeFiles(directory: File, fileName: String) {
        File(directory, fileName).delete()
        File(directory, fileName + TEMP_SUFFIX).delete()
        File(directory, "$fileName.new").delete()
        File(directory, "$fileName.bak").delete()
    }

    private fun enforceFileBudget(directory: File) {
        val files = directory.listFiles()?.filter { it.isFile && it.name.endsWith(FILE_SUFFIX) }
            ?: return
        if (files.size <= MAX_STORED_FILES) {
            return
        }
        files.sortedBy { it.lastModified() }
            .take(files.size - MAX_STORED_FILES)
            .forEach { it.delete() }
        logger("WEBVIEW_STATE_TRIM enforced $MAX_STORED_FILES file budget")
    }
}
