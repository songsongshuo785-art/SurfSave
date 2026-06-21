package com.myAllVideoBrowser.migration

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.edit
import androidx.core.net.toUri
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.myAllVideoBrowser.BuildConfig
import com.myAllVideoBrowser.data.local.room.AppDatabase
import com.myAllVideoBrowser.data.local.room.dao.HistoryDao
import com.myAllVideoBrowser.data.local.room.dao.PageDao
import com.myAllVideoBrowser.data.local.room.dao.ProgressDao
import com.myAllVideoBrowser.data.local.room.dao.VideoDao
import com.myAllVideoBrowser.data.local.room.entity.ProgressInfo
import com.myAllVideoBrowser.data.repository.PlaybackStateRepository
import com.myAllVideoBrowser.di.qualifier.ApplicationContext
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.BrowserThumbnailStore
import com.myAllVideoBrowser.util.CookieProfileStore
import com.myAllVideoBrowser.util.FileUtil
import com.myAllVideoBrowser.util.SharedPrefHelper
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskState
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
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
        private const val SCHEMA_VERSION = 1
        private const val EXPORT_FILE_NAME = "surfsave-migration-package.zip"
        private const val EXPORT_MIME_TYPE = "application/zip"
        private const val EXPORT_SUBDIRECTORY = "SurfSave"
        private const val ENTRY_MANIFEST = "manifest.json"
        private const val ENTRY_SETTINGS_PREFS = "prefs/settings_prefs.json"
        private const val ENTRY_PLAYBACK_PREFS = "prefs/playback_state_prefs.json"
        private const val ENTRY_BOOKMARKS = "db/bookmarks.json"
        private const val ENTRY_HISTORY = "db/history.json"
        private const val ENTRY_VIDEOS = "db/videos.json"
        private const val ENTRY_PROGRESS = "db/progress.json"
        private const val ENTRY_BROWSER_SESSION = "session/browser_session.json"
        private const val ENTRY_COOKIE_PROFILES = "prefs/cookie_profiles.json"
        private const val ENTRY_THUMBNAILS_PREFIX = "session/thumbnails/"
    }

    private val gson = Gson()

    fun getOverview(): MigrationOverview {
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

    fun exportMigrationPackage(includeCookieContents: Boolean = false): MigrationReport {
        check(BuildConfig.MIGRATION_EXPORT_ENABLED) { "Migration export is disabled for this build." }

        val bookmarks = pageDao.getPageInfos().blockingFirst(emptyList())
        val history = historyDao.getAllHistoryItems()
        val videos = videoDao.getAllVideos()
        val completedProgress = progressDao.getAllProgressInfos()
            .filter { it.downloadStatus == VideoTaskState.SUCCESS }
        val browserTabs = sharedPrefHelper.getSavedBrowserSessionTabs()
        val browserSession = BrowserSessionSnapshot(
            tabs = browserTabs,
            currentIndex = sharedPrefHelper.restoreBrowserSessionCurrentIndex()
        )
        val thumbnailCount = browserTabs.count { tab ->
            !tab.thumbnailPath.isNullOrBlank() && File(tab.thumbnailPath).exists()
        }
        val cookieProfiles = cookieProfileStore.snapshot(includeCookieContents)

        val manifest = MigrationManifest(
            schemaVersion = SCHEMA_VERSION,
            exportedAtEpochMs = System.currentTimeMillis(),
            exportedByPackage = context.packageName,
            exportedByRole = BuildConfig.MIGRATION_ROLE,
            appVersionName = BuildConfig.VERSION_NAME,
            bookmarkCount = bookmarks.size,
            historyCount = history.size,
            videoCount = videos.size,
            progressCount = completedProgress.size,
            browserSessionCount = browserTabs.size,
            thumbnailCount = thumbnailCount,
            cookieProfileCount = cookieProfiles.size,
            cookieContentIncluded = includeCookieContents
        )

        val archive = MigrationArchive(
            manifest = manifest,
            settingsPrefs = snapshotSharedPreferences(SharedPrefHelper.PREF_KEY),
            playbackPrefs = snapshotSharedPreferences(PlaybackStateRepository.PREFS_NAME),
            bookmarks = bookmarks,
            history = history,
            videos = videos,
            progress = completedProgress,
            browserSession = browserSession,
            cookieProfiles = cookieProfiles
        )

        deleteMigrationPackage()
        createPackageTarget().use { target ->
            ZipOutputStream(target.outputStream).use { zip ->
                writeJsonEntry(zip, ENTRY_MANIFEST, manifest)
                writeJsonEntry(zip, ENTRY_SETTINGS_PREFS, archive.settingsPrefs)
                writeJsonEntry(zip, ENTRY_PLAYBACK_PREFS, archive.playbackPrefs)
                writeJsonEntry(zip, ENTRY_BOOKMARKS, archive.bookmarks)
                writeJsonEntry(zip, ENTRY_HISTORY, archive.history)
                writeJsonEntry(zip, ENTRY_VIDEOS, archive.videos)
                writeJsonEntry(zip, ENTRY_PROGRESS, archive.progress)
                writeJsonEntry(zip, ENTRY_BROWSER_SESSION, archive.browserSession)
                writeJsonEntry(zip, ENTRY_COOKIE_PROFILES, archive.cookieProfiles)
                writeThumbnails(zip, browserTabs)
            }
        }

        val packageInfo = locateMigrationPackage()
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
            bookmarkCount = bookmarks.size,
            historyCount = history.size,
            videoCount = videos.size,
            progressCount = completedProgress.size,
            browserSessionCount = browserTabs.size,
            thumbnailCount = thumbnailCount,
            cookieProfileCount = cookieProfiles.size,
            cookieContentIncluded = includeCookieContents,
            privateVideoCount = privateVideos.count,
            privateVideoBytes = privateVideos.totalBytes,
            notes = listOf(
                "Only completed video metadata is migrated. Active downloads are excluded.",
                if (includeCookieContents) {
                    "Cookie profiles were included because the user explicitly enabled cookie export."
                } else {
                    "Cookie profile metadata is listed, but cookie contents are excluded by default."
                },
                "WebView login state is not included in the migration package."
            )
        )
        if (nextStage == MigrationStage.IMPORTED) {
            stateStore.markImported(report)
        } else {
            stateStore.markExportReady(report)
        }
        return report
    }

    fun importMigrationPackage(packageUri: Uri? = null): MigrationReport {
        check(BuildConfig.MIGRATION_IMPORT_ENABLED) { "Migration import is disabled for this build." }

        val packageInfo = packageUri
            ?.let { resolvePackageInfo(it) }
            ?: locateMigrationPackage()
            ?: error("No migration package was found in Downloads/SurfSave.")
        val archive = readArchive(packageInfo)
        val importedProgress = archive.progress
            .filter { it.downloadStatus == VideoTaskState.SUCCESS }

        appDatabase.runInTransaction {
            pageDao.deleteAll()
            if (archive.bookmarks.isNotEmpty()) {
                pageDao.insertAllProgressInfo(archive.bookmarks)
            }

            historyDao.clear()
            if (archive.history.isNotEmpty()) {
                historyDao.insertAll(archive.history)
            }

            videoDao.clear()
            if (archive.videos.isNotEmpty()) {
                videoDao.insertAll(archive.videos)
            }

            progressDao.clear()
            if (importedProgress.isNotEmpty()) {
                progressDao.insertAllProgressInfo(importedProgress)
            }
        }

        restoreSharedPreferences(SharedPrefHelper.PREF_KEY, archive.settingsPrefs)
        restoreSharedPreferences(PlaybackStateRepository.PREFS_NAME, archive.playbackPrefs)
        val restoredCookieProfileCount = cookieProfileStore.restore(archive.cookieProfiles)

        BrowserThumbnailStore.clearAll()
        val importedTabs = archive.browserSession.tabs.map { tab ->
            val importedThumbnail = readThumbnailFromPackage(packageInfo, tab.id)?.let { bytes ->
                BrowserThumbnailStore.importFile(thumbnailEntryFileName(tab.id), bytes)
            }
            tab.copy(thumbnailPath = importedThumbnail)
        }
        sharedPrefHelper.saveBrowserSessionTabs(
            importedTabs,
            archive.browserSession.currentIndex
        )
        applyStorageFlagsFromPreferences()

        val report = MigrationReport(
            stage = MigrationStage.IMPORTED,
            generatedAtEpochMs = System.currentTimeMillis(),
            packageInfo = packageInfo,
            bookmarkCount = archive.bookmarks.size,
            historyCount = archive.history.size,
            videoCount = archive.videos.size,
            progressCount = importedProgress.size,
            browserSessionCount = importedTabs.size,
            thumbnailCount = importedTabs.count { !it.thumbnailPath.isNullOrBlank() },
            cookieProfileCount = restoredCookieProfileCount,
            cookieContentIncluded = archive.manifest.cookieContentIncluded,
            privateVideoCount = inspectPrivateVideos().count,
            privateVideoBytes = inspectPrivateVideos().totalBytes,
            notes = listOf(
                "Imported data is written into the new app identity without deleting the migration package.",
                if (restoredCookieProfileCount > 0) {
                    "Cookie profiles with exported contents were restored."
                } else {
                    "Cookie contents were not restored unless they were explicitly exported."
                },
                "Please verify videos, bookmarks, settings, and open tabs before uninstalling the legacy app."
            )
        )
        stateStore.markImported(report)
        return report
    }

    fun inspectPrivateVideos(): PrivateVideoSummary {
        val entries = privateVideoEntries()
        return PrivateVideoSummary(
            count = entries.size,
            totalBytes = entries.sumOf { it.sizeBytes }
        )
    }

    fun movePrivateVideosToSharedDownloads(): PrivateVideoMoveResult {
        val entries = privateVideoEntries()
        if (entries.isEmpty()) {
            return PrivateVideoMoveResult(remainingPrivateVideos = PrivateVideoSummary())
        }

        val publicDownloadsDir =
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!publicDownloadsDir.exists()) {
            publicDownloadsDir.mkdirs()
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
            movedCount = movedCount,
            failedCount = failedCount,
            movedBytes = movedBytes,
            remainingPrivateVideos = inspectPrivateVideos()
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
            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
            val selectionArgs = arrayOf(EXPORT_FILE_NAME, buildRelativePathLikePattern())

            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
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
                val uri = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString())
                MigrationPackageInfo(
                    uriString = uri.toString(),
                    displayPath = "${relativePath.orEmpty()}$displayName",
                    sizeBytes = size
                )
            }
        } else {
            val file = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "$EXPORT_SUBDIRECTORY${File.separator}$EXPORT_FILE_NAME"
            )
            if (!file.exists()) {
                null
            } else {
                MigrationPackageInfo(
                    uriString = file.toUri().toString(),
                    displayPath = file.absolutePath,
                    sizeBytes = file.length()
                )
            }
        }
    }

    fun deleteMigrationPackage(): Boolean {
        val packageInfo = locateMigrationPackage()
            ?: stateStore.getLastReport()?.packageInfo
            ?: return false
        val deleted = deleteByUri(packageInfo.uriString.toUri())
        if (deleted) {
            stateStore.clearLastReportPackageInfo()
        }
        return deleted
    }

    fun canAutoLocateMigrationPackage(): Boolean {
        return locateMigrationPackage() != null
    }

    private fun writeThumbnails(
        zip: ZipOutputStream,
        tabs: List<SharedPrefHelper.BrowserSessionTab>
    ) {
        tabs.forEach { tab ->
            val path = tab.thumbnailPath ?: return@forEach
            val file = File(path)
            if (!file.exists() || !file.isFile) {
                return@forEach
            }
            zip.putNextEntry(ZipEntry(ENTRY_THUMBNAILS_PREFIX + thumbnailEntryFileName(tab.id)))
            file.inputStream().use { input ->
                input.copyTo(zip)
            }
            zip.closeEntry()
        }
    }

    private fun readArchive(packageInfo: MigrationPackageInfo): MigrationArchive {
        var manifest: MigrationManifest? = null
        var settingsPrefs = emptyList<PreferenceEntry>()
        var playbackPrefs = emptyList<PreferenceEntry>()
        var bookmarks = emptyList<com.myAllVideoBrowser.data.local.room.entity.PageInfo>()
        var history = emptyList<com.myAllVideoBrowser.data.local.room.entity.HistoryItem>()
        var videos = emptyList<com.myAllVideoBrowser.data.local.room.entity.VideoInfo>()
        var progress = emptyList<ProgressInfo>()
        var browserSession = BrowserSessionSnapshot()
        var cookieProfiles = emptyList<CookieProfileStore.CookieProfileBackup>()

        openInputStream(packageInfo.uriString.toUri()).use { inputStream ->
            ZipInputStream(BufferedInputStream(inputStream)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val bytes = zip.readBytes()
                        when (entry.name) {
                            ENTRY_MANIFEST -> {
                                manifest = gson.fromJson(
                                    String(bytes, Charsets.UTF_8),
                                    MigrationManifest::class.java
                                )
                            }

                            ENTRY_SETTINGS_PREFS -> {
                                val type = object : TypeToken<List<PreferenceEntry>>() {}.type
                                settingsPrefs = gson.fromJson(String(bytes, Charsets.UTF_8), type)
                                    ?: emptyList()
                            }

                            ENTRY_PLAYBACK_PREFS -> {
                                val type = object : TypeToken<List<PreferenceEntry>>() {}.type
                                playbackPrefs = gson.fromJson(String(bytes, Charsets.UTF_8), type)
                                    ?: emptyList()
                            }

                            ENTRY_BOOKMARKS -> {
                                val type =
                                    object : TypeToken<List<com.myAllVideoBrowser.data.local.room.entity.PageInfo>>() {}.type
                                bookmarks = gson.fromJson(String(bytes, Charsets.UTF_8), type)
                                    ?: emptyList()
                            }

                            ENTRY_HISTORY -> {
                                val type =
                                    object : TypeToken<List<com.myAllVideoBrowser.data.local.room.entity.HistoryItem>>() {}.type
                                history = gson.fromJson(String(bytes, Charsets.UTF_8), type)
                                    ?: emptyList()
                            }

                            ENTRY_VIDEOS -> {
                                val type =
                                    object : TypeToken<List<com.myAllVideoBrowser.data.local.room.entity.VideoInfo>>() {}.type
                                videos = gson.fromJson(String(bytes, Charsets.UTF_8), type)
                                    ?: emptyList()
                            }

                            ENTRY_PROGRESS -> {
                                val type = object : TypeToken<List<ProgressInfo>>() {}.type
                                progress = gson.fromJson(String(bytes, Charsets.UTF_8), type)
                                    ?: emptyList()
                            }

                            ENTRY_BROWSER_SESSION -> {
                                browserSession = gson.fromJson(
                                    String(bytes, Charsets.UTF_8),
                                    BrowserSessionSnapshot::class.java
                                ) ?: BrowserSessionSnapshot()
                            }

                            ENTRY_COOKIE_PROFILES -> {
                                val type = object : TypeToken<List<CookieProfileStore.CookieProfileBackup>>() {}.type
                                cookieProfiles = gson.fromJson(String(bytes, Charsets.UTF_8), type)
                                    ?: emptyList()
                            }
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }

        val resolvedManifest = manifest ?: error("Migration package is invalid: missing manifest.")
        return MigrationArchive(
            manifest = resolvedManifest,
            settingsPrefs = settingsPrefs,
            playbackPrefs = playbackPrefs,
            bookmarks = bookmarks,
            history = history,
            videos = videos,
            progress = progress,
            browserSession = browserSession,
            cookieProfiles = cookieProfiles
        )
    }

    private fun readThumbnailFromPackage(packageInfo: MigrationPackageInfo, tabId: String): ByteArray? {
        val targetName = ENTRY_THUMBNAILS_PREFIX + thumbnailEntryFileName(tabId)
        openInputStream(packageInfo.uriString.toUri()).use { inputStream ->
            ZipInputStream(BufferedInputStream(inputStream)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name == targetName) {
                        return zip.readBytes()
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        return null
    }

    private fun snapshotSharedPreferences(prefName: String): List<PreferenceEntry> {
        val preferences = context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
        return preferences.all.entries.mapNotNull { entry ->
            val key = entry.key
            when (val value = entry.value) {
                is String -> PreferenceEntry(key = key, valueType = "string", stringValue = value)
                is Int -> PreferenceEntry(key = key, valueType = "int", intValue = value)
                is Long -> PreferenceEntry(key = key, valueType = "long", longValue = value)
                is Float -> PreferenceEntry(key = key, valueType = "float", floatValue = value)
                is Boolean -> PreferenceEntry(key = key, valueType = "boolean", booleanValue = value)
                is Set<*> -> PreferenceEntry(
                    key = key,
                    valueType = "string_set",
                    stringSetValue = value.mapNotNull { it?.toString() }.toSet()
                )

                else -> null
            }
        }.sortedBy { it.key.lowercase(Locale.US) }
    }

    private fun restoreSharedPreferences(prefName: String, entries: List<PreferenceEntry>) {
        val preferences = context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
        preferences.edit {
            clear()
            entries.forEach { entry ->
                when (entry.valueType) {
                    "string" -> putString(entry.key, entry.stringValue)
                    "int" -> putInt(entry.key, entry.intValue ?: 0)
                    "long" -> putLong(entry.key, entry.longValue ?: 0L)
                    "float" -> putFloat(entry.key, entry.floatValue ?: 0f)
                    "boolean" -> putBoolean(entry.key, entry.booleanValue ?: false)
                    "string_set" -> putStringSet(entry.key, entry.stringSetValue ?: emptySet())
                }
            }
        }
    }

    private fun applyStorageFlagsFromPreferences() {
        FileUtil.IS_EXTERNAL_STORAGE_USE = sharedPrefHelper.getIsExternalUse()
        FileUtil.IS_APP_DATA_DIR_USE = sharedPrefHelper.getIsAppDirUse()
        FileUtil.INITIIALIZED = true
    }

    private fun privateVideoEntries(): List<PrivateVideoEntry> {
        val internalDir = File(context.filesDir, FileUtil.FOLDER_NAME)
        val externalDir = context.getExternalFilesDir(null)?.let { File(it, FileUtil.FOLDER_NAME) }
        val internalPrefix = Uri.fromFile(internalDir).toString()
        val externalPrefix = externalDir?.let { Uri.fromFile(it).toString() }

        return fileUtil.listFiles.mapNotNull { (fileName, data) ->
            val uri = data.second
            val uriString = uri.toString()
            val isPrivate = uriString.startsWith(internalPrefix) ||
                (!externalPrefix.isNullOrBlank() && uriString.startsWith(externalPrefix))
            if (!isPrivate) {
                null
            } else {
                PrivateVideoEntry(
                    fileName = fileName,
                    sizeBytes = runCatching { fileUtil.getContentLength(context, uri) }.getOrDefault(0L),
                    uri = uri
                )
            }
        }
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return runCatching {
            context.packageManager.getPackageInfo(packageName, 0)
        }.isSuccess
    }

    private fun writeJsonEntry(zip: ZipOutputStream, entryName: String, payload: Any) {
        zip.putNextEntry(ZipEntry(entryName))
        zip.write(gson.toJson(payload).toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun thumbnailEntryFileName(tabId: String): String {
        return tabId.replace(Regex("[^a-zA-Z0-9._-]"), "_") + ".jpg"
    }

    private fun buildRelativePathLikePattern(): String {
        return "${Environment.DIRECTORY_DOWNLOADS}/$EXPORT_SUBDIRECTORY%"
    }

    private fun uniquePublicDownloadTarget(rootDir: File, fileName: String): File {
        val source = File(fileName)
        val cleanBase = if (source.nameWithoutExtension.isBlank()) {
            "video"
        } else {
            source.nameWithoutExtension
        }
        val extension = source.extension.takeIf { it.isNotBlank() }?.let { ".$it" }.orEmpty()
        var index = 0
        var candidate = File(rootDir, "$cleanBase$extension")
        while (candidate.exists()) {
            index += 1
            candidate = File(rootDir, "${cleanBase}_$index$extension")
        }
        return candidate
    }

    private fun openInputStream(uri: Uri): InputStream {
        return if (uri.scheme == "content") {
            context.contentResolver.openInputStream(uri)
                ?: error("Unable to open migration package.")
        } else {
            File(uri.path ?: error("Invalid migration package path.")).inputStream()
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
                arrayOf(
                    OpenableColumns.DISPLAY_NAME,
                    OpenableColumns.SIZE,
                    MediaStore.MediaColumns.RELATIVE_PATH
                ),
                null,
                null,
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) {
                    return@use null
                }

                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                val relativePathIndex = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                val displayName = if (nameIndex >= 0) {
                    cursor.getString(nameIndex)
                } else {
                    uri.lastPathSegment
                }.orEmpty()
                val relativePath = if (relativePathIndex >= 0) {
                    cursor.getString(relativePathIndex).orEmpty()
                } else {
                    ""
                }
                val size = if (sizeIndex >= 0) {
                    cursor.getLong(sizeIndex)
                } else {
                    0L
                }
                MigrationPackageInfo(
                    uriString = uri.toString(),
                    displayPath = (relativePath + displayName).ifBlank { uri.toString() },
                    sizeBytes = size
                )
            }
        } else {
            val file = File(uri.path ?: return null)
            if (!file.exists()) {
                null
            } else {
                MigrationPackageInfo(
                    uriString = uri.toString(),
                    displayPath = file.absolutePath,
                    sizeBytes = file.length()
                )
            }
        }
    }

    private fun createPackageTarget(): MigrationPackageTarget {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, EXPORT_FILE_NAME)
                put(MediaStore.MediaColumns.MIME_TYPE, EXPORT_MIME_TYPE)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$EXPORT_SUBDIRECTORY")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Unable to create migration package entry.")
            val outputStream = context.contentResolver.openOutputStream(uri, "w")
                ?: error("Unable to open migration package stream.")
            MigrationPackageTarget(uri, outputStream) {
                val finalizeValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                context.contentResolver.update(uri, finalizeValues, null, null)
            }
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                EXPORT_SUBDIRECTORY
            )
            if (!dir.exists()) {
                dir.mkdirs()
            }
            val file = File(dir, EXPORT_FILE_NAME)
            MigrationPackageTarget(file.toUri(), file.outputStream()) { }
        }
    }

    private data class PrivateVideoEntry(
        val fileName: String,
        val sizeBytes: Long,
        val uri: Uri
    )

    private class MigrationPackageTarget(
        val uri: Uri,
        val outputStream: OutputStream,
        private val finalizeWrite: () -> Unit
    ) : AutoCloseable {
        override fun close() {
            outputStream.close()
            finalizeWrite.invoke()
        }
    }
}
