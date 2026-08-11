package com.myAllVideoBrowser.migration

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.myAllVideoBrowser.data.local.room.entity.HistoryItem
import com.myAllVideoBrowser.data.local.room.entity.PageInfo
import com.myAllVideoBrowser.data.local.room.entity.ProgressInfo
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.util.CookieProfileStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.StringReader
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

internal enum class MigrationImportState {
    PREPARED,
    COMMITTING,
    COMMITTED
}

internal data class MigrationImportJournalRecord(
    val state: MigrationImportState,
    val rollbackFileName: String,
    val report: MigrationReport
)

internal data class MigrationRollbackData(
    val bookmarks: List<PageInfo>,
    val history: List<HistoryItem>,
    val videos: List<VideoInfo>,
    val progress: List<ProgressInfo>,
    val settingsPrefs: List<PreferenceEntry>,
    val playbackPrefs: List<PreferenceEntry>,
    val cookieProfiles: CookieProfileStore.StoreSnapshot
)

internal data class LoadedMigrationRollback(
    val data: MigrationRollbackData,
    val thumbnailFiles: Map<String, ByteArray>
)

private data class MigrationRollbackIntegrity(
    val payloads: Map<String, MigrationPayloadDescriptor>
)

internal class MigrationRollbackStore(
    private val rootDirectory: File,
    private val gson: Gson = Gson(),
    private val zipIo: MigrationZipIo = MigrationZipIo()
) {
    companion object {
        private const val ENTRY_DATA = "snapshot.json"
        private const val ENTRY_INTEGRITY = "integrity.json"
        private const val THUMBNAIL_PREFIX = "thumbnails/"
        private val SAFE_SNAPSHOT_NAME = Regex("rollback-[A-Fa-f0-9-]{36}\\.zip")
        private val SAFE_THUMBNAIL_NAME = Regex("[A-Za-z0-9._-]{1,200}\\.jpg")
        private val SHA_256 = Regex("[a-f0-9]{64}")
    }

    fun create(
        data: MigrationRollbackData,
        thumbnailFiles: Map<String, ByteArray>
    ): File {
        ensureRoot()
        val id = UUID.randomUUID().toString()
        val temporary = File(rootDirectory, "rollback-$id.tmp")
        val target = File(rootDirectory, "rollback-$id.zip")
        val payloads = linkedMapOf(ENTRY_DATA to gson.toJson(data).toByteArray(Charsets.UTF_8))
        thumbnailFiles.toSortedMap().forEach { (fileName, bytes) ->
            require(SAFE_THUMBNAIL_NAME.matches(fileName)) {
                "Rollback snapshot contains an unsafe thumbnail name."
            }
            require(bytes.isNotEmpty()) { "Rollback thumbnail cannot be empty." }
            payloads[THUMBNAIL_PREFIX + fileName] = bytes
        }
        val integrity = MigrationRollbackIntegrity(
            payloads.mapValues { (_, bytes) ->
                MigrationPayloadDescriptor(bytes.size.toLong(), sha256(bytes))
            }
        )
        val entries = linkedMapOf(ENTRY_INTEGRITY to gson.toJson(integrity).toByteArray(Charsets.UTF_8))
        entries.putAll(payloads)

        try {
            zipIo.write(temporary, entries)
            load(temporary)
            check(temporary.renameTo(target)) { "Unable to publish migration rollback snapshot." }
            return target
        } catch (error: Throwable) {
            temporary.delete()
            target.delete()
            throw error
        }
    }

    fun load(snapshotFile: File): LoadedMigrationRollback {
        require(snapshotFile.parentFile?.canonicalFile == rootDirectory.canonicalFile) {
            "Migration rollback snapshot escapes its private directory."
        }
        require(
            SAFE_SNAPSHOT_NAME.matches(snapshotFile.name) || snapshotFile.name.endsWith(".tmp")
        ) { "Migration rollback snapshot name is unsafe." }
        val entries = zipIo.read(snapshotFile)
        val integrityBytes = entries[ENTRY_INTEGRITY]
            ?: throw IllegalArgumentException("Migration rollback integrity entry is missing.")
        val integrity = parseStrictJson<MigrationRollbackIntegrity>(
            integrityBytes,
            ENTRY_INTEGRITY
        )
        val actualPayloads = entries.keys - ENTRY_INTEGRITY
        require(integrity.payloads.keys == actualPayloads) {
            "Migration rollback payload set is invalid."
        }
        actualPayloads.forEach { name ->
            require(name == ENTRY_DATA || isThumbnailEntry(name)) {
                "Migration rollback contains an unknown entry: $name."
            }
            val bytes = entries.getValue(name)
            val descriptor = integrity.payloads[name]
                ?: throw IllegalArgumentException("Migration rollback descriptor is missing for $name.")
            require(descriptor.sizeBytes == bytes.size.toLong()) {
                "Migration rollback size does not match for $name."
            }
            require(SHA_256.matches(descriptor.sha256) && descriptor.sha256 == sha256(bytes)) {
                "Migration rollback checksum does not match for $name."
            }
        }
        val dataBytes = entries[ENTRY_DATA]
            ?: throw IllegalArgumentException("Migration rollback data is missing.")
        val parsedData = parseStrictJson<MigrationRollbackData>(dataBytes, ENTRY_DATA)
        val data = parsedData.copy(
            progress = ProgressInfoMigrationNormalizer.normalize(parsedData.progress)
        )
        requireNotNull(data.cookieProfiles) { "Migration rollback Cookie snapshot is missing." }
        val thumbnails = entries
            .filterKeys(::isThumbnailEntry)
            .mapKeys { (name, _) -> name.removePrefix(THUMBNAIL_PREFIX) }
        return LoadedMigrationRollback(data, thumbnails)
    }

    fun resolve(fileName: String): File {
        require(SAFE_SNAPSHOT_NAME.matches(fileName)) { "Migration rollback file name is unsafe." }
        ensureRoot()
        val file = File(rootDirectory, fileName)
        require(file.canonicalFile.parentFile == rootDirectory.canonicalFile) {
            "Migration rollback snapshot escapes its private directory."
        }
        return file
    }

    fun delete(snapshotFile: File) {
        require(snapshotFile.parentFile?.canonicalFile == rootDirectory.canonicalFile) {
            "Migration rollback snapshot escapes its private directory."
        }
        if (snapshotFile.exists()) {
            check(snapshotFile.delete()) { "Unable to remove migration rollback snapshot." }
        }
    }

    private inline fun <reified T> parseStrictJson(bytes: ByteArray, name: String): T {
        require(bytes.isNotEmpty()) { "$name is empty." }
        val reader = JsonReader(StringReader(bytes.toString(Charsets.UTF_8))).apply {
            isLenient = false
        }
        return try {
            val element = JsonParser.parseReader(reader)
            require(element.isJsonObject && reader.peek() == JsonToken.END_DOCUMENT) {
                "$name is invalid."
            }
            gson.fromJson(element, T::class.java)
                ?: throw IllegalArgumentException("$name cannot be null.")
        } catch (error: Exception) {
            throw IllegalArgumentException("$name contains invalid JSON.", error)
        } finally {
            reader.close()
        }
    }

    private fun ensureRoot() {
        check(rootDirectory.exists() || rootDirectory.mkdirs()) {
            "Unable to create private migration directory."
        }
    }

    private fun isThumbnailEntry(name: String): Boolean =
        name.startsWith(THUMBNAIL_PREFIX) &&
            SAFE_THUMBNAIL_NAME.matches(name.removePrefix(THUMBNAIL_PREFIX))

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(Locale.US, byte.toInt() and 0xff) }
}

internal class MigrationImportJournalStore(
    private val rootDirectory: File,
    private val gson: Gson = Gson()
) {
    companion object {
        private const val JOURNAL_FILE_NAME = "import-journal.json"
        private const val MAX_JOURNAL_BYTES = 1024L * 1024L
    }

    private val baseFile = File(rootDirectory, JOURNAL_FILE_NAME)
    private val newFile = File(rootDirectory, "$JOURNAL_FILE_NAME.new")
    private val backupFile = File(rootDirectory, "$JOURNAL_FILE_NAME.bak")

    fun read(): MigrationImportJournalRecord? {
        ensureRoot()
        recoverInterruptedWrite()
        if (!baseFile.exists()) {
            return null
        }
        val bytes = FileInputStream(baseFile).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= MAX_JOURNAL_BYTES) { "Migration import journal is too large." }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val reader = JsonReader(StringReader(bytes.toString(Charsets.UTF_8))).apply {
            isLenient = false
        }
        return try {
            val element = JsonParser.parseReader(reader)
            require(element.isJsonObject && reader.peek() == JsonToken.END_DOCUMENT) {
                "Migration import journal is invalid."
            }
            val record = gson.fromJson(element, MigrationImportJournalRecord::class.java)
                ?: throw IllegalArgumentException("Migration import journal cannot be null.")
            requireNotNull(record.state) { "Migration import journal has no state." }
            require(record.rollbackFileName.isNotBlank()) {
                "Migration import journal has no rollback snapshot."
            }
            requireNotNull(record.report) { "Migration import journal has no report." }
            record
        } catch (error: Exception) {
            throw IllegalStateException("Migration import journal is corrupt.", error)
        } finally {
            reader.close()
        }
    }

    fun prepare(rollbackFileName: String, report: MigrationReport): MigrationImportJournalRecord {
        check(read() == null) { "A migration import journal already exists." }
        return MigrationImportJournalRecord(
            state = MigrationImportState.PREPARED,
            rollbackFileName = rollbackFileName,
            report = report
        ).also(::write)
    }

    fun markCommitting(): MigrationImportJournalRecord = transition(
        expected = MigrationImportState.PREPARED,
        next = MigrationImportState.COMMITTING
    )

    fun markCommitted(): MigrationImportJournalRecord = transition(
        expected = MigrationImportState.COMMITTING,
        next = MigrationImportState.COMMITTED
    )

    fun clear() {
        deleteRequired(newFile)
        deleteRequired(baseFile)
        deleteRequired(backupFile)
    }

    private fun transition(
        expected: MigrationImportState,
        next: MigrationImportState
    ): MigrationImportJournalRecord {
        val current = read() ?: error("Migration import journal is missing.")
        check(current.state == expected) {
            "Invalid migration import journal transition: ${current.state} -> $next."
        }
        return current.copy(state = next).also(::write)
    }

    private fun write(record: MigrationImportJournalRecord) {
        ensureRoot()
        recoverInterruptedWrite()
        deleteRequired(newFile)
        try {
            FileOutputStream(newFile).use { output ->
                val writer = OutputStreamWriter(output, Charsets.UTF_8)
                gson.toJson(record, writer)
                writer.flush()
                output.fd.sync()
            }
            require(newFile.length() <= MAX_JOURNAL_BYTES) {
                "Migration import journal is too large."
            }

            if (baseFile.exists()) {
                check(baseFile.renameTo(backupFile)) {
                    "Unable to back up the migration import journal."
                }
            }
            try {
                check(newFile.renameTo(baseFile)) {
                    "Unable to publish the migration import journal."
                }
                deleteRequired(backupFile)
            } catch (error: Throwable) {
                restoreBackup(error)
                throw error
            }
        } catch (error: Throwable) {
            newFile.delete()
            throw error
        }
    }

    private fun recoverInterruptedWrite() {
        if (backupFile.exists()) {
            deleteRequired(baseFile)
            check(backupFile.renameTo(baseFile)) {
                "Unable to restore the migration import journal backup."
            }
        }
        deleteRequired(newFile)
    }

    private fun restoreBackup(originalError: Throwable) {
        if (!backupFile.exists()) return
        if (baseFile.exists() && !baseFile.delete()) {
            originalError.addSuppressed(
                IllegalStateException("Unable to discard the failed migration journal publish.")
            )
            return
        }
        if (!backupFile.renameTo(baseFile)) {
            originalError.addSuppressed(
                IllegalStateException("Unable to restore the migration journal after publish failure.")
            )
        }
    }

    private fun deleteRequired(file: File) {
        check(!file.exists() || file.delete()) {
            "Unable to delete migration journal file: ${file.name}."
        }
    }

    private fun ensureRoot() {
        check(rootDirectory.exists() || rootDirectory.mkdirs()) {
            "Unable to create private migration directory."
        }
    }
}

internal class MigrationCommitCoordinator(
    private val journalStore: MigrationImportJournalStore
) {
    fun execute(
        rollbackFileName: String,
        report: MigrationReport,
        commit: () -> Unit,
        rollback: (MigrationImportJournalRecord) -> Unit,
        afterCommit: (MigrationImportJournalRecord) -> Unit,
        cleanup: (MigrationImportJournalRecord) -> Unit
    ) {
        journalStore.prepare(rollbackFileName, report)
        try {
            journalStore.markCommitting()
            commit()
            val committed = journalStore.markCommitted()
            afterCommit(committed)
            cleanup(committed)
            journalStore.clear()
        } catch (error: Throwable) {
            val current = journalStore.read()
            if (current?.state == MigrationImportState.COMMITTING) {
                try {
                    rollback(current)
                    cleanup(current)
                    journalStore.clear()
                } catch (rollbackError: Throwable) {
                    error.addSuppressed(rollbackError)
                }
            } else if (current?.state == MigrationImportState.PREPARED) {
                cleanup(current)
                journalStore.clear()
            }
            throw error
        }
    }

    fun recover(
        rollback: (MigrationImportJournalRecord) -> Unit,
        afterCommit: (MigrationImportJournalRecord) -> Unit,
        cleanup: (MigrationImportJournalRecord) -> Unit
    ): MigrationImportState? {
        val current = journalStore.read() ?: return null
        when (current.state) {
            MigrationImportState.PREPARED -> Unit
            MigrationImportState.COMMITTING -> rollback(current)
            MigrationImportState.COMMITTED -> afterCommit(current)
        }
        cleanup(current)
        journalStore.clear()
        return current.state
    }
}
