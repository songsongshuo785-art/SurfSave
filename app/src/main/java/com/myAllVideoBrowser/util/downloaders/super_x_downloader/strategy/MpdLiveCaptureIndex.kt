package com.myAllVideoBrowser.util.downloaders.super_x_downloader.strategy

import com.myAllVideoBrowser.util.AppLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale

internal object MpdLiveCaptureIndex {
    private const val MANIFEST_NAME = "mpd_capture.json"
    private const val MANIFEST_VERSION = 1

    enum class Stream(val jsonKey: String, val filePrefix: String) {
        VIDEO("video", "segment_"),
        AUDIO("audio", "audio_segment_")
    }

    data class Entry(
        val sequence: Long,
        val url: String?,
        val fileName: String
    )

    data class Snapshot(
        val video: List<Entry> = emptyList(),
        val audio: List<Entry> = emptyList()
    ) {
        fun entries(stream: Stream): List<Entry> = when (stream) {
            Stream.VIDEO -> video
            Stream.AUDIO -> audio
        }

        fun withEntries(stream: Stream, entries: List<Entry>): Snapshot = when (stream) {
            Stream.VIDEO -> copy(video = entries)
            Stream.AUDIO -> copy(audio = entries)
        }
    }

    fun loadOrMigrate(downloadDir: File): Snapshot {
        ensureDirectory(downloadDir)
        val manifest = downloadDir.resolve(MANIFEST_NAME)
        restoreCommittedBackupIfNeeded(manifest)
        if (manifest.exists()) {
            if (!manifest.isFile || manifest.length() <= 0L) {
                throw IOException("MPD capture index is missing or empty: ${manifest.absolutePath}")
            }
            return decode(manifest)
        }

        val migrated = Snapshot(
            video = legacyEntries(downloadDir, Stream.VIDEO),
            audio = legacyEntries(downloadDir, Stream.AUDIO)
        )
        publishSnapshot(downloadDir, migrated)
        return migrated
    }

    fun containsUrl(snapshot: Snapshot, stream: Stream, url: String): Boolean {
        return snapshot.entries(stream).any { it.url == url }
    }

    fun nextEntry(snapshot: Snapshot, stream: Stream, url: String): Entry {
        if (url.isBlank()) {
            throw IOException("MPD capture segment URL cannot be blank.")
        }
        if (containsUrl(snapshot, stream, url)) {
            throw IOException("MPD capture already contains URL: $url")
        }
        val sequence = try {
            Math.addExact(snapshot.entries(stream).lastOrNull()?.sequence ?: -1L, 1L)
        } catch (error: ArithmeticException) {
            throw IOException("MPD capture sequence overflow.", error)
        }
        return Entry(
            sequence = sequence,
            url = url,
            fileName = stream.filePrefix + String.format(Locale.US, "%010d", sequence) + ".m4s"
        )
    }

    fun publishEntry(
        downloadDir: File,
        snapshot: Snapshot,
        stream: Stream,
        entry: Entry
    ): Snapshot {
        val expectedSequence = snapshot.entries(stream).size.toLong()
        if (entry.sequence != expectedSequence) {
            throw IOException(
                "MPD capture sequence must be contiguous: expected $expectedSequence, got ${entry.sequence}."
            )
        }
        if (entry.url == null || entry.url.isBlank()) {
            throw IOException("New MPD capture entries must include their source URL.")
        }
        if (containsUrl(snapshot, stream, entry.url)) {
            throw IOException("MPD capture already contains URL: ${entry.url}")
        }
        validateEntry(entry, stream)
        requireCompleteFile(downloadDir.resolve(entry.fileName), "MPD capture segment")

        val updated = snapshot.withEntries(stream, snapshot.entries(stream) + entry)
        validateSnapshot(updated)
        publishSnapshot(downloadDir, updated)
        return updated
    }

    fun filesInOrder(downloadDir: File, snapshot: Snapshot, stream: Stream): List<File> {
        validateSnapshot(snapshot)
        return snapshot.entries(stream)
            .sortedBy { it.sequence }
            .map { entry ->
                downloadDir.resolve(entry.fileName).also { file ->
                    requireCompleteFile(file, "Indexed MPD ${stream.jsonKey} segment")
                }
            }
    }

    private fun legacyEntries(downloadDir: File, stream: Stream): List<Entry> {
        val legacyFiles = downloadDir.listFiles { file ->
            file.isFile && file.name.startsWith(stream.filePrefix) &&
                file.name.endsWith(".m4s") && file.length() > 0L
        }?.sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name }) ?: emptyList()

        if (legacyFiles.isNotEmpty()) {
            AppLogger.w(
                "MPD live: Migrating ${legacyFiles.size} legacy ${stream.jsonKey} segments to an explicit order index."
            )
        }
        return legacyFiles.mapIndexed { index, file ->
            Entry(sequence = index.toLong(), url = null, fileName = file.name)
        }
    }

    private fun decode(manifest: File): Snapshot {
        try {
            val root = JSONObject(manifest.readText(Charsets.UTF_8))
            val version = root.getInt("version")
            if (version != MANIFEST_VERSION) {
                throw IOException("Unsupported MPD capture index version: $version")
            }
            val snapshot = Snapshot(
                video = decodeEntries(root.getJSONArray(Stream.VIDEO.jsonKey), Stream.VIDEO),
                audio = decodeEntries(root.getJSONArray(Stream.AUDIO.jsonKey), Stream.AUDIO)
            )
            validateSnapshot(snapshot)
            return snapshot
        } catch (error: Exception) {
            if (error is IOException) throw error
            throw IOException("Failed to read MPD capture index: ${error.message}", error)
        }
    }

    private fun decodeEntries(array: JSONArray, stream: Stream): List<Entry> {
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    Entry(
                        sequence = item.getLong("sequence"),
                        url = if (item.isNull("url")) null else item.getString("url"),
                        fileName = item.getString("file")
                    ).also { validateEntry(it, stream) }
                )
            }
        }.sortedBy { it.sequence }
    }

    private fun publishSnapshot(downloadDir: File, snapshot: Snapshot) {
        validateSnapshot(snapshot)
        val root = JSONObject()
            .put("version", MANIFEST_VERSION)
            .put(Stream.VIDEO.jsonKey, encodeEntries(snapshot.video))
            .put(Stream.AUDIO.jsonKey, encodeEntries(snapshot.audio))
        publishUtf8Atomically(downloadDir.resolve(MANIFEST_NAME), root.toString())
    }

    private fun encodeEntries(entries: List<Entry>): JSONArray {
        return JSONArray().apply {
            entries.sortedBy { it.sequence }.forEach { entry ->
                put(
                    JSONObject()
                        .put("sequence", entry.sequence)
                        .put("url", entry.url ?: JSONObject.NULL)
                        .put("file", entry.fileName)
                )
            }
        }
    }

    private fun validateSnapshot(snapshot: Snapshot) {
        Stream.entries.forEach { stream ->
            val entries = snapshot.entries(stream).sortedBy { it.sequence }
            entries.forEachIndexed { index, entry ->
                validateEntry(entry, stream)
                if (entry.sequence != index.toLong()) {
                    throw IOException(
                        "MPD ${stream.jsonKey} capture sequence is not contiguous at index $index."
                    )
                }
            }
            val urls = entries.mapNotNull { it.url }
            if (urls.size != urls.toSet().size) {
                throw IOException("MPD ${stream.jsonKey} capture index contains duplicate URLs.")
            }
            val fileNames = entries.map { it.fileName }
            if (fileNames.size != fileNames.toSet().size) {
                throw IOException("MPD ${stream.jsonKey} capture index contains duplicate files.")
            }
        }
        val allFiles = snapshot.video.map { it.fileName } + snapshot.audio.map { it.fileName }
        if (allFiles.size != allFiles.toSet().size) {
            throw IOException("MPD capture index reuses a file across streams.")
        }
    }

    private fun validateEntry(entry: Entry, stream: Stream) {
        if (entry.sequence < 0L) {
            throw IOException("MPD capture sequence cannot be negative.")
        }
        if (entry.url != null && entry.url.isBlank()) {
            throw IOException("MPD capture URL cannot be blank.")
        }
        val fileName = entry.fileName
        if (fileName.isBlank() || File(fileName).name != fileName ||
            !fileName.startsWith(stream.filePrefix) || !fileName.endsWith(".m4s")
        ) {
            throw IOException("Invalid MPD capture file name: $fileName")
        }
    }

    private fun requireCompleteFile(file: File, label: String) {
        if (!file.isFile || file.length() <= 0L) {
            throw IOException("$label is missing or empty: ${file.absolutePath}")
        }
    }

    private fun ensureDirectory(directory: File) {
        if ((!directory.exists() && !directory.mkdirs()) || !directory.isDirectory) {
            throw IOException("Unable to create MPD capture directory: ${directory.absolutePath}")
        }
    }

    private fun restoreCommittedBackupIfNeeded(manifest: File) {
        if (manifest.exists()) return
        val backup = File(manifest.parentFile, "${manifest.name}.bak")
        if (!backup.exists()) return
        if (!backup.isFile || backup.length() <= 0L) {
            throw IOException("MPD capture index backup is missing or empty: ${backup.absolutePath}")
        }
        if (!backup.renameTo(manifest)) {
            throw IOException("Unable to restore the committed MPD capture index backup.")
        }
        AppLogger.w("MPD live: Restored the committed capture index after an interrupted publish.")
    }

    private fun publishUtf8Atomically(target: File, content: String) {
        val parent = target.parentFile
            ?: throw IOException("MPD capture index has no parent: ${target.absolutePath}")
        ensureDirectory(parent)
        val staging = File(parent, "${target.name}.tmp")
        val backup = File(parent, "${target.name}.bak")
        try {
            if (staging.exists() && !staging.delete()) {
                throw IOException("Unable to clear stale MPD capture index staging file.")
            }
            staging.writeText(content, Charsets.UTF_8)
            if (backup.exists() && !backup.delete()) {
                throw IOException("Unable to clear stale MPD capture index backup.")
            }
            if (target.exists() && !target.renameTo(backup)) {
                throw IOException("Unable to stage the previous MPD capture index.")
            }
            if (!staging.renameTo(target)) {
                if (backup.exists() && !backup.renameTo(target)) {
                    throw IOException("Unable to publish or restore the MPD capture index.")
                }
                throw IOException("Unable to publish the MPD capture index.")
            }
            if (backup.exists() && !backup.delete()) {
                AppLogger.w("MPD live: Unable to delete the obsolete capture index backup.")
            }
        } catch (error: Exception) {
            if (staging.exists() && !staging.delete()) {
                error.addSuppressed(IOException("Unable to remove MPD capture index staging file."))
            }
            if (!target.exists() && backup.exists() && !backup.renameTo(target)) {
                error.addSuppressed(IOException("Unable to restore the previous MPD capture index."))
            }
            throw IOException("Failed to publish MPD capture index: ${error.message}", error)
        }
    }
}
