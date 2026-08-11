package com.myAllVideoBrowser.migration

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.system.Os
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import com.google.gson.Gson
import com.myAllVideoBrowser.BuildConfig
import com.myAllVideoBrowser.data.local.room.AppDatabase
import com.myAllVideoBrowser.data.local.room.dao.HistoryDao
import com.myAllVideoBrowser.data.local.room.dao.PageDao
import com.myAllVideoBrowser.data.local.room.dao.ProgressDao
import com.myAllVideoBrowser.data.local.room.dao.VideoDao
import com.myAllVideoBrowser.data.local.room.entity.HistoryItem
import com.myAllVideoBrowser.data.local.room.entity.PageInfo
import com.myAllVideoBrowser.data.local.room.entity.ProgressInfo
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.data.repository.PlaybackStateRepository
import com.myAllVideoBrowser.di.qualifier.ApplicationContext
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.BrowserThumbnailStore
import com.myAllVideoBrowser.util.CookieProfileStore
import com.myAllVideoBrowser.util.FileUtil
import com.myAllVideoBrowser.util.SharedPrefHelper
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskState
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MigrationManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val appDatabase: AppDatabase,
    private val pageDao: PageDao,
    private val historyDao: HistoryDao,
    private val videoDao: VideoDao,
    private val progressDao: ProgressDao,
    private val sharedPrefHelper: SharedPrefHelper,
    private val fileUtil: FileUtil,
    private val cookieProfileStore: CookieProfileStore,
    private val stateStore: MigrationStateStore
) {
    companion object {
        private const val EXPORT_FILE_NAME = "surfsave-migration-package.zip"
        private const val EXPORT_MIME_TYPE = "application/zip"
        private const val EXPORT_SUBDIRECTORY = "SurfSave"
        private const val PREF_BROWSER_SESSION_TABS = "BROWSER_SESSION_TABS"
        private const val PREF_BROWSER_SESSION_CURRENT_INDEX = "BROWSER_SESSION_CURRENT_INDEX"
        private const val MAX_THUMBNAIL_BYTES = 8L * 1024L * 1024L
        private const val MAX_ALL_THUMBNAILS_BYTES = 64L * 1024L * 1024L
        private val SAFE_THUMBNAIL_FILE = Regex("[A-Za-z0-9._-]{1,200}\\.jpg")
    }

    private val gson = Gson()
    private val archiveCodec = MigrationArchiveCodec(gson)
    private val privacySanitizer = MigrationPrivacySanitizer(gson)
    private val migrationRoot = File(context.noBackupFilesDir, "migration")
    private val rollbackStore = MigrationRollbackStore(migrationRoot, gson)
    private val journalStore = MigrationImportJournalStore(migrationRoot, gson)
    private val commitCoordinator = MigrationCommitCoordinator(journalStore)

    @Synchronized
    fun recoverInterruptedImport() {
        val recoveredState = commitCoordinator.recover(
            rollback = ::restoreFromJournal,
            afterCommit = ::finishCommittedJournal,
            cleanup = ::cleanupJournalSnapshot
        )
        if (recoveredState != null) {
            AppLogger.w("Recovered migration import journal in state $recoveredState.")
        }
    }

    @Synchronized
    fun getOverview(): MigrationOverview {
        recoverInterruptedImport()
        val lastReport = stateStore.getLastReport()
        return MigrationOverview(
            migrationRole = BuildConfig.MIGRATION_ROLE,
            exportEnabled = BuildConfig.MIGRATION_EXPORT_ENABLED,
            importEnabled = BuildConfig.MIGRATION_IMPORT_ENABLED,
            stage = stateStore.getStage(),
            packageInfo = locateMigrationPackage() ?: lastReport?.packageInfo,
            companionPackage = BuildConfig.MIGRATION_COMPANION_PACKAGE,
            companionPackageInstalled = isPackageInstalled(BuildConfig.MIGRATION_COMPANION_PACKAGE),
            privateVideoSummary = inspectPrivateVideos(),
            lastReport = lastReport
        )
    }

    @Synchronized
    fun exportMigrationPackage(includeCookieContents: Boolean = false): MigrationReport {
        check(BuildConfig.MIGRATION_EXPORT_ENABLED) { "Migration export is disabled for this build." }
        recoverInterruptedImport()
        ensureMigrationRoot()

        val database = captureDatabaseSnapshot()
        val completedProgress = database.progress.filter { it.downloadStatus == VideoTaskState.SUCCESS }
        val originalTabs = sharedPrefHelper.getSavedBrowserSessionTabs()
        val browserSession = privacySanitizer.sanitizeBrowserSession(
            BrowserSessionSnapshot(
                tabs = originalTabs,
                currentIndex = sharedPrefHelper.restoreBrowserSessionCurrentIndex()
            )
        )
        val thumbnailBytes = snapshotExportThumbnails(originalTabs)
        val cookieProfiles = privacySanitizer.sanitizeCookieProfiles(
            cookieProfileStore.snapshot(includeCookieContents),
            includeCookieContents
        )
        val settingsPrefs = privacySanitizer.sanitizeSettingsPreferences(
            snapshotSharedPreferences(SharedPrefHelper.PREF_KEY)
        )
        val playbackPrefs = privacySanitizer.sanitizePlaybackPreferences(
            snapshotSharedPreferences(PlaybackStateRepository.PREFS_NAME)
        )
        val bookmarks = privacySanitizer.sanitizeBookmarks(database.bookmarks)
        val history = privacySanitizer.sanitizeHistory(database.history)
        val videos = privacySanitizer.sanitizeVideos(database.videos)
        val progress = privacySanitizer.sanitizeProgress(completedProgress)

        val manifest = MigrationManifest(
            schemaVersion = MigrationArchiveCodec.SCHEMA_V2,
            exportedAtEpochMs = System.currentTimeMillis(),
            exportedByPackage = context.packageName,
            exportedByRole = BuildConfig.MIGRATION_ROLE,
            appVersionName = BuildConfig.VERSION_NAME,
            bookmarkCount = bookmarks.size,
            historyCount = history.size,
            videoCount = videos.size,
            progressCount = progress.size,
            browserSessionCount = browserSession.tabs.size,
            thumbnailCount = thumbnailBytes.size,
            cookieProfileCount = cookieProfiles.size,
            cookieContentIncluded = cookieProfiles.any { it.content != null },
            encryption = MigrationArchiveCodec.ENCRYPTION_NONE
        )
        val archive = MigrationArchive(
            manifest = manifest,
            settingsPrefs = settingsPrefs,
            playbackPrefs = playbackPrefs,
            bookmarks = bookmarks,
            history = history,
            videos = videos,
            progress = progress,
            browserSession = browserSession,
            cookieProfiles = cookieProfiles
        )

        val stagingFile = File.createTempFile("migration-export-", ".zip", migrationRoot)
        val validated = try {
            archiveCodec.writeV2(stagingFile, archive, thumbnailBytes)
        } catch (error: Throwable) {
            stagingFile.delete()
            throw error
        }
        val packageInfo = try {
            publishExport(stagingFile)
        } finally {
            stagingFile.delete()
        }

        val privateVideos = inspectPrivateVideos()
        val nextStage = if (
            BuildConfig.MIGRATION_IMPORT_ENABLED &&
            stateStore.getStage() == MigrationStage.IMPORTED
        ) {
            MigrationStage.IMPORTED
        } else {
            MigrationStage.EXPORT_READY
        }
        val report = MigrationReport(
            stage = nextStage,
            generatedAtEpochMs = System.currentTimeMillis(),
            packageInfo = packageInfo,
            bookmarkCount = validated.archive.bookmarks.size,
            historyCount = validated.archive.history.size,
            videoCount = validated.archive.videos.size,
            progressCount = validated.archive.progress.size,
            browserSessionCount = validated.archive.browserSession.tabs.size,
            thumbnailCount = validated.thumbnailsByTabId.size,
            cookieProfileCount = validated.archive.cookieProfiles.size,
            cookieContentIncluded = validated.archive.manifest.cookieContentIncluded,
            privateVideoCount = privateVideos.count,
            privateVideoBytes = privateVideos.totalBytes,
            notes = listOf(
                "Only completed video metadata is migrated. Active downloads are excluded.",
                if (validated.archive.manifest.cookieContentIncluded) {
                    "Cookie profiles were included because the user explicitly enabled cookie export."
                } else {
                    "Cookie profile metadata is listed, but cookie contents are excluded by default."
                },
                "Proxy credentials, generated credentials, authenticated request bodies/headers, and sensitive URL parameters were excluded.",
                "The migration package uses schema v2 with encryption=none; WebView login state is not included."
            )
        )
        if (nextStage == MigrationStage.IMPORTED) {
            stateStore.markImported(report)
        } else {
            stateStore.markExportReady(report)
        }
        return report
    }

    @Synchronized
    fun importMigrationPackage(packageUri: Uri? = null): MigrationReport {
        check(BuildConfig.MIGRATION_IMPORT_ENABLED) { "Migration import is disabled for this build." }
        recoverInterruptedImport()
        ensureMigrationRoot()

        val packageInfo = if (packageUri != null) {
            resolvePackageInfo(packageUri)
                ?: error("The selected migration package cannot be opened.")
        } else {
            locateMigrationPackage()
                ?: error("No migration package was found in Downloads/SurfSave.")
        }
        val stagedInput = stageInputPackage(packageInfo)
        try {
            val validated = archiveCodec.read(stagedInput)
            validateImportedThumbnailLimits(validated.thumbnailsByTabId)
            val archive = validated.archive
            val privateVideos = inspectPrivateVideos()
            val restoredCookieCount = archive.cookieProfiles.count { !it.content.isNullOrBlank() }
            val report = MigrationReport(
                stage = MigrationStage.IMPORTED,
                generatedAtEpochMs = System.currentTimeMillis(),
                packageInfo = packageInfo,
                bookmarkCount = archive.bookmarks.size,
                historyCount = archive.history.size,
                videoCount = archive.videos.size,
                progressCount = archive.progress.size,
                browserSessionCount = archive.browserSession.tabs.size,
                thumbnailCount = validated.thumbnailsByTabId.size,
                cookieProfileCount = restoredCookieCount,
                cookieContentIncluded = archive.manifest.cookieContentIncluded,
                privateVideoCount = privateVideos.count,
                privateVideoBytes = privateVideos.totalBytes,
                notes = listOf(
                    "Imported data is written into the current app identity without deleting the migration package.",
                    if (restoredCookieCount > 0) {
                        "Cookie profiles with explicitly exported contents were restored."
                    } else {
                        "Cookie contents were not restored because the package contains metadata only."
                    },
                    "Please verify videos, bookmarks, settings, and open tabs before removing the previous app."
                )
            )

            val rollbackData = captureRollbackData()
            val rollbackThumbnails = snapshotThumbnailDirectory()
            val rollbackFile = rollbackStore.create(rollbackData, rollbackThumbnails)
            try {
                commitCoordinator.execute(
                    rollbackFileName = rollbackFile.name,
                    report = report,
                    commit = { applyValidatedPackage(validated) },
                    rollback = ::restoreFromJournal,
                    afterCommit = ::finishCommittedJournal,
                    cleanup = ::cleanupJournalSnapshot
                )
            } finally {
                if (journalStore.read() == null && rollbackFile.exists()) {
                    rollbackStore.delete(rollbackFile)
                }
            }
            return report
        } finally {
            stagedInput.delete()
        }
    }

    @Synchronized
    fun inspectPrivateVideos(): PrivateVideoSummary {
        val entries = privateVideoEntries()
        return PrivateVideoSummary(entries.size, entries.sumOf { it.sizeBytes })
    }

    @Synchronized
    fun movePrivateVideosToSharedDownloads(): PrivateVideoMoveResult {
        recoverInterruptedImport()
        val entries = privateVideoEntries()
        if (entries.isEmpty()) {
            return PrivateVideoMoveResult(remainingPrivateVideos = PrivateVideoSummary())
        }

        val publicDownloadsDir = fileUtil.publicDownloadsDir
        check(fileUtil.ensurePublicDownloadDestination()) {
            "Unable to access the managed public Downloads directory."
        }
        var movedCount = 0
        var failedCount = 0
        var movedBytes = 0L
        entries.forEach { entry ->
            val target = uniquePublicDownloadTarget(publicDownloadsDir, entry.fileName)
            val moved = runCatching {
                fileUtil.moveMedia(context, entry.uri, Uri.fromFile(target))
            }.getOrElse { error ->
                AppLogger.e("Failed to move private video ${entry.fileName}: ${error.message}")
                false
            }
            if (moved) {
                movedCount += 1
                movedBytes += entry.sizeBytes
            } else {
                failedCount += 1
            }
        }
        return PrivateVideoMoveResult(
            movedCount,
            failedCount,
            movedBytes,
            inspectPrivateVideos()
        )
    }

    fun locateMigrationPackage(): MigrationPackageInfo? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.DISPLAY_NAME
            )
            val selection =
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND " +
                    "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND " +
                    "${MediaStore.MediaColumns.IS_PENDING} = 0"
            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                arrayOf(EXPORT_FILE_NAME, standardRelativePath()),
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                if (!cursor.moveToFirst()) {
                    return@use null
                }
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                val size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE))
                val relativePath =
                    cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH))
                val displayName =
                    cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME))
                MigrationPackageInfo(
                    uriString = ContentUris.withAppendedId(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        id
                    ).toString(),
                    displayPath = "${relativePath.orEmpty()}$displayName",
                    sizeBytes = size
                )
            }
        } else {
            val file = standardLegacyFile()
            if (!file.isFile) {
                null
            } else {
                MigrationPackageInfo(file.toUri().toString(), file.absolutePath, file.length())
            }
        }
    }

    @Synchronized
    fun deleteMigrationPackage(): Boolean {
        recoverInterruptedImport()
        val packageInfo = locateMigrationPackage()
            ?: stateStore.getLastReport()?.packageInfo
            ?: return false
        val deleted = deleteByUri(packageInfo.uriString.toUri())
        if (deleted) {
            stateStore.clearLastReportPackageInfo()
        }
        return deleted
    }

    fun canAutoLocateMigrationPackage(): Boolean = locateMigrationPackage() != null

    private fun applyValidatedPackage(validated: ValidatedMigrationPackage) {
        val archive = validated.archive
        val thumbnailFiles = validated.thumbnailsByTabId.mapKeys { (tabId, _) -> "$tabId.jpg" }
        appDatabase.runInTransaction {
            replaceDatabase(
                archive.bookmarks,
                archive.history,
                archive.videos,
                archive.progress
            )
            cookieProfileStore.replaceFromMigration(archive.cookieProfiles)
            val importedPaths = replaceThumbnailDirectory(thumbnailFiles)
            val importedTabs = archive.browserSession.tabs.map { tab ->
                tab.copy(thumbnailPath = importedPaths["${tab.id}.jpg"])
            }
            replaceSharedPreferences(
                SharedPrefHelper.PREF_KEY,
                withBrowserSessionPreferences(
                    archive.settingsPrefs,
                    importedTabs,
                    archive.browserSession.currentIndex
                )
            )
            replaceSharedPreferences(PlaybackStateRepository.PREFS_NAME, archive.playbackPrefs)
        }
    }

    private fun restoreFromJournal(record: MigrationImportJournalRecord) {
        val snapshot = rollbackStore.load(rollbackStore.resolve(record.rollbackFileName))
        appDatabase.runInTransaction {
            replaceDatabase(
                snapshot.data.bookmarks,
                snapshot.data.history,
                snapshot.data.videos,
                snapshot.data.progress
            )
            cookieProfileStore.restoreRollbackSnapshot(snapshot.data.cookieProfiles)
            replaceThumbnailDirectory(snapshot.thumbnailFiles)
            replaceSharedPreferences(SharedPrefHelper.PREF_KEY, snapshot.data.settingsPrefs)
            replaceSharedPreferences(PlaybackStateRepository.PREFS_NAME, snapshot.data.playbackPrefs)
        }
        applyStorageFlagsFromPreferences()
    }

    private fun finishCommittedJournal(record: MigrationImportJournalRecord) {
        stateStore.markImported(record.report)
        applyStorageFlagsFromPreferences()
    }

    private fun cleanupJournalSnapshot(record: MigrationImportJournalRecord) {
        rollbackStore.delete(rollbackStore.resolve(record.rollbackFileName))
    }

    private fun captureRollbackData(): MigrationRollbackData {
        val database = captureDatabaseSnapshot()
        return MigrationRollbackData(
            bookmarks = database.bookmarks,
            history = database.history,
            videos = database.videos,
            progress = database.progress,
            settingsPrefs = snapshotSharedPreferences(SharedPrefHelper.PREF_KEY),
            playbackPrefs = snapshotSharedPreferences(PlaybackStateRepository.PREFS_NAME),
            cookieProfiles = cookieProfileStore.createRollbackSnapshot()
        )
    }

    private fun captureDatabaseSnapshot(): DatabaseSnapshot {
        var snapshot: DatabaseSnapshot? = null
        appDatabase.runInTransaction {
            snapshot = DatabaseSnapshot(
                bookmarks = pageDao.getPageInfos().blockingFirst(emptyList()),
                history = historyDao.getAllHistoryItems(),
                videos = videoDao.getAllVideos(),
                progress = progressDao.getAllProgressInfos()
            )
        }
        return checkNotNull(snapshot) { "Unable to capture database migration snapshot." }
    }

    private fun replaceDatabase(
        bookmarks: List<PageInfo>,
        history: List<HistoryItem>,
        videos: List<VideoInfo>,
        progress: List<ProgressInfo>
    ) {
        pageDao.deleteAll()
        if (bookmarks.isNotEmpty()) pageDao.insertAllProgressInfo(bookmarks)
        historyDao.clear()
        if (history.isNotEmpty()) historyDao.insertAll(history)
        videoDao.clear()
        if (videos.isNotEmpty()) videoDao.insertAll(videos)
        val normalizedProgress = ProgressInfoMigrationNormalizer.normalize(progress)
        progressDao.clear()
        if (normalizedProgress.isNotEmpty()) progressDao.insertAllProgressInfo(normalizedProgress)
    }

    private fun snapshotSharedPreferences(prefName: String): List<PreferenceEntry> {
        val preferences = context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
        return preferences.all.entries.map { entry ->
            val key = entry.key
            when (val value = entry.value) {
                is String -> PreferenceEntry(key, "string", stringValue = value)
                is Int -> PreferenceEntry(key, "int", intValue = value)
                is Long -> PreferenceEntry(key, "long", longValue = value)
                is Float -> PreferenceEntry(key, "float", floatValue = value)
                is Boolean -> PreferenceEntry(key, "boolean", booleanValue = value)
                is Set<*> -> PreferenceEntry(
                    key,
                    "string_set",
                    stringSetValue = value.map { item ->
                        requireNotNull(item) { "Shared preference $key contains a null set item." }
                        item.toString()
                    }.toSet()
                )
                else -> throw IllegalStateException(
                    "Shared preference $key has an unsupported value type."
                )
            }
        }.sortedBy { it.key.lowercase() }
    }

    private fun replaceSharedPreferences(prefName: String, entries: List<PreferenceEntry>) {
        val editor = context.getSharedPreferences(prefName, Context.MODE_PRIVATE).edit().clear()
        entries.forEach { entry ->
            when (entry.valueType) {
                "string" -> editor.putString(entry.key, requireNotNull(entry.stringValue))
                "int" -> editor.putInt(entry.key, requireNotNull(entry.intValue))
                "long" -> editor.putLong(entry.key, requireNotNull(entry.longValue))
                "float" -> editor.putFloat(entry.key, requireNotNull(entry.floatValue))
                "boolean" -> editor.putBoolean(entry.key, requireNotNull(entry.booleanValue))
                "string_set" -> editor.putStringSet(entry.key, requireNotNull(entry.stringSetValue))
                else -> throw IllegalArgumentException(
                    "Unsupported migration preference type: ${entry.valueType}."
                )
            }
        }
        check(editor.commit()) { "Unable to commit $prefName during migration." }
    }

    private fun withBrowserSessionPreferences(
        settings: List<PreferenceEntry>,
        tabs: List<SharedPrefHelper.BrowserSessionTab>,
        currentIndex: Int
    ): List<PreferenceEntry> {
        return settings.filterNot { entry ->
            entry.key == PREF_BROWSER_SESSION_TABS ||
                entry.key == PREF_BROWSER_SESSION_CURRENT_INDEX
        } + listOf(
            PreferenceEntry(
                key = PREF_BROWSER_SESSION_TABS,
                valueType = "string",
                stringValue = gson.toJson(tabs)
            ),
            PreferenceEntry(
                key = PREF_BROWSER_SESSION_CURRENT_INDEX,
                valueType = "int",
                intValue = currentIndex
            )
        )
    }

    private fun snapshotExportThumbnails(
        tabs: List<SharedPrefHelper.BrowserSessionTab>
    ): Map<String, ByteArray> {
        val thumbnailRoot = BrowserThumbnailStore.directory().canonicalFile
        val result = linkedMapOf<String, ByteArray>()
        var total = 0L
        tabs.forEach { tab ->
            val path = tab.thumbnailPath ?: return@forEach
            val file = File(path)
            if (!file.isFile || file.canonicalFile.parentFile != thumbnailRoot) {
                AppLogger.w("Skipped a browser thumbnail outside the private thumbnail directory.")
                return@forEach
            }
            val bytes = readFileLimited(file, MAX_THUMBNAIL_BYTES)
            total += bytes.size
            require(total <= MAX_ALL_THUMBNAILS_BYTES) {
                "Browser thumbnails exceed the migration size limit."
            }
            if (bytes.isNotEmpty()) {
                result[tab.id] = bytes
            }
        }
        return result
    }

    private fun snapshotThumbnailDirectory(): Map<String, ByteArray> {
        val directory = BrowserThumbnailStore.directory()
        if (!directory.exists()) return emptyMap()
        require(directory.isDirectory) { "Browser thumbnail path is not a directory." }
        var total = 0L
        return directory.listFiles().orEmpty()
            .sortedBy { it.name }
            .associate { file ->
                require(file.isFile && SAFE_THUMBNAIL_FILE.matches(file.name)) {
                    "Browser thumbnail directory contains an unsafe entry."
                }
                val bytes = readFileLimited(file, MAX_THUMBNAIL_BYTES)
                total += bytes.size
                require(total <= MAX_ALL_THUMBNAILS_BYTES) {
                    "Browser thumbnails exceed the rollback size limit."
                }
                file.name to bytes
            }
    }

    private fun replaceThumbnailDirectory(files: Map<String, ByteArray>): Map<String, String> {
        files.forEach { (name, bytes) ->
            require(SAFE_THUMBNAIL_FILE.matches(name) && bytes.isNotEmpty()) {
                "Migration thumbnail is invalid."
            }
        }
        val target = BrowserThumbnailStore.directory()
        val parent = target.parentFile ?: error("Browser thumbnail directory has no parent.")
        val stage = File(parent, "${target.name}.import-${UUID.randomUUID()}")
        val backup = File(parent, "${target.name}.rollback-${UUID.randomUUID()}")
        check(stage.mkdir()) { "Unable to create thumbnail staging directory." }
        var oldMoved = false
        var newMoved = false
        try {
            files.forEach { (name, bytes) ->
                val file = File(stage, name)
                require(file.canonicalFile.parentFile == stage.canonicalFile) {
                    "Migration thumbnail escapes its private directory."
                }
                FileOutputStream(file, false).use { output ->
                    output.write(bytes)
                    output.flush()
                    output.fd.sync()
                }
            }
            if (target.exists()) {
                check(target.renameTo(backup)) { "Unable to stage existing thumbnails." }
                oldMoved = true
            }
            check(stage.renameTo(target)) { "Unable to publish imported thumbnails." }
            newMoved = true
            if (oldMoved) backup.deleteRecursively()
            return files.keys.associateWith { name -> File(target, name).absolutePath }
        } catch (error: Throwable) {
            if (newMoved && target.exists() && !target.deleteRecursively()) {
                error.addSuppressed(IllegalStateException("Unable to remove partially imported thumbnails."))
            }
            if (oldMoved && backup.exists() && !backup.renameTo(target)) {
                error.addSuppressed(IllegalStateException("Unable to restore previous thumbnails."))
            }
            if (stage.exists() && !stage.deleteRecursively()) {
                error.addSuppressed(IllegalStateException("Unable to clean thumbnail staging directory."))
            }
            throw error
        }
    }

    private fun publishExport(stagingFile: File): MigrationPackageInfo {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            publishExportToMediaStore(stagingFile)
        } else {
            publishExportToLegacyDownloads(stagingFile)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun publishExportToMediaStore(stagingFile: File): MigrationPackageInfo {
        val resolver = context.contentResolver
        val previousOwnedPackages = findOwnedStandardPackageUris()
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, EXPORT_FILE_NAME)
            put(MediaStore.MediaColumns.MIME_TYPE, EXPORT_MIME_TYPE)
            put(MediaStore.MediaColumns.RELATIVE_PATH, standardRelativePath())
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val newUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Unable to create migration package entry.")
        val verification = File.createTempFile("migration-publish-verify-", ".zip", migrationRoot)
        try {
            val descriptor = resolver.openFileDescriptor(newUri, "w")
                ?: error("Unable to open migration package output.")
            ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
                FileInputStream(stagingFile).use { input -> input.copyTo(output) }
                output.flush()
                output.fd.sync()
            }
            copyUriToFileLimited(newUri, verification, archiveCodec.maxArchiveBytes)
            archiveCodec.read(verification)
            val published = resolver.update(
                newUri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null
            )
            check(published == 1) { "Unable to publish migration package." }
            copyUriToFileLimited(newUri, verification, archiveCodec.maxArchiveBytes)
            archiveCodec.read(verification)
            val packageInfo = resolvePackageInfo(newUri)
                ?: error("Published migration package cannot be resolved.")
            previousOwnedPackages.filterNot { it == newUri }.forEach { oldUri ->
                if (resolver.delete(oldUri, null, null) <= 0) {
                    AppLogger.w("Unable to clean an older app-owned migration package: $oldUri")
                }
            }
            return packageInfo
        } catch (error: Throwable) {
            if (resolver.delete(newUri, null, null) <= 0) {
                error.addSuppressed(
                    IllegalStateException("Unable to clean the failed pending migration package.")
                )
            }
            throw error
        } finally {
            verification.delete()
        }
    }

    private fun publishExportToLegacyDownloads(stagingFile: File): MigrationPackageInfo {
        val target = standardLegacyFile()
        val directory = target.parentFile ?: error("Migration export directory is invalid.")
        check(directory.exists() || directory.mkdirs()) {
            "Unable to create migration export directory."
        }
        val temporary = File(directory, ".$EXPORT_FILE_NAME.${UUID.randomUUID()}.tmp")
        try {
            copyFileWithSync(stagingFile, temporary)
            archiveCodec.read(temporary)
            Os.rename(temporary.absolutePath, target.absolutePath)
        } finally {
            temporary.delete()
        }
        return MigrationPackageInfo(target.toUri().toString(), target.absolutePath, target.length())
    }

    private fun findOwnedStandardPackageUris(): List<Uri> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
        val result = mutableListOf<Uri>()
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.OWNER_PACKAGE_NAME),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
            arrayOf(EXPORT_FILE_NAME, standardRelativePath()),
            null
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val ownerIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
            while (cursor.moveToNext()) {
                if (cursor.getString(ownerIndex) == context.packageName) {
                    result += ContentUris.withAppendedId(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        cursor.getLong(idIndex)
                    )
                }
            }
        }
        return result
    }

    private fun stageInputPackage(packageInfo: MigrationPackageInfo): File {
        if (packageInfo.sizeBytes > 0L) {
            require(packageInfo.sizeBytes <= archiveCodec.maxArchiveBytes) {
                "Migration package exceeds the compressed size limit."
            }
        }
        val staged = File.createTempFile("migration-import-", ".zip", migrationRoot)
        try {
            openInputStream(packageInfo.uriString.toUri()).use { input ->
                writeInputLimited(input, staged, archiveCodec.maxArchiveBytes)
            }
            return staged
        } catch (error: Throwable) {
            staged.delete()
            throw error
        }
    }

    private fun copyUriToFileLimited(uri: Uri, target: File, maxBytes: Long) {
        openInputStream(uri).use { input -> writeInputLimited(input, target, maxBytes) }
    }

    private fun writeInputLimited(input: InputStream, target: File, maxBytes: Long) {
        FileOutputStream(target, false).use { rawOutput ->
            val output = BufferedOutputStream(rawOutput)
            try {
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    total += count
                    require(total <= maxBytes) { "Migration package exceeds the compressed size limit." }
                    output.write(buffer, 0, count)
                }
                output.flush()
                rawOutput.fd.sync()
            } finally {
                output.close()
            }
        }
    }

    private fun copyFileWithSync(source: File, target: File) {
        FileInputStream(source).use { input ->
            FileOutputStream(target, false).use { output ->
                input.copyTo(output)
                output.flush()
                output.fd.sync()
            }
        }
    }

    private fun readFileLimited(file: File, maxBytes: Long): ByteArray {
        require(file.length() in 0..maxBytes) { "Migration file exceeds its size limit." }
        return FileInputStream(file).use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                total += count
                require(total <= maxBytes) { "Migration file exceeds its size limit." }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    private fun validateImportedThumbnailLimits(thumbnails: Map<String, ByteArray>) {
        var total = 0L
        thumbnails.forEach { (_, bytes) ->
            require(bytes.size.toLong() <= MAX_THUMBNAIL_BYTES) {
                "Migration thumbnail exceeds the per-file size limit."
            }
            total += bytes.size
            require(total <= MAX_ALL_THUMBNAILS_BYTES) {
                "Migration thumbnails exceed the total size limit."
            }
        }
    }

    private fun applyStorageFlagsFromPreferences() {
        FileUtil.IS_EXTERNAL_STORAGE_USE = sharedPrefHelper.getIsExternalUse()
        FileUtil.IS_APP_DATA_DIR_USE = sharedPrefHelper.getIsAppDirUse()
        FileUtil.INITIIALIZED = true
    }

    private fun privateVideoEntries(): List<PrivateVideoEntry> {
        return fileUtil.listFiles.mapNotNull { entry ->
            val isPrivate = entry.storageClass == FileUtil.MediaStorageClass.INTERNAL_PRIVATE ||
                entry.storageClass == FileUtil.MediaStorageClass.EXTERNAL_PRIVATE
            if (!isPrivate) {
                null
            } else {
                PrivateVideoEntry(
                    entry.displayName,
                    runCatching {
                        fileUtil.getContentLength(context, entry.uri)
                    }.getOrDefault(0L),
                    entry.uri
                )
            }
        }
    }

    private fun isPackageInstalled(packageName: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(packageName, 0)
    }.isSuccess

    private fun openInputStream(uri: Uri): InputStream {
        return when (uri.scheme) {
            "content" -> context.contentResolver.openInputStream(uri)
                ?: error("Unable to open migration package.")
            "file", null -> File(uri.path ?: error("Invalid migration package path.")).inputStream()
            else -> error("Unsupported migration package URI scheme.")
        }
    }

    private fun deleteByUri(uri: Uri): Boolean {
        return if (uri.scheme == "content") {
            runCatching {
                if (DocumentsContract.isDocumentUri(context, uri)) {
                    DocumentsContract.deleteDocument(context.contentResolver, uri)
                } else {
                    context.contentResolver.delete(uri, null, null) > 0
                }
            }.getOrDefault(false)
        } else {
            File(uri.path ?: return false).delete()
        }
    }

    private fun resolvePackageInfo(uri: Uri): MigrationPackageInfo? {
        return if (uri.scheme == "content") {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                val displayName = if (nameIndex >= 0) cursor.getString(nameIndex) else uri.lastPathSegment
                val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else 0L
                MigrationPackageInfo(
                    uri.toString(),
                    displayName.orEmpty().ifBlank { uri.toString() },
                    size
                )
            }
        } else if (uri.scheme == "file" || uri.scheme == null) {
            val file = File(uri.path ?: return null)
            if (file.isFile) {
                MigrationPackageInfo(uri.toString(), file.absolutePath, file.length())
            } else {
                null
            }
        } else {
            null
        }
    }

    private fun ensureMigrationRoot() {
        check(migrationRoot.exists() || migrationRoot.mkdirs()) {
            "Unable to create private migration staging directory."
        }
    }

    private fun standardRelativePath(): String =
        "${Environment.DIRECTORY_DOWNLOADS}/$EXPORT_SUBDIRECTORY/"

    private fun standardLegacyFile(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        "$EXPORT_SUBDIRECTORY${File.separator}$EXPORT_FILE_NAME"
    )

    private fun uniquePublicDownloadTarget(rootDir: File, fileName: String): File {
        return fileUtil.uniqueMediaTarget(context, File(rootDir, fileName))
    }

    private data class DatabaseSnapshot(
        val bookmarks: List<PageInfo>,
        val history: List<HistoryItem>,
        val videos: List<VideoInfo>,
        val progress: List<ProgressInfo>
    )

    private data class PrivateVideoEntry(
        val fileName: String,
        val sizeBytes: Long,
        val uri: Uri
    )
}
